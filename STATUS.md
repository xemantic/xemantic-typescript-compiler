# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **195,132** lines (unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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
**(P18.101) — OURS-ONLY ROWS ON PLAIN TS: SEVEN CLOSED, THREE TS2309 HALVES MATCHED, AND THREE OF SIX "MECHANISMS" WERE tsc-6 TRANSCRIPTIONS, 19,370 / 0 / 83 (2026-09-15).**
Pending 66 -> **58** (7 removed, 3 entries rewritten to a measured residue), skipped -7, +26 pins, 9 modules
asserted. Picked by THEME — rows where ours reports what tsgo 7.0.2 does not, or prints the wrong head — sized
with one screen run per candidate. TS2346 has ZERO tsgo call sites and is retired; TS2309 is tsgo's
value-exports-or-shadowed-namespace rule with no `.d.ts` skip and no JS guard (an alias to an UNEXPORTED namespace
member had to be classified tsgo's way — the screen caught the one green baseline it moved); the TS2300 at an
`export { Sub }` specifier was a one-fixture pin walker (deleted); `RelationHeadSuppression` gained tsgo's two-arg
leaves (TS4104 / TS2859 / TS2321); TS2615 is no longer paired with TS2589; `bigintWithLib`'s triplicated chain
was HARDCODED in a `pinDiag` walker whose engine cannot answer the fixture (re-transcribed, said so). **The full
suite read FIVE reds, all pre-existing pins asserting tsc 6's form of the changed rules** — re-pointed after a
tsgo measurement each; their NAMES named no code, so a source grep could not have found them. Thirteen arms, all
discriminating (a2b: the JS `exports.p` gate is load-bearing on 5 green baselines). cost_gate 20/20 +0.00%, grid
8×0/0 + emit 78/78 (controls, counted), huge_methods exit 0 (871 classes), warning-clean, screen errors 3,084/0,
emit 5,688/0.
**(P18.100) — THE F8 SPAN/WIDTH FAMILY, TEN OF TEN IN SEVEN MECHANISMS, AND THE tsc-6 MIRROR'S EXPECTATIONS ARE FILES, 19,344 / 0 / 90 (2026-09-14).**
Pending 76 -> **66**, skipped -10, +29 pins, 9 modules asserted. File-less ORDER was not emission
order but the test-support comparator sorting by code — tsgo ranks an OPTIONS diagnostic before a
checker GLOBAL, now decided by our `start` marker convention; the related TS1356 anchors on a
nameless function's ASSIGNED name (measured over 8 parents — the brief said "keyword"); TS2447 on the
operator token; TS4032 over the whole expando assignment; TS1092 over the reparsed `@template` LIST
(five shapes, and `comment.text` runs two characters past `*/`); the pretty renderer prints one `~`
for a zero-width span; and astral characters are padded by RUNE in tsgo's harness while its regexp
scanner splits a non-BMP rune into surrogates — one row a tsgo-vs-tsc-6 divergence. `pathsValidation5`
REFUSED with the count: its tsconfig-first order protects 7 green `baseUrl`/`node` baselines tsgo
never runs, a (LEGACY.1) question. **The suite's one red was the tsc-6 mirror again**, and the agent's
grep missed it because the mirror's expectations are FILES, not class source — third counted
annotation. Ablation 10 arms, all discriminating; 20 of 29 pins red pre-change, the rest exactly the
controls. cost_gate 20/20 +0.00%, grid 8×0/0 + emit 78/78 (controls), huge_methods exit 0 (871
classes), warning-clean, screen errors 3,077/0, emit 5,688/0.
