# Status

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
