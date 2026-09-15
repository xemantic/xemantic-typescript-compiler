# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,996** lines (unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
the metric was created, and the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200 in between, which
are fixes and pins rather than extractions — so the file is now BELOW where the metric started
WITH that work still in it). TEN collaborators extracted: `TypeInterner`, `Relation`+`Ternary`
and **`LexicalScopeResolver`** — ambient surface NONE for all three — `TypeInstantiator`
(4 reads, 1 write), `NameResolver` (2,284 lines, 26 reads), `Relater` (1,446 lines),
`MemberResolver` (834 lines, 22 reads), `MemberNames` (771 lines, 4 reads / ZERO writes),
`EnumSemantics` (1,138 lines, 13 reads / ZERO writes) and `CaptureRecorder` (3,214 lines,
61 reads / 9 writes — the arc's largest MOVE and its largest ambient ROW, which is
`docs/INVERSION-DESIGN.md` § 2's "the answers are functions of walk-scoped state" as a number
rather than an argument), all stated in the ledger. The ambient TOTAL first fell at (P18.59),
which wired `Relater` and `MemberNames` to `EnumSemantics` DIRECTLY. **STAGE 0 IS NOW
DECIDED-DOWN rather than exhausted**: (P18.61) sized what is left — 50.6% of the file is check
passes, whose candidate collaborators census at 59-97 ambient reads (`cae*`: 97 reads for EIGHT
declarations) — and turned the arc toward Stage 3. Reference points: tsc ≈ 50k lines (one file),
tsgo 60,479 across 25 files. Contract: `docs/INVERSION-DESIGN.md` § 10; ledger:
`docs/inversion-ambient-ledger.md`.

**(P18.108) — (LEGACY.1) STEP (h): `outFile` WAS ALREADY INERT ON THE PROJECT PATH — SIX HARNESS-ONLY ARMS DELETED, THE REFERENCE EDGES KEPT AS tsgo's PROGRAM ORDER, 19,506 / 0 / 83 (2026-09-15).**
34 scratch projects on tsgo: TS5102 at the quoted key, per-file emit, `--listFiles` order decided by
`/// <reference path>` edges with and WITHOUT `outFile`, no TS6082 emitter, TS5074 without an outFile read. The
six arms only the harness reached are deleted (the single-file name, the flattened layout, the topological
transform order, the no-outDir `.js` admission, the None/outFile drop, TS5074's conjunct); the item's
"reference-directive ordering only used when outFile is set" was a stale comment and the edges stay. 19 pins —
the six `-project` ones green on both arms and named controls (the tsgo-shaped receipt); five of six arms
discriminate, `transformOrder` had no observable. Screens 3,084/0 + 5,688/0 and grid 8×0/0 + emit 78/78 all
counted controls (0 live test sources mention outFile); cost_gate 20/20 +0.00%; huge_methods exit 0 (874
classes); warning-clean with an injected positive control. (g) is BLOCKED-PENDING-USER: 28 active embedded-tsconfig
`baseUrl` baselines tsgo never ran.
**(P18.107) — (LEGACY.1) STEP (f): amd/umd/system FOLD ONTO CommonJS AS A *PROPERTY* OF THE KIND, THREE OF NINE ARMS WERE DELETABLE, 19,487 / 0 / 83 (2026-09-15).**
240 cells on tsgo (emit via `--outDir`, rows via the LSP): the removed kinds emit byte-identically to commonjs in
17 of 20 programs (three tsgo residues in a removed configuration, recorded not copied) and check identically
except System's three live arms (TS1218, top-level await, `import.meta`), which stay. `ModuleKind.foldsToCommonJS`
replaces the untransformed pass-through; TS5071 (no tsgo emitter), the TS2882 exemption, the System TS2305
suppression, the tslib exemptions and the never-`false` `wrapCallsWithZero` parameter are deleted; `export as
namespace` untouched (`-externals` 290/0). **The corpus is a counted control here** — no active subtest runs under
a removed kind — so the 23 pins are the whole gate; nine arms all discriminating. cost_gate 20/20 +0.00%;
huge_methods exit 0 (874 classes); grid 8×0/0 + emit 78/78 (controls); warning-clean. Seven pre-existing
commonjs-cell gaps found for the ledger.
**(P18.106) — (LEGACY.1) STEP (e): NO "classic" RESOLUTION EXISTS IN TypeScript 7 — ONE DERIVATION REPLACES FIVE, AND A REMOVED VALUE HAD LIVE CORPUS COVERAGE, 19,465 / 0 / 83 (2026-09-15).**
42 cells measured on tsgo: classic/node/node10 equal the unset cell bar the TS5108 row, unset derives
Node16/NodeNext/Bundler from the module kind; TS5070 and TS2792 have no emitter in tsgo. Five string-typed
derivation copies in the Checker and one in TypeScriptCompiler become `effectiveModuleResolution`; the classic
family, TS5070 and the classic root-tslib rule are deleted; TS5109 added; two importer-format gates that had
been keyed on resolution fixed in both directions. **The errors screen was a real gate**: 17 active baselines carry
`node10`/`node` in an EMBEDDED tsconfig `usesUnsupportedOption` never sees, and two arms move exactly them. 38
pins, twelve arms all discriminating; cost_gate 20/20 +0.00%; huge_methods exit 0 (874 classes); grid 8×0/0 +
emit 78/78 (controls); warning-clean. `Checker.kt` −89. (f) inherits TS5071 (no tsgo emitter, now visible on
`*×system`); (g) inherits a load-bearing emit-order ancestor walk.
**(P18.105) — (LEGACY.1) STEP (d2): TypeScript 7 READS NEITHER INTEROP FLAG, THE SYNTHETIC DEFAULT IS A PROPERTY OF THE *TARGET*, AND (d) IS CLOSED, 19,427 / 0 / 83 (2026-09-15).**
45 scratch projects on tsgo (five module families × the {unset,false,true}² matrix, 8 targets, 4 import forms):
every cell byte-identical on diagnostics and emit except the TS5108 row; in the Go source both fields are read
only by the removed-option diagnostic. Deleted: the 12 no-interop Transformer arms, the TS1259/TS2617 emitters,
NameResolver's gate, the explicit-false conjuncts, and the two boolean options; changed at the default: tsgo's
`canHaveSyntheticDefault` (a `.d.ts` with named exports is default-importable, an explicit `true` no longer
blanket-skips TS1192) and TS2595/TS2616 keyed on `module`. 25 pins, seven arms all discriminating; screens
3,084/0 + 5,688/0 a counted control (the reachable fixtures are dropped or target `.ts`); cost_gate 20/20 +0.00%;
huge_methods exit 0 (872 classes); grid 8×0/0 + emit 78/78 (controls); warning-clean. `Checker.kt` −50. Six
pre-existing import divergences recorded for (LEGACY.0b)/(e).
**(P18.104) — (LEGACY.1) STEP (d1): DEPRECATION AND REMOVED-OPTION ROWS ANCHOR AT THE tsconfig TOKEN ON BOTH PATHS, FROM ONE SCANNER, 19,402 / 0 / 83 (2026-09-15).**
tsgo's rule measured over 18 projects: TS5107/TS5108 at the VALUE, TS5101/TS5102 at the KEY, root config only,
`extends`/CLI options at the root's `compilerOptions` key, file-less without it. The project path had no
positions at all and the harness path had two asymmetries of its own; `tsconfigOptionPositionsOf` +
`tsconfigAnchorFor` now serve both. 20 pins (16 in `-project`), five arms all discriminating — the errors screen
is a real gate here (`pathMappingInheritedBaseUrl` moves under two arms); cost_gate 20/20 +0.00%; huge_methods
exit 0 (872 classes); grid 8×0/0 + emit 78/78 (controls); warning-clean. Residue: the scanner is a text scan and
records a commented-out option. (d2), the interop flags' behaviour, is next.
