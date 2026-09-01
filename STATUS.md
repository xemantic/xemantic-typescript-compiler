# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **190,878** lines (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook, an ADDITION and not an extraction;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: three
checker reads, one table write, stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.7) — TWO OWNER DECISIONS LAND: THE POM LICENCE AND STAGE 1 OF THE INVERSION, 16,828 → 16,838 / 0 / 3 (2026-09-02).**
(LIC.2) the root POM's `licenses` block now declares `AGPL-3.0-only WITH
LicenseRef-xtsc-output-exception` plus a second entry for the Output Exception (was
Apache-2.0; verified on the generated core JVM POM). **(INV.1) the per-file node-answer
store** (`NodeAnswerStore`, `Type` slots by `nodeId`, filled at the capture/sink hook under
the reconstructed ambient, first-wins, refusal before resolution), OFF by default behind a
`Checker` parameter / `--nodeAnswers`; 10 pins incl. the round-911 positive control (body
local `number` recorded vs `string` post-hoc) and the production-mode computation count at
0; cost_gate +0.00%, huge_methods clean, warm A/B flag-off NOISE-DOMINATED (3 rotated
pairs, sd < 1%); **flag ON measured: +14.9 % warm on the compiler profile (1.34 µs per
recorded expression, 598,455 of them) and +10.3 % on many-small-2400-dom (1.49 µs,
232,106)** — per-node, attributed next by (INV.1b). (INV.2) Stage 2 queued
BLOCKED-PENDING-USER. Design record: `docs/INVERSION-DESIGN.md` § 9a.

**(P18.6) — SESSION CLOSE, FIVE LANDINGS, 16,803 → 16,828 / 0 / 3 (2026-09-02).** (EXT.7) the **smol-toml rung is GREEN**: the
externals generator goes MULTI-FILE (`generateKotlinExternals(List<SourceFileEntry>)`, one
Binder + one Checker, cross-file by-name rendering, cross-file type-name collisions a loud
skip), top-level overloads render (implementation signature omitted, duplicates collapsed),
`#private` omitted, heritage markers name the base, export wiring loud (`export {}` silent);
`KotlinExternalsSmolTomlGateTest` embeds the verbatim seven `smol-toml@1.7.1` files and
metadata-compiles the output with zero checker diagnostics (externals 64/0; full suite
16,815/0/3). (TEST.1) the "order-sensitive" `ProjectTrustedFilesystemTest` control was a
DATA RACE in the test's own `CountingVfs` under the crawl's 16 concurrent readers (old
wrapper: 12,880 of 16,000 threaded reads counted); atomics + a CAS-swapped per-path map,
`CountingVfsConcurrencyTest` reddens the old wrapper (full suite 16,816/0/3). (INV.0) step 3:
`TypeInstantiator` extracted (the instantiation seam, ~290 lines verbatim, `Checker.kt`
191,030 → 190,771; ledger row 3 with the first NON-none ambient surface; suite 16,819/0/3 byte-identical, cost_gate +0.00%, ab −0.81% NOISE-DOMINATED, JFR alloc unchanged, the 10 B hop `inline (hot)`). (EXT.8) heritage to GENERATED targets (supertypes, `override`/`open`,
inherited constructors, `open external class`, cross-file bases via the new lens member
`heritageBaseSymbol`; externals 70/0; full suite 16,825/0/3). (EXT.9) exported values (`val`/`var`, literal consts widened) and
accessor pairs as properties (externals 73/0; full suite 16,828/0/3).

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

