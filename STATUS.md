# Status

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

**(TSGO.5-c) DONE — `tsc --watch` and `-b --watch` on the port; (TSGO.5) DONE (2026-10-13):** `execute`'s `Watcher`,
`execute/watchmanager`, `vfs/trackingvfs` and fswatch's platform-independent core (coalescing, debounce, filtering) ported
mechanically; only the OS layer is replaced — an overlay copy of `inotify_linux.go`'s backend over an `XtscNotifier`
(`runtime/FsNotify`: inotify on Kotlin/Native, `WatchService` on the JVM). New porter rules: `select`, `go`, interfaces
extending shimmed external ones structurally; `context.WithCancel`, `time.After`; a failing `WaitGroup` goroutine now
poisons only its own group's channel waits (a process-wide poison killed `--watch`'s debounce loop). `TsgoMain`: SIGINT/SIGTERM
end a watch with exit 0, stdout flushed per write. `scripts/tsgo-watch-smoke.py` (tsgo vs the JVM main: both scenarios
equal) joins the native CI script. Diag/Emit 13,127, LS 21,614, API 594,007, CLI 120 / 121 equal; `-tsgo` 152 / 0,
`-goport` 26 / 0, `-lsp` 38 / 0, `linuxX64Test` 91 / 0; warning-clean (docs/goport-cli.md § 2c). Native CI run 38388249235
green: image replay 65 / 65 and both watch scenarios equal to tsgo through the PGO image.

**(TSGO.5-b) DONE — `tsc --build` on the port (2026-10-12):** `internal/execute/build` ported; its task graph runs on Go
channels, now lowered onto `runtime.GoChan` (a failing goroutine poisons channel waits instead of hanging them). The
buildinfo reader decodes by STATIC type where the json shim cannot see one (`reflect.goJsonTarget`), so `-b`'s up-to-date
checks and `--incremental` re-runs read it as tsgo does; `time.Time.Format` and nanosecond mtimes. CLI parity 120 / 121
equal incl. 11 `build-*` + 4 `incremental-*` cases (docs/goport-cli.md § 2b); Diag/Emit 13,127, LS 21,614, API 594,007
equal; `-tsgo` 144 / 0, `-goport` 26 / 0, `-lsp` 38 / 0; native CI green (image replay 65 / 65, `linuxX64Test` 85 / 0).

**(TSGO.6-p) DONE — tsc-rs measured; plain relation keys landed (2026-10-12):** tsc-rs 0.5.0 (an LLM hand port of
tsgo to Rust) is 2.3-3.9x faster than tsgo 7.0.2 on our 10 projects (compiler profile 727 vs 1,864 ms; ours 1,841),
doing the same work at 2.2-4x less CPU — a data-model redesign (arena indices, column-store AST, no GC), not a
transferable cache. Landed: `checker.getRelationKey` override — a non-generic relation key is the value, not its xxh3
hash (`getRelationKey` 1.5% → 0.3% of the thread; wall inside the A/B noise). Priced and refused: its flow-walk skip
(no measurable effect on tsc-rs itself here), the small lookup levers (≤ 2.6% each), an Epsilon no-GC image (CPU
−20..−30%, wall better only on small projects, RSS +20..+50%; an opt-in image is a build change). docs/goport-perf.md
§ 28. Diag/Emit 13,127, CLI 105 equal; `-tsgo` 137 / 0, `-goport` 26 / 0. Then the native `-tsgo` CI jobs (`native.yml`
`tsgo-native-image` = `scripts/tsgo-native-ci.sh`: the PGO image replayed against tsgo 7.0.2's own output, 50 / 50
equal locally; `tsgo-kotlin-native` = `linuxX64Test`) — green on GitHub (run 38298712635: replay 50 / 50, native 79 / 0): **(TSGO.6) DONE**.

