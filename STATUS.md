# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,194** lines (+27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.113) — (LEGACY.1) STEP (j4): THE TARGET OPTION SURFACE — THREE PARITY FIXES, AND THE NOTION *COLLAPSE* REFUSED WITH BOTH DIRECTIONS MEASURED, 19,586 / 0 / 81 (2026-09-16). (j) IS CLOSED.**
tsgo's emit at a written es5 is byte-identical to its es2015 emit (12 shapes of 12) while its CHECKER honours the
written value — so `effectiveTarget`/`defaultedTarget` are tsgo's own split, not a tsc-6 residue: collapsing onto
the written target silences the strict-reserved binding rows, onto the emit target it opens (j2)'s kept TS2318
gate and changes the module default. Refused, pinned, both KDocs rewritten to tsgo's reason. Landed instead: the
es5 DEFAULT lib now reaches es2015 as tsgo's does (6 ours-only TS2550 gone; the explicit-`lib` path and (i)/(j2)'s
pins untouched), ES3 is an invalid ARGUMENT after which the target is unset (ES3 out of the enum, TS6046 keyed on
an unknown-value marker, emit byte-identical to tsgo, `es4` now reports), and `effectiveModule`'s `else` — which
the item calls dead — was LIVE and mis-notioned (tsgo defaults a written-es5 project to CommonJS where we emitted
ESM). 20 pins, six arms, one of them a real errors-screen gate; cost_gate 20/20 +0.00%; huge_methods exit 0 (874
classes); grid 8×0/0 + emit 78/78 (controls); warning-clean. **Flagged, not edited**: `inc50-stability-lib.sh` pins
`target: ES5`, so (INC.50)'s three stability rates need re-measurement. (LEGACY.1) now has only (g) — blocked on
the owner — and (k).
**(P18.112) — (LEGACY.1) STEP (j3): tsgo's CHECKER NEVER SPELLS `__extends`, `__generator` OR `__assign` — AND ITS HELPER TABLE EXPOSED TWO TARGET-FREE DEFECTS IN THE SAME EMITTER, 19,566 / 0 / 81 (2026-09-16).**
`checkExternalEmitHelpers` is tsgo's one TS2354/TS2343 emitter and its callers request helpers by flag (`__rest`
< ES2018, `__awaiter` < ES2017, the async-generator trio < ES2018, decorators and interop at any target); the four
ES5 helpers are requested by nobody. Measured over 30 cells, ours named `__extends` in the six es5 ones; all four
arms deleted with `isEs5Target` and the `checkExprForMissingHelper` walk. The same table then fixed two defects
that are not about the target: the variable `__rest` walk's missing ES2018 bound, and TS2343's dedup per tslib
INSTALL where tsgo dedups per `(file, helper)` — which closed **two pending rows** (58 → 56 pending, skipped −2).
15 pins, eight arms; the dedup arm is the ONE place the errors screen was a gate (all 35 active `@importHelpers`
subtests are es2015+). cost_gate 20/20 +0.00%; huge_methods exit 0 (874 classes); grid 8×0/0 + emit 78/78
(controls); warning-clean. Found and left for a queue item: on a real project the tslib check is dead in one
direction and a false TS2354 in the other, because `node_modules/tslib` is never in the program.
**(P18.111) — (LEGACY.1) STEP (j2): tsgo HAS EXACTLY *ONE* `< ES2015` CHECKER GATE — SEVEN OF OURS DELETED, FOUR RE-KEYED ON THE LIB, ONE UN-SUPPRESSED, ONE KEPT, 19,551 / 0 / 83 (2026-09-16).**
tsgo honours a written es5 in its 29 `languageVersion` reads, but only one is `< ES2015` (TS2318 for a rest-only
binding pattern — kept and pinned in both directions). Measured gate by gate at a written es5: TS18045, TS2396,
TS2659, TS2340/`checkSuperPropertyAccessES5`, the TS1501 `u`/`y` rows, the es5 parameter-scope hoist with its
`bodyVarRefs` leg, and a raw-target pin arm have no tsgo emitter and are gone; the TS2461/TS2488 forks and the
never-destructure gate read the LIB in tsgo and are re-keyed (`uplevelIterationLib()`) — deleting them would have
inverted `lib: ["es5"]` projects; TS18027's lower bound was wrongly suppressing. 24 pins, eleven arms all
discriminating, every tsc-6 pin re-vehicled; screens 3,084/0 + 5,688/0 and grid 8×0/0 + emit 78/78 counted
controls; cost_gate 20/20 +0.00%; huge_methods exit 0 (874 classes); spine closure audit clean; warning-clean.
`Checker.kt` −183. (j3) tslib arms and (j4) the option surface remain; (g) stays blocked.
**(P18.110) — (LEGACY.1) STEP (j1): TS1250/TS1251 AND TS18028 HAVE NO REACHABLE EMITTER IN TypeScript 7 — BOTH FAMILIES DELETED, −285 LINES, 19,538 / 0 / 83 (2026-09-15).**
The three Go references to the TS1250 family are one uncalled binder function's `return` statements; TS18028 has
none. Four tsgo cells at a written es5: 0 rows, byte-identical to es2015, where ours printed 8 per file regardless
of strictness. Both families deleted by reference census (TS1251 shares the emitter and went too);
`PrivateIdentifierTargetGateTest` deleted as a tsc-6 countdown. **The corpus cannot see the family in either
direction** (0 active es5/es3 subtests — a first census read 557 off `@target: es5, es2015` lists whose only
active variation is es2015), so the 17 pins are the gate; two arms partition them exactly. cost_gate 20/20
+0.00%; huge_methods exit 0 (874 classes); grid 8×0/0 + emit 78/78 (controls); warning-clean. (j2)-(j4) remain,
high risk; (g) stays blocked on the embedded-tsconfig skip decision.
**(P18.109) — (LEGACY.1) STEP (i): TS2802 IS *LIB*-GATED IN TypeScript 7, NOT TARGET-GATED — THE `downlevelIteration` BLOCK WAS WRONG BOTH WAYS AND IS GONE, −243 LINES, 19,527 / 0 / 83 (2026-09-15).**
16 cells on tsgo: a written `target: es5` checks and emits byte-identically to es2015 (its default lib reaches
es2015, so `Iterable` exists), 0 TS2802; TS2802 fires only when `lib` excludes es2015, at any target. Our block
fired where tsgo is silent and was silent where tsgo fires; deleted with its seven functions by reference census,
the TS2488 gate keeps its target conjunct for (j), the option's parse and row stay. 21 pins (one blind NodeList pin
found and re-pointed), two arms; screens 3,084/0 + 5,688/0 and grid 8×0/0 + emit 78/78 counted controls;
cost_gate 20/20 +0.00% (real — a pass went); huge_methods exit 0 (874 classes); warning-clean. (j) `target:
ES5/ES3` is next and high-risk; (g) stays blocked on the embedded-tsconfig skip decision.
