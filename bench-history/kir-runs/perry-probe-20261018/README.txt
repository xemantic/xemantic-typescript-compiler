(BENCH.4) Perry 0.5.1520 (LLVM 22.1.8, --march native default) on the KIR bench fixtures, 2026-10-18,
AMD EPYC-Rome 8 cores, one process at a time, nothing else timed concurrently except where noted.

Fixtures compile UNMODIFIED (perry compile src/main.ts), both sinks agree with every other arm.

toml, full driver (20,000 parses x 16 rounds):  best 11,551 ms/round = 577.55 us/parse, sink=-5440000,
    197 s wall, 190 MB max RSS (toml-full-1-process.txt).  tsgo->node median: 23.90 us/parse  -> 24.2x slower.
mitt, full driver (4,000,000 emits x 16 rounds): did NOT finish in 60 min (RSS 582 MB and growing);
    an earlier attempt inside the equivalence gate was stopped at 30 min. tsgo->node: ~6 s per process.
mitt, driver scaled to 1/100 (20,000 iterations): 5,525 ns/emit best, sink=1280000 (= full/100), 3.9 s.
mitt, scaled to 1/40 (50,000 iterations), per-round times (ms), probe run concurrently with the toml run:
    warm 479 491 533 635 620 642 | meas 690 797 891 963 876 1177 1248 1292 1180 1274
    -> each round is slower than the last: cost grows with work already done (heap/GC), so the
       per-op figure is not a property of the code alone and the full driver diverges.
mitt, scaled to 1/10 (200,000 iterations): > 110 s (linear from 1/100 would be 39 s).
