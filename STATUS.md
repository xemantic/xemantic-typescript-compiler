# Status

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

**(KIR.LOWER.7) DONE — a class declared in a body lowers (2026-10-18):** a Kotlin IR LOCAL class built whole at its
own statement (shell, members, override links, the (LIB.6) bag protocol, bodies — a class is not hoisted), so Kotlin's
closure lowering gives methods their captures; its VALUE is a fresh `JsConstructor` per execution, in a block-scoped
slot, so `outer() !== outer()` as in node, while `new P()` constructs directly. Before the statement (the enclosing
function's pass-1 signature) its type erases to the bag. Named refusals: a `static` member (a JVM static is per
program), a reference lowered above the statement. One divergence pinned: `instanceof` does not tell two invocations'
classes apart. Fixed on the way: a class's STATIC side (`typeof C`) erased to its INSTANCE type for every class — now
`JsConstructor`. `KirNestedClassTest` 17 pins from node (all characterised against tsgo 7.0.2 + node); the four old
refusal pins re-pointed to node's answers. KIR 330 / 0, warning-clean, `kir-bench.sh` equivalence green.
Next: (KIR.NATIVE.2).

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
