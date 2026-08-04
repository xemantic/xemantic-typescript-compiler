# (M2)/(PERF.HW) — the worker-scaling ladder, re-taken on the FIXED binary

*Round 826, 2026-08-04. Compiler profile (`build/bench/tsc-project-637d5746`, 78
files), `--noEmit --listAll`, `-Xmx4g`, one cold JVM per run, `compileKotlinJvm`
rebuilt from HEAD `8e5bf9de` and **all daemons stopped inside the harness between the
build and the first measurement**. No `src/` change: measurement only.*

Round 825 fixed the `--workers` race (a singleton id-space collision). That
invalidated round 824's ladder **as a claim** — it compared a correct sequential arm
against a parallel arm that was sometimes computing a different answer. This is the
re-take. Four results:

1. **Correctness holds on every single timing run: 24/24 at 46 errors, ONE md5 across
   all 24 sorted captures, identical to sequential and to round 825's post-fix
   capture.** Post-fix evidence now totals **72 runs**.
2. **The race was DEFLATING the parallel arms, not flattering them.** Every paired
   delta improved: w4 −23.84% → **−27.10%**. Round 824's numbers were conservative.
3. **w4 = 1.361×, and worker count alone is EXHAUSTED there** — w8 regresses to
   1.242×. The 1.25× cap is refuted for the second time, on an honest binary.
4. **(M2) step (b) prices SMALL for wall time** (§ 5): at most **1.55×** in an
   unreachable limit, realistically **~1.45×** — ≈1.0–1.6 s off a 23.2 s compile.

---

## 1. Method

`w826.sh`: rebuild → verify `BUILD SUCCESSFUL` → `./gradlew --stop` + graceful
kotlin-daemon kill → 25 s settle → ladder. 6 reps × 4 levels, **rotated interleave**
(rep *r* starts at level *r* mod 4), `/usr/bin/time -v` per run. `seq` is *no*
`--workers` flag, i.e. the true sequential path, not `--workers 1`.

Three deliberate differences from round 824, each closing a hole that round named:

- **`--listAll` on EVERY run, not just a separate correctness stage.** Both arms are
  now produced by one command modulo `--workers` (round 811's law), and every timing
  point carries its own full capture. It costs nothing measurable: round 824's
  `--listAll` seq run (26,013 ms) sat mid-range of its five non-`--listAll` seq runs.
- **Every capture is checked for the `... and N more error(s)` truncation tell** (0/24)
  and diffed **sorted** (round 824 manufactured three phantom extras with an unsorted
  diff).
- **The exact command line is echoed to a log per run** and verified to carry
  `--workers N` — round 825 lost ~10 minutes to a "clean" run that had no such flag.

The agent started the harness and **ended its turn**; nothing else touched the box
(round 774: watching a benchmark is part of the benchmark).

---

## 2. The ladder

| level | self ms (rep 1..6) | **median** | sd | spread | **paired Δ** | **wins** | **speedup** | user CPU s | cores | peak RSS |
|---|---|---:|---:|---:|---:|:---:|---:|---:|---:|---:|
| **seq** | 21,328 / 23,298 / 23,453 / 23,068 / 22,684 / 23,735 | **23,183** | 861 | 10.4% | — | — | 1.000× | 97.4 | 4.20 | 808 MB |
| **w2** | 17,606 / 17,038 / 18,613 / 18,765 / 17,070 / 17,933 | **17,770** | 742 | 9.7% | **−22.54%** | **6/6** | **1.305×** | 90.9 | 5.11 | 1,133 MB |
| **w4** | 17,251 / 17,221 / 16,857 / 18,253 / 16,266 / 16,524 | **17,039** | 699 | 11.7% | **−27.10%** | **6/6** | **1.361×** | 102.0 | 5.97 | 1,389 MB |
| **w8** | 18,101 / 20,082 / 19,362 / 18,700 / 18,626 / 17,970 | **18,663** | 797 | 11.3% | **−17.67%** | **6/6** | **1.242×** | 123.9 | 6.63 | 2,234 MB |

Per-rep deltas, all sign-consistent — no level straddles zero:

    w2:  -17.45 / -26.87 / -20.64 / -18.65 / -24.75 / -24.44 %
    w4:  -19.12 / -26.08 / -28.12 / -20.87 / -28.29 / -30.38 %
    w8:  -15.13 / -13.80 / -17.44 / -18.94 / -17.89 / -24.29 %

Bootstrap 90% CI on the speedup (10k resamples over the six reps):
**w2 [1.240, 1.351] · w4 [1.289, 1.414] · w8 [1.196, 1.287]**. w4 beats w8 with the
intervals barely touching; w2 vs w4 is not separated.

---

## 3. Correctness on every run — the point of the re-take

| check | result |
|---|---|
| runs at 46 errors | **24 of 24** (both `grep -c 'error TS'` and the compiler's own `FAILED — N error(s)`) |
| distinct sorted-capture md5s | **1** (`59d930db8493…`), across all 24 |
| identical to sequential | **yes** — sorted diff seq↔w8 empty |
| identical to round 825's post-fix capture | **yes**, same md5 |
| captures carrying the `more error(s)` truncation tell | **0** |

The 46 decompose as **43× TS2591** (`Cannot find name 'process'` — the offline
missing-`@types/node` artifact), 2× TS2304, 1× TS2584. That distribution is
unchanged since the 2026-07-28 bench row, so the workload is constant across every
round being compared here.

**(PERF.HW.a) post-fix evidence: 72 runs** — round 825's 48 plus these 24 — every one
byte-identical to sequential.

---

## 4. What the race was doing to the timings

This is the round's actual question, and the paired deltas answer it: **the racy
binary's parallel arms were slower relative to sequential, i.e. round 824 UNDER-stated
the parallel win.**

| level | round 824 paired Δ (racy) | **round 826 paired Δ (fixed)** | change |
|---|---:|---:|---:|
| w2 | −19.05% | **−22.54%** | +3.49 pp better |
| w4 | −23.84% | **−27.10%** | +3.26 pp better |
| w8 | −17.02% | **−17.67%** | +0.65 pp better |
| **w4 speedup** | **1.343×** | **1.361×** | — |

**Two confounded causes, and this round cannot separate them.** Round 825 landed two
commits: the id-slice fix itself, and `RealLibSnapshots.prewarmParsedLibFiles`, which
removes N−1 duplicate real-lib parses and is a genuine parallel-only saving. An A/B of
the two commits was not run. The w8 row is the argument against attributing the gain
mainly to the prewarm — w8 removes *seven* duplicate parses and improved least
(+0.65 pp) — but that is an inference, not a measurement.

**Direction is what matters for the decision, and it is unambiguous: fixing the race
did not cost the parallel mode anything.** Round 824's headline (1.343×) was
conservative, and the 1.25×-cap refutation strengthens.

### The absolute numbers moved ~11% and NO code change explains it

The whole ladder is faster than round 824's. Both round-825 fixes are gated to the
parallel path (`prewarmParsedLibFiles` sits inside the `workers > 1` branch;
`forceIntrinsicTypeInit` inside `runInDeepStackWorkers`, which returns early for
`tasks.size <= 1`), so **the sequential arm is byte-identical code to round 824's**,
on a byte-identical profile — and it moved 26,145 → 23,183 ms (−11.3%).

Four sequential anchors, same code, same 78-file profile:

| when | seq self ms |
|---|---:|
| 2026-07-28 (bench row) | 26,518 |
| round 823 (~16:00) | 25,299 |
| round 824 (~16:30) | 26,145 |
| **round 826 (~18:00)** | **23,183** |

That is a **12.8% spread within one afternoon**. Round 824's doc reconciled its arm
with round 823's as "3.3% apart, well inside either arm's band"; the third and fourth
points show that agreement was luck. **Consequence, and it is a rule not an
observation: only WITHIN-round paired deltas are quotable. Never compare an absolute
ms figure across rounds** — which is exactly why the ladder is run interleaved.

---

## 5. Amdahl, re-fitted — and what (M2) step (b) is worth

### 5.1 The 2-parameter fit, and what does *not* reproduce

Model `seq = R + P`, `wN = R + P/N`, on the medians (per-rep range in brackets):

| fitted from | P (divisible) | P share | R (non-divisible) | R share | infinite-worker floor | round 824 |
|---|---:|---:|---:|---:|---:|---:|
| seq / w2 | 10,827 ms | **46.7%** [34.9–53.7] | 12,356 ms | 53.3% | 1.876× | 36.9% → 1.584× |
| seq / w4 | 8,192 ms | **35.3%** [25.5–40.5] | 14,991 ms | **64.7%** | **1.546×** | 34.0% → 1.516× |
| seq / w8 | 5,166 ms | 22.3% [15.8–27.8] | 18,017 ms | 77.7% | 1.287× | 19.8% → 1.248× |

**Only the seq/w4 fit reproduces across rounds** (34.0 → 35.3%, floor 1.516 → 1.546×).
The seq/w2 fit does not (36.9 → 46.7%).

**So round 824's "the w2 and w4 fits now agree within 8% … the model is coherent
through w4 and breaks at w8" is retracted.** Here they disagree by 32%, and the fitted
P share falls *monotonically* with N — 46.7 → 35.3 → 22.3% — which is the signature of
an overhead that grows with worker count and that a 2-parameter model cannot express
at any level. The model was never coherent; round 824 caught a tighter draw.

**A 3-parameter fit cannot rescue it.** `wN = R + P/N + C·N` over three points is
exactly determined, and fitted per rep it gives R = 1,569 / 4,414 / 6,326 / 6,513 /
12,786 / 14,929 ms — a 10× spread on the parameter the whole decision turns on. **Do
not quote a 3-parameter fit from a 3-point ladder**; it is unidentifiable here.

### 5.2 The realistic ceiling

**1.361× at w4, and that is where worker count runs out** — w8 measures 1.242×, a
regression against w4 with the bootstrap intervals essentially separated. The ~1.55×
seq/w4 asymptote is *not* reachable by adding workers; reaching it is precisely the
job (M2) step (b) proposes.

### 5.3 The structural fact that prices step (b)

Step (b) is "shrink R — the full per-worker re-bind is the single biggest identified
duplication". The measurement says something the framing does not:

> **The per-worker re-bind and the ~318 program-wide collectors are duplicated in CPU
> and in MEMORY, but NOT in WALL.** Every worker runs them *concurrently*, so their
> wall contribution is ~1× — they already sit inside R. De-duplicating them (bind
> once, share) removes N−1 copies of CPU and ~nothing of wall, *unless cores are
> saturated*.

They are not saturated. Cores used peak at **6.63 of 8** at w8, and the CPU column
shows the shape directly:

| level | wall s | user+sys s | cores | CPU vs seq | wall vs seq |
|---|---:|---:|---:|---:|---:|
| seq | 23.36 | 98.2 | 4.20 | — | — |
| w2 | 17.95 | 91.8 | 5.11 | **−6.5%** | −22.6% |
| w4 | 17.26 | 103.1 | 5.97 | **+5.1%** | −25.5% |
| w8 | 18.89 | 125.2 | 6.63 | **+27.6%** | −18.5% |

w8 spends +27.6% CPU to buy *less* wall than w4. That gap — 1,624 ms — is the entire
wall cost of the duplication at the level where it hurts most, and it is the upper
bound on what de-duplicating alone can return.

### 5.4 The bounded price

| scenario | time | speedup | vs today's w4 |
|---|---:|---:|---:|
| **today, w4 (measured)** | **17,039 ms** | **1.361×** | — |
| duplication cost fully removed, w8 (`R + P/8`) | 16,015 ms | 1.448× | **−1,024 ms** |
| duplication cost fully removed, N→∞ (`= R`) | 14,991 ms | 1.546× | **−2,048 ms** |
| R also halved, N→∞ | 7,496 ms | 3.09× | −9,543 ms *(re-architecture, not step (b))* |

**So step (b), as scoped, is worth at most 2.0 s of a 23.2 s compile (8.8%), and
realistically ~1.0–1.6 s (4–7%).** The 1.546× row requires infinite workers *and* zero
duplication cost; the 1.448× row is the honest engineering target. Going past 1.55×
requires making the re-bind and the collectors **divisible** (partitioned across
workers), not merely **de-duplicated** — a different and much larger item, floored by
the truly sequential prefix (crawl + parse + one bind; round 666 put the non-spine
part of checker-init at ~3.3 s).

### 5.5 Recommendation

**Do not attempt step (b) for wall time.** Reasons, in order:

1. **The prize is ~1.0–1.6 s realistic / 2.0 s absolute ceiling**, against work that
   makes the binder and checker share state across threads — the exact area that just
   produced a race latent since round 754 and found only because a box upgrade made
   it observable. The risk/reward is poor at 4–7%.
2. **1.361× is already banked** at `--workers 4` for zero further work, now that the
   mode is correct over 72 runs.
3. **Larger, already-measured wins are on the shelf**: warm `--serve` 11.9 s
   (2.0× against this seq arm) and the Kotlin/Native release binary at 20.0 s.

**What step (b) *would* legitimately buy is CPU and memory, not wall** — w8 costs
+27.6% CPU and 2.8× the peak RSS of sequential (2,234 vs 808 MB). If parallel mode is
ever to become the *default*, RSS is the binding constraint and (b) is the item that
addresses it. **Queue it as a memory item, priced at ~4–7% wall, not as a performance
item priced at 1.5×.**

---

## 6. What did not work

- **The 3-parameter `R + P/N + C·N` fit** — exactly determined on three points, and
  per-rep it put R anywhere between 1.6 s and 14.9 s. Two hours of ladder cannot
  identify three parameters from three levels; it would need w3/w5/w6 as well.
- **Cross-round absolute comparison.** The seq anchor moved 11% with byte-identical
  code and workload, so the entire "is the fixed binary faster?" question can only be
  answered through the paired within-round deltas (§ 4), not by putting round 824's
  and round 826's tables side by side.

---

*Raw per-run data (24 runs: rep, level, wall/self ms, error count, the compiler's own
reported count, truncation flag, sorted-capture md5, user/sys CPU, peak RSS) in
`worker-scaling-round826.tsv` beside this file. Gates: no `src/` change, so `jvmTest`
is **not applicable** and the 13,808 / 0 / 3 baseline is untouched by construction;
`cost_gate.py` and `huge_methods.py` likewise measure a binary identical to HEAD's.*
