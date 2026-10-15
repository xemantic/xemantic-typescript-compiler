# Status

**(TSGO.9-a) DONE — a node's `Loc` is a packed value class, not an object (2026-10-15):** the AST is 58% of the live heap
after a check, at 197 B and 8.1 objects per node (docs/goport-perf.md § 29); most of those objects are lowering artifacts.
First porter rule ("Packed structs", docs/goport-lowering.md § 3): `core.TextRange` is a `@JvmInline value class` over a
`Long` (the run proves no field is ever assigned or address-taken), a pointer to one a live `GoPtr`. Warm, compiler
profile: single **−6.9%** (4/4), parallel −3.8% (3/4), allocation −7.1% / −5.7%; the AST's live heap 188 → 170 MB.
Diag/Emit 13,127, LS, API 594,007, CLI 120 / 121 equal; `-tsgo` 153 / 0 (`GoProcsTest`'s timing pin flaked once under the
full suite, 3/3 alone), `-goport` 28 / 0. Next: (9-b) the atomic `id` wrapper, (9-c) one object per node.

**(TSGO.7) DONE — every consumer moved; (TSGO.7-d/e/f/g) `-kir` no longer depends on `-core` (2026-10-14):** KIR's value model is its own
(`kir.model`); the lowering walks tsgo's tree converted to KIR's own syntax classes (`front/TsgoSyntax.kt`, `kir.ast`)
instead of a second parse by `-core`'s parser paired by position. The converter was held to `-core`'s parser on all 44
corpus files (equal but one — a `-core` bug: raw template text compiled `\n` to backslash-n; fixed and pinned,
`KirTemplateEscapeTest`). KIR 308 / 0, `kir-bench.sh` equivalence green, its JavaScript arm on the port (byte-identical
to tsgo's). Next: (TSGO.8) — the owner's decision on `-core` (`-project`, `-cli`'s image, coordinates).

**(TSGO.7-b/c) DONE — the JVM-vendor bench on the port; KIR's `-core` front end removed (2026-10-14):** `bench-jvms.sh`
times `CheckBenchMain` / `TsgoMain` (CI run 38647609748 green, 65 errors in every arm); its one rejected rep exposed a
`CheckBenchMain` emit-counter race (fixed). Two flaky `--watch` pins fixed (atomic edits). KIR's `XTSC_KIR_ENGINE=core`
front end and `StructuralCensus` deleted; KIR 306 / 0. Next: the lowering's value model, then its syntax tree.

**(TSGO.7-a) DONE — the compile server, `xtsc`'s one-shot mode and `scripts/xtsc` run the port (2026-10-14):**
`-daemon` depends on `-tsgo` instead of `-core`: `CompileServer` runs `TsgoCli.run` per request with the request's
directory as tsc's current directory (nothing process-global installed or restored), `XtscMain`'s one-shot mode is
`TsgoMainKt`'s `main` and the `--daemon` fallback its new `runMain`. `xtsc` now answers exactly as tsc 7 (`-p` for a
project; exit 0 / 1 / 2). Serve-parity matrix 31 / 31 PASS; tsc's compiler profile served over a real socket: 75 lines
byte-identical to tsgo on every request, emit tree identical (78 files), warm requests ~1.6 s server-side. `-daemon`
63 / 0 (the two `-core` mode-leak pins retired), `-tsgo` 153 / 0, warning-clean.

**(OWNER 2026-10-14) DONE — the opt-in no-GC image and the published bench on the port:** `-PnativeImageGc=epsilon`
(`tsgo-native-image-pgo.sh --gc epsilon`) is faster than the G1 PGO image on every project measured (mitt 40 → 20 ms,
services 2,470 → 2,320 ms) at up to +50% peak RSS; out of memory it exits 3 (`ExitOnOutOfMemoryError` — the first probe
hung), and `TsgoMain` now ends a main-thread failure with Go's status 2. `bench.yml` measures the port (series V3: warm
`CheckBenchMain`, cold `TsgoMain`, the `-tsgo` image, ratios against tsgo). `tsgo-ab-warm.sh` had medianed warm-up
rebuilds — fixed, docs/goport-perf.md §§ 13/14/19 annotated. `-tsgo` 153 / 0, `linuxX64Test` 91 / 0, replay 121 / 121.
