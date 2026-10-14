# Status

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

