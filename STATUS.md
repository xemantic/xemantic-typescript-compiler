# Status

**(TSGO.8-a) DONE — Arm B has a checked-in generator; the incremental bench runs the port (2026-10-16):** the owner
decided (TSGO.8) — retire `-core`, deprecate `-project` (its incremental learnings queued as (TSGO.10)), ship the port's
image as `xtsc-linux-x64`, the tsgo-based module is the default artifact. `scripts/gen-many-small-project.py … 48 50
--lib es2020,dom --edits …` regenerates Arm B (`many-small-2400-dom`) and its four edit variants byte-identical;
`scripts/inc46-vs-tsgo.sh` times the port's native CLI through tsgo's own harness (`TSC_BIN`). Indicative, 2 reps, a
pre-(TSGO.9) image: port cold 735 / no-op 559 / body 644 / signature 633 ms against tsgo's 409 / 253 / 298 / 316, one
row in every cell. Next: (8-b) the shipped image.

**(TSGO.9) DONE — a denser AST through porter rules (2026-10-15):** (9-c) one object per node — every data struct
EXTENDS its node-embedding parent (`NodeDefault : nodeData : Node`), so a node, its data and the embedding chain are one
object (205 classes; the porter proves interface identity, type assertions and dispatch cannot tell them apart). (9-c)
alone: parallel **−7.8%** (4/4, GC −55%), single −2.4%. Across (9-a..c) on tsc's compiler profile: the AST's live heap
**188 → 100 MB** (197 B / 8.1 objects per node → 105 B / 3.0), a full check 327 → 234 MB live, warm single ≈ −12% and
parallel ≈ −14% chained. Diag/Emit 13,127, LS 21,614, API 594,007, CLI 120 / 121 equal; `-tsgo` 156 / 0, `-goport` 31 / 0,
KIR 308 / 0, `-daemon` 63 / 0, `-lsp` 38 / 0, `-externals` 291 / 0, `linuxX64Test` 91 / 0. Next: (TSGO.8), the owner's decision.

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
