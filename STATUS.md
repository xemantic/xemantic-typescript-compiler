# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **191,070** lines (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: three
checker reads, one table write, stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.9) — THE RxJS CORE RUNG COMPILES, 16,867 → 16,881 / 0 / 3 (2026-09-02).** (EXT.11a):
`rxjs@7.8.2`'s 15 `internal/` declaration files generate with zero checker diagnostics and
the generated Kotlin metadata-compiles (`KotlinExternalsRxjsGateTest`, verbatim,
Apache-2.0). Three compile errors, two mechanisms: interface CALL SIGNATURES (rendered as a
nameless method) are now function-type aliases — `public typealias UnaryFunction<T, R> =
(T) -> R`, and an empty interface over one is an alias to it
(`OperatorFunction<T, R> = UnaryFunction<Observable<T>, Observable<R>>`, transitively),
nameable but never a supertype; `typeof Action` (rendered as the un-instantiated instance
type, CHK.73) now refuses with a marker naming the written query, plus an ARITY GUARD in
the type mapper. One silent defect fixed: a function type's `this:` parameter rendered
positionally — now a Kotlin RECEIVER (`SchedulerAction<T>.(T) -> Unit`). New instrument:
`ExternalsLibraryProbe` (env-gated; generated Kotlin, compile errors, diagnostics, a marker
census per mechanism). 10 exact pins, each red by stash-ablation; externals 94/0. Census
after: 97 markers / 0 errors — nullable unions, `any`, arrays and literals are the next
rung ((EXT.11b), queued); a `val plain: Plain` class-value defect queued as (CHK.73b).

**(P18.8) — STAGE 2 OF THE INVERSION LANDS: THE POST-HOC TYPE ORACLE; THEN THE EXTERNALS ALIAS-REFERENCE RUNG, 16,838 → 16,867 / 0 / 3 (2026-09-02).**
**(INV.2)** (owner-approved this session): `TypeOracle` over the (INV.1) store + retained
graph + live checker — `typeAt` / `symbolAt` / `resolvedCallAt` / `contextualTypeAt` /
`typeOfSymbolAt` recorded during the walk, the bin-A rows forwarded at rest, `resolveName` /
`symbolsInScope` refused naming Stage 3, per-build handles, `close()` on edit; entries
`typeOracleOf(files)` and `ProjectCompiler.build(…, oracleHolder)`; the store grew
`symbols` / `calls` / `contextual`; per-row divergences in `docs/type-oracle.md`; 23 pins;
cost_gate +0.00 %. **Flag ON measured: compiler profile +21.5 % (1.90 µs per recorded
expression), many-small-2400-dom +6-7 % (0.95 µs)** — after the first arm read +57-64 % and
a per-channel attribution + JFR found the object-literal KEY leg re-typing its literal per
key (`getTypeOfExpression` has no per-node memo; O(keys²) on tsc's message tables), fixed by
reading the store. (INV.2b) queued: `Project` integration with the invalidation decided.
Design record: `docs/INVERSION-DESIGN.md` § 9b. **(EXT.10)**: references to a generated
alias render by NAME where the resolved body has no Kotlin spelling (`Handler<string>` →
`Handler<String>`; function-typed aliases now emitted and named) under identity evidence
through the new lens member `typeReferenceSymbol`; Dukat pin kept; 7 pins, externals 80/0.
**(INV.1b)** answered: a reconstruction-only arm (`nodeAnswers:reconstruction`) reads the
plain check (5,290 / 5,266 vs 5,270 ms) while types-only reads 6,158 / 6,121 — the whole
1.45 µs per expression is `getTypeOfExpression` re-typing what the walk already typed.

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

