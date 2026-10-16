# Status

**(TSGO.8-d) DONE — `-project` deprecated (2026-10-16):** `Project` carries `@Deprecated` naming its successors, the
port's `TsgoProject` (type oracle) and `TsgoLanguageService` (language features) in
`com.xemantic.typescript:xemantic-typescript-compiler`; its KDoc says what is not carried over yet (in-session
incremental updates, partitioned diagnostics, saved state — (TSGO.10), queued with the measured learnings). Callers see
the warning (251 sites inside `-project` would); the module silences its own (`-Xwarning-level=DEPRECATION:disabled`).
`ProjectDeprecationTest` pins it; `-project` 957 / 0. Next: (8-e) the deletion — BLOCKED-PENDING-USER on when.

**(TSGO.8-c) DONE — the port is the default artifact (2026-10-16):** `-tsgo` is published as
`com.xemantic.typescript:xemantic-typescript-compiler` (`-jvm`), jar `xemantic-typescript-compiler-jvm-<ver>.jar`, with
sources, an empty javadoc jar and typescript-go's `LICENSE` / `NOTICE.txt` under `META-INF/typescript-go/` (POM: the AGPL
entries plus Apache-2.0 for the upstream). `-core` takes `xemantic-typescript-compiler-core` for its jar too; `-cli` and
`-daemon` POMs now depend on `xemantic-typescript-compiler-jvm`. Nothing was ever released (snapshots only), so no
consumer's coordinates move. `DefaultArtifactTest` pins the jar name and licence files (fails on the old build).
`-cli` 20 / 0, `-daemon` 63 / 0, `-lsp` 38 / 0, `-externals` 291 / 0, KIR 308 / 0, `-tsgo` 156 / 0. Next: (8-d).

**(TSGO.8-b) DONE — the shipped `xtsc` image is the port's (2026-10-16):** `native.yml` uploads the port's PGO image
(replayed against tsgo 7.0.2 in the same job) as `xtsc-linux-x64`, binary `xtsc`; `-core`'s image job,
`scripts/build-native-pgo.sh` and `-cli`'s `nativeImage` task are gone. `-cli` is the lean one-shot JVM CLI over the
port (`api(-tsgo)`, still refusing `--serve`/`--daemon`/`--socket` with exit 2); `-core` is off its classpath
(`LeanClasspathTest`), and `NativeImageEntryPointTest` pins the shipped artifact to the port's image (fails on the old
workflow). A real project build is now TypeScript 7.0 wherever `xtsc` ships. `-cli` 18 / 0. Next: (8-c) coordinates.

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
