# Status

**(P18.1/P18.2) — THE DOC ARC, THE 142-METHOD CENSUS, AND THE FIRST TWO PHASE-18 CONSUMERS
(2026-09-01).** (LIC.1)/(DOC.1)/(DOC.2 on `docs/reposition`)/(INV.D) landed in the main
context; then ONE two-agent worktree wave landed **(EXT.1)** — Kotlin externals from the
CHECKED program, alias-resolution pin `Species`->`String`, metadata-compile gate with a
negative control, 15 pins — and **(LSP.1)** — JSON-RPC/LSP over `Project`, initialize +
didOpen + hover, **LSP UTF-16 = Project offsets CONFIRMED identical modulo the 1-base** at
an astral-char pin, 42 pins. `docs/INVERSION-DESIGN.md` answers the WebStorm question:
of tsgo's 142 API methods, **A=94 answerable post-hoc today, B=15 walk-scoped (13 closable
by a record-during-walk NodeLinks store — (INV.1) proposal BLOCKED-PENDING-USER), C=33 not
checker questions**; on-demand flow is NOT required for the census. **The LSP's first
fixture found a `-project` defect ((API.18): a file-final token is unreachable without a
trailing newline)** — the mission thesis demonstrating itself: a new consumer finds what
the corpus structurally cannot. Also queued: (LIC.2) the root POM says Apache-2.0
(BLOCKED-PENDING-USER, build file). Suite on the
merged tree: **16,734 / 0 failures / 3 skipped** (+57: 15 externals + 42 lsp);
`cost_gate.py` exit 0, every counter +0.00% (the new modules move nothing — the control
passes); `huge_methods.py --fail-over 0` clean (core-only census: a CONTROL for the new
modules, per its own gotcha).

**(P18.0) — THE PROJECT IS RE-POINTED: TYPESCRIPT FOR THE JVM AND KOTLIN (owner directive
2026-09-01).** The WebStorm evaluation paused — their need was a post-hoc TYPE ORACLE (the
query shape of tsgo's `tsc/internal/api/proto.go`, 142 methods) and this checker's answers are
functions of walk-scoped state; tsgo is the free official default, so "a TypeScript compiler"
is not the mission. **The mission: no Node and no Go in the toolchain; an embeddable
whole-program checker (`Project`); a Kotlin externals generator with resolved types (the
Dukat/Karakum gap); the KIR JVM bytecode backend; an LSP anyone can try in five minutes.**
The directive is persisted in CLAUDE.md § "AI agent mission", the WORK ORDER at the top of the
PLAN-PHASE-5.md QUEUE (new items (LIC.1) (DOC.1) (DOC.2) (EXT.1…n) (LSP.1…n) (INV.D) (INV.0)),
and SESSION-PROMPT.md, so run-loop iterations cannot revert to the old mission. **The (INC.\*)
latency family is CLOSED** at a 94-110 ms incremental floor / 93-217 ms plugin query — further
INC rounds are REFUSED unless a plugin-facing query measures > 300 ms warm. Suite unchanged:
**16,677 / 0 failures / 3 skipped** (doc-only commit).

**(INC.91) — THE REOPENED CLOSURE, CENSUSED THE SAME DAY AND REFUSED ON SOUNDNESS
(2026-09-01).** (INC.90) reopened the reverse-dependency closure on a 12.7x measurement; this
census refuses the PROPOSAL without touching that number. Counts, two reproducing runs.
**THREE FRAMINGS REFUTED, INCLUDING TWO OF MY OWN.** The transitive importer closure of a
`layer00` module is **187 of 2,401 files (7.8%)**, not "most of the program" — fan-out is ~4
per hop. The offset-sensitivity worry (foreign types keyed by `(fileName, pos, end)`) is REAL
(`Checker.kt:57089`) and costs exactly **ONE extra hop**: 1 fingerprint moved for an
append-at-END edit, **3** for the identical edit at the TOP, **0 beyond hop 2**.
**THE BLOCKER WAS IN THE FINGERPRINT'S OWN KDoc THE WHOLE TIME** (`:57050`): (INC.47)'s
file-boundary cut gives up TRANSITIVITY, and `incrementalDiagnostics` is sound BECAUSE a moved
signature anywhere falls back. The proposal kept the signal and deleted the fallback.
**Refuting number: on a length-preserving three-file edit the only error is at HOP 2, hop 1 is
silent in every channel, and the walk reports 0 rows where the truth is 1** — a missing
diagnostic. tsgo answers 1/1 and its own hop-1 `.d.ts` is unchanged too, so the feature is
achievable but its soundness cannot come from a signature.
**WHAT SURVIVES IS MOST OF THE WIN:** narrow a signature edit to the transitive importer
CLOSURE (`Result.importEdges`, already computed), splice the rest, use the fingerprint for
nothing — **2,401 -> 187 files (12.8x)**, sound because a superset always is, degrading to
today's behaviour on barrels. The remaining 187 -> 4-8 needs a TRANSITIVE signature, a larger
item than the walk. **The method is the reusable part: one probe runner, no wall clock, and it
killed a design with a real 12.7x measurement behind it — a prize being real is not evidence
that a mechanism for collecting it is sound.**

**(INC.90) — THE tsgo INCREMENTAL COMPARISON RE-TAKEN ON A SECOND ARM THAT IS FINALLY
LIKE-FOR-LIKE, AND THE SIGNATURE CLIFF REOPENS (INC.35) (2026-09-01).** Every tsgo incremental
number this repo had published came from ONE arm — tsc's 78 huge barrel-exporting sources,
where we report **46** rows against tsgo's **65**. New arm: `many-small-2400-dom`, 2,401 files
in 48 layers, edited at `layer00` (deepest-dependency worst case), where **both compilers
report the identical single row**, so the equivalence gate this comparison always lacked
passes exactly.
**ARM A DID NOT MOVE** (ours 5,523 warm / 226 body / 5,578 signature against the recorded
5,352 / 232 / 5,694) — expected, since the ~25 (INC.\*) rounds since removed per-FILE costs
and that profile has 78 files.
**ARM B IS THE FINDING.** (INC.35) closed the reverse-dependency closure on tsc's sources, and
Arm A corroborates it FOR TSGO TOO (signature edit **1,695 ms against its own 1,667 cold** —
its pruning recovers nothing). On LAYERED code the same mechanism is worth almost everything:
tsgo **304 ms against its own 427 cold**, i.e. a signature edit costs it what a body edit costs
(297), where we rebuild at **3,850**. **12.7x wall, ~96x marginal — the largest gap ever
measured here, and the only one with a named mechanism on the other side.** Queued (INC.91).
**BOTH NUMBERS, BECAUSE ONE GETS IT WRONG:** on the wall we answer a body edit in **137 ms
against 297** and a no-op in **0 against 264**; but tsgo's floor is 89% of its own body cell,
so its MARGINAL body cost is ~33 ms against our ~137. We win the wall on the live-session
model; they win the compute on a real invalidation algorithm.
**THE PLUGIN'S OWN CALL IS IMMUNE TO THE CLIFF** — `incrementalDiagnostics()` is reached from
`diagnostics()` and nowhere else, while the plugin asks `diagnosticsOf` exclusively (narrows at
the SOURCE): **93-106 ms on Arm B, 187-217 on Arm A, independent of edit shape**, corroborating
(INC.86)'s 90 ms per-keystroke figure.
**THREE HARNESS DEFECTS, AND THE FIRST IS THE ONE TO REMEMBER:** the inherited fixture's
`orig.ts` was CRLF while both edit variants were LF, so every "one-line edit" was that line plus
a 3,916-line newline normalisation; the tsgo harness read its row count out of a subshell and
printed stale values; both harnesses were hardcoded to `binder.ts` and to scratchpad paths that
survived by luck. All three fixed, plus two receipts the old runner could not print (a per-cell
row count, and served-vs-fell-back from `Project.incrementalAnswers` — both arms read body 3/3,
signature 0/3).
**AND CLAUDE.md's ROUND-938 CLAIM IS FALSE:** pristine `typescript@6.0.3` IS runnable here and
agrees with tsgo on all 65 rows, so the gap is **19 genuine false negatives of ours, 0 tsgo
divergences** — but 18 of 19 are emission-side on work already done, so it is not a 29% work
gap. `docs/perf/tsgo-diagnostic-gap.md` (new), `docs/perf/incremental-vs-tsgo.md` (rewritten).
Suite **16,677 / 0 failures / 3 skipped**.

**(INC.89) — THREE INHERITED REFUSALS RE-DERIVED, ONE PLUGIN-FACING API MEMBER PINNED, ONE
SPLIT LANDED (2026-09-01).** (INC.88) left a standing instruction — "anything larger needs the
refusals re-derived rather than inherited" — and the first half of this round is that, on
reading alone. Fresh baseline, TWO processes ((INC.52)): WALL **102/106 ms**, init block
**40.0/48.2**, head `init:buildFileLocalTypeMaps` **21.4/15.3**, `init:buildPerFileScopes`
**5.7/5.5**, `init:computeAllEnumValues` **4.8/4.5**.
**THE THREE ANSWERS DIFFER FROM EACH OTHER, WHICH IS THE ARGUMENT FOR DOING IT.**
`buildFileLocalTypeMaps` is **CONFIRMED** — it is partition-scoped already, builds ONE file's
map (`eagerBuilds=1`), and the ms is that file's first real type-resolution cascade; do not
re-open it from its size. The two un-Boyer-Moore-able whole-program regexes in
`collectUmdGlobalsAndModuleFiles` are **already censused honestly** ("0 ms — 0 `.d.ts` files.
LATENT on a `@types` tree"). And `isModuleFile`, recomputed **≥5 times per build** across the
init setup block, is **REFUTED as a lever with no build at all**: it early-returns on the FIRST
import/export, which every file of a module-shaped project has. A repetition count is not a
cost — round 801's law one predicate over.
**(a) `Project.reloadFile` AND `OverlayVfs.revert` ARE NOW PINNED** — the API (INC.75) tells the
IntelliJ plugin to adopt, documented as a first-class third change kind and present in the suite
only as a step inside two `trustFilesystem` tests, with its implementation half unpinned
entirely. 17 pins, no production code changed. **TWO ablations, because one cannot grade both
halves**: emptying `reloadFile` reddens 7; emptying `revert` reddens 12 — all 7 revert pins plus
the 5 reload VALUE pins, cross-validating that reload's promises flow through `revert`. A doc
edge was pinned rather than waved through: for a file existing ONLY in the overlay, "what is on
disk is the truth" means **ABSENCE**, so reload removes it.
**(b) THE (INC.20) SPLIT FOR `checkCrossFileUseBeforeDeclaration`** — its emitter walked all
2,401 files to produce rows `getDiagnostics()` then dropped, because the diagnostic is anchored
at the USE file and the partition filter discards the rest. **The ordinal invariant is the whole
risk**: the verdict compares `decl.fileIdx > useFileIdx`, both ordinals of `binderResults`, so
re-heading on `checkedResults.withIndex()` renumbers `useFileIdx` to ~0 and flips the verdict
toward FALSE POSITIVES silently. The head therefore stays `binderResults.withIndex()` and the
partition is a `continue` AFTER the enumeration. Receipt is a COUNT, never a millisecond.
Ablation reddens **pin 6 only**, and the other six are recorded as structural rather than
claimed as coverage.
**(d) THE BIGGEST PLUGIN-FACING LATENCY ITEM ON THE PAGE IS NOT A DEFECT WORTH FIXING — IT IS
THE FLOOR.** `docs/language-service.md` § 13's "one open defect" was that
`completionsAt`/`signatureHelpAt` cannot reach a prepared check (207 ms after `prepare(6)`
against 194 cold). Both refusals standing in front of it were re-derived at HEAD and **both
hold; (INC.33) is FIRMER than when written** — break-even **1.40 -> 1.52** and **12.1 -> 12.9**,
*because* the floor arc cut the base while per-anchor capture did not; retention unchanged to
the digit (**54.4 M** records for one widened `checker.ts` entry). The queue's own named
successor, the PREPARE-AMORTISED case, is **REFUTED BY MECHANISM**: the typed `.` must reach
`updateFile` or the completion anchor is computed from stale text, and `updateFile` does
`captures.clear(); prepared = null`, so the dominant completion is invoked at a state nothing
can have prepared. **And the prize it assumes does not exist** — `member.caret` costs what
`base.noCapture` costs (224 vs 254 ms; 2,035 vs 2,189), so the ~200 ms **is one narrowed
build**: completion latency IS the incremental floor. § 13 now cites BY SYMBOL after its line
numbers rotted a third time in a day.
**GATES.** Suite **16,677 / 0 / 3** (+24, all this round's pins); `cost_gate.py` exit 0, every
counter +0.00%, `output.errors` 46; `huge_methods.py --fail-over 0` clean; **`partition-gate.sh
sensitivity` EQUIVALENT on all 76 files across 78 netting passes, 72 carrying rows** — the arm
that can see a starved partition. `cost_gate` and the corpus are CONTROLS here, not coverage.
