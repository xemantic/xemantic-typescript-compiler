# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,438** lines (**+101 at (P18.90)** — a SEMANTIC parity change (the F3 last-overload rule), not an extraction; **+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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
**(P18.89) — F6a: THE "UNBLOCKER" WAS NOT NEEDED, BECAUSE tsgo's CONDITION IS OVER *RENDERED STRINGS* — 27 OF 28 ROWS, BOTH DIRECTIONS, 19,139 / 0 / 215 (2026-09-13).**
Pending 217 → **190**, skipped 242 → **215**, both −27. (P18.88) refused this family for
want of a relation-error funnel across "~30 sites"; the re-taken census says **61 sites and
119 baselines** (not 73), and the funnel was never needed: **tsgo's `chainArgsMatch` compares
RENDERED STRINGS**, so deciding it from a finished `Diagnostic` at the single
`Checker.getDiagnostics()` exit is not an approximation of its rule but the rule itself — one
call site routing all 61, fail-closed on a head that does not parse. Stage 1 landed as an
identity function with a byte-identical receipt; stage 2 is the four-conjunct condition, with
conjunct 3 parsed and then EXCLUDED exactly as tsgo does. **Both directions are one rule** —
23 of 24 leaf-reporting plus 4 of 4 head-keeping close together, and the 4 turned out to be
three mechanisms. **The pre-measurement is what made it safe**: of 2,955 active baselines not
one has a head whose displays match its chain entry, and the 25 where ours did were all
already ignored — so the rule provably could not move a green baseline, and the first
post-change suite had 32 failures of which zero were corpus subtests. Ablation 98 / 771 / 14
/ 49 RED, no conjunct redundant, one arm recorded as having no local pin and then given one.
32 existing pins in 27 classes strengthened from code-only to full text. Grid a MEASURED
control (the rule fires zero times on the profiles). One cost refused on arithmetic: three
hand-written pins now differ in code rather than text, and avoiding them would cost 7 of the
27 rows. **An instrument failure was also found and fixed**: this session's warning check
carried a `-q` that suppresses the warnings it greps for — a positive-control probe read
zero — so four rounds' "warning-clean" claims were worthless and HEAD was in fact dirty; the
current tree is clean against a gate proven live.
**(P18.88) — (LEGACY.0b) STEP 3: F6 DECOMPOSED INTO EIGHT MECHANISMS, FOUR SUB-FAMILIES LANDED, THE LARGEST REFUSED ON A MEASURED BLOCKER, 19,130 / 0 / 242 (2026-09-13).**
Pending 242 → **217** and skipped 267 → **242**, both −25. **"F6 code-differs" was a
first-differing-LINE label for the second round running**: its 88 rows are EIGHT mechanisms,
and the decomposition table is the round's durable output. Its largest, F6a (28 rows,
missing-property head suppression), confirms the design's hypothesis — one decision point in
tsgo's `reportRelationError` — and **refutes its direction**: 24 rows have tsgo reporting the
leaf but 4 have tsgo KEEPING a head we drop. **REFUSED on a measured blocker rather than
deferred**: that message is emitted at ~30 independent sites with no relation-error funnel,
reaching 73 of 2,955 active baselines, so it needs an unblocker first; all 28 reasons are
rewritten greppable as `F6a`. Landed instead: unused-local ANCHORS 10/10 (the handover said
seven — TypeScript 7 dropped three tsc-6 special cases, and the other 15 F8 rows are a
different emitter entirely), TS2497 deleted 7/8 (the code is referenced by no tsgo code and
appears in none of its baselines), TS1127 spanning one character 5/6, TS8017 spanning the
declaration 3/3. Ablation 14 arms, 13 discriminating, one recorded UNDISCRIMINATED, `@Test`
identical in all. Grid a real gate for TS2497 alone, a control for the other three. Five
predictions refuted, including two of the round's OWN censuses taken off a stale generated
tree at the repo root — conclusions survived, numbers did not; the same tree had already
misled the orchestrator this session, so it is now a documented trap. cost_gate all 20
counters +0.00%, huge_methods exit 0, warning-clean.
**(P18.87) — (LEGACY.0b) STEP 2: THE "FREE WINS", AND F9 WAS A FIRST-DIFFERING-*LINE* LABEL RATHER THAN A FAMILY, 19,100 / 0 / 267 (2026-09-13).**
Pending 285 → **242** and skipped 310 → **267**, both −43 — and that agreement IS the
receipt, since the build fails on a stale ledger entry, so a green suite after deleting 43
entries proves 43 tsgo rows now pass. **F9 "wording" was assigned by each row's FIRST
DIFFERING LINE, which is not a family**: 13 of 53 are wording (in four unrelated mechanisms,
one of them an elaboration TypeScript 7 does not have at all — 0 tsgo baselines against 12
tsc ones), and 40 were reclassified in place, 23 of them into span/width. Two findings beat
the 13: seven of those span rows share F4's anchor mechanism (tsgo anchors unused-local on
the NAME, tsc on the statement), and two duplicate-identifier rows are not a wording swap at
all — tsgo picks the leading message from the error's existing related list where we pick by
index. **F4 landed 26/26** and the sizing was wrong about where it lives: the population is
type PARAMETERS served by two dedicated emitters, and a code-only change closes just 14 —
the span moves to the type-parameter NODE and TS7 keeps one grouping (TS6205 over the whole
list). **F5 landed 4/4 and settles (LEGACY.1)'s wording question**: an option TS7 DELETED
from its table is simply unknown (TS5023, no ladder, so no directive silences it) while
every option it KEEPS but refuses still says TS5102/TS5108 — so (LEGACY.1) step (k)'s plan
is CONFIRMED, and its stated blocker for moving `simulatedVersion` to `"7.0"` is gone (the
four rows it cited are now tsgo baselines saying TS5102, and moving would close two pending
rows rather than redden anything). Ablation 2 / 98 / 6 RED, the 98 including 5 pins failing
in OPPOSITE directions. Grid 8×`added=0 removed=0` with a split verdict — a control for the
display half and for F4 (no profile sets `noUnusedLocals`), a real gate for F5 and the
elaboration. cost_gate all 20 counters +0.00%, huge_methods exit 0, warning-clean.
**(P18.86) — (LEGACY.0b) STEP 1: THE CORPUS READS tsgo's OWN BASELINES, AND A `.diff` CLASSIFIES A *FILE* WHERE A FAILURE CLASSIFIES WHAT *WE* GOT WRONG, 19,082 / 0 / 310 (2026-09-13).**
`cloneTypeScriptGoRepo` pins tsgo at tag `typescript/v7.0.2` and the four baseline lookups
now choose per subtest between tsgo's checked-in output and tsc's, through a three-way
fallback whose four bucket counts are **asserted together** (`adopted=8,765` / `new=23` /
`deleted=9` / `kept-tsc=87`) because a wrong fallback is SILENT — it removes a subtest
rather than failing one. **The guard fired twice and caught an off-by-one**: `new` is 23,
not the design's 24, because (0a)'s pin move already carried
`coAndContraVariantInferences5` a round early. Corpus 8,838 → **8,852**; the red set is
**289** with two controls that make it attributable (all 289 from the tsgo root, all 289
carrying a `.diff` layer), disposed as 268 pending (285 with (0a)'s) + 22 divergences (21
TS-1 + 1 `/.src/`), so the suite is 0 failed with 310 visible skips. **The per-family
ranking moved materially from the design's** (F10 45→4, F9 11→53, F7 43→4) for a reason
worth carrying: a `.diff` classifies a FILE, a failure classifies what WE got wrong. Seven
predictions refuted, the sharpest being that a NEGATIVE diagnostic code is not impossible
here — `checkPreEmitCountMismatchPins` synthesizes `TS-1` deliberately, so the round's first
invariant pin went red against the real binary. Ledgering the 21 TS-1 rows cost 16 real tsc
comparisons, which were **paid back verbatim** in `TsgoHarnessSelfCheckBaselinesTest` (net
coverage change zero). cost_gate all 20 counters +0.00% (no `commonMain` touched, so the
grid is unaffected by construction), huge_methods exit 0, warning-clean. **Left open for a
decision before (0b-3)**: a fourth "harness artifact ⇒ fall back to tsc" arm would preserve
those 17 subtests with no ledger at all.
