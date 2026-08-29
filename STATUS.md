# Status

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
