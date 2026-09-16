# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,706** lines (**+31 at (P18.120)**, three duplicate-identifier mechanisms plus a computed-name span arm — a SEMANTIC parity change; **+97 at (P18.119)**, the `for`-header and `for…in` binding recorders plus the shared type half — a SEMANTIC parity change, not an extraction; unchanged at (P18.118), a KIR-only round whose four checker classes are byte-identical to the previous binary; **+43 at (P18.117)**, the index-signature read; **+61 at (P18.116)**, one related-span helper replacing three call sites; unchanged at (P18.115), whose two mechanisms live in `TypeScriptCompiler.kt`, 6,558 → 6,718; **+280 at (P18.114)**, the JS CommonJS `exports` model — two walkers and five helpers in, B438d out; +27 at (P18.113), all but two lines KDoc; **−118 at (P18.112)**, the tslib ES5 helper arms; **−183 at (P18.111)**, seven target-gate families; **−285 at (P18.110)**, the TS1250/TS18028 families; **−243 at (P18.109)**, the `downlevelIteration` block; unchanged at (P18.108), which deleted `outFile`'s six arms from `TypeScriptCompiler.kt`, 6,566 → 6,553; +3 at (P18.107), the amd/umd/system fold — deleted behaviour, added KDoc; **−89 at (P18.106)**, five module-resolution derivation copies → one; **−50 at (P18.105)**, the interop arms; unchanged at (P18.104), which lifted the tsconfig option-position scan into `CompilerOptions.kt` for both paths; **+8 at (P18.103)**: code −6, KDoc +14, the `alwaysStrict: false` arms; unchanged at (P18.102), which deleted **249** lines of dead System-module code from `Transformer.kt`, 17,862 → 17,613 — the first (LEGACY.1) removal; **+24 at (P18.101)** — ~150 deleted (TS2346 gate + helpers, a one-fixture TS2300 walker), ~175 for tsgo's TS2309 rule; **+93 at (P18.100)**, anchor rules; **+333 at (P18.99)**, a SEMANTIC change — two new JSDoc walkers; **+79 at (P18.98)**, a SEMANTIC parity change — four tsgo mechanisms; **+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.120) — (LEGACY.0b) STEP 20: THE LAST THREE F2 DUPLICATE-IDENTIFIER ROWS, 19,694 / 0 / 67 (2026-09-16).**
Three distinct mechanisms, all landed: an INTERFACE's duplicate group reports at every member whatever its binder
visibility; a method-vs-property name across MERGED interface blocks is TS2300 at every declaration with no TS2717
and no TS2687 (the walker had been emitting tsc-6's answer); and a constructor overload's own parameter property is
reported beside the implementation's. The skip count falling 70 → 67 is the receipt that the rows are now ACTIVE
tests; `tsgoPendingBaselines` 46 → 43. **The recorded reason was wrong on the first row** — `lateBindMember` is a
real emitter and not that one — and **the obvious generalisation is a measured false positive**: un-gating
late-bound TS2300 holds for an interface and not for a class, where tsgo is order-dependent, so it was built,
measured and reverted. The only instrument that saw that over-reach was a hand-written pin: the corpus screen read
0 and the grid is a measured CONTROL here (admissions 0 on all eight profiles, 4/1/3 on the scratch fixtures). Two
countdown pins asserting pristine's answer fell and were re-measured against tsgo, never weakened.
**(P18.119) — (CHK.136): A `for`-HEADER BINDING AND A `for…in` BINDING TYPED `any`, 19,672 / 0 / 70 (2026-09-16).**
tsgo reports 7 rows on an 11-line fixture where we reported 2. A `ForStatement`'s initializer is a
`VariableDeclarationList` whose parent is the LOOP, not a `VariableStatement`, and both recorders test that parent —
so every `for`-header binding was recorded by NOTHING and typed `any` inside its own loop, annotated or not, while
`for-of` (a (CHK.29) arm) and an ordinary `let` were correct, which is why every earlier probe read healthy. It is
also the CHECKER-side root cause (P18.118) measured from the backend: `nums[i]` was `any` because `i` was. The two
recorders register against the loop's own nodeId, so the scope covers the condition and incrementor and pops at the
leave; the type half is shared structurally with the statement recorder so the rules cannot drift. **The 8-profile
grid is the gate and reads `added=0 removed=0` on all eight, with 78 emit files byte-identical**; cost gate max
+0.15% on `typeOfExpr.distinct`, moving with `calls` on a flat `spine.nodes`. 20 pins, 4 arms; the split briefly
left a 39-bytecode delegating wrapper that `HugeMethodLimitTest`'s partition pin caught — repaired by inlining it,
never by lowering the bound.
**(P18.118) — (KIR.LOWER.3)+(KIR.LOWER.4): THE LOWERING'S BAG FALLBACK, AND TWO DEFECTS THE ITEMS DO NOT NAME, 19,652 / 0 / 70 (2026-09-16).**
The first round of this session outside the checker-parity lane, taken because these two are the largest measured
KIR performance lever and a native-arm blocker sharing one mechanism: the lowering asks for a receiver's type, does
not get one, and falls back to the dynamic bag. Measured as BYTECODE SHAPE. (LOWER.3)'s headline is wrong — an
element access keeps its type; the INDEX loses it when it is a `for`-header `let`, and that is a CHECKER gap (three
missing true positives against tsgo), deliberately NOT fixed here because it is (CHK.50) at maximum radius; KIR
recovers locally, 2 ops → 0. (LOWER.4) understates itself — every `this` member READ was broken too, both paths
consulting `isDynamicReceiver` before resolving a field they could already resolve; 4 ops → 0 and parameter
properties go from a refused compile to 0, with the store prologue above the initializers (measured off tsgo's
emit; the other order prints the wrong answer). **Two defects neither item names**: `ps[0].x` emitted a `getfield`
on `java.lang.Object` and died with `NoSuchFieldError` at ZERO dynamic ops — the shape a shape-only pin waves
through, which is why every mechanism now has a behaviour case — and method calls still reached `jsInvoke` in the
loop the fields had just left. 15 cases, 8 arms; KIR module 174/0; `huge_methods` run over the KIR module as well
as core, since the default census is core-only; the grid is inapplicable (checker classes byte-identical) and that
comparison is the receipt.
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
