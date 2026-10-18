# Status

**(KAPI.2) 2026-10-18 — the platform-half agreement is pinned, and it does not hold.** `KirPlatformAgreementTest` (6 pins) compiles one reference per exported declaration as common code against the klib and as JVM code against the KIR classes from ONE export; its positive control (kotlinc's JVM compile of the same API source) resolves everything. Measured: the JVM consumer resolves NOTHING KIR emits — every class is `@Metadata(k=3)` (synthetic, empty), which kotlinc skips — and beneath that the JVM names disagree (`MainKt`/`ApiKt`, `x$get`/`getX`, `object$`/`object`, no enum class); class members and `mitt`/`parse` erasures agree. Fix is a design choice → (KAPI.4) BLOCKED-PENDING-USER. KIR 340 tests, 0 failures, warning-clean.

**(KIR.LOWER.2) DONE — a named library type with no runtime class is refused, not erased to the bag (2026-10-18):**
the lead held on the port: `function f(p: Promise<string>) { p.then(…) }` COMPILED, because an instantiation carries
no symbol of its own and `ErasedTypes.mapObject` read its declaration as null ("anonymous object" → bag). It now
falls back to the reference's TARGET symbol, so the type has its lib declaration and refuses ("cannot map the type
'Promise<string>'"); `WeakMap` still maps to `JsMap` by design. No other erasure moved: KIR 334 / 0 and the
`kir-bench.sh` equivalence gate agree on mitt and toml. Pin red under ablation. Next: (KAPI.2).

**(BENCH.2) DONE — placement is an option with a banner, graded and NOT adopted as the default (2026-10-18):**
`KIR_BENCH_PIN=<cpus>` runs the node/bun/native/wasm arms under `taskset -c`, never the JVM arm (its C1/C2 threads are
part of it); every run prints each arm's placement first and records it in the JSON, and the generated table shows
it. Paired in one round (A/B/A/B, 5 processes each, pinned to CPU 3): the control arm's CV fell (mitt 2.3/2.7% →
1.8/0.8%, toml 4.2/4.1% → 2.3/1.4%) but the tsgo baseline's did not (mitt 2.2/1.7% → 1.1/3.2%, toml 1.4/0.9% →
2.1/1.0%), so the default recipe is unchanged and the series does not restart. The JVM arm's 500+ ms mitt outliers
appear unpinned too (an earlier run today) — JVM noise, not placement. Receipts:
`bench-history/kir-runs/placement-ab-20261018/`. Next: (KIR.LOWER.2).

**(BENCH.3) DONE — the KIR bench's table is generated and refuses itself (2026-10-18):** `scripts/kir-bench.sh` writes
the run as schema-1 JSON (commit, dirty flag, box, the arms actually run, per-process samples, median/sigma/min/max,
each library's `sink=` verdict); `--record` commits it under `bench-history/kir-runs/` and
`scripts/kir_bench_report.py` regenerates `docs/perf/kir-bench.md` between markers, losses included and labelled
(runtime peer / control / ours). The generator refuses a run with non-positive medians, a ratio disagreeing with its
medians, rows short of arms × libraries, sample counts off the process count, or a split equivalence verdict —
`--self-test` proves all five (and fails when the checks are ablated). First recorded run (5 processes): mitt KIR
1.18× faster than tsgo/node, toml 1.49× slower; README's KIR row now cites it instead of pre-port prose.
Next: (BENCH.2).

**(KIR.NATIVE.2) DONE — a TypeScript `function main()` compiles, and a backend clash no longer reads as success
(2026-10-18):** re-measured on the JVM first: a top-level `function main()` (or `moduleInit()`) has the generated
entry's / initializer's JVM signature, the backend files `CONFLICTING_JVM_DECLARATIONS` with the generation state's
reporter, which `KotlinIrEmitter`'s hand-assembled pipeline never forwarded — the result said "emit succeeded, 0
class file(s)" and the program died on a missing class. Now: the backend's errors are the failure reason, an emit
that wrote no class file fails, and user top-level functions named `main` / `moduleInit` get the `$` suffix a keyword
gets — which also leaves the native plugin's by-name lookup exactly one entry. Three pins, each red under its
ablation. KIR 333 / 0, warning-clean, `kir-bench.sh` equivalence green. The native link itself was not run here
(Kotlin/Native lives in CI). Next: (BENCH.2).
