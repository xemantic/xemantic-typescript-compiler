

**(INC.52) — THE INCREMENTAL FLOOR'S DEAREST PASS STOPS WALKING EVERY FILE'S SYMBOL
TABLE, AND ITS PRICE IS BELOW WHAT THIS REPO CAN MEASURE (2026-08-29).** With project
diagnostics incremental ((INC.46)) and restart-proof ((INC.48)), what an editor pays per
keystroke is the FLOOR. Decomposed: **68 ms**, of which the checker is **42 ms (67%)** with
nothing to check, and the largest pass in both draws is `init:computeAllEnumValues` — whose
second loop visited EVERY file's `locals` and recursed through every namespace's `exports`
to find the program's enums. `BinderResult.bindsEnum` answers that from the bind that
already happened: an identity, not an approximation, since `bindEnumDeclaration` is the one
site minting a conventional enum symbol and `enumValues` is ID-keyed. **MEASURED AS A
POPULATION, from ONE binary with the verify arm as the "before": 12,871 top-level symbol
visits -> 8,676 (-32.6%)**, plus every namespace recursion beneath the **45 of 78** files
skipped, with `localsSkipViolations = 0` over a non-empty skipped set. **AND THE TIME IS NOT
RESOLVABLE, WHICH IS THE PART WORTH KEEPING**: the row that motivated the round read 13.16
ms in one draw and **8.42 ms in the next draw of the same binary**; after the change, 7.27
and 9.66; the floor wall reads 68 before and 74 after with draws spanning 57-86. So it is
landed as a WORK REDUCTION with a control and no millisecond is claimed — a single-draw
per-pass row on a 68 ms floor is not a measurement, and that is now a CLAUDE.md entry
because the next agent will read the same table and reach for the same row. **GATES.** Suite
**16,485 / 0 / 3** (+2, exactly the new pins); `cost_gate.py` exit 0; `huge_methods.py
--fail-over 0` clean; warning-clean.

**(INC.48) — THE INCREMENTAL STATE OUTLIVES THE PROCESS, AND A RESTART IS **60x**
(2026-08-29).** (INC.46) made project-wide diagnostics incremental within a process and
every bit of that state died with it: an IDE restart, a plugin reload or a daemon recycle
paid a whole-program build for a tree nobody had touched. `Project.saveState()` encodes
what has to survive — export signatures, escapes, the program's file list, that build's
diagnostics and a content hash per input — and `restoreState()` adopts it, so the next
process starts at the (INC.46) gate instead of at a rebuild. **MEASURED on tsc's own 78
sources, every arm asserted to agree ROW FOR ROW**: warm, **5,855 ms -> 94 ms (62x)**
clean and 259 ms (23x) with a file changed on disk; in a **COLD process — which is what a
restart actually is — 9,625-9,844 ms -> 155-175 ms (~60x)**, the snapshot being **47 KB**
for a 78-file project. The cold column is the one that matters and it is nearly as good as
the warm one, which was not obvious: an IDE restart pays the JIT ramp, and (INC.49)
attributed ~18 s of a 23 s first query to exactly that — but the ramp barely touches a path
that never checks the whole program. **IT WRITES NO FILE**: `encode`/`decode` answer and
take a string, so the host decides where its caches live; the CLI's `--incremental`
(`tsconfig.xtsbuildinfo`, INV.7(d3)) remains the convention for callers who want the other
one. **EVERY PART OF THE CLAIM IS CHECKED, because skipping any of it is a stale answer**:
the compiler build id (never a `.dirty`/`unknown` one — two dirty trees share an id without
sharing behaviour), the config path, a CONTENT hash per file (never mtime — round 871), and
the `.json` INPUTS as well as the sources, since a changed tsconfig or a `package.json`
whose `type` decides a module format makes every stored row suspect rather than one file's.
**AND THE STALENESS CASE NO HASH CAN SEE HAS ITS OWN MECHANISM**: a file ADDED while the
process was down is in no stored hash and no stored list, so a restored state is not
trusted until a build has re-crawled and found the same program — even a clean project runs
the gate once, with an EMPTY partition. Ablated, the naive "trust the snapshot" version
reddens exactly two pins and nothing else. **GATES.** Suite **16,483 / 0 / 3** (+13,
exactly the new pins); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean;
warning-clean.

**(INC.50)/(INC.51) — THE STABILITY RATE IS A PROPERTY OF THE CODEBASE, NOT OF LAYERING;
AND ONE LINE OF ORDINARY LIBRARY CODE ESCAPED THE WHOLE FILE (2026-08-29).** (INC.47) left
one question: is 67% a property of the mechanism or of tsc's own sources? Measured on three
corpora of 40 real commits each, whole trees per side: tsc `src/compiler` **67%**,
`cronstrue` **50%**, `marked` **72%** — the two libraries BRACKET tsc, so layered code is
**not materially above** it and (INC.50)'s per-hop closure is refused by its own stated
threshold. `cronstrue` is the CONTROL arm and was chosen as one: it is the only library
outside the corpus where this checker agrees with tsgo 7.0.2 exactly (0 errors both sides)
and has no dependencies, because a library we report errors on has types degraded to `any`
and a degraded type is artificially STABLE. The transferable statement is that the rate
tracks **what a codebase's commits touch** — cronstrue's edits are to the ~44 locale
classes that ARE its exported surface (its MOVED cases are real signature changes such as
`commaOnlyOnX0()` -> `commaOnlyOnX0(s?: string)`), where tsc's are inside function bodies.
AND **(INC.51)**: pointing the mechanism at real code found a defect in ONE run.
`marked.ts` escaped because of `export { useExtension as use }` — the walk collected the
name an IMPORTER sees and looked it up in `locals`, which the file keys by the name it
DECLARES, so every renaming export missed, read as "an exported name with no file-level
symbol", and escaped the WHOLE file: every edit to it rebuilt the whole program forever and
the export's type was never hashed. tsc's own 78 sources never use the shape, so all eight
dashboard profiles are structurally blind to it. Fixed, with three pins — one of which
records a DELIBERATE conservatism: renaming the LOCAL still moves the hash, because
dropping declaration names would make two structurally identical classes hash equal and a
class with a `private` member is nominally typed. **AND THE (INC.47) LAW REPEATED ON A
SECOND CORPUS: removing an escape buys NOTHING** — marked's escapes went 1 -> 0 with its
rate unchanged at 72%, exactly as `types.ts` left tsc's at 67%. On both, the file that
could not be summarised was also one whose surface genuinely moved. **GATES.** Suite
**16,470 / 0 / 3** (+4, exactly the (INC.51) pins); `cost_gate.py` exit 0;
`huge_methods.py --fail-over 0` clean; warning-clean.

**(INC.47) — THE EXPORT FINGERPRINT IS A CANONICAL SERIALIZATION, THE ESCAPE CLASS IS
EMPTY, AND THE 87.5% CEILING IT WAS AIMED AT DID NOT EXIST (2026-08-29).** The walk no
longer recurses: every type reachable from a file's exports is DISCOVERED once, in a
deterministic order, and named by its discovery INDEX, so a reference — forward, back or
self — costs one lookup and cycles need no special case. There is no strongly-connected
component left to hash, which is why this is simpler than the Tarjan machinery the queue
named and strictly stronger. **MEASURED whole-program on tsc's own 78 sources**:
`types.ts` **122.52 ms for ONE export and a node-budget STOP -> 6.21 ms for 871 exports**;
whole-program **131 -> 16 ms**; structural nodes **2,019,605 -> 38,502**; budget stops
1 -> **0**; escapes `[types.ts]` -> **[]**; exports hashed 2,137 -> **3,007**; both
controls held (identical-text stability **78/78**, narrowed-vs-whole agreement **24/24**).
**AND THE PRIZE IS REFUTED ON BOTH ARMS RATHER THAN ARGUED**: the 40-commit stability
corpus reads **27/40 = 67% before AND after, with every one of the 40 per-case verdicts
identical**. (INC.46)(2)'s ceiling came from its runner printing *"N moved only because a
touched file ESCAPES"* over the code `if (escaped)` — which counts every case that
TOUCHED an escaping file — while its own detail lines showed four other movers in the same
case; re-derived, exactly ONE of the 8 qualified, so the ceiling was **70%**, and after
this even that one moves, because `types.ts` is a file of exported declarations and an
edit to it really does move the surface. **IT LANDS ON SOUNDNESS, NOT ON THE RATE**: the
old walk bounded its recursion with a DEPTH CAP of 24 and hashed everything below it as
one constant — a MISSED invalidation, i.e. a stale diagnostic, live since (INC.46)(3)
began answering project-wide diagnostics from the previous build. Both new pins are RED
against the pre-(INC.47) binary and green after, one for the mechanism (pinned on the node
COUNTER, not a time) and one for the soundness half. **The escape class being empty is a
claim about OTHER codebases** — a single-file library with a large cyclic type graph is
ordinary in real TypeScript and would have forced a whole-program rebuild on every
keystroke forever. **GATES.** Suite **16,466 / 0 / 3** (+2, exactly the new pins);
`cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean; build warning-clean.
**SUCCESSOR: (INC.50)** — the 67% is not improvable on this corpus by any mechanism, so
the live question is the rate on ordinary LAYERED code (`knip`, `jsonrepair`, `cronstrue`).

**(INC.46)(3) — PROJECT-WIDE DIAGNOSTICS ARE INCREMENTAL, AND WITH (INC.44)/(INC.45)
NOTHING AN EDITOR ASKS IS WHOLE-PROGRAM BY DEFAULT ANY MORE (2026-08-29).**
`Project.diagnostics()` no longer rebuilds after every edit: when the edit moved no
exported signature it answers the previous build's rows with the edited files' rows
replaced, from ONE narrowed build — **108-113 ms against 4,864-5,096 ms, a factor of 45**
on a served edit. **GRADED AS A DIFFERENTIAL THAT NEEDS NO BASELINE**: over (INC.46)(2)'s
40 real tsc commits, edited THROUGH THE OVERLAY as an editor's unsaved buffers, the answer
must equal a project opened fresh on the edited text — **EQUIVALENT, 40 agreed of 40**,
with `served=27` as the control that keeps the agreement from being vacuous (a run whose
`served` is 0 is REFUSED — round 790: a verifier reads 0 both when the skip is sound and
when the instrument is dead). The 27 is exactly step (2)'s 67%, two instruments
corroborating. **Five preconditions, each CHECKED rather than argued** and each with its
own pin; the pin set is a PAIR by construction (a body-only edit must be served and a
signature edit must not — an implementation that always serves passes the first, one that
never serves passes the second), and each is pinned twice, on the ANSWER and on the
BUILD COUNT, because without the cost family every pin passes against the old
always-rebuild behaviour. **TWO DEFECTS THE PINS FOUND THAT REVIEW DID NOT**: the
incremental answer was NOT RETAINED (`cached` cannot hold it — that field is a
whole-program `Result` — so a second `diagnostics()` with no intervening edit rebuilt, and
an editor asks twice constantly); and **the build-counting unit every cost pin in this repo
uses is BLIND for an edited config** — an overlaid file is served from the overlay and
never reaches the backing `Vfs`, so the config's read count stops moving and "did this
rebuild" reads 0 for a build that certainly happened. Two pre-existing control pins
legitimately moved 1 -> 2 builds and were updated to state the new cost model rather than
papered over: adding an export IS a signature change, and the gate being wrong costs the
narrowed build plus the rebuild. **GATES.** Suite **16,464 / 0 / 3** (+11, exactly the new
pins); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean; build warning-clean.
**THE SUCCESSOR IS SCC-AWARE HASHING**: `types.ts` still escapes on an in-file
strongly-connected component that no budget closes (measured at 2 M and 12 M nodes) and it
accounts for 8 of the 13 fallbacks, so Tarjan-per-component is the one lever between the
measured **67%** floor and the **87.5%** ceiling.

**(INC.46)(2) — THE STABILITY RATE IS **67%** OVER 40 REAL tsc COMMITS, AND ONE TEXT SCAN
WAS WORTH 35 POINTS OF IT (2026-08-29).** Step (2) is the one the queue said could refuse the
whole mechanism ("under ~70% the 45x is diluted to nothing"). `scripts/inc46-stability.sh`
fetches its OWN blob-filtered depth-3000 clone of microsoft/TypeScript — never
`typescript-repo`, which is a depth-1 shallow clone AND a build-pinned input — and replays
**40 real no-merge commits** touching `src/compiler`, materialising the WHOLE tree at the
parent against the whole tree at the commit (a file from another era beside a tree from this
one resolves against symbols that may not exist). **27 of 40 stable = 67%**, and **8 of the
13 that moved did so ONLY because `types.ts` escapes** — so the band is a **67% floor and an
87.5% ceiling** with one named lever between them. **THE FIRST READING WAS 32% AND WAS AN
ARTIFACT OF MY OWN CODE**: `declaresGlobalSurface` scanned whole source for
`export as namespace` — a construct with NO AST NODE in this parser — and `checker.ts` says
those words **twice, both in `//` comments**; since it is the file tsc's history edits most,
that single false positive cost **35 percentage points** and presented as a plausible
refusal rather than as a defect. **No fixture would have found it** — nobody writes
`// export as namespace foo` into a hand-written test — and the edit corpus found it in one
run. **`types.ts`'s escape is STRUCTURAL and was measured rather than assumed**: it is a
node-budget stop at 2,000,000 nodes (129.6 ms) AND still a stop at **12,000,000 (741 ms,
whole budget burned)**, because the file-boundary cut cannot help INSIDE a file and
`types.ts` declares ~874 mutually recursive interfaces in one. The lever is **SCC-aware
hashing**, deliberately not attempted here; the budget stays bounded and the file is recorded
in `ExportSignatures.whole` — a full rebuild, never a stale diagnostic. **GATES.** Suite
**16,453 / 0 / 3** (+13 over 16,440: the 12 step-(1) pins plus the comment-mention pin this
defect earned); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean. Step (3),
wiring the invalidation into `Project.diagnostics()`, is now the only item left.

**(INC.46)(1) — THE EXPORTED-SIGNATURE FINGERPRINT IS BUILT AND MEASURED, AND ITS WALK
HAD TO BE FOUND BY MEASUREMENT THREE TIMES (2026-08-29).** The queue's step-(1) threshold
("single-digit ms on `types.ts`'s 874 exports, or stop") is met with room: **136 ms
whole-program** on a 5,215 ms rebuild, and **0 ms on 23 of 24 narrowed builds** — a
narrowed build fingerprints only its partition, so the per-EDIT cost of the gate is under
a millisecond against the 108-113 ms build it rides on. **The two controls that decide
feasibility are not cost figures**: two builds of identical text agree **78/78** (the
id-freedom claim — a hash carrying a `Type.id` passes every structural test and then
invalidates everything, always), and a narrowed build's fingerprint equals the
whole-program one **24/24** (the CONVERGENCE claim — the baseline comes from a
whole-program build and the edit's answer from a narrowed one, so a systematic
disagreement means every first edit falls back forever). **THE WALK'S SHAPE WAS THE REAL
QUESTION.** A path-only cycle guard is EXPONENTIAL in DAG width — 159 s inside one build,
found by an external `jcmd Thread.print` — and closed-subtree memoization is still not
enough, because tsc's resolved-type graph is one giant SCC (`Node.parent: Node` plus
hundreds of mutually recursive interfaces): **6 of 78 files unfinished inside a
2,000,000-node budget, among them `checker.ts`, `binder.ts` and `emitter.ts`**. What works
is CUTTING at the file boundary — a type declared elsewhere is unchanged by construction
while only this file is edited, so it is keyed by its declaration's `(fileName, pos, end)`
and not descended into. That took the arm from 719 ms / 6 escapes / **4-of-24** agreement
to **136 ms / 2 escapes / 24-of-24**. **AND THE QUEUE CENSUSED THE WRONG QUANTITY**: cost
tracks the transitive type CLOSURE, not the export COUNT, and the two are near-inversely
related — `utilities.ts`'s 692 exports are 1.6 ms where `types.ts`, which declares the
SCC, is 129.6 ms. Steps (2) (the stability RATE, which needs a deepened TypeScript clone)
and (3) (wiring the invalidation) are deliberately NOT in this commit — the order of work
is measure-first and (2) can still refuse the whole thing. **GATES.** Suite **16,452 / 0 /
3** (+12 over 16,440, exactly the new pins); `cost_gate.py` exit 0 with a largest move of
**+0.08%** (the profile's standing residual — the expected answer, since the walk is off
by default and a strict no-op then); `huge_methods.py --fail-over 0` **0 over limit**.

**(INC.46) QUEUED AND PRICED — AND MEASURING IT REFUTED THE QUEUE'S OWN EXPLANATION OF WHY
PROJECT-WIDE DIAGNOSTICS CANNOT BE INCREMENTAL (2026-08-29, owner's idea).** The standing
story, from round 772 and (INC.35), is that a dependency closure buys nothing on tsc because
its sources are `export *` barrels. **The barrels were never the cause.** A SYMBOL-level use
graph — which is free, since `capturedDefinitions` already records span -> declaration —
re-checks **100% of the program's characters at the median edit, the same as the file-level
graph** (94.9% of imported names placed, so not an under-count): those files genuinely use
symbols from most other files and the relation is transitive. **What collapses it is asking
whether an edit moved any EXPORTED SIGNATURE**, not which symbols a file uses: a body-only
edit moves none, so no dependent re-checks and the cost is one narrowed build — **108-113 ms
against 4,864-5,096 ms, a factor of 45**, already measured by (INC.31)/(INC.37). **91.6% of
the program's characters are inside brace bodies** (a proxy for edit POSITION, optimistic
because an inferred return type leaks, pessimistic because it counts `interface` bodies).
**This needs no corpus and no owner call** — a signature hash pays on DENSE code too, so
unlike (INC.35) it is gradable on the dashboard profile. **THE SHARP HAZARD IS RECORDED**:
`typeToString` is the wrong hash source in BOTH directions — `aliasDisplayMap` is a
first-wins global so it is not a pure function of the type (spurious invalidation), and B58.1
renders `errorType` as `"any"` so a degraded resolution hashes as a genuine `any` (a MISSED
invalidation, silently). The hash must be an id-free structural fingerprint; (INC.16) already
built one to copy. Cost input censused: **3,398 exported declarations, mean 44/file, max 874
in `types.ts`**; its runtime is the first thing to measure, with a stated refusal threshold.
**No code landed — the entry is the deliverable.**

**(INC.45) — `renameAt` IS NARROWED TOO, AND ITS ABLATION FOUND A BLIND PIN SET
(2026-08-29).** The rename sweep took (INC.44)'s spelling closure and hands the resulting
file set to the compiler as a check partition. Two things make it more than a copy.
**Both of a rename's builds must share ONE partition** — `verifyRename` compares
diagnostics as a `(file, code)` MULTISET, which a partition filters, so a narrowed
"before" against a whole-program "after" reports every unswept row as removed; the
soundness argument for narrowing it at all is that a rename edits only files the plan
names and an unedited file's meaning can change only through a name it imports, which it
must then SPELL. **And the population is the closure UNION every occurrence of the NEW
name**, because `verifyRename`'s third check — the only one that can see a rename which
compiles and means something else — scans for occurrences already spelling it and would
otherwise pass VACUOUSLY. **THE ABLATION'S FINDING**: arm b2 (the after-build forgets the
partition) reddened **NOTHING**, because every fixture was a CLEAN program and both bags
were empty whatever either build walked — one file carrying a diagnostic and spelling
neither name takes it to **2 RED**. Arm b3 (never narrow) is **UNDISCRIMINATED and
recorded as such**: the change is equivalence-preserving by construction, so what stands
in its place is one pin on the shipped DEFAULT with no mode install in it ((INC.16)'s
lesson). **MEASURED**: an ordinary rename is **~1.0-1.3 s against ~15 s (12-14.5x)** —
`emitFiles` 2 of 78 files at 1,304 ms, `transformNodes` 3 of 78 at 1,025,
`checkSourceElement` 1 of 78 (but that file is `checker.ts`) at 4,725.
**GATES.** Suite **16,440 / 0 / 3** (+18 over the session's 16,422 baseline, exactly the new pins); rename differential **EQUIVALENT** — 8 carets,
7 narrowed, 6 producing an APPLICABLE plan, 1,691 edits compared plan for plan, 0
diverged, 56.5 s against 114.2 s; three ablation arms b1 **1 RED** / b2 **2 RED** / b3
undiscriminated with a reason.

**(INC.44) — `referencesAt` IS NARROWED BY *SPELLING*, AND THE DOC CLAIM THAT IT "CANNOT
BE" CONFUSED THE CLAIM WITH THE EVIDENCE (2026-08-29).** `docs/language-service.md` said in
three places that find-references and rename "are NOT narrowed and will not be: their claim
is about every file, so there is nothing to narrow to". The claim is program-wide; the
EVIDENCE is not — an occurrence can only be an answer if it SPELLS a name the symbol is
reachable by. `referencesAt` now selects that population before typing it and `captureIn`'s
partition, which has always been DERIVED from the request's spans, narrows the check with
it: **no new mechanism**. On tsc's own 78 sources an ordinary name costs **510–553 ms
against 8.8–11.1 s (17–18x)**, `checker.ts`-only names 1,940 ms (4.8x), and the worst
realistic case (`SyntaxKind`, 9,827 hits in 49 files) still wins at 4,904 ms; a repeat is
free (119–150 ms) because the narrow path reaches a memo the whole-program one never did.
The closure over `import { p as q }` / `export { p as q }` terminates because both spellings
are tokens of the file DECLARING the alias; everything else — a default export, a default
import's local, `export =`, `import x = require(…)`, a namespace binding, the spelling
`default` — REFUSES and runs the old sweep. **The near-miss worth remembering**: the obvious
substring file filter is not exact, because `StringLiteralNode.text` is the COOKED value and
`\a` is an identity escape, so `o["pl\ain"]` names `plain` — a file may be skipped only if
it holds no backslash at all (29 of 78 do, carrying 78.2% of the characters). **The
ablation's honest half**: arm a3 reddens only the REFUSAL pins, so the escape guards are
CONSERVATISM — kept because tsc answers **6** references where we answer **2** on a
`export { renamed as default }` edge, which is now pinned so the day it closes is loud.
**GATES.** Suite **16,434 / 0 / 3** (+12 from a re-verified 16,422 baseline, exactly the new pins); reference differential **EQUIVALENT** — 60 carets drawn by stride over all 381,775 occurrences, **59 of them actually narrowed** (the control), **0 diverged**, 12,248 hits compared element for element; mean partition **17.5 of 78 files**, aggregate 182.0 s narrowed against 561.6 s whole-program (**3.09x** on a draw that lands proportional to occurrence count, i.e. on the hottest names);
four ablation arms, four DISTINCT red sets; `cost_gate.py` / `huge_methods.py` are CONTROLS here (no `-core` source
touched) and both are green: `cost_gate.py` exit 0 with `output.errors` **46** and a largest move of **+0.08%**
(`globals.lookups`/`globals.misses` — the profile is unchanged, this is its standing
run-to-run residual), `huge_methods.py --fail-over 0` clean.

