# Status

**(P18.3) — THE LSP IS FEATURE-COMPLETE FOR A FIRST RELEASE, AND THE HONEST tsgo NUMBER IS
30-50x AGAINST US (2026-09-01).** (LSP.2): the full feature map onto `Project` — lifecycle,
navigation, completion, signatureHelp, rename-with-refusals-as-errors, pull diagnostics,
PROJECT-WIDE publishDiagnostics off the narrowed `diagnostics()` — 16 new pins (module 58/0,
warning-clean), nativeImage task wired (build needs a GraalVM host). (LSP.3), both servers
long-lived on tsc's 78 sources: tsgo `--lsp` answers a per-edit hover in **12-18 ms** where we
take **398-630** (their lazy NodeLinks answering vs our narrowed-build-per-question —
`docs/INVERSION-DESIGN.md`'s bin-B gap measured end-to-end); first open **255 ms vs 24.8 s**
(different work: we eagerly publish the whole 46-row project error list, which their LSP
cannot do at all — our wave: **524 ms, 5 files, exactly 46 rows**). Receipts caught the
46-vs-65 gap per-file. Published in `docs/perf/incremental-vs-tsgo.md` (LSP arm) + § 3b.
Suite unchanged **16,734 / 0 / 3** plus the 16 new LSP pins → next count on the full run.

**(P18.1/P18.2) — THE DOC ARC, THE 142-METHOD CENSUS, AND THE FIRST TWO PHASE-18 CONSUMERS
(2026-09-01).** (LIC.1)/(DOC.1)/(DOC.2 on `docs/reposition`)/(INV.D) landed in the main
context; then ONE two-agent worktree wave landed **(EXT.1)** — Kotlin externals from the
CHECKED program, alias-resolution pin `Species`->`String`, metadata-compile gate with a
negative control, 15 pins — and **(LSP.1)** — JSON-RPC/LSP over `Project`, initialize +
didOpen + hover, **LSP UTF-16 = Project offsets CONFIRMED identical modulo the 1-base** at
an astral-char pin, 42 pins. `docs/INVERSION-DESIGN.md` answers the WebStorm question:
of tsgo's 142 API methods, **A=94 answerable post-hoc today, B=15 walk-scoped (13 closable
by a record-during-walk NodeLinks store — (INV.1) proposal BLOCKED-PENDING-USER), C=33 not
checker questions**; on-demand flow is NOT required for the census. **The LSP's first
fixture found a `-project` defect ((API.18): a file-final token is unreachable without a
trailing newline)** — the mission thesis demonstrating itself: a new consumer finds what
the corpus structurally cannot. Also queued: (LIC.2) the root POM says Apache-2.0
(BLOCKED-PENDING-USER, build file). Suite on the
merged tree: **16,734 / 0 failures / 3 skipped** (+57: 15 externals + 42 lsp);
`cost_gate.py` exit 0, every counter +0.00% (the new modules move nothing — the control
passes); `huge_methods.py --fail-over 0` clean (core-only census: a CONTROL for the new
modules, per its own gotcha).

**(P18.0) — THE PROJECT IS RE-POINTED: TYPESCRIPT FOR THE JVM AND KOTLIN (owner directive
2026-09-01).** The WebStorm evaluation paused — their need was a post-hoc TYPE ORACLE (the
query shape of tsgo's `tsc/internal/api/proto.go`, 142 methods) and this checker's answers are
functions of walk-scoped state; tsgo is the free official default, so "a TypeScript compiler"
is not the mission. **The mission: no Node and no Go in the toolchain; an embeddable
whole-program checker (`Project`); a Kotlin externals generator with resolved types (the
Dukat/Karakum gap); the KIR JVM bytecode backend; an LSP anyone can try in five minutes.**
The directive is persisted in CLAUDE.md § "AI agent mission", the WORK ORDER at the top of the
PLAN-PHASE-5.md QUEUE (new items (LIC.1) (DOC.1) (DOC.2) (EXT.1…n) (LSP.1…n) (INV.D) (INV.0)),
and SESSION-PROMPT.md, so run-loop iterations cannot revert to the old mission. **The (INC.\*)
latency family is CLOSED** at a 94-110 ms incremental floor / 93-217 ms plugin query — further
INC rounds are REFUSED unless a plugin-facing query measures > 300 ms warm. Suite unchanged:
**16,677 / 0 failures / 3 skipped** (doc-only commit).
