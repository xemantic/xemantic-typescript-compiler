# Status

**(KIR.BIND.1) DONE — a nested `function` is a typed local function and its calls are direct (2026-10-18):** the
blocker was `-core`'s binder (a nested declaration had no signature); tsgo binds it, so the lowering now builds a
typed local IR function where the expression form's lambda used to be built (top of the list when hoistable, else
at its own statement) and a call already past that point is DIRECT — 0 `jsCall` where it was 1. The slot keeps the
function object (a forwarder; `JsVarargFunction` for a rest parameter), so value positions and per-invocation
identity are unchanged; a call lowered before emission (a forward call in mutual recursion, the TDZ case) stays
dynamic. Overload signatures map to their implementation. 5 new shape pins (all red with the direct arm ablated);
KIR 313 / 0, warning-clean, `kir-bench.sh` equivalence green (mitt, toml). Next: (KIR.LOWER.7), nested classes.

**(TSGO.8-e) DONE — `-core` and `-project` deleted (2026-10-18, owner: "please delete the core"):** both modules
gone with `-core`'s Kotlin/Native CI job, the generated-corpus pipeline, the `partition-gate` fixture, the
`corpus-triage` skill and ~380 frozen-era scripts (scripts/ 497 → 61 files: what CI, CLAUDE.md, the port's and KIR's
docs reach). The pinned `cloneTypeScriptRepo` / `cloneTypeScriptGoRepo` tasks moved to the root build;
`bench-compile-tsc.sh` is the profiles' materializer only; `ParseBenchMain` keeps its `tsgo` arm. 119 superseded queue
items closed; the WORK ORDER is now KIR. Two (TSGO.9-c) warnings fixed. `-tsgo` 156, `-goport` 31, `-cli` 20,
`-daemon` 63, `-client` 20, `-api` 30, `-lsp` 38, KIR 308, `-externals` 291 — all 0 failures; warning-clean.
Next: KIR — (KIR.LOWER.7).

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
