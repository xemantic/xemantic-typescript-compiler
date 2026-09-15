# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,993** lines (**−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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
**(P18.103) — (LEGACY.1) STEP (c): TypeScript 7 BINDS EVERY FILE STRICT, SO `alwaysStrict: false` WAS FOUR DEAD ARMS AND ONE MISSING DIAGNOSTIC, 19,382 / 0 / 83 (2026-09-15).**
Six configurations measured on tsgo (LSP diagnostics + emitted JS): TS1101 at a `with` and `"use strict"` in
every file whatever the flags say; the Go binder has no `inStrictMode`. The Emitter suppression, the
`spineWithStrictActive` gate, the `explicitNonStrict` false-disjunct and a TS1344 return are deleted; the
removed-option row the item said to KEEP did not exist and was added (TS5107 at the 6.0 default, TS5108
value-anchored under 7.0). Found for later: `strict: false` alone is still strict in tsgo (three TS1212 rows
ours lacks — (LEGACY.0b)), and the PROJECT path anchors no deprecation row (family-wide, (d)). 14 pins, five
disjoint arms; screens 3,084/0 + 5,688/0 and grid 8×0/0 + emit 78/78 all counted controls; cost_gate 20/20
+0.00%; huge_methods exit 0 (871 classes); warning-clean.
**(P18.102) — (LEGACY.1) STEPS (a)+(b): THE DEAD System-MODULE HELPERS, −249 LINES, 19,370 / 0 / 83 (2026-09-15).**
The first removal round of the TS7-only directive, picked over (LEGACY.0)'s 58-singleton tail (said so, successor
(c)). Two `Transformer.kt` clusters deleted, closed by a repo-wide reference census rather than the compile
(mutually recursive helpers compile either way); `collectBoundNames` stays with 34 callers. The item's "fold
System onto CommonJS" is NOT today's behaviour — System/AMD/UMD pass module statements through untransformed —
so it is step (f)'s routing change, with `TypeScriptCompiler.kt`'s five System arms and a never-`false`
`wrapCallsWithZero` parameter. Screen emit 5,688/0 (the instrument), errors 3,084/0; cost_gate 20/20 +0.00%;
huge_methods exit 0 (871 classes); grid 8×0/0 + emit 78/78 (controls, counted); warning-clean.
