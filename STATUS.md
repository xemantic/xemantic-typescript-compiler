# Status

**(KAPI.4) DONE — a JVM Kotlin consumer links and runs a TypeScript library through its exported API (2026-10-19):** owner chose "kotlinc declares, KIR defines". The klib's own API source is compiled into the KIR JVM pipeline (`TypeScriptApi.kt`); `KirApiDefinition` fills top-level bodies by delegating to the lowering, routes properties through `x$get`/`x$set`, answers enum constants, and makes each exported class the LOWERED class carrying kotlinc's metadata (stub dropped, types re-pointed; the lowered file borrows an empty seeded file's metadata). The facade's `<clinit>` runs the API entry's module inits; a module init now runs once. `KirPlatformAgreementTest` 8 pins: all references resolve on both sides for the pinned library, mitt and smol-toml; JVM signatures agree; a consumer RUNS with node's answers; 3 ablations each fail their pin. KIR 342 / 0, warning-clean, bench equivalence agrees.

**(BENCH.4) DONE — Perry runs our fixtures unmodified, and is too slow on mitt to record (2026-10-18):** `KIR_BENCH_PERRY=1` adds Perry 0.5.1520 (SWC + LLVM 22.1.8; needs clang, both under gitignored `tools/`) as the TS-to-native peer, gated by the same `sink=` equivalence and refusing when absent. Measured one process each: toml 577.55 µs/parse = 24.2× tsgo→node, sink agrees; mitt did not finish in 60 min (node ~6 s), each round slower than the last (heap/GC growth), so no recorded run includes it. Prose + raw numbers: `docs/perf/kir-bench.md`, `bench-history/kir-runs/perry-probe-20261018/`. Also: bench.yml "Commit results" fixed (re-apply onto the fresh tip; the first queued run after it pushed cleanly).

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
