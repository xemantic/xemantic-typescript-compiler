# Status

**(TSGO.9-b) DONE — the atomic `id`/`facts` wrappers are volatile fields of their holder (2026-10-15):** porter rule
"Inlined atomic fields" (docs/goport-lowering.md § 3): `Node.id`, `Symbol.id` and `CompositeBase.facts` are only ever
loaded, stored and compared-and-swapped, so each is a `@Volatile` primitive; a swap takes a stripe lock (common code —
`linuxX64Test` 91 / 0). Single −3.2% (3/4), GC −28%, allocation −3.6%; the AST's live heap 170 → 136 MB (142 B and 5.3
objects per node, from 197 B / 8.1 before (9-a)); a full check leaves 270 MB live, was 327. Diag/Emit 13,127, LS 21,614,
API 594,007, CLI 120 / 121 equal; `-tsgo` 155 / 0, `-goport` 30 / 0. Next: (9-c) a node and its data as one object.

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
