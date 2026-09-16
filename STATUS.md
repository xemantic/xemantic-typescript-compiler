# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,578** lines (**+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.117) — (CHK.135) RE-SCOPED: THE MAPPED ALIASES WERE FINE, THE *INDEX-SIGNATURE READ* WAS NOT, 19,637 / 0 / 70 (2026-09-16).**
The item claims `Record` and other lib mapped aliases resolve to `any`; measured, every one of them is already
correct, including `Record` with a literal-union key. The real gap was `computeRawTypeOfPropertyAccess`'s miss path
returning `anyType` without consulting the index signatures — round 479 had granted such a name EXISTENCE and never
a TYPE, which is why the symptom was silence rather than TS2339. One helper (tsc's `getApplicableIndexInfoForName`)
now serves the property-access miss and the numeric-named-key leg, sharing its applicability test with the
existence check so the halves cannot drift; nine shapes move. **The mapped-type half was BUILT, priced at three
corpus baselines and REFUSED** — one of them needs tsgo's alias-variance probe, where a blanket shortcut deletes
five true positives and round 336 records the general machinery as a dead end. **And the grid is a CONTROL here,
proved by an arm**: a deliberately wrong answer moves zero rows on all 8 profiles, so the 12 pins are the round's
only gate. cost_gate 20/20 +0.00%; huge_methods exit 0 (875 classes); screen 3,097/0 + 5,688/0; warning-clean.
Residue: writes through an index signature, and `noUncheckedIndexedAccess`'s `| undefined`.
**(P18.116) — (LEGACY.0b) STEP 19: THE DUPLICATE-IDENTIFIER FOLLOW-ON INDEX IS PER MERGE *CALL*, AND THE IRRECONCILABLE SOURCE WAS TWO FUNCTIONS AWAY, 19,625 / 0 / 70 (2026-09-16).**
The first related node of each `addDuplicateDeclarationError` call is TS6203 and the rest of that call's nodes are
TS6204, so N declarations of one symbol give `[6203, 6204, …]` and N separate files give all TS6203. (P18.93) read
the `if` correctly and inferred wrongly: `lookupOrIssueError` compares through `CompareDiagnostics`, whose last
comparison is the RELATED LIST, so a second call misses the lookup, issues a second diagnostic that again starts
empty, and `compactAndMergeRelatedInfos` folds them only at the end. Verified over 128 baselines and 317
diagnostics with **0 unexplained** — and 11 diagnostics in that population carry a TS6204 from three OTHER
producers, so the discriminator is the call's LEADING code. Pending 48 → **45**. The TS2751 pair is REFUSED as a
tsgo defect: the layer is the directory of the `.diff`, not the base baseline every case has. 9 pins with BOTH
witnesses pinned (the second pair green on both arms by design, being the sole detector of an over-broad rule);
two arms with disjoint pin sets AND disjoint baselines. cost_gate 20/20 +0.00%; huge_methods exit 0 (875 classes);
grid 8×0/0 + emit 78/78 (controls); warning-clean.
**(P18.115) — (LEGACY.0b) STEP 18: FOUR ROWS IN TWO MECHANISMS, AND THE SIGNATURE-RENDERING FAMILY REFUSED WITH ITS EXPOSURE COUNTED, 19,616 / 0 / 73 (2026-09-16).**
Self-name resolution must FAIL when the project root is ambiguous — tsgo raises TS2209 and returns unresolved, so
TS2307 follows; we raised the first and resolved anyway. `isolatedDeclarations` reports TS9025 on the WHOLE
PARAMETER when declaration emit must add `undefined` implicitly, which turns on a clause the brief never named —
an initialized parameter is optional exactly when NO LATER PARAMETER IS REQUIRED — and tsgo has no whole-file JS
skip. Pending 52 → **48**. The signature-rendering rows were REFUSED: tsgo reuses a parameter's written type node
VERBATIM (an alias stays unresolved, an escape stays intact) when the rendered signature's declaration is a
function-like with a body — not the quote rule the brief guessed — and 186 baselines / 94 active subtests render
such a signature. 12 pins, four arms; **two "negative controls" are each the sole detector of their own over-broad
rule**, and one arm is invisible to the corpus entirely. A trap now recorded: top-level private functions of
`TypeScriptCompiler.kt` compile into `TypeScriptCompilerKt.class`, so the enclosing class's md5 can read as "the
build did not land". cost_gate 20/20 +0.00%; huge_methods exit 0 (874 classes); grid 8×0/0 + emit 78/78.
**(P18.114) — (LEGACY.0b) STEP 17: THE JS CommonJS `exports` MODEL — FOUR ROWS, AND THE ERRORS SCREEN WAS A *GATE* ON FIVE OF SIX ARMS, 19,604 / 0 / 77 (2026-09-16).**
tsgo declares `exports` as a file local whose type an `export =` COLLAPSES onto its target, so a one-level
`exports.p` is a property access on it — read or write, before or after the assignment, order irrelevant — and
`exports` is UNBOUND in a `.js` file with none of tsgo's four CommonJS indicators (a bare `require(…)` call being
one the brief missed). B438d, which emitted a TS2303 tsgo never produces, is deleted. Pending 56 → **52**.
TS7009-from-the-callee-type was BUILT, fixed 6 of 12 shapes, and was REVERTED on two measurements: it does not
close its row (a third mechanism — the module's own exports object) and it costs a green baseline through a
class/function merge gap. 18 pins, 9 of 9 non-controls red; **two pins were added because the ARMS found them
missing**, which is the round's lesson. A trap for every future JS round: `isJSLiteralType` makes tsgo silent
without `noImplicitAny`, so a scratch project must set it or the binary appears to contradict its own baseline.
cost_gate 20/20 +0.00%; huge_methods exit 0 (874 classes); grid 8×0/0 + emit 78/78 (controls — no `.js` in the
profiles); warning-clean.
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
