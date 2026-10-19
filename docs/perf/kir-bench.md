# The KIR runtime benchmark

Two real, unmodified npm libraries — [`mitt`](https://github.com/developit/mitt) 3.0.1 and
[`smol-toml`](https://github.com/squirrelchat/smol-toml) — run as one source through every arm of
`scripts/kir-bench.sh`. The table below is **generated**: `scripts/kir-bench.sh --record [processes]` commits the
run's JSON under `bench-history/kir-runs/`, and `scripts/kir_bench_report.py` writes it between the markers. Nothing
between the markers is typed by hand, and the generator refuses to print a run whose medians are not positive, whose
ratio column would disagree with its own medians, whose rows fall short of arms × libraries, or whose equivalence
gate did not agree (`python3 scripts/kir_bench_report.py --self-test` proves each refusal).

How to read it:

- **Equivalence comes first.** Every arm prints a `sink=` accumulator and the run refuses unless all arms agree, so
  every row below computed the same thing.
- **Steady state.** Each process warms up, then times rounds itself and reports its best; the median is over
  processes, interleaved across arms.
- **Classes**, after Perry's: a *runtime peer* is the same input running where these libraries normally run (tsgo's
  JavaScript on node); the *control* is our own JavaScript on the same runtime — byte-identical to tsgo's since
  (TSGO.7-g), so its gap to the baseline is the run's noise floor; *ours* are the KIR backends. Losses are printed as
  losses.
- A run on one developer box is one draw on one box: the box line says which.
- **Placement** is recorded per arm. `KIR_BENCH_PIN=<cpus>` pins the single-threaded-loop arms with `taskset` and
  never the JVM arm (its JIT threads are part of it); it is opt-in and stays so: the paired grading of (BENCH.2) —
  unpinned, pinned, unpinned, pinned, 5 processes each, `bench-history/kir-runs/placement-ab-20261018/` — narrowed
  the control arm's spread but not the baseline's, so the default recipe (and the series) is unchanged.

- **Perry, the TS-to-native peer** ((BENCH.4), `KIR_BENCH_PERRY=1`) is an arm the script can run and gate, but
  it is not in the table below, because a recorded run with it does not finish. Perry 0.5.1520 compiles both
  fixtures unmodified and computes the same `sink=` as every other arm. On toml it measures 577.55 µs/parse, 24.2×
  tsgo's JavaScript on node, at 197 s for one process. On mitt one process did not finish in 60 minutes; node
  takes ~6 s. A probe printing every round shows why: each round is slower than the one before (479 ms, then
  1,274 ms by round 16, at 1/40 of the driver), so the cost grows with work already done rather than per
  operation. The raw numbers are in `bench-history/kir-runs/perry-probe-20261018/`. Perry has no type checker,
  so it is a peer for what it computes, not for what it accepts.

<!-- KIR-BENCH-TABLE-START -->
Run `20261018T142401Z-852b3b3c76e3.json` — commit `852b3b3c76e3` (dirty tree), 2026-10-18T14:24:01Z, 5 processes per arm, interleaved.
Box: AMD EPYC-Rome Processor, 8 cores, 15.2 GB; java openjdk version 26.0.2 2026-07-21, node v22.20.0.
Placement: tsgo os scheduler (not recorded), xtsc os scheduler (not recorded), kir os scheduler (not recorded).

**mitt** — 4,000,000 ops/round; every arm printed `sink=128000000`.

| arm | class | median | per op | spread (min..max) | vs tsgo |
|---|---|---:|---:|---:|---|
| tsgo 7.0.2 -> JS -> node | runtime peer (baseline) | 361 ms | 90.25 ns/emit | 343..437 | baseline |
| xtsc -> JS -> node | control: our front end, same runtime | 364 ms | 91.00 ns/emit | 351..372 | 1.01x slower |
| xtsc -> JVM bytecode -> java | ours | 307 ms | 76.75 ns/emit | 298..546 | 1.18x faster |

**toml** — 20,000 ops/round; every arm printed `sink=-5440000`.

| arm | class | median | per op | spread (min..max) | vs tsgo |
|---|---|---:|---:|---:|---|
| tsgo 7.0.2 -> JS -> node | runtime peer (baseline) | 478 ms | 23.90 us/parse | 460..493 | baseline |
| xtsc -> JS -> node | control: our front end, same runtime | 477 ms | 23.85 us/parse | 468..501 | 1.00x faster |
| xtsc -> JVM bytecode -> java | ours | 710 ms | 35.50 us/parse | 694..727 | 1.49x slower |
<!-- KIR-BENCH-TABLE-END -->
