# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,595** lines (**+81 at (P18.94)** — union DISPLAY sites routed through the (P18.85) comparator; **-28 at (P18.93)**, a SEMANTIC parity change (duplicate members reported at every declaration) that DELETES three tsc-6 narrowings and the TS6200 amalgamation; **+78 at (P18.92)**, a SEMANTIC parity change (TS2683/TS7009 in JS files) that also RETIRES tsc-6 walker B424; **+26 at (P18.91)**, a SEMANTIC parity change (TS2303 at every alias declaration); **+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.97) — THE JS-EMIT RESIDUE: SEVEN MECHANISMS, 11 ROWS, AND THE HOIST KEYWORD IS A *SCOPE* PROPERTY, 19,260 / 0 / 117 (2026-09-14).**
Pending 103 -> **92**, skipped -11, +20 pins, 9 modules asserted. Briefed as five mechanisms / 9
rows, landed seven / 11 — the agent sized the two singletons it was told to report on and landed
both on a measured zero. **The finding came from the red arms**: tsgo decides the enum/namespace
`var <name>;` hoist with a PER-SCOPE first-declaration map (functions, classes, every variable
declarator, in source order) and its keyword by the SCOPE (`let` anywhere but the SourceFile, a
dotted inner inheriting its outer's); ablating the keyword rule moves **40** baselines and the
per-scope reset **29** — the old file-level name sets had matched them by coincidence — and a
CaseClause must gate its recording on the subtree containing TypeScript syntax, as tsgo's early
return does. The other six: `declare import` never emits (a tsc-6 special case deleted); `import
I = M` in a plain block prints `var I = M;`; a setter's return type is never printed, and its
object-literal twin was a PARSER gap invisible to every gate; `emitBOM` prepended nothing before
and the harness's round-375 mojibake branch went with the pristine artefact; a body-less `global`
recovery emits nothing; and a source-written `export {}` is kept in place in a JS file only — the
general keep moved 14 green TS baselines and is refused. One BLIND pin repaired by its own arm.
Ablation 12 arms, all discriminating. JS emit is down to 3 singletons. cost_gate 20/20 +0.00%
and the grid/`--outDir` control both COUNTED controls; huge_methods exit 0 (869 classes);
warning-clean; screen emit 5,688/0, errors 3,050/0.
**(P18.96) — THE CommonJS EXPORT-PATTERN ASSIGNMENT (7) AND F10's CONSTRUCT-SIGNATURE CHAIN (4), AND THE F-LETTERS ARE NOT FAMILIES, 19,240 / 0 / 128 (2026-09-14).**
Pending 114 -> **103**, skipped -11, 9 modules asserted. **The primary is one rule and it is SEVEN
rows, not six**: tsgo converts an exported binding pattern into a destructuring ASSIGNMENT with
each leaf substituted to `exports.<name>`, and its own comment gives the reason — that preserves
native destructuring and therefore the ITERATOR SEMANTICS of an array pattern, where TypeScript 6
flattened. A row filed as an unrelated singleton was the same mechanism plus a printer half
(`Emitter.emitObjectLiteral` dropped a property's leading comment in its single-line branch).
**The secondary landed too, and the chain loses TWO links, not one**: tsgo has ZERO call sites for
`Types of construct signatures are incompatible.` — the message survives in its table unreferenced.
**Blast radius measured at zero for both**, on the changed binary over the unchanged population.
Ablation 12 arms, all discriminating; **a4's double zero is attributed and is the reusable
finding** — below ES2018 the object-rest downlevel has ALREADY rewritten the declaration before
the CommonJS transform sees it, so a `target` conjunct there can never decide anything, while
dropping the gate that DOES refuse it moves 41 baselines. A blind pin was found by its own
ablation and repaired, and two structural pins that used the removed shape as a VEHICLE were
re-vehicled rather than weakened. **The durable output is the decomposition of the remaining
103**: F6 "top code differs" is 38 rows and **~32 distinct code pairs, largest cluster 2**, so
every estimate phrased in the ledger's F-letters overstates the work — and the letters hide
cross-family clusters (TS2880 split across two, TS1003 across two). JS-emit residue is 3 groups
plus 8 singletons, and `emitBOM` turns out to be a SCANNER bug. Grid and `--outDir` + `diff -r`
both CONTROLS and both counted; the corpus and its screen were the gates. cost_gate 20/20 +0.00%,
huge_methods exit 0 (867 classes), warning-clean, screen 8,727 / 0.
**(P18.95) — THE SCREEN GAINS THE *EMIT* CHANNEL, AND THREE PRINTER RULES CLOSE 12 JS-EMIT ROWS, 19,214 / 0 / 139 (2026-09-14).**
Pending 126 -> **114**, skipped -12, tests +14, 9 modules asserted present. **Part 0 is why this
family had been deferred five rounds**: JS emit was the largest pending family and had NO
blast-radius instrument, because the (P18.93) screen covered only errors subtests. It now runs
both — **8,716 subtests in ~70 s** — through the suite's OWN helpers, so `stripDtsSection`, CRLF,
the BOM decode and the conformance `casesDir` provenance cannot drift; floors are PER CHANNEL,
since one combined number is satisfied by a healthy channel while its sibling has collapsed. The
emit channel is nearly TWICE the errors one (5,692 against 3,160) — this round's own brief
under-counted it. **The orchestrator's decomposition was wrong three ways**, one of which would
have mis-routed six rows into (LEGACY.1): the destructuring group was hypothesised as a
`target ES5` question and `downlevelLetConst13(target=es2015)` refutes it in ONE file at ONE
target, where top-level exported bindings keep their pattern while namespace-level ones are
lowered. **Landed: three printer rules** — an instantiation expression prints as its operand
bare (stripped at PRINT time, so no transform decision moves; its JSDoc half was another tsc-6
transcription and was DELETED, not re-transcribed), a recovered `;` before `}` is a TRAILING
separator, and tsc's `parenthesizeExpressionOfNew`. **The screen paid for itself inside the first
hour, on a SEMANTIC bug**: the first cut stripped the paren unconditionally and moved two green
baselines, because a `<T>` list ENDS an optional chain — `a?.b<c>.d` is `(a?.b).d` — and nothing
downstream can re-derive that, so the parser marks it. Ablation 8 arms, all discriminating, each
reporting pin reds AND screen mismatches; **a5 read 0 RED and was repaired** (its control cannot
see the mistake, because there the loop's `else` runs last). **The gates had to be labelled and
the emit-mode control is itself blind**: `--outDir` + `diff -r` read 78 files IDENTICAL across a
change that moved 12 baselines, because tsc's own sources contain none of the three shapes — for
an emit family the corpus EMIT CHANNEL is the gate. cost_gate all 20 counters +0.00% (a control
here), huge_methods exit 0 (867 classes), warning-clean.
**(P18.94) — THE ORDER FAMILY: 13 OF 18, AND FIVE OF THE "MODEL GAPS" WERE REACH ROWS, 19,200 / 0 / 151 (2026-09-14).**
Pending 139 -> **126**, skipped 164 -> **151**, both -13, plus +8 pins. **The orchestrator's
read-only decomposition was wrong in both directions and the measurement said so**: it is THREE
groups not two, five of the nine briefed "model gaps" were reachable, and two briefed reach
attributions were the same site. The discriminator is one scratch run — rewrite the SOURCE
union's order: if the output changes the display reads nodes and the fix is a sort; if not, the
TYPE is wrong. **A third group nobody had: tsc-6 TRANSCRIPTION**, whose find is that
`baseClassImprovedMismatchErrors` did not merely hold a stale string — it **actively rewrote**
`() => string | number` into `() => number | string`, converting TypeScript 7's answer into
TypeScript 6's for this entire arc. Deleted, not re-transcribed. **The screen is now the primary
instrument**: 3,033/0 before, **3,033/0 after all 13 fixes** on the family with the highest
display blast radius left, with every closed row verified through `--include` and the 5 holdouts
as the positive control that the path was live. Every ablation arm reports screen mismatches
beside pin reds, which is what makes its six ZERO-PIN arms attributable — each moves precisely
one baseline. **A blind pin was found by its own ablation** (arm a3 stayed green while moving a
baseline): more than one emitter owns TS2353, so the pin asserted the right answer from the wrong
site. A recorded ledger REASON was also wrong — `typeParameterDiamond4` is a resolution gap, not
an ordering one — and two holdouts are not ORDER rows at all. **An operational failure, and it
was the orchestrator's**: four suite runs were lost to `EOFException`/`NoSuchFileException`/a KIR
timeout because it ran `./gradlew` while the agent's suite was in flight, having read a stop
notification as "finished"; CLAUDE.md's rule is one invocation per BOX, not per agent. It also
`--stop`ped a later invocation's daemon and `pgrep`-killed its own shell — both already
documented, both walked into. The KIR timeout is NOT a regression (seven straight-line
`console.*` calls, no loop; 159 KIR tests pass in isolation). cost_gate all 20 counters +0.00%,
huge_methods exit 0 (867 classes), warning-clean, screen 3,046/0.
**(P18.93) — THE CORPUS *SCREEN* IS COMMITTED, AND F2 DUPLICATE-IDENTIFIER LANDS 12 OF 18 WITH THREE tsc-6 NARROWINGS DELETED, 19,192 / 0 / 164 (2026-09-14).**
Two commits. **Part 0** is the instrument (P18.92) built, used and LOST: `scripts/corpus-screen.sh`
+ `CorpusScreenMain.kt`, ~28 s over every active errors subtest outside Gradle, now committed
because with 139 rows still open its absence was a tax on every remaining round. Three properties
are STRUCTURAL, not remembered — it **refuses the frozen repo-root generated tree** (whose
positive control shows it really is a different corpus, 3,145 subtests against the live 3,160),
it **calls the suite's own** `errorsMatchBaseline`/`Path.readText()` so CRLF, `.d.ts` stripping
and the UTF-16 BOM decode cannot drift, and it **refuses below a subtest floor**. It is NOT the
gate and its header says so. **Part 1**: pending 151 -> **139**, skipped 176 -> **164**, both -12,
plus +22 pins. **The screen decided the round** — TS2300 is in **80** active baselines, the
largest radius of any remaining family, but the candidate rule read **0 mismatches / 3,021** on a
throwaway build before any commitment, which is how an 80-baseline family landed in one round.
The pre-measurement also corrected the roster (group A was 9, not 12; the missing three are a
NAMING mechanism; group B is two mechanisms, not one). **The rule deletes three tsc-6
narrowings**: `reportDuplicateMemberErrors` errors at EVERY matching member, so the flag table
collapses to "report all, except a get/set PAIR and METHOD OVERLOADS", and the TS6200/TS6201
amalgamation is deleted rather than re-thresholded. Byte-identical to tsgo across all 29 rows of
a 14-shape fixture, silences included. **My read-only TS2717 rule was WRONG and the measurement
said so**: it is not a "differing KIND" test but tsgo's binder SPLIT — the group's first member
must not be a METHOD — which no two-member fixture can distinguish. **Six countdown pins, not
anticipated**: five in `PristineDivergenceRound940Test` and one whose own comment read "a tsgo
divergence this compiler does not chase", all asserting pristine's answer for the family being
closed; all six re-measured against tsgo and re-pointed. **And changing a row's NAME silently
changed its SQUIGGLE** (an `else -> name.length` fallback), caught only by a two-character width
diff. Three holdouts have named mechanisms and **the related-span three are a refusal with
numbers**: the rule built from tsgo's SOURCE does not reproduce tsgo's own BASELINES. Ablation 12
arms, ALL discriminating, each reporting pin reds AND screen mismatches. Grid a MEASURED control
(TS2300/TS2717/TS6200 all 0/0 in both arms of all eight). cost_gate all 20 counters +0.00%,
huge_methods exit 0 (862 classes), warning-clean against a gate proven live.
