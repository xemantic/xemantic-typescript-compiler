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
**(P18.92) — TS2683 IN JS FILES: THE SKIP WAS STANDING IN FOR A *GATE BUG*, AND 3 OF 4 "CASCADES" WERE FOUR SEPARATE FAMILIES, 19,170 / 0 / 176 (2026-09-14).**
Pending 155 -> **151**, skipped 180 -> **176**, both -4, plus +17 pins. **4 of 7 rows — and the
shortfall is the finding.** Picked by the same blast-radius method as (P18.90)/(P18.91), but
where those chose codes appearing in ZERO active baselines, TS2683 is in **17** and the changed
gate is exposed to **133 active baselines involving a `.js` file**, so the brief demanded the
exposure measurement before any code. The cascade hypothesis (tsgo emits TS2683 -> `this` becomes
`any` -> our downstream error disappears) holds for exactly ONE of four rows; the other three are
a kept TS2339, two ours-only `.ts` rows, and a JSDoc `@param` typing gap. **A shared diagnostic
CODE is no more a family than a shared first-differing LINE** — the third way this arc has
mis-grouped rows. **The JS skip turned out to be standing in for a gate bug**: tsgo's
`GetStrictOptionValue` makes an explicit sub-option `false` WIN over `strict`'s default-on, which
our `X || strict || !strictExplicitlyFalse` idiom did not model — the one fixture in 133 that
separates them was being protected by the skip instead of by its own `@noImplicitThis: false`.
Fixed at 2 measured sites; **28 left on the old idiom deliberately**, recorded rather than
silently inconsistent. **The corpus did NOT catch the round's one real mistake — a negative
control did**: dropping the skip wholesale emits under `allowJs` without `checkJs`, where tsgo is
silent and no baseline exists to notice. A second control was vacuous (no `this` in its fixture)
and was fixed rather than trusted — probed properly, our `.d.ts` guard is NOT tsgo-faithful, now
pinned as `residue - ...`. Also landed: the TS7009 sibling family (same gate, same bug), tsc-6
walker B424 RETIRED, and a `declarationOnly` driver — which is what actually blocked two rows,
not anything `this`-shaped. Ablation 10 arms, 9 discriminating, a7's zero attributed to a
redundant guard via its control. Grid a MEASURED control (TS2683 and TS7009 both 0 in both arms
on all eight; no profile sets `checkJs`/`allowJs`/`emitDeclarationOnly`). **A reusable instrument
landed with it**: a 28-second full-active-corpus harness outside Gradle (2,789 subtests, 0
mismatches after every step). cost_gate all 20 counters +0.00%, huge_methods exit 0 (862
classes), warning-clean against a gate proven live.
**(P18.91) — F6d: TypeScript 7 REPORTS TS2303 AT *EVERY* ALIAS DECLARATION ON THE CYCLE, AND THE DETECTOR WAS ALREADY THERE, 19,153 / 0 / 180 (2026-09-14).**
Pending 165 → **155**, skipped 190 → **180**, both −10, all ten subtests verified PRESENT and
PASSED. **The family was picked on a BLAST-RADIUS measurement rather than on size** — 10 rows
against F2's 15, chosen because TS2303 appears in **ZERO active baselines** where F2's TS2300
appears in **80**, the same "cannot redden a green baseline on its own axis" property that
carried (P18.90). The exposure was inverted (this ADDS a diagnostic), so the grid was briefed as
a real gate; measured, it is a **control** — TS2303 fires 0 times on all eight profiles and on
cronstrue/marked/many-small, which the new script's header states rather than hiding. tsgo's
`popTypeResolution` marks the whole resolution SUFFIX false, so **every frame from the cycle
start upward emits**: one row per alias DECLARATION the cycle passes through, each named after
its own symbol, where tsc 6 reported one. Third member of the "tsgo reports at ALL declarations"
family after F2's TS2300. **The brief's design question was answered "neither"** — not a new
emitter and not a collecting pass: FOUR existing walkers each needed the same one-line
generalisation, and tsc 6's `findEntry` entry-point heuristic went with it (−38 lines). The four
are **measurably disjoint** (PassLab, one `disable` per run: each is the sole emitter of its
shape, none redundant, nothing deletable) — **and the first such measurement was DEAD**, because
the probe script `cd`s into the fixture directory and `PassLab` loads from the process CWD; a
dead lab prints exactly what three redundant passes would. Ablation 9 arms, all discriminating;
a8's initial **0** was investigated rather than shrugged at and its control a8b reddens 10 —
`Identifier.end` coincides with the correct span whenever the next token is `;` or EOF, so the
pin needed a following statement to discriminate. A refuted prediction worth keeping:
`declarationEmitUnknownImport` is **not a cycle at all** in tsgo — moving the `export` above the
import silences it, an artifact of tsgo's own resolution stack. cost_gate all 20 counters
+0.00%, huge_methods exit 0 (862 classes), warning-clean against a gate proven live.
**(P18.90) — F3 LAST-OVERLOAD: 25 OF 25, AND THE THREE tsc-6 ANCHOR HEURISTICS WENT WITH IT, 19,139 / 0 / 190 (2026-09-13).**
Pending 190 → **165**, skipped 215 → **190**, both −25, with all 25 subtests verified PRESENT
and PASSED in the XMLs rather than merely un-skipped. **F3 was third by red count and FIRST by
mechanism count** — one rule where F6z's 33 and JS emit's 33 are many — and the pre-measurement
is what licensed it: of the 2,982 active `errors.txt` subtests **ZERO** carry tsc 6's
`Overload N of M, '<sig>', gave the following error.`, because tsgo never emits it, so the change
was structurally unable to redden a green baseline on its own axis. The rule was taken from
tsgo's SOURCE (`reportCallResolutionErrors`, checker.go ~9624) and not from its baselines: report
only the LAST argument-failing candidate, under `The last overload gave the following error.`
(**TS2770**) and `No overload matches this call.`, with `The last overload is declared here.`
(**TS2771**) at that candidate's declaration — neither code existed in the general path before,
only inside hardcoded pins. **Because tsgo anchors wherever the last candidate's own argument
check anchors, three tsc-6 heuristics became unreachable and were deleted** (B418's best-overload
collapse, 17.15b/B50.11's fn-vs-fn callee anchor, B280's method-name anchor) along with the
per-candidate related-info accumulation: the emission block 153 → 107 lines. (The change SET is
−115 lines, but `Checker.kt` itself is **+101** — the deleted heuristics are outweighed by the new
TS2770/TS2771 helpers and the tsgo-citing KDoc; the −159 is build.gradle.kts shedding 25 pending entries.) **Nine pin
walkers updated and ZERO deletable — measured with the PassLab, not assumed**: with the pin off
the general path differs in every case. Two silent-failure mechanisms found and recorded: a pin
that locates its row by ANCHOR POSITION stops firing when a general emitter moves the anchor
(no error, the general answer just leaks through), and a call signature `(x: T): R` is a
`MethodDeclaration` with an EMPTY name at pos 0, so the idiomatic `?.pos ?: decl.pos` renders at
`1:1`. Ablation 10 arms, 8 discriminating, both zeros ATTRIBUTED rather than shrugged at (a2's
control a2b reddens 48, so `multi` is load-bearing and merely always-true here). 27 hand-written
assertions in 5 classes re-measured against tsgo — **three were countdowns asserting pristine's
per-candidate chain** and were renamed. Grid a MEASURED control (TS2769 rows = 0 on all eight
profiles and on every library; the gate is the 28 corpus baselines that carry one). cost_gate all
20 counters +0.00%, huge_methods exit 0 (861 classes), warning-clean with the gate proven live.
