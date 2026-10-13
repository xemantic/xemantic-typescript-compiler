# Status

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

**(TSGO.6-o) DONE — the bundled libs parsed at image build time; the PGO image at or past tsgo on 5 of 6 projects
(2026-10-10):** a lib `.d.ts` parse depends only on name, path and text, so `TsgoLibSnapshot` parses every bundled lib
inside the GraalVM builder (the port initialized at build time, `GoProcs` excepted) into the image heap, and the
`compilerHost.GetSourceFile` override answers a tree only for the same options and text. `scripts/tsgo-native-image-pgo.sh`
builds it (the plain Gradle task is unchanged — owner decision). PGO + snapshot vs tsgo 7.0.2: tsc's compiler profile
**1,722 / 1,728 ms**, services **2,305 / 2,335**, immer 544 / 549, ky 123 / 125, mitt **70 / 92**, date-fns 483 / 359
(docs/goport-perf.md § 21). CLI replay 106 / 106 with and without the snapshot, Diag 13,127 equal; native 79 / 0. Then
(§§ 22-23): bounded spin guards (date-fns -10%), one `stat` per host question (16.7k -> 9.1k syscalls), in-place glob
matching (date-fns config 73-86 -> 49-63 ms), and `TSGO_IO_STATS` / the case-sensitivity probe kept out of build-time init.

**(TSGO.6-n) DONE — transparent wrappers: an `ast.Identifier` is 6 objects, not 13 (2026-10-10):** Go embeds by value;
the port made each `*Base` wrapper level its own object. A porter rule lowers the 9 wrapper structs with no field or method
of their own as `typealias`es of what they wrap (the expression chain onto `NodeDefault`), and a selection through one is
no step. Warm wall **−5.8% single / −2.6% compiler parallel / −5.2% services**, allocation −5% (docs/goport-perf.md
§ 19). The shipped PGO image is now 1,755 / 2,352 ms on tsc's compiler / services profiles against tsgo's 1,737 /
2,286 ms (§ 20). Diag 13,127 equal, Emit/CLI/LS/API parity green; `-tsgo` 136 / 0, `-goport` 26 / 0, `-lsp` 38 / 0, native 79 / 0.
