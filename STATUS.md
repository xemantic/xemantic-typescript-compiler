# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **191,030** lines (was 191,155 at the metric's creation;
2 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.6) — IN PROGRESS (2026-09-02).** (EXT.7) the **smol-toml rung is GREEN**: the
externals generator goes MULTI-FILE (`generateKotlinExternals(List<SourceFileEntry>)`, one
Binder + one Checker, cross-file by-name rendering, cross-file type-name collisions a loud
skip), top-level overloads render (implementation signature omitted, duplicates collapsed),
`#private` omitted, heritage markers name the base, export wiring loud (`export {}` silent);
`KotlinExternalsSmolTomlGateTest` embeds the verbatim seven `smol-toml@1.7.1` files and
metadata-compiles the output with zero checker diagnostics (externals 64/0; full suite
16,815/0/3). (TEST.1) the "order-sensitive" `ProjectTrustedFilesystemTest` control was a
DATA RACE in the test's own `CountingVfs` under the crawl's 16 concurrent readers (old
wrapper: 12,880 of 16,000 threaded reads counted); atomics + a CAS-swapped per-path map,
`CountingVfsConcurrencyTest` reddens the old wrapper (full suite 16,816/0/3).

**(P18.5) — DONE (2026-09-02).** Owner additions applied ((INV.0) merged with
receipt protocol, INVERSION-DESIGN § 10 cost-neutrality contract, approvals recorded,
shrinkage dashboard row); (LIC.3) CONTRIBUTING.md; (EXT.4) classes + enums landed
(externals 40/0 — `external class` with primary ctor + companion statics;
`sealed external interface` enums; `const enum` refused loudly; full suite 16,775/0/3);
(INV.0) STEP 1: `TypeInterner` extracted — first Stage-0 collaborator, ambient surface
NONE, suite 16,781/0/3 byte-identical, cost_gate +0.00%, wall NOISE-DOMINATED at +0.26%,
allocation profile unchanged, and the receipt protocol found the split IMPROVED hot
inlining (the 277 B monolith never hot-inlined; the 13 B hop + body both do);
(API.18) file-final token healed by an ownership descent (suite 16,791/0/3, the LSP
recorded-edge pin flipped to healed, punctuation-final files pinned conservative);
(EXT.5) generic aliases + generic methods + method overloads (externals 47/0);
(EXT.6) default exports + generic references to generated targets — **the mitt rung is
GREEN** (verbatim mitt@3.0.1 d.ts generates and metadata-compiles; externals 52/0);
(INV.0) step 2: `Relation`+`Ternary` relocated to `TypeRelationCache.kt` (suite 16,803/0/3).

**(P18.4) — SESSION CLOSE: THE PHASE 18 FIRST ARC IS LANDED END-TO-END, 16,764 / 0 / 3
(2026-09-01).** In one session under the re-pointing directive: the directive persisted;
licence strings aligned ((LIC.1), with (LIC.2) POM drift flagged BLOCKED-PENDING-USER); the
tsgo comparison made honest ((DOC.1)) and then MEASURED against the right tsgo ((LSP.3));
README repositioned on `docs/reposition` ((DOC.2), awaiting owner review); the 142-method
census written ((INV.D): A=94 / B=15 / C=33, (INV.1) proposal BLOCKED-PENDING-USER); the
externals generator through THREE rungs ((EXT.1-3): interfaces, generics, references,
typealiases, functions, function types — 29 pins, zero-classpath metadata compile gate);
the LSP server feature-complete for a first release ((LSP.1-2): 58 pins, nativeImage
wired); (API.18) honestly refused twice with the mechanism recorded. `cost_gate.py`: every
counter unchanged all session — the INC-closure directive holding by construction. Next
top items: (EXT.4…n) ladder, (INV.0) split (Stage 0 of the inversion), (API.18)'s
sibling-bound descent.

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
