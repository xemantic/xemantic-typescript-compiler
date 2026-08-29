# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `docs/history/PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

### Round (INC.53) — the incremental floor's largest block was never in a pass

**WHAT THIS ROUND BOUGHT.** ~13 ms off the per-keystroke FLOOR of every
language-service query, a permanent instrument for a class of cost this repo could
not previously see, and one measured refusal.

**HOW IT WAS FOUND, because the route matters more than the fix.** (INC.52) left the
floor decomposed to phases: 32-44 ms of a 63-72 ms floor is "checker construct +
getDiagnostics", while the `--passTimingRows` table INSIDE it sums to ~19 ms. A third
of the floor was therefore un-attributed and nobody had asked which of the two owners
held it. Two `FrontEnd` rows answered it in one run: **`getDiagnostics()` is 2-3
MICROSECONDS on a floor build**, so the entire phase is the CONSTRUCTOR. Splitting
the constructor at its `init` block (Kotlin runs property initializers then init
blocks in declaration order, and this class declares every field above its one init
block — a CLAUDE.md invariant, which is what makes the subtraction exact) gave
**~20 ms of ~494 PROPERTY INITIALIZERS**, and the decisive control is that the number
is IDENTICAL on a 63 ms floor build and a 5.2 s full one (16.0-21.8 ms across eight
draws either side). A constant that is 0.4% of a full compile and ~30% of a keystroke.

**WHY NO INSTRUMENT HERE COULD SEE IT.** A field initializer is not a `pass("…")`.
The `--passTiming` table is built BY `pass()`, `cost_gate.py` reads that table's
counters, and the 8-profile grid compares diagnostics, which do not move. So the
whole pass-gating arc — (INC.7)/(INC.20)/(INC.21), 189 walkers gated onto the check
partition, four batches of loop-header sweeps — was structurally incapable of
reaching this, however carefully it swept. **That is the transferable half of the
round and it is now a CLAUDE.md entry: before pricing anything else in the floor,
ask what runs OUTSIDE a pass.**

**THE ARITHMETIC THAT NAMED THE CULPRITS, before any code was read.** 20 ms over ~494
initializers is 40 us each, which is impossible for an allocation by ~3 orders of
magnitude — the repo's own "a total is a LOCATION, not a price; divide it and refuse
an impossible per-op cost" rule. So a handful had to be doing real work, and four
were: `parseBuiltinLib` ~11.0 ms, `localTypeAliasIndex` ~5.4, `enclosingImportIndex`
~3.4, `topLevelConstStringValues` ~3.1. The partition is exact — sum against measured
field region reads 18.3/18.6, 25.2/25.4, 28.5/29.6, 29.7/30.2 — so the other ~490
initializers are **0.2-1.2 ms between them**.

**WHAT LANDED.** The three whole-program INDICES build on first ask. Each has exactly
ONE read site, which is what makes this not an approximation: `localTypeAliasIndex`
becomes a per-FILE index (`localTypeAliasesOf`) over that file's own frozen
statements, in the same DFS order, first-wins per name; the other two are
`lazy(NONE)` whole maps. Floor field region **18.6 / 25.4 / 29.6 / 30.2 ms -> 8.1 /
12.6 / 8.4 / 11.2**, with all three rows reading 0.00 ms and 0 files on a floor build.
A FULL build needs only **69 of 78** files' alias index, which was not predicted.

**CLAIMED AS A WORK REDUCTION, NOT A MILLISECOND** — (INC.52)'s law, and it applies to
this round's own rows: the floor's draws span 57-86 ms. `EagerIndexCensus` counts the
population instead and `EagerIndexDeferralTest` pins it.

**THE PIN THAT CAUGHT ITSELF, and it is the reusable lesson.** The per-file assertion
(`fileScans < program size`) first passed as **0 < 3** — the fixture never reached
`findLocalTypeAlias` at all, whose one caller needs a FUNCTION-LOCAL (B83.5-unbound)
discriminated-union alias used as an array-literal element type. The vacuity guard
written beside it ("an unpartitioned build still builds the indices it needs") is what
went RED and exposed it. A count-based deferral pin needs a sibling asserting the
mechanism is REACHED, or "it was not built" and "it is never built" are indistinguishable.

**THE FOURTH IS REFUSED WITH ITS PRICE, and the tempting hypothesis is MEASURED WRONG.**
`parseBuiltinLib` fills `builtinLibDecls`/`builtinLibMemberDecls`/`realLibDeclFile`,
which are keyed by AST NODE — Kotlin data classes, whose `hashCode()` recurses the
whole subtree (round 471) — and CLAUDE.md flags those very sets as safe only "because
lib decl subtrees are small", which the DOM lib is not. Split, that walk is
**1.9-2.8 ms** of 8-11: the binds are 3.2-5.3 and the lib-set resolution plus 45
`mergeSymbolTable` calls 3.1-5.3. No part clears the floor for a round, and the two
larger ones are per-checker BY REQUIREMENT — the checker merges into and mutates lib
symbols (round 882: 406 adopts, 175 mutates, all on LIB symbols). **Named unblocker:
round 884's `mergedSymbols` clone-on-write forwarding table.** The probe rows stay, so
the next attempt starts from the split rather than from the refuted hypothesis.

**WHAT WAS NOT ESTABLISHED, stated rather than left to be found.** `cost_gate.py`
exits 0 with `output.errors` flat at 46, but two counters moved inside tolerance
(`typeOfExpr.calls` +0.54%, `narrow.memoServed` +1.55%) — a first-touch ORDER shift,
since the three indices are now built during the check rather than before it. **It is
NOT separated from (INC.52)'s own drift**: the baseline was last recorded at
`7a488783b` and (INC.52) is the only checker-touching commit since, and it quoted no
per-counter deltas. Separating them needs a parent build and was not done.

**GATES.** Suite **16,489 / 0 / 3** (+4, exactly the new pins); `cost_gate.py` exit 0;
`huge_methods.py --fail-over 0` clean, and `Checker.<init>` shrank **5,538 -> 5,464**
bytecodes, buying back (JIT.1)(d) headroom. Commits `af032b5e2`, `5f8390d3f`.

**NAMED SUCCESSORS.** (1) The floor's next-largest block is now the pass table itself
at ~19-24 ms, whose top rows are `init:computeAllEnumValues` 6.9 ms,
`init:moduleTypeNameIndex` 2.6, `checkModulePreserve4Pin` 1.7,
`init:computePerFileVisibility` 1.4, `checkJsxImportResolutions` 1.2,
`init:buildPerFileScopes` 1.0 — the same "build it on first ask" question, now for
`init:` passes rather than field initializers. (2) The crawl still READS AND DECODES
every file on every query (10-12 ms wall, 44-56 ms of CPU) although the PARSE is
already content-cached; for a host that owns its VFS that is redundant, and it is the
largest remaining front-end row. (3) `parseBuiltinLib`, behind round 884.

### Round (CHK.73) — REFUSED with its price measured, and the entry it refutes is its own

**WHAT THIS ROUND BOUGHT.** Not a fix: a diagnosis that replaces the queue's, and a
measured refusal with a named prerequisite. (CHK.73) said a default or namespace import
"types as `any`" and priced a fix against round 409's TS2315 flood, with an
ambient-module-only containment as the thing to try first. Built and measured, all three
parts of that reading are wrong in a useful way.

**FIRST, THE INSTRUMENT.** The bench profile's `node_modules/@types` is EMPTY, so
CLAUDE.md's "only `build/bench/tsc-project-*` carries the real `@types/node`" is stale and
the shape cannot be probed there at all — the tell is `unresolved imports: N (e.g. 'fs')`
plus a TS2688, which reads exactly like the defect under investigation. A scratch project
with `npm i @types/node@20` (network works; `tools/node/bin/npm` needs `node` ON THE PATH)
gives a live repro against tsgo: **tsgo answers 3 rows where we answer 1**.

**THREE DEFECTS, NOT ONE, AND NONE OF THEM IS THE ONE THE ENTRY NAMED.**

 1. `resolveAlias`'s `ImportDeclaration` arm has no `resolveImportTargetFallback` leg —
    (CHK.30)'s standing rule for a BARE package specifier, applied to every other ladder.
 2. **`getTypeOfSymbolWorker` HAS NO `SymbolFlags.Module` ARM.** A fully resolved module
    symbol with a populated `exports` table falls through to `anyType`. The alias
    resolution the entry blamed has worked for a long time — `createModuleSymbol` even
    digs a `.d.ts`'s single `declare module "…"` block out for exactly this case.
 3. `@types/node` is AMBIENT, so no FILE resolves and the crawl is right to report `fs`
    unresolved. `import x = require("fs")` already takes a `globals[specifier]` second
    chance; `import * as fs from "fs"` did not.

**WITH ALL THREE, THE BINDING AND ITS MEMBERS TYPE EXACTLY AS tsgo** — `fsStar` -> the
module, `fsStar.statSync` -> `StatSyncFn`, `fsStar.readFileSync('x')` ->
`Buffer<ArrayBuffer>` — i.e. hover and completion on `fs.` work, which is the editor-facing
half of the goal.

**AND IT MAY NOT LAND, WHICH THE CORPUS SAID AND REVIEW DID NOT.** A general
`SymbolFlags.Module` arm moves **21** baselines (the internal-module family:
`aliasUsageIn*`, `typeValueConflict*`, `moduleAndInterfaceWithSameName`,
`typeofInternalModules`). Containing it to an import alias's TARGET — so a bare `namespace
N` value reference is untouched — takes that to **4**, and those four are ONE cause and a
MEANING regression: a module object exposes an exported CLASS as its CONSTRUCTOR, and this
checker types a class VALUE as its INSTANCE type, so a ctor-less class has no construct
signature to match `new () => Model`. **The prerequisite is therefore the static side of a
class value**, which is a checker-wide model change and not this round's business.

**A THIRD THING THE PROBE SEPARATED.** The `statSync` silence that remains after all of
this is not about namespaces: `statSync('x')` fails to type through a NAMED import too. It
is a call whose callee type is a callable INTERFACE (`StatSyncFn`), which is its own gap.

**NOTHING WAS LANDED AND THE TREE IS AT HEAD.** The queue entry now carries the
decomposition, the four named tests, and the prerequisite; CLAUDE.md carries the two facts
a future agent would otherwise re-derive (the missing Module arm, and the empty `@types`).

### Round (INC.52) — the floor's dearest pass, and the measurement that says its price is unknowable

**WHY THIS PASS.** With project diagnostics incremental ((INC.46)) and restart-proof
((INC.48)), what an editor pays per keystroke is the FLOOR — the crawl, parse, bind and
program-wide passes a narrowed build runs whatever it is checking. Decomposed this round
(`scripts/floor-decomposition.sh`): a **68 ms** floor, of which the CHECKER is 42 ms (67%)
with nothing to check, and the largest single pass in both draws is
`init:computeAllEnumValues`.

**WHAT LANDED.** That pass has two loops. (INC.16) gave the first a projection; the second
still walked EVERY file's `locals` and recursed through every namespace's `exports` looking
for `SymbolFlags.Enum`. `BinderResult.bindsEnum` answers the same question from the bind
that already happened — an identity, not an approximation: `bindEnumDeclaration` is the one
site minting a conventional enum symbol, `enumValues` is ID-keyed, and a merged symbol is
one object shared by both files' tables (round 884), so a skipped file's enums are computed
through the file that minted them.

**MEASURED AS A POPULATION, FROM ONE BINARY.** The verify arm walks everything, so it *is*
the before: **12,871 top-level symbol visits -> 8,676 (-32.6%)**, plus every namespace
recursion beneath the **45 of 78** files skipped, with `localsSkipViolations = 0` over that
non-empty skipped set (round 790's rule: a zero is evidence only beside a non-zero skip).

**AND THE TIME IS NOT RESOLVABLE — WHICH IS THE PART WORTH KEEPING.** The row that motivated
the round read **13.16 ms**, one draw. Two draws of the SAME binary read **13.16 and 8.42
ms**; after the change, **7.27 and 9.66**. The floor wall reads 68 ms before and 74 after,
with draws spanning **57-86 ms**. So the distributions overlap on both instruments and no
millisecond is claimed: this is landed as a WORK REDUCTION with a control, not as a
speed-up. **A single-draw per-pass row on a 68 ms floor is not a measurement**, and the
floor's rows swing ~40% draw to draw — which is now a CLAUDE.md entry, because the next
agent will read the same table and reach for the same row.

**WHAT THE FLOOR IS MADE OF, for whoever prices the next lever** (68 ms, one draw, so read
the shares and not the millisecond): config load + `@types` + root glob 9 ms, import-graph
crawl 10 ms (80% of it read+decode), bind 4-6 ms, **checker construct + getDiagnostics 42
ms**, post-checker 2 ms. Inside the checker, after this round, the largest rows are
`init:computeAllEnumValues` ~7-10, `init:moduleTypeNameIndex` ~5.2,
`checkModuleAugmentationReexportDuplicates` ~4.4-5.4, `init:computePerFileVisibility` ~1.6.
**Nothing there is worth a round on its own** at this resolution; the honest next lever is
either an amplified measurement (round 759's shape) or a structural one — reusing the BIND
across queries, which CLAUDE.md records as blocked by `nodeToSymbol`'s cross-file `(pos,
end)` collisions.

**GATES.** Suite **16,485 / 0 / 3** (+2, exactly the new pins); `cost_gate.py` exit 0;
`huge_methods.py --fail-over 0` exit 0; build warning-clean.

### Round (INC.48) — the incremental state outlives the process, and a restart is 60x

**WHAT LANDED.** `Project.saveState()` / `restoreState()`, plus `ProjectStateSnapshot` in
core. (INC.46) made project-wide diagnostics incremental WITHIN a process and every bit of
that state died with it: an IDE restart, a plugin reload or a daemon recycle paid a
whole-program build for a tree nobody had touched. The snapshot carries what has to
survive — export signatures, escapes, the program's file list, that build's diagnostics,
and a content hash per input — so the next process starts at the (INC.46) gate instead of
at a rebuild.

**MEASURED on tsc's own 78 sources (`scripts/inc48-restart-cost.sh`), every arm asserted to
agree ROW FOR ROW:**

| arm | warm | COLD process |
|---|---|---|
| `cold-open` — what a host pays today on every restart | 5,855 ms | 9,625-9,844 ms |
| `restored-clean` — snapshot restored, nothing changed | **94 ms (62x)** | **155-175 ms (~60x)** |
| `restored-edited` — one file changed on disk since | 259 ms (23x) | — |

The snapshot is **47 KB** for a 78-file project. **The cold column is the one that
matters and it is nearly as good as the warm one**, which was not obvious: an IDE restart
pays the JIT ramp, and (INC.49) measured ~18 s of a 23 s first query as exactly that. It
barely touches the restored path, because that path never checks the whole program —
there is no ramp to pay for work that is not done. (The 9.6-9.8 s cold-open here is not
(INC.49)'s 23.3 s; the conditions differ and the difference is not decomposed.)

**IT WRITES NO FILE, DELIBERATELY.** `encode`/`decode` answer and take a string, so the
host decides where — and whether — its caches live. An embedding API that dropped a file
into somebody's source tree unasked would be making that decision for it, and the CLI's
`--incremental` (`tsconfig.xtsbuildinfo`, INV.7(d3)) already serves callers who want the
other convention.

**EVERY PART OF THE CLAIM A SNAPSHOT CARRIES IS CHECKED, because skipping any of it is a
stale answer**: the compiler build id (and never a `.dirty`/`unknown` one — two dirty dev
trees share an id without sharing behaviour), the config path, a CONTENT hash per file
(never mtime — round 871), and **the `.json` INPUTS as well as the sources**. That last is
not a nicety: a changed `tsconfig` or a `package.json` whose `type` decides a module
format ((CHK.29)) makes every stored row suspect rather than one file's, so it refuses the
restore rather than narrowing it. `OverlayVfs` records which `.json` files a build actually
read, because that set is not a function of the project path (`extends`, nodenext scopes).

**AND THE STALENESS CASE NO CONTENT HASH CAN SEE HAS ITS OWN MECHANISM.** A file ADDED
while the process was down is in no stored hash and in no stored list, and it changes what
every importer resolves. So a restored state is NOT TRUSTED until a build has re-crawled
and found the same program: even a clean project runs the gate once, with an EMPTY
partition, which is the 94-155 ms floor rather than the 5.9 s rebuild. **Ablated**: the
naive "trust the snapshot when nothing changed" implementation reddens exactly two pins —
`a file added while the process was down is not missed` and `a restored state answers its
first query through the gate` — and nothing else.

**A SEAM THAT EXISTS TO STOP A PIN SET BEING VACUOUS IN ONE ENVIRONMENT.** A development
tree's build id ends in `.dirty` and is correctly refused, so without
`allowUnstableBuildIdForTesting` every pin here would be vacuous locally and exercise the
real path only in CI — a pin that passes for opposite reasons in two environments is worse
than no pin. It is installed and restored per test, it does not weaken the id EQUALITY
check (`a snapshot from a different compiler build is refused` is the pin that says so),
and the shipped default is pinned by a test of its own ((INC.16)'s law: a mode every pin
installs is a default pinned by nothing).

**13 pins in three families** — value, cost, refusal — with value paired to cost by
construction, because an implementation that restored nothing passes every value pin.

**GATES.** Suite **16,483 / 0 / 3** (+13, exactly the new pins); `cost_gate.py` exit 0;
`huge_methods.py --fail-over 0` exit 0; build warning-clean.

### Round (INC.50)/(INC.51) — the stability rate is a property of the CODEBASE, not of layering; and one line of ordinary library code escaped the whole file

**WHAT THIS ANSWERS.** (INC.47) closed the escape question and left one thing open: is
**67%** a property of the mechanism or of tsc's own sources? tsc is one codebase's style —
`export *` barrels, 78 files in a flat directory, one file declaring the whole type
universe. (INC.50) said to refuse the per-hop closure *"unless the measured stability rate
on a layered corpus is materially above the 67% measured here"*.

**MEASURED ON THREE CORPORA, 40 real commits each, whole trees materialised per side:**

| corpus | files | rate | escapes |
|---|---|---|---|
| tsc `src/compiler` | 78 | **67%** | 0 |
| `cronstrue` (i18n locale layer, nested `src`) | 52 | **50%** | 0 |
| `marked` (Lexer -> Tokenizer -> Parser -> Renderer) | 13 | **72%** | 1 -> **0** after (INC.51) |

**SO (INC.50) IS REFUSED BY ITS OWN THRESHOLD.** Layered code is not materially above 67%
— the two libraries BRACKET it, and the higher of the two carries a bias toward stability
(18 ours-only diagnostics degrade some of its types to `any`, and a degraded type is
artificially stable). The transferable statement is that the rate tracks **what a
codebase's commits touch**, not how layered it is: cronstrue's edits are overwhelmingly to
the ~44 locale classes that ARE its exported surface — measured, its MOVED cases are real
signature changes such as `commaOnlyOnX0()` -> `commaOnlyOnX0(s?: string)` — where tsc's
edits are mostly inside a compiler's function bodies.

**cronstrue IS THE CONTROL ARM AND THAT IS WHY IT WAS CHOSEN.** It is the only library
outside the corpus on which this checker agrees with tsgo 7.0.2 exactly (0 errors both
sides) and it has no dependencies, so its fingerprints are computed from types that are
actually resolved. A library we report errors on would read MORE stable, not less.

**(INC.51) — AND POINTING IT AT REAL CODE FOUND A DEFECT IN ONE RUN.** `marked.ts` escaped,
and the cause is `export { useExtension as use }`: the walk collected the name an IMPORTER
sees and looked it up in `locals`, which the file keys by the name it DECLARES. Every
renaming export missed, read as "an exported name with no file-level symbol", and escaped
the WHOLE file — so every edit to it rebuilt the whole program forever, and the export's
type was never hashed. tsc's own 78 sources never use the shape, so the eight dashboard
profiles are structurally blind to it. Fixed by carrying the two names separately, with
three pins; the third is the interesting one, because it pins a DELIBERATE conservatism:
renaming the LOCAL still moves the hash (a function's type carries its declaration's name),
and dropping declaration names would make two structurally identical classes hash equal —
which is unsound, since a class with a `private` member is nominally typed.

**AND THE SAME LAW APPEARED TWICE MORE: REMOVING AN ESCAPE BUYS NOTHING.** marked's escape
went 1 -> 0 and its rate stayed at **72%**, exactly as `types.ts`'s removal left tsc's at
67%. On both corpora the file that could not be summarised was also a file whose surface
genuinely moved. An escape is a conservative label on a file that is *changing a lot* —
which is why it looks like a cause and is not one.

**WHAT THIS MEANS FOR THE ARC.** Two thirds of edits (67% / 72%) and half of cronstrue's are
answered from a ~110 ms narrowed build instead of a ~5 s rebuild. The remaining third are
commits that genuinely move an exported signature, and no refinement of the FINGERPRINT can
serve them — only re-checking fewer dependents can, which is the closure (INC.35) measured
at 100% of tsc's characters and this round refuses on the library corpora too.

**GATES.** Suite **16,470 / 0 / 3** (+4 over 16,466 — exactly the (INC.51) pins);
`cost_gate.py` exit 0; `huge_methods.py --fail-over 0` exit 0; build warning-clean.

### Round (INC.47) — the fingerprint walk is now LINEAR and the escape class is empty; the 87.5% ceiling it was aimed at did not exist

**WHAT LANDED.** The exported-signature walk no longer recurses. Every type reachable from
a file's exports is DISCOVERED once, in a deterministic order, and named by its discovery
INDEX; a reference — forward, back or self — hashes as that index, and the file's hash folds
each discovered type's own LOCAL structure in discovery order. That is a canonical
serialization of the reachable subgraph: linear in nodes plus edges, cycles needing no
special case, and **no strongly-connected component to canonicalise** — which is why it is
both simpler and stronger than the Tarjan-per-SCC machinery the queue named.

**MEASURED, whole-program, on tsc's own 78 sources (`scripts/inc47-fingerprint-cost.sh`):**

| | before | after |
|---|---|---|
| `types.ts` | **122.52 ms for ONE export**, node-budget STOP | **6.21 ms for 871 exports** |
| whole-program fingerprint | 131 ms | **16 ms** |
| structural nodes visited | 2,019,605 | **38,502** |
| budget stops / escapes | 1 / `[types.ts]` | **0 / `[]`** |
| exports hashed | 2,137 | **3,007** |
| identical-text stability | 78/78 | **78/78** |
| narrowed-vs-whole agreement | 24/24 | **24/24** |

**AND THE PRIZE IT WAS BUILT FOR DOES NOT EXIST — MEASURED, NOT ARGUED.** (INC.46)(2)
recorded *"8 of the 13 still-MOVED cases moved ONLY because `types.ts` escapes"* and derived
from it a **67% floor with an 87.5% ceiling**, which is what made (INC.47) the named
successor. Running the same 40-commit corpus on BOTH arms: **27 stable / 40 = 67% on each,
and every one of the 40 per-case verdicts is IDENTICAL.** Removing every escape bought
exactly nothing on this corpus.

**THE CEILING WAS A MIS-READ LABEL ON THE INSTRUMENT'S OWN OUTPUT.** `Inc46StabilityMain`
printed *"N were moved only because a touched file ESCAPES"* over the code
`if (escaped) movedBecauseEscaped.add(case.name)` — which counts every case that TOUCHED an
escaping file, whether or not it also moved for a reason of its own. Its own printed detail
contradicted the summary in the same run: case `009-0208948c` reads
`[checker.ts, commandLineParser.ts, core.ts, executeCommandLine.ts, types.ts(escape)]`, i.e.
four files moved beside the escape. Re-derived properly, **exactly ONE of the 8 (`005`) had
the escape as its only mover**, so the real ceiling was 70%, not 87.5% — and after (INC.47)
even that one moves, because `types.ts`'s real fingerprint moves: it is a file of exported
interface declarations, so an edit to it usually IS a surface change. The runner now prints
BOTH counts, with the mis-read recorded beside them.

**SO WHY IT LANDS ANYWAY, AND THE STRONGEST REASON IS NOT THE COST.** The old walk bounded
its own recursion with a DEPTH CAP of 24 (`EXPORT_FINGERPRINT_MAX_DEPTH`) and hashed
everything below it as one constant. That is a **MISSED INVALIDATION** — the direction that
costs a stale diagnostic — and it is live in shipped code as of (INC.46)(3), which serves
project-wide diagnostics from the previous build whenever no touched file's fingerprint
moved. Pinned and ABLATED: `a change deep inside a cyclic type graph moves the fingerprint`
is RED on the pre-(INC.47) binary and green after, as is `a dense cyclic in-file type graph
is fingerprinted exactly`. Discovery indices need no depth cap, so there is nothing left to
truncate.

**AND THE ESCAPE CLASS IS EMPTY, WHICH IS A CLAIM ABOUT OTHER CODEBASES.** tsc's corpus
cannot show what that is worth — its one escaping file is one whose edits genuinely move the
surface — but a single-file library with a large cyclic type graph is ordinary in real
TypeScript, and before this it would have escaped and forced a whole-program rebuild on
every keystroke, forever. That is the population an editor integration lives in.

**WHAT A NEXT ROUND SHOULD NOT REDO.** Do not re-open SCC-aware hashing: there is no SCC
left to hash, and the rate it was supposed to move is measured flat on both arms. The
stability rate's remaining 33% is 13 commits that each genuinely move an exported signature;
the only mechanism that could serve those is the per-hop pruning of (INC.50), which is
measured to buy tsgo nothing on this same codebase.

**GATES.** Suite **16,466 / 0 / 3** (+2 over 16,464 — exactly the two new pins);
`cost_gate.py` exit 0; `huge_methods.py --fail-over 0` exit 0; build warning-clean.

### Round (INC.46)(3) — project-wide diagnostics ARE incremental now, and the gate is 40 real commits

**WHAT LANDED.** `Project.diagnostics()` no longer rebuilds the whole program after every
edit. When an edit moved no exported signature it answers the previous build's rows with
the edited files' rows replaced, computed by ONE narrowed build. `Project.surface` +
`Project.incrementalDiagnostics`, `ProjectCompiler.build(exportSignatures = …)` and its two
new `Result` fields. This is the last interactive operation in the API that was
whole-program in every case; with (INC.44)/(INC.45) it means **nothing an editor asks is
whole-program by default any more.**

**GRADED AS A DIFFERENTIAL OVER REAL EDITS, WHICH NEEDS NO BASELINE.**
`scripts/inc46-incremental-differential.sh` replays (INC.46)(2)'s 40-commit corpus: build
the parent tree, edit each touched file THROUGH THE OVERLAY (an editor's unsaved buffer),
ask `diagnostics()`, and compare row for row against a project opened FRESH on the edited
text. **EQUIVALENT — 40 agreed of 40 compared** — and the control that makes that mean
something is `served=27`, i.e. 27 of the cases were actually answered incrementally rather
than falling back. **A run with `served=0` is REFUSED by the harness**, because an
implementation that always fell back would agree on every case and prove nothing (round
790: a verifier reads 0 both when the skip is sound and when the instrument is dead). The
27 is exactly (INC.46)(2)'s 67%, which is the two measurements corroborating each other on
different instruments.

**FIVE PRECONDITIONS, EACH CHECKED RATHER THAN ARGUED, EACH WITH ITS OWN PIN.** A baseline
exists; every edited file was in that program; no edited file ESCAPES; the narrowed build
finds the SAME program (so an edit that adds an import falls back — the crawl still runs in
full, and a new file changes what every importer resolves); and no edited file's fingerprint
moved. The last is sound because a narrowed build's fingerprint equals the whole-program
build's, swept 24 of 24 in step (1) — the property that makes the mechanism CONVERGE instead
of falling back on every first edit.

**THE PIN SET IS A PAIR BY CONSTRUCTION.** A body-only edit must be SERVED and a signature
edit must NOT: an implementation that always serves passes the first, one that never serves
passes the second, and only both together say the gate discriminates. Both are pinned twice
— once on the ANSWER (equal to a fresh build's) and once on the COST (builds counted at the
`Vfs`), because without the cost family every pin passes against the pre-(INC.46) behaviour
of rebuilding every time. **11 pins**, `ProjectIncrementalDiagnosticsTest`.

**TWO THINGS THE PINS FOUND THAT REVIEW DID NOT.**
**(a) THE INCREMENTAL ANSWER WAS NOT RETAINED.** `cached` cannot hold it — that field is a
whole-program `ProjectCompiler.Result` and a narrowed build's is not one — so a second
`diagnostics()` with no intervening edit fell through and REBUILT. An editor asks twice
constantly (a project panel and a per-buffer annotator), so the mechanism would have paid
for itself once and then thrown it away. The retention lives on the surface, which the
accepted answer already updates.
**(b) THE BUILD-COUNTING UNIT IS BLIND FOR AN EDITED CONFIG.** Every cost pin in this repo
counts reads of `tsconfig.json` at the backing `Vfs`. An OVERLAID file is served from the
overlay and never reaches the backing store — so after `updateFile("/proj/tsconfig.json", …)`
the config's read count stops moving entirely, and a "did this rebuild" pin reads **0 builds
for a build that certainly happened**. That is a general trap for every counted pin whose
test edits the file it counts; the config pin now counts a SOURCE file with its own control.

**ORDER IS PRESERVED, DELIBERATELY.** `diagnostics()` is documented as answering in the
compiler's own order, so the edited files' fresh rows are SPLICED where their old rows were
rather than appended — otherwise every edited file's rows would jump to the bottom of a
project-wide list after an edit. A file that had no rows and now has some appends, which is
the only case with no position to preserve.

**COST, MEASURED ON THE SAME RUN.** The whole 40-case replay is **109,857 ms incremental
against 211,271 ms full (1.92x)** — and that number is DILUTED on purpose: it includes the
13 fallback cases, which pay a narrowed build AND a rebuild. The per-edit figure is the one
from (INC.31)/(INC.37) that this mechanism now delivers on a served edit: **108-113 ms
against 4,864-5,096 ms, a factor of 45.** A signature edit costs two builds instead of one,
which is the price of the gate being wrong and is pinned as such.

**GATES.** Suite **16,464 / 0 / 3** (+11 over the session's 16,453, exactly the new pins).
`cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean; the differential EQUIVALENT
40/40 with `served=27`. Build warning-clean (the round's one `No cast needed.` was fixed
rather than left).

**WHAT IS LEFT, NAMED.** `types.ts` still escapes — an in-file SCC that no budget closes
(measured at 2 M and 12 M nodes), and it accounts for 8 of the 13 fallbacks, so **SCC-aware
hashing is the one lever between the measured 67% and an 87.5% ceiling.** Second: the
fingerprint is armed through a process-global that `ProjectCompiler.build` sets and restores
around its own compile — sound for a single-threaded embedding API and stated in the code,
but the right shape is a threaded parameter, which is a mechanical change through four
layers.


### Round (INC.46)(2) — the STABILITY RATE against a real edit corpus: 67%, and one text scan was worth 35 points of it

**WHAT THIS ANSWERS.** (INC.46) step (2), the one the queue said could still refuse the
whole mechanism: *"sample commits touching `src/compiler` and ask what fraction move no
exported fingerprint. Under ~70% the 45x is diluted to nothing and the round should
refuse."* Step (1) had established the fingerprint is cheap, rebuild-stable and
partition-stable; none of that says how OFTEN a real edit leaves it alone, which is where
all of the value lives.

**THE CORPUS IS REAL AND THE HARNESS MATERIALISES WHOLE TREES.**
`scripts/inc46-stability.sh` fetches its OWN blob-filtered depth-3000 clone of
microsoft/TypeScript under `build/bench` — never `typescript-repo`, which is a depth-1
shallow clone AND a build-pinned input (`typeScriptCommit`) — and takes 40 no-merge
commits touching `src/compiler`, newest-first from the profile's own base commit
`637d5746`, restricted to MODIFIED `.ts` files (a rename or an addition changes the
program's name set, which is a different question). Per case it materialises the FULL
`src/compiler` at the parent and at the commit into a SCRATCH copy of the bench profile,
builds each with fingerprints on, and asks whether any TOUCHED file's fingerprint moved.
Whole trees rather than just the changed files: a file from another era beside a tree from
this one resolves against symbols that may not exist, which degrades its exports to `any`
in a way that is neither the before nor the after. `Inc46StabilityMain`.

**THE FIRST READING WAS 13 STABLE OF 40 — 32% — AND IT WAS AN ARTIFACT OF MY OWN ESCAPE
LOGIC.** **24 of the 27 MOVED cases moved ONLY because a touched file ESCAPED**, and there
were just two escaping files. One of them, `checker.ts`, escaped because
`declaresGlobalSurface` scanned the whole source for `export as namespace` — a construct
with NO AST NODE in this parser — and `checker.ts` says those words **twice, both times
inside a `//` comment**. Since `checker.ts` is the file tsc's own history edits most, that
single false positive was worth **35 percentage points**. Requiring the match to BEGIN ITS
LINE (the construct is a top-level statement) took the rate to:

| arm | stable / 40 | rate | escaping files |
|---|---|---|---|
| bare substring scan | 13 | **32%** | `checker.ts`, `types.ts` |
| **line-anchored scan** | **27** | **67%** | `types.ts` |

**AND THE REMAINING GAP HAS ONE NAMED CAUSE: 8 of the 13 still-MOVED cases moved ONLY
because `types.ts` escapes.** So the achievable band is **67% at the floor and 87.5% at the
ceiling**, the ceiling being loose — a commit touching `types.ts` often really does move a
declaration.

> **RETRACTED 2026-08-29 BY (INC.47), WHICH MEASURED BOTH ARMS.** That sentence is this
> runner's summary line read at face value, and the line was mislabelled: the code behind
> it is `if (escaped)`, which counts every case that TOUCHED an escaping file whether or
> not it also moved on its own. The same run's detail lines contradict it — case
> `009-0208948c` prints four moved files beside `types.ts(escape)`. Re-derived, exactly
> ONE of the 8 had the escape as its only mover, so the ceiling was **70%**, not 87.5%.
> With every escape removed the rate is **67% on both arms and all 40 verdicts
> identical**. The general law, now in CLAUDE.md: a derived attribution PRINTED by an
> instrument must be re-derived from that instrument's own detail before it becomes a
> queue item's threshold.

**`types.ts`'s ESCAPE IS STRUCTURAL AND WAS MEASURED, NOT ASSUMED.** It is a node-budget
stop, and raising the budget does not close it: at **2,000,000** nodes it costs 129.6 ms and
stops, and at **12,000,000** it costs **741 ms and still stops**, having burned the entire
budget. The file-boundary cut cannot help INSIDE a file, and `types.ts` declares tsc's
whole type universe — ~874 mutually recursive interfaces in ONE file — so the closed-subtree
memo has nothing to memoize there for the same reason it had nothing program-wide before the
cut. **The lever is SCC-AWARE hashing** (Tarjan, then hash each strongly-connected component
as a unit), which is real machinery and deliberately not attempted here. The budget stays at
the bounded 2,000,000, and `types.ts` is recorded in `ExportSignatures.whole` — the
conservative direction, which costs a full rebuild and never a stale diagnostic.

**WHAT THIS MEANS FOR THE QUEUE'S THRESHOLD.** 67% is at the ~70% line, not clearly past it —
and the honest reading is that the mechanism is NOT refused, because the one thing standing
between the measured floor and the ceiling is a named, bounded piece of work rather than a
property of real edits. **The measured floor already pays**: 67% of edits answered from a
108-113 ms narrowed build instead of a 4,864-5,096 ms rebuild is a 45x saving on two edits
in three.

**A LESSON WORTH MORE THAN THE NUMBER.** A whole-source substring scan for a construct that
has no AST node is not a test for that construct — it is a test for the WORDS, and a
compiler's own sources talk about compiler constructs constantly. No fixture would have
found this: nobody writes `// export as namespace foo` into a hand-written test. The edit
corpus found it in one run, and it presented as a plausible refusal (32%, well under
threshold) rather than as a defect.

**GATES.** Suite **16,453 / 0 / 3** (+13 over 16,440: the 12 step-(1) pins plus the
comment-mention pin this round's defect earned). `cost_gate.py` exit 0; `huge_methods.py
--fail-over 0` clean. Step (3) — wiring the invalidation into `Project.diagnostics()` —
remains the next item, and it is now the only one left.


### Round (INC.46)(1) — the exported-signature FINGERPRINT: step 1 landed, and the walk's shape had to be found by measurement three times

**WHAT LANDED.** `ExportSignatures` (a census/mode object) plus
`Checker.exportedSignatureFingerprints()` — one `Long` per program file summarising
everything an IMPORTER can observe: the exported NAME SET, each name's meaning flags,
and the STRUCTURE of its resolved type. OFF in the shipped compiler; nothing consults
it yet. `scripts/inc46-fingerprint-cost.sh` + `Inc46FingerprintCostMain` are the
runner; `ExportSignatureFingerprintTest` is 12 pins.

**THE QUEUE'S THRESHOLD IS MET WITH ROOM.** (INC.46) said *"hook the fingerprint cost
on a full build and read it — if it is not single-digit ms on `types.ts`'s 874 exports,
stop."* Measured on tsc's own 78 sources, three ABBA-rotated rotations, one process:
**136 ms whole-program** against a 5,215 ms rebuild (2.6%), and — the number that
actually matters — **0 ms on 23 of 24 narrowed builds, 2 ms on the 24th**, because a
narrowed build fingerprints only its own partition. So the per-EDIT cost of the gate is
under a millisecond against the 108-113 ms narrowed build it rides on.

**BUT THE SHAPE OF THE WALK IS THE WHOLE ROUND, AND IT WAS WRONG TWICE.**

*(a) A PATH-ONLY CYCLE GUARD IS EXPONENTIAL, AND ITS SYMPTOM IS A HANG WITH NO
DIAGNOSIS.* The first walk kept only a path set (the obvious way to break a cycle) and
re-walked a type once per path reaching it. A real program's resolved-type graph is a
dense DAG, not a tree: **159 s inside a single build and still running**, found only by
`jcmd Thread.print` from an EXTERNAL process. Fixed by caching a completed subtree —
but only when it is **CLOSED**, i.e. nothing inside it referred to a type strictly above
it on the path (an open subtree's hash carries a path DISTANCE and is wrong at any other
depth). `minRef` carries that back up; a SELF-reference still counts as closed, which is
what makes an ordinary recursive interface memoizable.

*(b) AND CLOSED-SUBTREE MEMOIZATION IS NOT ENOUGH, BECAUSE tsc's TYPE GRAPH IS ONE
GIANT SCC.* With the memo, the whole program cost 200 ms — and **7 of 78 files did not
finish inside a 400,000-node budget; raising it to 2,000,000 left 6 unfinished**, among
them `checker.ts`, `binder.ts` and `emitter.ts`, i.e. the most-edited files. `Node.parent:
Node` plus hundreds of mutually recursive interfaces put nearly everything in one
strongly-connected component, so the memo has NOTHING to memoize until the component
completes. **The cheap per-file numbers in that run were an artifact of a warm SHARED
memo, not of the files' own size** — the same files measured from a cold memo (one
narrowed build each) cost **115-146 ms** apiece.

*(c) THE FIX IS TO CUT AT THE FILE BOUNDARY, AND IT FOLLOWS FROM WHAT THE GATE ACTUALLY
ASKS.* The fingerprint answers one question: *given every other file is unchanged, did
editing THIS file move what an importer can observe?* A type declared in another file is
then unchanged BY CONSTRUCTION, so it is keyed by its declaration's
`(fileName, pos, end)` — stable across two builds of identical text, id-free — and not
descended into. `Checker.ExportFingerprinter.foreignKey`. What it gives up is
transitivity, which is not wanted: a moved signature anywhere falls back to a
whole-program build, the only answer a dependency closure could give on this program
anyway ((INC.46)'s own measurement: a closure re-checks 100% of characters at the median
edit).

**THE THREE ARMS, SO THE PROGRESSION IS LEGIBLE** (same runner, same profile, one
process each):

| arm | whole-program fp | escapes | partition agreement | fp on a narrowed build |
|---|---|---|---|---|
| path memo, 400 k budget | 200 ms | 7/78 | 20/24 | (all 78 files) |
| + partition-scoped, 2 M budget | 719 ms | 6/78 | **4/24** | 115-146 ms |
| **+ foreign-declaration cut** | **136 ms** | **2/78** | **24/24** | **0 ms (23 of 24)** |

**THE TWO CONTROLS THAT DECIDE FEASIBILITY, AND NEITHER IS A COST FIGURE.**
(i) **STABILITY — 78/78 fingerprints identical across two builds of identical text.**
This is the id-freedom claim under test: `Type.id`/`Symbol.id` are per-build,
per-THREAD sequences (INV.6(6c0)), so a hash carrying one passes every structural test
and then invalidates everything on every edit, which is indistinguishable from the
mechanism not working. (ii) **PARTITION AGREEMENT — a narrowed build's fingerprint for a
file must equal the whole-program build's, or the mechanism can never CONVERGE**: the
baseline comes from a whole-program build and the edit's answer from a narrowed one, so
a systematic disagreement means every first edit falls back, restores the whole-program
baseline, and disagrees again forever. It read **4/24 with the transitive walk and 24/24
with the cut** — i.e. the cut is not only cheaper, it is the thing that makes the
mechanism converge at all, because the deep foreign structure is exactly where (INC.2)'s
capture divergence lives.

**WHAT THE QUEUE CENSUSED WAS THE WRONG QUANTITY.** (INC.46) priced the work as "~3,400
`getTypeOfSymbol` + fingerprint calls" off a census of 3,398 exported declarations
(mean 44/file, max 874 in `types.ts`). Cost does not track export COUNT — it tracks the
transitive type CLOSURE, and the two are close to inversely related: with the cut,
`utilities.ts`'s **692 exports cost 1.6 ms** while `types.ts` — which declares the SCC
and therefore cuts nothing — is **129.6 ms and the round's one budget stop**. Before the
cut the eight dearest files had **1 to 6 exports each**.

**THE ESCAPE SET IS 2 OF 78** — `types.ts` (budget stop) and `checker.ts` (an exported
name with no file-level symbol). Both are recorded in `ExportSignatures.whole`, never
hidden: a file that cannot be fingerprinted exactly must invalidate the whole program,
because an omission is a MISSED invalidation and that is the only direction that costs a
stale diagnostic. `checker.ts`'s reason is undiagnosed and is the first thing the next
round should look at — it is the file an editor edits most.

**STEP (2) IS UNRUN AND SAYS SO.** The stability RATE against a real edit corpus needs a
separate deepened TypeScript clone; `typescript-repo` here is a depth-1 shallow clone
and is a build-pinned input. The 91.6%-of-characters-in-bodies proxy already in the
queue entry is what stands in the meantime, and it is a proxy and not a rate. **Step (3),
wiring the invalidation into `Project.diagnostics()`, is deliberately NOT in this
commit** — the queue's order of work is measure-first and step (2) is the one that can
still refuse the whole thing.

**GATES.** Suite **16,452 / 0 / 3** (+12 over the 16,440 baseline, exactly the new pins).
`cost_gate.py` **exit 0**, largest move **+0.08%** (`globals.lookups`/`globals.misses`,
the profile's standing run-to-run residual) — the expected answer, since the walk is off
by default and is a strict no-op then. `huge_methods.py --fail-over 0` clean.

**A PROCESS TRAP WORTH ONE LINE.** An EMPTY `build/classes/kotlin/jvm/main` DURING a
Kotlin compile is normal — the backend writes its output at the end — and reading it
mid-build manufactures a convincing round-851 "the build was killed and wiped the class
dir" diagnosis. It cost a redundant rebuild and a concurrent second Gradle invocation.
Check `ps` for a live compile before reading an empty class dir as a wipe.


### Round (INC.44) — `referencesAt` is narrowed by SPELLING; the doc claim that it "cannot be" confused the CLAIM with the EVIDENCE

**THE HEADLINE.** `docs/language-service.md` said in three places, over three rounds,
that `referencesAt` and `renameAt` "are NOT narrowed and will not be: their claim is
about every file, so there is nothing to narrow to". The claim really is program-wide.
The **evidence** is not: an occurrence can only be an answer if it SPELLS one of the
names the caret's symbol is reachable by, so the population is selectable before it is
typed. `referencesAt` now selects it, and `captureIn`'s partition — which has always
been DERIVED from the request's own spans — narrows the check with it, using no new
mechanism. On tsc's own 78 compiler sources a search for an ordinary name costs
**510–553 ms against 8.8–11.1 s**, and the worst realistic case (`SyntaxKind`, 9,827
hits in 49 files) still wins at 4,904 ms.

**WHAT ANCHORS THE CLOSURE.** Two forms give one symbol two written spellings —
`import { p as q }` and `export { p as q }` — and both write BOTH names in the file
that declares the alias. So "select the files containing a name I am looking for, read
the aliases they declare, repeat" is a fixed point that never opens a file the search
had no other reason to open. Everything that binds a symbol to a spelling written
nowhere near the other one is REFUSED and falls back to the old sweep: a default
export, a default import's local, `export =`, `import x = require(…)`, a namespace
binding, and any closure reaching the spelling `default`.

**THE ONE THING THAT ALMOST MADE IT UNSOUND, AND IS THE ROUND'S TRANSFERABLE LESSON.**
The obvious file filter — does the file's text contain the name — is not exact.
`StringLiteralNode.text` is the **cooked** value, so `o["pl\ain"]` names the member
`plain` while the file spells `pl\ain`; and `\a` is an IDENTITY escape, so it is not
`\u` that is dangerous but ANY backslash inside a literal. Measured on the profile: 29
of 78 files contain a backslash and they hold **78.2%** of the characters, so the rule
is "skip only a file with no backslash at all", and the exact filter stays
`occurrenceText(node) in names`. The partition is therefore exact either way; only the
indexing cost moves. A pin (`a member spelled by an escape sequence…`) fails against
the plain substring test and against nothing else.

**WHAT THE ABLATION SAID, INCLUDING THE PART THAT DOES NOT FLATTER THE CHANGE.** Four
arms, four distinct red sets. But **a3 — "nothing is an alias escape" — reddens only
the three REFUSAL pins**, and the equivalence assertions above them pass: on every
shape this round fixtures, the narrowed answer would be right without the escape guard
at all. So the guard is CONSERVATISM, not a fix, and the round says so. It is kept
because the gap it anticipates is **measured**: `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`
answers **6** references on a `export { renamed as default }` declaration — both `d`
occurrences in the importing file included — where this API answers **2**. The day that
divergence closes is the day the guard becomes load-bearing, and there is now a pin
whose failure announces it.

**WHAT COST A REPAIR.** The first alias pin was written expecting the specifier's
`propertyName` span and the answer carries the specifier's **LOCAL** name — a search
from the exporting end returns three `localAlias` spans and no `renamed` one. That is
the fact the whole narrowing turns on and it had to be measured rather than assumed.
And two ablation arms were lost to a second `gradlew` starting while the first was
still running (`Unable to delete directory …/classes/kotlin/jvm/main`), which is
CLAUDE.md's one-gradle-per-box rule collected again.

**GATES.** Suite **16,434 / 0 / 3** (+12 from a re-verified 16,422 baseline, exactly the new pins); differential **EQUIVALENT** — 60 carets drawn by stride over all 381,775 occurrences, **59 of them actually narrowed** (the control), **0 diverged**, 12,248 hits compared element for element; mean partition **17.5 of 78 files**, aggregate 182.0 s narrowed against 561.6 s whole-program (**3.09x** on a draw that lands proportional to occurrence count, i.e. on the hottest names); `cost_gate.py` and `huge_methods.py` are CONTROLS here, not gates — nothing in
`-core` was touched — and both are green: `cost_gate.py` exit 0 with `output.errors` **46** and a largest move of **+0.08%**
(`globals.lookups`/`globals.misses` — the profile is unchanged, this is its standing
run-to-run residual), `huge_methods.py --fail-over 0` clean.

### Round (CHK.71) — B83.5 was the WRONG NAME for the blocker, the real one is a **fourth shadow shape** and it LANDS; the receiver half is refused again, on a *different* row

**THE HEADLINE.** (CHK.71) was queued as "blocked on nested-function shadowing (B83.5)".
The blocker reduced to twelve lines, turned out to be a **fourth, uncovered shadow shape**
rather than an unbound declaration, and is **landed on its own** — it is a shipped false
positive with no optional chain anywhere near it. The optional-chain receiver half is
**still refused**, but its price has moved: `moduleNameResolver.ts:706/710` are **gone**,
and what remains is **one knip row** needing a narrowing nobody had named.

**THE FOURTH SHADOW SHAPE.** `currentLocalTypes` is flat and first-decl-wins, and a
function body enters on a COPY of its enclosing scope, so three mechanisms keep a
shadowing declaration from reading the inherited binding: round 351's
`applyBodyLocalShadowing` (a declaration at the nested function's TOP level), round 460's
`applyAmbiguousBlockScopedLocals` (two declarations of one name in ONE body) and round
455's `applyNestedGlobalShadow` (a BLOCK-scoped declaration shadowing a GLOBAL or
file-level binding). The fourth combination — **a BLOCK-scoped declaration inside a NESTED
function shadowing an ENCLOSING FUNCTION's local** — was none of them, and
`registerNestedGlobalShadowName`'s condition says so literally:
`outerBound && !currentLocalTypes.containsKey(nm)`, i.e. it fires only when the name is
*not* already bound, which is exactly the inherited case inverted.

The reduction separates all four in one file, and only the fourth fires:

```
function m1() {            // FIRES on the parent, tsgo silent
  let result = mkO();
  function inner(): Inner | undefined {
    if (flag) { let result: Inner | undefined; result = mkI(); return result; }
    return undefined;
  }
  inner(); return result;
}
function m2() { …nested fn, TOP-level decl… }   // silent — round 351
function m3() { …same fn, plain inner block… }  // silent — round 460
```

**WHY IT IS SAFE TO WRITE, AND WHAT MAKES IT NOT BLANKET SUPPRESSION.** This function runs
BEFORE the body walk on a freshly copied scope, so every entry standing in
`currentLocalTypes` at that moment belongs to an ENCLOSING function — the test needs no
new bookkeeping. An ANNOTATED declaration records **its own annotation** (the flat map's
best approximation, and exactly what the top-level arm does), an un-annotated one records
`anyType`, because a block-scoped inferred type must not be claimed for reads outside the
block. Ablation arms b2/b3 are two spellings of "ignore the annotation" and reddened
exactly the pin that asserts a wrong assignment is still caught against the INNER type.

**THE RECEIVER HALF, RE-PRICED.** Re-derived and re-measured on top of (CHK.72)(a):

  * **the two `moduleNameResolver.ts` rows are gone** — they were the fourth shadow shape,
    and it is now fixed, so `added=0 removed=0` with BOTH halves in place (grid digest
    `790c337141b167657e4f1f3a219474aa`, identical to HEAD);
  * **knip goes 49 -> 50**, a NEW row and an ours-only false positive:
    `compilers/compilers.ts:60:49 TS18047 'match' is possibly 'null'` in
    `return match?.[1] ? [\`… ${match[1]} …\`] : []`. tsc narrows `match` to non-null in the
    true branch of a truthy test on `match?.[1]`; we do not, and the row was invisible only
    because `match?.[1]` used to answer `any`. **An optional-chain condition narrowing its
    RECEIVER is the blocker now — not B83.5, which is closed.**
  * the capture channel gains **236 definitions** in both arms, and exactly **one** of them
    is order-dependent: `resolutionCache.ts @39543..39549` resolves in the FULL arm and is
    ABSENT in the narrowed one, taking that gate's standing `definitions=0` to 1. The
    (INC.2) first-touch family, in a population that did not exist on the parent.

So the receiver half remains **built, measured and NOT landed** — a strictly better price
than last round (two profile rows -> one knip row plus one capture definition), with a
named, reducible blocker. Requeued as (CHK.71)(a).

**ABLATIONS — three arms, one mistake each, each `cmp`-diffed against its own snapshot,
each restore verified by `cmp` plus a rebuilt md5.**

| arm | injected mistake | class | RED | kind of zero |
|---|---|---|---|---|
| b1 | the whole inherited-local shadow reverted | `ff21e8f6` | **3** | — |
| b2 | register `anyType` instead of the declaration's annotation | `80e1a0a3` | **1** | — |
| b3 | stop THREADING the annotation (the decl arm passes null) | `ff73fa00` | **1** | — identical red set to b2; ONE observable in two spellings |

**A PIN WRITTEN AS A CONTROL MEASURED AS A POSITIVE.** `m4` asserts that the ENCLOSING
function's binding still catches its own wrong assignment; b1 reddens it, because on the
parent the FIRST TS2322 in that file is the INNER assignment reported against the OUTER
type (`Type 'Inner' is not assignable to type 'Outer'`). Relabelled as a positive rather
than left claiming control coverage. Two of my first four pin EXPECTATIONS were also wrong
— the message strips nullish (`… to type 'Inner'`, not `'Inner | undefined'`) — and tsgo
7.0.2 prints ours verbatim, which is now recorded in the pin.

**GATES.** Suite **16,422 / 0 / 3** (+5, exactly the new pins; **no corpus baseline
moved**), grid `790c337141b167657e4f1f3a219474aa` with `added=0 removed=0` on all eight,
`cost_gate.py` exit **0** with `output.errors` **46** and no counter over +-2%,
`huge_methods.py --fail-over 0` **783 scanned / 0 over**, partition-equivalence EQUIVALENT
all 78 with floor **65 ms** [55, 81, 65, 58] (one draw), capture-equivalence DIVERGED
**964** in 43 of 76 with `definitions=0 moreAny=0` — the standing state exactly — and knip
**49** / jsonrepair **4**, unchanged.


### Round (CHK.72)(a) — the queue's attribution was wrong a TENTH time: `statSync` is not an overload-resolution gap, the flow walk's call shortcut is, and knip's row is a **default/namespace import typing as `any`**

**THE HEADLINE.** Two independent findings, one landed. (1) `resolveFlowCalleeDecl`
answers ONE declaration and does **no overload selection at all**, so both consumers that
read a RETURN ANNOTATION off it were answering about a signature the call does not select
— a shipped WRONG TYPE and a shipped FALSE NEGATIVE, both reduced to four lines and both
confirmed against tsgo 7.0.2. That is landed. (2) The knip row (CHK.72) was queued for is
**not** about `statSync`'s overloads: `import fs from 'node:fs'` gives `fs` the type
**`any`**, so every member access and call through it is `any`. Re-queued as (CHK.73) with
the measurement.

**THE REDUCTION TOOK SIX MINUTES AND KILLED THE QUEUE'S PREMISE TWICE.** The queue says
`fs.statSync(dir, { throwIfNoEntry: false })` "resolves to `any` for us where tsc gives
`Stats | undefined`", and calls it an overload-resolution / `@types/node` question. Neither
half survived. With the seven `statSync` overloads hand-written, the DIRECT reader
(`const q: number = statSync("x", …)`) answers `Stats | undefined` — the overload IS
resolved, correctly, and `getReturnTypeOfCallExpression` had it right the whole time. What
answered wrongly was a local whose type is INFERRED from the call. And inside knip's own
project, three import spellings of the SAME function disagree:

| spelling | our answer |
|---|---|
| `import { statSync } from 'node:fs'` | **`Stats \| undefined`** — correct, whole overload set present |
| `import fs from 'node:fs'; fs.statSync(…)` | **`any`** |
| `import * as fs from 'node:fs'; fs.statSync(…)` | **`any`** |

`fs` ITSELF is `any` (probed directly: `const c = fs; const q: number = c` is silent), so
this is not a member-lookup gap but the binding's type. `path.join(…)` and
`fs.readFileSync(…)` are `any` for the same reason. knip's `glob-cache.ts:62` needs
`stat?.isDirectory() ? stat.mtimeMs : Number.NaN` to type as `number`, which needs `stat`,
which needs `fs.statSync` — so **no amount of narrowing or overload work closes that row**,
and (CHK.70)(f)'s refusal of an `any` ternary is correct as written.

**WHAT LANDED — (CHK.72)(a), the flow walk's call shortcut.** `resolveFlowCalleeDecl`
answers `symbol.valueDeclaration ?: declarations.firstOrNull()`. For an overload set that
is the FIRST signature, and its two return-annotation consumers then answer about it:

  * `resolvedCallReturnTypeForFlow` (the post-overwrite reset) installed the **wrong
    overload's return** — `const c = f("x")` where the string overload returns
    `Stats | undefined` and the number one returns `Other | undefined` read
    `Other | undefined` at every later use. A wrong type, not a lost narrow.
  * `callRhsHasNonNullishReturnAnnotation` (behind `rhsIsDefinitelyNonNullish`) claimed
    non-nullish off the first signature, so the caller took the OVERWRITE branch and
    **stripped a `| undefined` the selected overload genuinely has** — silent at every
    later read.

Both now route through `getReturnTypeOfCallExpression`, i.e. the engine's own overload
resolution, gated behind a BODYLESS resolved declaration (every overload signature is one,
an ordinary implementation is not) so the common case asks nothing. It is universal, not a
`declare function` curiosity: an implementation-bearing overload set, an interface method
pair and a `declare namespace` member all read first-wins on the parent, and **arity alone
did not discriminate** (`ff("x","y")` picked the 1-parameter overload).

**THE "CONSERVATIVE" HALF WAS NOT FREE, AND ONLY THE GRID SAID SO.** The first version had
`callRhsHasNonNullishReturnAnnotation` simply `return false` for an overload set — refusing
a claim it could not justify, which reads as strictly safe. It cost **one ours-only TS2322
on every profile** (`esDecorators.ts:1309`, `output.errors` 46 -> 47, the cost gate's only
red): `factory.getGeneratedNameForNode` is **two overloads that BOTH return `Identifier`**,
and declining it lost round 465's destructured-member non-nullish proof for
`visitReferencedPropertyName`. **Conservatism is not free when the claim is what SUPPRESSES
a diagnostic** — answering from the SELECTED overload instead restores it. The suite was
green on the refused version; the grid was not.

**ABLATIONS — three arms, one mistake each, each `cmp`-diffed against its own snapshot and
each restore verified by `cmp` plus a rebuilt md5.**

| arm | injected mistake | class | RED | kind of zero |
|---|---|---|---|---|
| a1 | both halves reverted | `be6aedf7` | **3** | — (p1, p2, p3) |
| a2 | only the RESET half reverted | `5407d8db` | **4** | — p1/p2/p3 **plus the c3 CONTROL** |
| a3 | only the NON-NULLISH half reverted | `afbdafe8` | **2** | — (p2, p3; p1 stays green) |

a2 is the round-927 pair reading and is recorded as **ONE observable**: the non-nullish
refusal is *unsound without* the precise reset — with the reset gone, `c3` (an overload set
whose SELECTED overload is non-nullish) stops narrowing. a3 gives each half a uniquely-its-
own failure, so neither is a redundant guard.

**VACUITY, PER PIN.** Every positive names the DECLARATION reader with a PRIMITIVE target,
which is the instrument that PRINTS the flow type in its TS2322 message — the pins assert
the **source type string**, never the presence of a row, so a pin cannot pass by a
different mechanism emitting the same code. The subject must be a local whose type is
INFERRED from the call: a directly-read call is GREEN on both binaries (`c2`, recorded as a
CONTROL) because that path was always right, and a fixture written that way would have been
vacuous. `c3` is the control that stops "select the overload" from degenerating into
"refuse to narrow" — it is the one a2 turns red.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * I spent two builds instrumenting before finding the site, and the FIRST probe was the
    decisive one in the wrong direction: `cvdaRecordInferredLocalType` records
    `Stats | undefined` **identically** for the overloaded and the non-overloaded callee.
    The recording was never the problem; the flow READ was. A probe that prints the value
    at the site you suspect is worth more than any amount of reading — and the value it
    printed said "look elsewhere".
  * The RETURN reader was correct while the DECLARATION reader was wrong on the same local
    in the same function, which is what localised it to the flow shortcut rather than to
    overload resolution.
  * `narrowRendersMoreAny=0`, `definitions=0` and `DIVERGED 964 in 43 of 76` are all
    unchanged, but **both capture ARM DIGESTS moved** (`full=3208853970728874912
    narrow=-1697007308088931828`). That is expected for a change that alters a type in
    BOTH arms and is re-recorded, not read as a regression.

**GATES.** Suite **16,417 / 0 / 3** (16,411 + exactly the 6 new pins; **no corpus baseline
moved**), grid `790c337141b167657e4f1f3a219474aa` — the standing fifth-lineage digest —
`added=0 removed=0` on all eight against `chk70_gatefinal`, `cost_gate.py` exit **0** with
`output.errors` **46** (largest move `narrow.memoServed` +1.55%, `typeOfExpr.calls` +0.54%
— the overload asks, both inside +-2%), `huge_methods.py --fail-over 0` **783 classes
scanned, 0 over**, partition-equivalence EQUIVALENT all 78 with floor **67 ms**
[103, 67, 60, 57] (one draw), capture-equivalence DIVERGED **964** in 43 of 76 with
`definitions=0 moreAny=0`, knip **49** and jsonrepair **4** — both unchanged, i.e. this
lands with no library cost and does not close knip's row.


### Round (CHK.70) + (CHK.63) — **THE GATE IS OPEN**, the last row was *not* the loop, and both remaining costs are pre-existing gaps the gate merely makes visible

**THE HEADLINE.** `canUseTypeEngine`'s nullish-union-versus-primitive refusal is gone and
the 8-profile grid is `added=0 removed=0` — the first time in the (CHK.61)-(CHK.69) arc.
Three commits: `2ed1779b` (CHK.70)(a), `acb6d92b` (CHK.70)(c), `7a488783` the gate.

**THE QUEUE'S DECOMPOSITION WAS WRONG A NINTH TIME, AND THE MEASUREMENT SAID SO IN ONE
BUILD.** (CHK.70)(a) is real and landed — a loop whose back edges only COMPOUND-assign the
reference has a fixpoint bounded by `entry union nonNullish(declaredType)`, which is a
function of the DECLARATION and so costs no back-edge traversal — and it did **NOT** move
`harness/tsserverLogger.ts:28:5`. Rebuilding the combined arm on top of it read `added=1`
on `tsc-harness`, byte-identical to the arm without it. The row was **(CHK.70)(c)**: the
LITERAL arm of `narrowByAssignmentRhs` is the one arm (CHK.63)(a) did not route through
`assignmentReduceBase`, so `let r: string | undefined = undefined; r = ""` answered
`undefined` — a literal cannot restore a member the ANTECEDENT has already lost, and an
assignment OVERWRITES. Both halves are shipped FALSE POSITIVES in their own right, five and
four, every one confirmed silent under tsgo 7.0.2 through round 784's UNION-target return
reader, and both are 8-profile-grid-identical on their own.

**(CHK.70)(a)'s RULE IS THE *ORDER-FREE* ONE, AND THAT WAS A CORRECTION MADE BY AN
ABLATION.** The first version stopped the scan at the first compound assignment — sound for
that path, since it overwrites. Arm a2 (return COMPOUND at the first sighting) read **0
RED**, and the reason was that the c2 control's two `if` arms happened to be written in the
order that makes the stack meet the PLAIN assignment first: with the arms swapped the arm
is silent where tsgo reports. Fixing the fixture would have pinned an order-dependent rule;
the rule was changed instead to "EVERY assignment to the name reachable backward from a back
edge is a non-nullish compound one", which also keeps us on tsc's side of a second
difference (tsc's compound arm takes the ANTECEDENT's base type, so
`while (…) { r = maybeUndefined(); r += s }` is `string | undefined` there and a string at
run time). Two controls, `c2b` and `c2d`, exist for exactly that and are what arm a2 turns
green.

**THE GATE NEEDED THREE MORE FIXES AND THE SUITE FOUND ALL THREE — THE DASHBOARD FOUND
NONE.** With E1-E4 applied the grid was already `added=0 removed=0`, and the suite had
**seven** failures:

  * `functionReturn.ts` — a corpus baseline LOST a row. The return reader's flow answer at
    an UNREACHABLE node is `never`, which relates to everything, and its substitution is
    suppression-only: `return ''; return undefined;` suppressed itself. Pristine tsc
    reports it. The fix is (CHK.69)'s own `never` refusal, one reader over.
  * `WeakCallableSourceAnchorTest` x2 — TS2559/TS2560 lost at `o.weakMember = …`, because
    an optional member's ASSIGNMENT TARGET now carries `| undefined` and the weak rule
    wants the object half. tsc reaches it by DISTRIBUTING the relation over the union, and
    a nullish constituent can never accept a weak source, so the target strips nullish.
  * `EarlyExitNarrowsTheRestOfTheBlockTest` x2 and `CtaFnBodyAnchorTest` x1 — pins that
    recorded OUR OLD answer. The two RESIDUES are rows tsc is silent on and the
    `CtaFnBody` `n == 0` was a FALSE NEGATIVE tsc reports at `(3,11)`; all three
    re-confirmed against tsgo and INVERTED rather than deleted, because they are now the
    only thing that would notice the gate closing again.
  * `FlowNodeCensusTest` — an instrument pin, not a behaviour one. Under this gate an
    entirely UNREAD container is much harder to write (the declaration, assignment and
    return readers all consult the flow walk for a primitive target now), so its
    `untouched` function is rewritten to hold no assignment to a local, no `return` of a
    reference and no initialisation from one.

**AND A FOURTH, FOUND BY knip.** `narrowByAssignmentRhs` has resolving arms for a bare
Identifier and for a PropertyAccess and had none for a ConditionalExpression, and no
STRUCTURAL test can stand in — a ternary's arms are member reads and a member may be
optional — while `getTypeOfExpression` answers the ternary EXACTLY (measured: the same
`number` tsc gives, including through a `?.` condition). (CHK.70)(f).

**BOTH REMAINING COSTS, NAMED.**

  * **knip 48 -> 49** (jsonrepair 4, unchanged, byte-identical). The row is
    `util/glob-cache.ts:62:3`, and it was reduced INSIDE knip's own project with a probe
    file: `fs.statSync(dir, { throwIfNoEntry: false })` resolves to `any` for us where tsc
    gives `Stats | undefined`, so the ternary defaulting `mtime` is `any` and (f)'s arm
    correctly refuses it (accepting `any` would not match tsc either — tsc is silent only
    because IT resolves the call). A TYPE-RESOLUTION gap, queued as (CHK.72).
  * **The capture channel loses 611 of 742,265 spans (0.08%) from a real type to `any`**,
    against **451** that correctly GAIN the `| undefined` an optional member has, 63 that
    improve from `any`, 68 other, 114 absent and 26 new. `x?.y` over a `T | undefined`
    receiver has ALWAYS answered `any` here — measured on the parent with a plainly
    declared `number[] | undefined`, no optional member involved — so the gate only
    enlarges the population. **The receiver half was BUILT and MEASURED and deliberately
    NOT landed**: it restores all 611 and turns 8 measured false negatives into true
    positives, and it costs **2 ours-only rows per profile** at
    `moduleNameResolver.ts:706/710`. Those two are B83.5: a nested function's own
    `let result: Resolved | undefined` resolves to the ENCLOSING function's `result`
    (inferred `ResolvedTypeReferenceDirectiveWithFailedLookupLocations | undefined`), which
    was invisible only because the outer initializer is itself an optional chain and
    answered `any`. (CHK.71).

**ABLATIONS — eleven arms, one mistake each, each `cmp`-diffed against its own snapshot,
each restore verified by `cmp` PLUS a rebuilt md5.**

| arm | injected mistake | class | RED | kind of zero |
|---|---|---|---|---|
| a1 | the whole of (CHK.70)(a) reverted | `dcaf1594` | **5** | — |
| a2 | the scan stops at the first compound assignment | `1bf146a5` | **0** | BLIND FIXTURE — the arm IS reached, with the `if` arms swapped; the rule and the controls were changed |
| c1 | (CHK.70)(c) reverted | `191927d4` | **4** | — (disjoint from a1's set) |
| c2 | (CHK.63)(a)'s nullish-only bound dropped | `9aefa0ba` | **0** | UNDISCRIMINATED — full suite green AND grid byte-identical, recorded not claimed |
| g0 | the whole gate reverted | `855d0eab` | **4** | — |
| g1 | `canUseTypeEngine`'s nullish gate restored | `ea1a2535` | **5** | — |
| g2 | the RETURN reader's flow admission removed | `f3059cb7` | **1** | — (a pre-existing switch-clause pin) |
| g3 | the ASSIGNMENT reader's flow admission removed | `d726ede8` | **2** | — (exactly the two inverted residues) |
| g4 | an optional member loses its `| undefined` | `7e2b7d1f` | **1** | — |
| g5 | the `never` refusal dropped | `557b6990` | **1** | — |
| g6 | the weak target keeps the `| undefined` | `5e0dcea6` | **3** | — |
| g7 | the CONDITIONAL right-hand-side arm removed | `61f84d6b` | **1** | after de-vacuuming; **0** as first written |

**g7's FIRST ZERO WAS A VACUOUS PIN AND NOTHING ELSE SAID SO.** The (f) pin's ternary had a
LITERAL false arm (`: 0`), which some other mechanism already reaches, so it passed against
a binary with the arm deleted. Written with a bare `number` identifier it goes red. Round
902's law, and the only instrument was the ablation reading zero.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **(CHK.70)(a) alone buys nothing on the dashboard**, and its whole visible value is
    five hand-written false positives. It was still worth landing (it is the loop half of
    the same defect class) but the queue's claim that it was "the single remaining row" was
    wrong.
  * **Four of my first pin fixtures were vacuous or wrong-reader** — a `.length` and an
    argument-position probe over the same loop shape read identically on both binaries, and
    the ARGUMENT reader's gate pin turned out GREEN on the parent (it never went through
    `canUseTypeEngine` at all) and is recorded as a CONTROL rather than as coverage.
  * **The optional-chain receiver fix looked free and was not.** It is a strict improvement
    on every instrument except the dashboard, where it adds two rows — and those two rows
    are a DIFFERENT defect (B83.5) that it merely unmasks. Refusing it is the brief's rule
    applied to my own work.
  * **I re-committed the round-(CHK.62b) PLAN-PHASE-5.md trim mistake**, keyed on "the next
    `###` heading", and deleted the whole queue (452 KB -> 102 KB). Caught by grepping for
    the queue immediately afterwards and recovered by `git checkout --`; the note that
    warns about it is four rounds up in this same file.

**GATES, PER COMMIT.** `2ed1779b`: suite 16,391/0/3, grid `790c3371…` `added=0 removed=0`,
cost_gate exit 0 (`globals.*` +0.02%, everything else +0.00%), huge_methods 783/0.
`acb6d92b`: suite 16,398/0/3, same grid digest, cost_gate exit 0 with NO counter moved at
all, huge_methods 783/0. `7a488783`: suite **16,411 / 0 / 3**, same grid digest,
`added=0 removed=0` on all eight, cost_gate REBASELINED (`narrow.walks` +11.17%,
`narrow.memoServed` +6.61%, `globals.*` +1.0%, everything else <= 0.3%, `output.errors` 46,
cold self-compile 26,660 ms against the parent's 26.4-26.9 s band — one draw each),
huge_methods 783/0, partition-equivalence EQUIVALENT all 78 with floor **62 ms**
[62, 60, 52, 73] (one draw), capture-equivalence DIVERGED **964** in 43 of 76 (from 967),
`definitions=0 moreAny=0`, both arm digests re-recorded
(`full=6075298610392249308 narrow=-9054794969403683490`). **No corpus baseline moved in any
of the three** — the suite delta is exactly the pins added.

### Round (CHK.69) — the loop join's ~20x is **MEMOIZATION BEING SWITCHED OFF**, a SOUND cut-keyed memo recovers **0.003%**, and the whole prize turns out to need **no back edge at all**

**THE HEADLINE, AND IT IS A REFUSAL THAT PAID FOR ITSELF.** (CHK.66)(b)'s back-edge
union was reproduced digit-for-digit this session (`globals.lookups` 759,945 ->
**15,128,215**, `output.errors` 47), then ATTRIBUTED, then refused — and the attribution
handed over a change that delivers the same rows for nothing.

**THE PRIZE, MEASURED BEFORE ANY FIX (the brief's first law).** One arm, `M1`, deletes the
`narrowLoopCutUsed` term from the memo-store gate — unsound, and therefore exactly the
CEILING of what any memoization scheme can return:

| counter | parent | loop join | M1 (memo restored) | M1 vs loop join |
|---|---|---|---|---|
| `globals.lookups` | 771,681 | 15,128,215 | 1,630,952 | **-89.2%** |
| `typeNode.cacheable` | 179,886 | 10,831,464 | 885,424 | **-91.8%** |
| `typeOfExpr.calls` | 595,665 | 1,269,016 | 782,936 | -38.3% |
| cold wall | 26,669 ms | 91,677 ms | 44,123 ms | -51.9% |

So **~90% of the blowup is the suppression** — the loop body's paths are ENUMERATED
instead of folded, because nothing computed under the cut may be stored and the flag
propagates to the walk root. The (CHK.68) hypothesis is confirmed as to mechanism.

**AND THE SOUND REPAIR OF IT IS MEASURED AT ZERO.** Arm `M2` gives `NarrowFlowMemo` a
`cuts: LongArray` — a rolling hash of the in-progress label set as an extra equality field
on every entry, so a cut answer is served only back to a walk standing in the SAME cut. It
reads **15,127,750** against the loop join's 15,128,215: **0.003%**. The reason is
structural and is the round's most transferable finding — **the cycle almost never closes
ON the loop label; it closes on the walk's OWN PREFIX**, which happens whenever the query
sits inside or after the loop, and that answer is a function of the PATH, not of the cut.
**Even the unsound ceiling is +115% globals lookups, +395% type-node resolutions and
44.1 s against 26.7 s cold, so the direction is refused in its BEST case.**

**WHAT LANDS INSTEAD (`92598fb0`), AND IT IS THE LOOP JOIN'S OWN KDoc ARGUMENT USED
FORWARD.** A loop label's value is the least fixpoint `L = E union (union of narrow_i(L))`.
When no back edge ASSIGNS the reference every back edge is a pure NARROWING of `L`, so
iterating from `E` never grows past `E` and **the fixpoint IS `E`** — the label can be
answered by FOLLOWING ITS ENTRY, with no back-edge traversal, no cut and no memo
suppression. `loopBodyMayAffectName` decides that by pure graph reachability (it resolves
no type and asks the binder nothing) and answers TRUE — today's conservative
`declaredType` — on anything it cannot rule out. **`output.errors` 46, wall 26.9 s against
26.7 s, counters +0.3-0.4 pp over the standing residual.**

**AND THE BINDER HALF, WHICH IS A SHIPPED FALSE NEGATIVE NOBODY WAS LOOKING FOR.**
`bindForInStatement` / `bindForOfStatement` joined the **PRE-loop flow** to the post-loop
label instead of the **LOOP LABEL**; tsc's `bindForInOrForOfStatement` sets
`currentFlow = preLoopLabel` BEFORE `addAntecedent(postLoopLabel, currentFlow)`. So a
`for-in`/`for-of` body was unreachable BACKWARD from any read after the loop and
`for (const n of xs) { h.req = 1 }` did not invalidate a narrow established before it.
`while` / `do` / `for(;;)` never had it — they exit through their condition, which carries
the label. It also blinded the new back-edge scan whenever such a loop sat inside another
one, which is why the two halves are ONE commit: each alone regresses the other's shape
(measured — `while (cond()) { for (const n of xs) { h.req = 1 } }` is a lost diagnostic
with only the checker half).

**GROUND TRUTH, 14 HAND-WRITTEN SHAPES AGAINST tsc 7.0.2.** The parent has **5 shipped
FALSE POSITIVES** (a narrow lost across a loop that cannot touch it: a `while` read inside
and after, a `for-of`, a `do`/`while`, and a loop assigning a DIFFERENT member of the same
object) and **2 shipped FALSE NEGATIVES** (the `for-of` exit). The shipped binary
reproduces tsc **EXACTLY** on all 14.

**THE GATE ((CHK.63)) IS RE-PRICED AND IT IS NOW *ONE ROW ON ONE PROFILE*.** The combined
arm — gate + RETURN/ASSIGNMENT readers + (CHK.61)(b)'s checking half + (CHK.67) + this
round — measures `added=0 removed=0` on **seven** profiles and `added=1` on `tsc-harness`:
`harness/tsserverLogger.ts:28:5`. **(CHK.66)(b)'s own residue `checker.ts:43282:21` is
GONE** — the capture dump shows that site (`getSignaturesOfSymbol`'s `decl`/`previous`
loop) going from `any` to a real type with four go-to-definition rows GAINED. And the gate
is now AFFORDABLE: `narrow.walks` **+11.2%**, `narrow.memoServed` +6.6%, everything else
<= 1%, wall flat. **It is still NOT opened** — 1 ours-only row on a dashboard whose v1 exit
is zero, with a named and tractable cause, is a decision to take at 0 rows.

**GATES, PER COMMIT (`92598fb0`).** Suite **16,367** / 0 / 3 (+11, exactly the new pins; **16,380** after the rebase onto the (LIB.4) arc, which does not touch the checker — `Checker.class dcaf1594` either side)
and **NO corpus baseline moved**. Grid `790c337141b167657e4f1f3a219474aa`,
`added=0 removed=0` on all eight against a parent capture taken THIS SESSION from a
rebuilt parent (`Checker.class b2675304`) — the digest is IDENTICAL to the parent's. NOTE
the recipe: this round's `build/chk69/cap.sh` concatenates the eight per-profile sorted
files in glob order, which is a FIFTH lineage and is not comparable to (CHK.68)'s
`503774c2…`; the row counts (46/95/46x6) are identical. `cost_gate` REBASELINED — the
round adds +0.43 pp on `globals.misses`/`globals.lookups` and +0.34 pp on
`typeNode.cacheable` over the standing residual, which pushed `globals.misses` to +2.20%;
**the rebaseline also absorbs the residual accumulated before this round, so the next
round's gate is exact against `dcaf1594`**. `huge_methods` exit 0, **783** classes.
`partition-equivalence` EQUIVALENT all 78, floor **63 ms** [63, 60, 71, 62] (one draw).
`capture-equivalence` DIVERGED **967** in 43 of 76 (from 968), `definitions=0 moreAny=0`;
**both arm digests move and are re-recorded** (`full=2052686027637998102`
`narrow=-2628066049853121726`). knip **48** and jsonrepair **4**, byte-identical to arms
taken from a parent class dir this session.

**THE CAPTURE DIGEST MOVE, CLASSIFIED PER ELEMENT** (`XTSC_CAPEQ_DUMP` on both binaries).
**168 of 742,255 spans change (0.023%), 0 LOST and 11 GAINED.** By direction: **66 are
`any` -> a real type** (a member on a receiver that had washed at a loop), **29 are
`X | undefined` -> `X`**, 63 are reformulations, 4 are narrower and **6 are WIDER** — the
last group being the post-loop invalidation the binder half restores, one of which
additionally loses an alias name to the known first-wins (INC.29) family. The 11 gained
are go-to-definition rows that now resolve (`forEach` on a narrowed receiver reaching
`lib.es5.d.ts`). The six wideners were NOT individually verified against tsc.

**FOUR ABLATION ARMS, ONE MISTAKE EACH, EACH `cmp`-DIFFED AGAINST ITS OWN SNAPSHOT,
RESTORE VERIFIED BY `cmp` PLUS A REBUILT md5 (`dcaf1594`).**

| arm | injected mistake | class | RED | kind of zero |
|---|---|---|---|---|
| a1 | the soundness gate deleted (the loop is ALWAYS answered by its entry) | `fcdb566c` | **9** | — |
| a2 | the `for-in`/`for-of` exit goes AROUND the label again | `dcaf1594` (Flow.kt) | **4** | — |
| a3 | the `never` refusal dropped | `9cd7d51a` | **0** | UNPINNED, but MEASURED — see below |
| a4 | the whole arm dead (every label answers `declaredType`) | `e017e684` | **5** | — |

a1 and a2 have DISTINCT red sets (a1 additionally reddens the two loop-assign controls and
all four verifier controls), so they are two observables, not a round-927 pair. a4 is the
arm-is-live control and reddens exactly the five "survives" positives.

**a3's ZERO IS AN UNPINNED GUARD AND ITS INSTRUMENT IS THE GRID, NOT A PIN.** Run over the
compiler profile the a3 binary adds **exactly the five `emitter.ts` `never` rows**
(`4472:38`, `4473:40`, `4487:60`, `4488:39`, `4488:77`), so the refusal is load-bearing and
measured — it just has no fixture. Four attempts to reduce it (a negated GENERIC
type-guard call on an object union and on a primitive union, identifier and property-path
subjects) produced **identical output on both arms** and all four are a SEPARATE shipped
divergence: a negated generic type guard does not narrow at all here, where tsc is silent.
Reported as an unpinned-but-measured observable, not as coverage.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The cut-keyed memo (M2) is a complete, sound design that buys nothing.** It is kept
    only as a measurement (`build/chk69/m2.py`). Writing it was the only way to learn WHY
    the suppression cannot be lifted — the cycle closes on the prefix, not on the label.
  * **My first `a2` was a DEAD ARM that read RED=0 and looked like a redundant guard.** The
    patch inserted the pre-(CHK.69) `joinAntecedent(postLoop, currentFlow)` without
    REMOVING the new one, so `postLoop` got both antecedents and the label was still
    reachable. **The `cmp`-against-its-own-snapshot check PASSED** (the file really did
    differ), which is round 855/922's law shown to be necessary and not sufficient: a diff
    proves the edit landed, never that it removes the thing under test.
  * **My first pin family was VACUOUS in both directions and the controls hid it.** An
    IDENTIFIER subject (`if (typeof x === "string")` then a loop) is answered from
    `currentLocalTypes`, which is statement-ordered and loop-BLIND, so the positives passed
    on the parent AND the controls passed on a binary that had no gate at all — `x = 1`
    inside a loop does not invalidate the pre-loop narrow on either arm (a separate shipped
    false negative, queued as (CHK.70)). Only a PROPERTY PATH reaches the flow walk.
  * **The two verifier CONTROL classes went red for the right reason.**
    `LoopEntryRetryGateTest` and `UnionRetrySubstitutionTest` assert that the plain walk
    DOES diverge from the FollowLoopEntry mirror over the loop-crossing population; since
    this round the plain walk agrees with the mirror on those shapes, so `typeDiff` read
    ZERO. Both classes get a `loopCrossing` fixture whose loops ASSIGN the guarded
    reference, which still washes. **A control that goes vacuous because the compiler
    improved is indistinguishable from a control that was always dead** — the tell was
    that only the CONTROLS moved while every functional pin in those classes stayed green.

### Round (LIB.4) — `cronstrue` **COMPILES TO JVM BYTECODE**; the queue's five rungs were half the ladder, and four of the five defects found were SILENT wrong answers

**The deliverable.** `cronstrue`'s English entry point — 11 files of published source,
unmodified — reads `successful=true` through `compileTypeScriptProjectToJvm`, with the checker
at **0 errors agreeing with tsgo 7.0.2 exactly**. It then fails at RUN time on the
nominal/structural boundary, twice for one reason ((LIB.6)). Thirteen capabilities landed as
corpus 17-29, in six commits, each gated on a green full suite (16,339 / 0 at the last).

**The method, and the one thing it corrected about the queue.** The queue named five rungs; the
ladder is thirteen. Its list was short because the earlier session peeled it *by patching a
throwaway copy*, which walks past whatever the patch removed. Re-probing the UNMODIFIED library
after each fix — `LibraryProbe`, read the one refusal, close it, re-probe — found the other
eight. **Order matters too: the refusals arrive in the lowering's own file order, not the
queue's**, so rung 2 arrived tenth.

**Every `.expected` in 17-29 is `node`'s own stdout** (it runs a `.ts` directly), and each
program is written so the INTUITIVE implementation fails it: `[10, 9].sort()` is `10,9`,
`new Date(99, 0, 1)` is 1999, `substr`'s second argument is a length, `every` is true for an
empty array. Program 23 is the single exception — node's stripper will not parse `<T>expr`, so
its oracle is the `as`-spelled twin, which IS the claim under test.

**The five defects, four of them silent.** None was a missing capability:

1. **`for (let j …)` had no per-iteration binding** — every closure the loop made shared one
   variable (`3,3,3` where JavaScript says `0,1,2`). Found ONLY because corpus 18 runs the `var`
   and `let` spellings side by side: the `var` answer is correct and the `let` answer is not, and
   neither alone shows it.
2. **`toFixed` used the machine's LOCALE** — `"2,0"` on this box. Invisible on en-US, so CI could
   never catch it, and it appeared on a plain `number` receiver, so it predates this arc.
3. **The array callbacks were typed `Function1`**, truncating JavaScript's
   `(element, index, array)`. Before the arc `map((v, i) => …)` was refused; with the new arity
   adapter it would have begun dropping the index SILENTLY — the same defect with no diagnostic.
4. **This arc's own `var` hoisting emitted into the wrong body** — `blockBodyOf` is the funnel for
   SYNTHESIZED bodies too, so `var days = { … }` put its hoisted declaration into the constructor
   of the shape class its own initializer had just built. Only the IR validator saw it; corpus 18
   now carries the shape.
5. **A checker FP, still open ((CHK.69))**: an assignment before a `var`'s declaration does not
   count toward definite assignment. The mirror is silent, so it is that direction specifically.

**What is left is one architectural milestone, not a queue.** (LIB.6), with a cheaper candidate
named and priced against `docs/kir-structural-typing.md`'s measured 158-edge closure.


### Round (CHK.68) — `x = y = z` was a **SHIPPED** false positive and it LANDS; the gate re-prices **6 rows -> 5**, the COMBINED arm is **exactly 1 row** — and the loop join it needs is a **~20x cost blowup nobody had priced**

**THE HEADLINE, AND IT IS A REFUSAL WITH A NEW REASON.** `armBGR` was re-measured on top
of (CHK.66)(a) — the round's assigned first move — and is **UNCHANGED at 6 rows**: the
subtype reduction closes none of them. (CHK.67) was then diagnosed, and the queue's
description of it was half wrong in the useful direction: of its two named shapes, the
`index = index! + 1` BinaryExpression RHS was **already handled** by the (CHK.33)
computed-primitive arm, and the CHAINED assignment is the whole gap. It landed
(`2cbb3847`) and the gate re-prices **6 -> 5**. The COMBINED arm — gate + RETURN/ASSIGNMENT
readers + (CHK.61)(b)'s checking half + (CHK.67) + the loop join — then measures
**`added=1 removed=0` on all eight profiles**, the single row being (CHK.66)(b)'s known
residue `checker.ts:43282:21`.

**SO THE GATE IS ONE ROW AWAY IN DIAGNOSTICS AND NOWHERE NEAR IT IN COST.** The loop join
was priced in ROWS for three consecutive rounds (8 -> 3 -> 1) and never once in COUNTERS.
Measured this round, **alone**, on the compiler profile:

| counter | baseline | loop join alone | delta |
|---|---|---|---|
| `globals.lookups` | 759,945 | 15,128,215 | **+1,891%** |
| `globals.misses` | 742,400 | 15,111,247 | **+1,935%** |
| `typeNode.cacheable` | 178,997 | 10,831,464 | **+5,951%** |
| `typeNode.cacheHits` | 119,618 | 10,772,084 | **+8,905%** |
| `typeOfExpr.calls` | 587,332 | 1,269,016 | **+116%** |
| `narrow.memoServed` | 43,133 | 593,709 | **+1,276%** |
| `narrow.walks` | 32,154 | 44,048 | **+37%** |

`spine.nodes` and `typeOfExpr.distinct` are FLAT (+0.00% / +0.98%) against `calls` +116%,
so the population is unchanged and **the same questions are simply re-asked ~20x**; 99.45%
of the 10.8 M type-node resolutions are cache HITS and 99.9% of the 15.1 M globals lookups
are MISSES, i.e. the cost is the ASKING, not the answering. On the wall it is ~3.5x: the
8-profile grid went from ~25 s to ~90 s per profile and the harness killed it at 6 of 8.
The mechanism is the shape of the arm — a loop label now walks EVERY antecedent including
the back edges, and nothing computed under `narrowLoopCut` may be memoized
(`narrowLoopCutUsed`), so each loop body is re-walked per query. **Stated as a hypothesis
supported by the counters, not as a measured attribution** — no probe was built for it.

**THE GATE IS THEREFORE REFUSED AGAIN, ON A REASON NO EARLIER ROUND HAD.** Its dependency
is not one diagnostic away from clean; it needs a cost redesign. (CHK.66)(b) is re-queued
with the counters attached and the direction named (memoize under the cut, keyed by the
in-progress label set, or hoist the per-antecedent resolutions).

**THE FIVE SURVIVING `armBGR` ROWS WERE READ INDIVIDUALLY AND ARE ALL ONE MECHANISM** — a
narrow established OUTSIDE a loop, lost inside or after it. `moduleNameResolver.ts:824`
(`if (host.directoryExists && host.getDirectories)` outside, the call inside the `for`),
`moduleNameResolver.ts:2265` (guard outside, use inside `for (const conditions of …)`),
`server/project.ts:502` and `:528` (`Debug.assertIsDefined(host.require)` outside, the
call inside the loop), `harness/tsserverLogger.ts:28` (a `while (true)` join re-adding
`undefined` before the `return`). The loop join removes all five, which is why the combined
arm nets to +1.

**(CHK.67), MEASURED AND LANDED.** A six-shape census against tsc 7.0.2 (`build/chk68/f2`,
graded by a deliberate mis-assignment — the only instrument that PRINTS the flow type)
isolated it: `x = a + b`, `x = y` and `x = o.p` all already matched tsc, in and out of a
loop; only `x = y = z` did not, in BOTH regimes. Every arm of `narrowByAssignmentRhs`
classifies the right-hand side SYNTACTICALLY, and `y = z` matches none of them — it is a
`BinaryExpression` whose operator IS `=`, which the (CHK.33) arm excludes by construction.
Reachable with NO gate and NO loop at the UNION-target declaration reader:
`let i: number|undefined; i = c = o.len; const p: number|string = i` is ours-only, as is
the object-typed sibling. `unwrapAssignmentChainRhs` descends the `=` chain through parens
before the arms classify; a COMPOUND assignment is deliberately not unwrapped.

**GATES, PER COMMIT (`2cbb3847`).** Suite **16,356** / 0 / 3 (+8, exactly the new subtests)
and **NO corpus baseline moved**. Grid `503774c23b4535130ffdebabef430cf0`,
`added=0 removed=0` on all eight against a parent capture taken THIS SESSION from a
rebuilt parent (`Checker.class 19b32bf2`, the digest the (CHK.66) note recorded).
`cost_gate` exit 0, `output.errors` **46**; counters are the standing residual to the
third decimal (`typeOfExpr.calls` +1.42%, `globals.lookups` +1.54%, `globals.misses`
+1.77%, `narrow.walks` +0.87%, `typeNode.cacheable` +0.49 -> +0.50%). `huge_methods` exit
0, **783** classes. `partition-equivalence` EQUIVALENT all 78, floor **61 ms**
[58, 69, 61, 50] (one draw). `capture-equivalence` DIVERGED **968** in 43 of 76,
`definitions=0 moreAny=0`, and **both arm digests UNCHANGED** from (CHK.66)'s
(`full=446836089224869508 narrow=-3963031488196695014`). knip **48** and jsonrepair **4**,
byte-identical to arms taken from the parent rebuilt this session.

**LAST ROUND'S TWO HONEST GAPS ARE BOTH CLOSED.**

  * **(CHK.66)'s capture digest move, classified per ELEMENT.** Both binaries dumped with
    `XTSC_CAPEQ_DUMP` (the pre-(CHK.66) parent rebuilt to `Checker.class d0997340`, the
    digest that note records). **67 spans of 742,254 moved — 0.009% — and NOT ONE GAINED A
    MEMBER.** 57 are pure DROPS, every dropped member a strict subtype of a survivor
    (`JsxEmit | JsxEmit.ReactJSX | undefined` -> `JsxEmit | undefined`,
    `Expression | BinaryExpression | undefined` -> `Expression | undefined`,
    `TypeFlags | TypeFlags.Intersection | undefined` -> `TypeFlags | undefined`); 3 collapse
    to the alias the source itself spells (`PropertyName`, `ForInitializer`) and are
    strictly better; 3 are member REORDERINGS inside a captured type; and **the two rows
    that are not improvements are named**: one loses an alias NAME to the known first-wins
    (INC.29) family (`ModuleKind | ModuleKind.None` -> the 13 members spelled out, where
    the sibling caret two spans away keeps `ModuleKind`), and **3 are go-to-definition
    location lists that lost the dropped constituent's own declaration** — (API.5)'s
    "complete enough to highlight, not to edit" one mechanism over. Zero spans were added
    or removed from the population.
  * **A genuine parent library arm was rebuilt and both libraries re-taken.** knip **48**
    and jsonrepair **4** on the rebuilt parent, byte-for-byte identical to (CHK.67)'s. The
    stale 49-row capture the last round flagged is superseded.

**THREE ABLATION ARMS, ONE MISTAKE EACH, EACH `cmp`-DIFFED AGAINST ITS OWN SNAPSHOT,
RESTORE VERIFIED BY `cmp` PLUS A REBUILT md5 (`b2675304`).**

| arm | injected mistake | class | RED | kind of zero |
|---|---|---|---|---|
| a1 | the chain is never unwrapped | `04502d1e` | **6** — every positive | — |
| a2 | the unwrap does not see through parens | `b7c8cd6f` | **1** — P5, uniquely | — |
| a3 | the unwrap descends exactly ONE link | `99f095d0` | **1** — P6, uniquely | — |

No arm read 0; every one is uniquely discriminating, so there is no undiscriminated, dead
or unpinned zero to report this round.

**HOW VACUITY WAS RULED OUT, PER PIN.** Parent `f7fc33a1` rebuilt in this session
(`Checker.class 19b32bf2`): **6 of 8 RED**, exactly the 6 positives, the 2 controls green
on both binaries. Each positive names its reader — P1/P2/P5/P6 the DECLARATION reader with
a UNION target (live with no gate, so the row simply disappears), P3/P4 the DECLARATION
reader with a PRIMITIVE target graded by a deliberate mis-assignment, P4 inside a `for`.
A nullish-source fixture is NOT vacuous here because the union target keeps it outside
(CHK.63)'s own gate — which is why every positive uses `number | string` or an object
union rather than a primitive target, and why P3/P4 assert on the MESSAGE's type rather
than on silence.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The 43282 residue did not reproduce.** Three hand-written shapes of it (a 4-way
    `.kind ===` disjunction over `Sig | Cls`, with and without an `as` assertion, in and
    out of a loop) are all silent on the combined arm AND on tsc 7.0.2. The real site
    additionally carries an enum discriminant, a `previousDeclaration = undefined` reset
    in a sibling branch and a long body; the reduction was not attempted. Reported as an
    unreproduced residue, not as a diagnosis.
  * **The 8-profile grid on the loop arm exceeded the harness's 10-minute ceiling and was
    killed at 6 of 8 profiles.** The two survivors were run directly with grid.sh's own
    command; the capture is complete and the per-profile diff is over all eight. That the
    grid TIMED OUT is itself the first evidence of the cost finding above — a wall-clock
    tell noticed before any counter was read.
  * **My first placement of the (CHK.67) helper put its KDoc BETWEEN
    `narrowByAssignmentRhs`'s own KDoc and the function**, silently detaching the latter's
    documentation. Relocated and proved inert the way the brief prescribes: `javap -c -p`
    minus `line N:` is byte-identical across the move (diff exit 0, 0 lines) while
    `Checker.class` md5 moves `bc643b95` -> `b2675304`.
  * **`loop.py` still reverts edits inside the region it rewrites**, as the last round
    recorded; on the current head it leaves `flowJoinUnion` DEFINED and the loop join
    un-routed (2 references instead of 3), which builds cleanly and measures the wrong
    arm. The routing must be re-applied by hand after it — `build/chk68/snap/Checker.kt.loopR`
    is the correct composed tree.

### Round (CHK.66) — the loop join's blocker is a **SHIPPED four-line divergence at a plain BRANCH label**, it lands, and the loop join re-prices **3 rows -> 1**

**THE HEADLINE.** The queue said the loop join's residue was `getUnionType`'s missing
subtype reduction, and named the site set as "downstream of the label, at a branch
join". Measured, that is exactly right — and the branch-join defect is **reachable on
the SHIPPED binary in four lines with no loop, no partition and no gate**:

```ts
const x = zzzMk();            // string | number
if (x === "a") { zzzSink(x); }
const p: boolean = x;         // ours: string | number | "a"
                              // tsc 7.0.2: string | number
```

So the census that the round's first move should be — what does the rule RENDER? — put
the whole item on a one-second CLI loop instead of a grid. `flowJoinUnion` landed as
**`ad888740`** and the loop join was then re-priced on top of it.

**THE SITE SET, AND WHY IT IS TWO LINES AND NOT `getUnionType`.** tsc reduces at
`getTypeAtFlowBranchLabel` / `getTypeAtFlowLoopLabel` with `UnionReduction.Subtype` and
NOWHERE ELSE that matters here; INV.5(a) interns our unions by member-id list alone and
union member ORDER is pinned byte-for-byte across ~13k baselines, so a reduction inside
`getUnionType` was refused on sight. The shipped change routes the TWO flow joins in
`narrowTypeFromFlowCore` through one helper and touches nothing else.

**TWO CONSERVATISMS AGAINST tsc, BOTH DELIBERATE AND BOTH PINNED.**

  * **Only a member the DECLARATION does not itself contain may be dropped.** Every arm
    of this walk filters DOWNWARD from the declaration, so a "foreign" member is exactly
    one some narrowing step INTRODUCED — which is the whole defect class. It also makes
    the reduction free on the overwhelming majority of joins (an id-set membership test,
    no relation query at all), and it means a union the USER wrote is never re-shaped.
    tsc would also reduce `type T = string | "a"` at a join; we do not. Pinned by C2,
    which is the arm-a2 separating control.
  * **The drop needs a STRICT subtype** (`m` assignable to `o` AND `o` NOT assignable to
    `m`). **This repo has no subtype relation at all — `subtypeRelation` is declared at
    `CheckerState` and has ZERO readers** — so assignability is what there is, and it is
    bidirectional for pairs a subtype relation separates.

**THE LOOP JOIN, RE-PRICED: 3 ROWS -> 1.** `build/chk65/loop.py baseR` re-applied on top
of the reduction, with BOTH joins routed through `flowJoinUnion`, costs exactly **ONE**
ours-only row on every one of the 8 profiles (added=1 removed=0, the same row each
time): `checker.ts:43282:21`. **Both `utilities.ts` rows are GONE** — `11586:63` and
`11704:47`, the `ConditionalTypeNode | Node | undefined` family the queue named, are
closed by the reduction exactly as predicted. The survivor is a DIFFERENT mechanism and
its shape says so: `previousDeclaration = node` reports
`SignatureDeclaration's own 14 constituents | ClassDeclaration | ClassExpression`, i.e.
our narrowing leaves two CLASS kinds in a union tsc has filtered to `SignatureDeclaration`
— a discriminant/`isFunctionLike` filter gap over a loop-carried state, not a reduction
one. Re-queued as **(CHK.66)** at 1 row; the built tree is
`build/chk66/snap/Checker.kt.loopR` and it regenerates as
`python3 build/chk65/loop.py baseR` + the two `flowJoinUnion` routings (note that
`loop.py` REWRITES the region holding the reduction helper, so the helper must be
re-inserted after it — `build/chk66/red.py` carries the text).

**THE GATE ((CHK.63)) WAS NOT RE-MEASURED THIS ROUND** — `armBGR`'s grid was not re-run
on top of the landed reduction, so its 6-row list stands as (CHK.65) measured it. What
changed is what it is blocked on: (CHK.66)'s standalone price fell 3 -> 1, so the gate
now needs one loop row plus (CHK.67). Re-taking `armBGR` is the next round's first move,
and it is cheap — one build plus one grid.

**GATES, PER COMMIT (`ad888740`).** Suite **16,348** / 0 failed / 3 skipped (+9, exactly
the new subtests) and **NO corpus baseline moved** — which is the number this round was
most exposed on, union display being byte-pinned there. 8-profile grid
**`503774c23b4535130ffdebabef430cf0`**, `added=0 removed=0` on all eight, i.e. BYTE-
IDENTICAL to the recorded parent capture; parent verified by rebuilding `184832b1` in
this session (`Checker.class d0997340`, the same digest the (CHK.65) note recorded).
`cost_gate.py` exit 0, `output.errors` **46**; the counters are the standing residual
moved in the third decimal (`typeOfExpr.calls` +1.42%, `typeOfExpr.distinct` +0.97%,
`narrow.walks` +0.84 -> +0.87%, `typeNode.cacheable` +0.39 -> +0.49%, `globals.lookups`
+1.53 -> +1.54%, `globals.misses` +1.75 -> +1.77%) — the relation queries the reduction
adds are below a tenth of a percent. `huge_methods --fail-over 0` exit 0, **783** classes
scanned, 0 over. `partition-equivalence` EQUIVALENT all 78, floor **54 ms**
[75, 54, 54, 54] (one draw). `capture-equivalence` DIVERGED **968** in 43 of 76,
`types=968 definitions=0 narrowRendersMoreAny=0 absentInNarrow=0 absentInFull=0` —
UNCHANGED in every field; both arm digests moved (`full=446836089224869508`
`narrow=-3963031488196695014`), which is expected of a change that re-renders a narrowed
join, and is NOT classified per element this round (see the residue below). knip **48**
and jsonrepair **4** — jsonrepair byte-identical to the last stored capture.

**HOW VACUITY WAS RULED OUT, PER PIN.** Parent `184832b1` rebuilt in this session:
**7 of 9 RED**, and they are exactly the 7 positives. Each names its reader — P1/P2/P3
the DECLARATION reader (a deliberate mis-assignment, the only instrument that PRINTS the
flow type; a `zzzTake(r)` fixture is vacuous for a local, and a NULLISH union is vacuous
a second time under (CHK.63)'s own gate, so every fixture here is `string | number` or a
non-nullish object union), P4/P5 the CALL-ARGUMENT reader (live only for a PARAMETER
source), P6 a two-function differential (the narrowed join must render exactly like the
un-narrowed one), P7 the ORDER of the survivors of a REAL drop in a 3-member union. The
2 controls are green on BOTH binaries.

**THREE ABLATION ARMS, ONE MISTAKE EACH, EACH `cmp`-DIFFED AGAINST ITS OWN SNAPSHOT,
RESTORE VERIFIED BY `cmp` PLUS A REBUILT md5 (`19b32bf2`).**

| arm | injected mistake | class | RED | kind of zero |
|---|---|---|---|---|
| a1 | the reduction never fires | `30820166` | **7** — every positive | — |
| a2 | the FOREIGN gate removed (every member a drop candidate) | `45a0b283` | **1** — C2, uniquely | — |
| a3 | the drop needs only plain ASSIGNABILITY | `ddd51425` | **0** | **UNDISCRIMINATED, not redundant** |

**a3's zero is named and was chased.** It is not "measured redundant": the strictness
clause can only REFUSE a drop, so it cannot introduce a wrong answer, and it is what
stops `any` (mutually assignable with everything) being deleted from a join. I could not
construct an input on which it fires — the two candidate shapes both collapse for another
reason (`ZzzD` structurally identical to `ZzzA`, and `ZzzE` differing only by an OPTIONAL
member, are BOTH already absent from the join on the fixed binary, and **tsc 7.0.2 also
answers `ZzzA | ZzzC` for both**, so a "must keep" control there would have pinned a
divergence). It is also profile-inert: the a3 binary is ROW-IDENTICAL to the parent on
`tsc-project`, `tsc-services` and `tsc-harness`. Kept as a documented conservatism.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The queue's decomposition was right for the first time in six rounds, and the
    surprise was in the other direction**: the blocker it named for a LOOP item is a
    defect of the plain BRANCH join, present on the shipped binary, needing no loop at
    all. The instrument that found it in ninety seconds was the round's first move —
    census what the rule RENDERS, on four lines, against `tsgo --noEmit`.
  * **`P6`'s first form was a false pin.** It asserted the reduced join renders its ALIAS
    name (`ZzzAl`); measured, the UN-narrowed control renders structurally too, so the
    alias loss is the pre-existing (INC.27)/(INC.29) family and nothing to do with this
    change. Rewritten as a two-function differential, which is discriminating.
  * **`C4`'s first form pinned a KNOWN-OPEN gap** (inside the then-branch we say `string`
    where tsc says `"a"`) and was deleted rather than pinned — CLAUDE.md's rule that an
    open gap belongs in a session note, not in a control.
  * **`loop.py` silently REVERTS an edit inside the region it rewrites.** Its patch spans
    the `FlowBranchLabel` arm and the KDoc above `assignmentReduceBase`, so applying it
    on top of the landed reduction deleted the helper AND un-routed the branch join; the
    build's `Unresolved reference 'flowJoinUnion'` is the only reason it was noticed.
  * **The capture-equivalence ARM DIGESTS moved and are NOT classified per element this
    round.** The (CHK.64) `XTSC_CAPEQ_DUMP` comparison needs a dump from the PARENT
    binary as well, i.e. a second full sweep plus a rebuild; the evidence carried instead
    is that every classified field of the divergence is unchanged (968 / 43 of 76 /
    definitions=0 / moreAny=0), the grid is byte-identical and no corpus baseline moved.
    Say so rather than implying it was checked.
  * **knip's stored BEFORE arm is three rounds stale** (Aug 26, 49 rows) and no 48-row
    capture was on disk; my capture reads **48**, the count the (CHK.65) note records for
    the parent, and the one row that differs from the stale capture
    (`src/util/git.ts:17:55 TS2769`) was removed by a round between them, not by this
    one. A parent library arm was NOT rebuilt this session — flagged, not claimed.

### Round (CHK.65) — the four residues are **ONE mechanism at TWO readers**, it is a **SHIPPED false positive**, and it lands; the gate re-prices **7 rows -> 6**, and the loop join re-prices **8 -> 3** with its blocker finally named

**THE HEADLINE.** The queue said (CHK.63) needed two things: (CHK.61)(b)'s checking
half and the loop join. Measured, **(b)'s checking half is CORRECT and complete** — the
five-shape census fixture matches tsc 7.0.2 EXACTLY, code for code, message for
message, at the 1-based column — and what it costs is four MORE rows, of which
**three are the loop family and one was a mechanism nobody had seen**. That fourth one
turned out to be reachable on the SHIPPED binary with an explicit `| undefined`
member, at TWO readers, and it is what landed.

**THE DEFECT: A DOMAIN OF EXACTLY ONE LITERAL, MINUS THAT LITERAL, IS EMPTY.**

```ts
if (s.p !== undefined) { return s.p; }   // fine
if (s.p !== undefined) { return s.p; }   // SHIPPED FALSE POSITIVE
```

The first guard's ELSE branch narrows the path to exactly `undefined`;
[Checker.narrowUnionByLiteral]'s NON-union `keep = false` arm then answered its input
UNCHANGED, on reasoning that is right for an INFINITE primitive domain and wrong when
the input IS the literal being subtracted. tsc's `filterType` is ONE function for the
union and non-union cases; here they are two branches and only the union one
subtracted. **An IDENTIFIER subject goes through the M1.9 if-arm machinery and was
always correct, which is exactly what hid it — every hand-written narrowing fixture in
this repo uses a local.** It needs a PROPERTY PATH plus a preceding guard that leaves
the else state behind: a `return`, a `throw`, or an earlier `&&` conjunct.

**AND CLOSING THE FIRST READER LEAVES THE SECOND.** The assignment/return reader
(TS2322) reads the flow walk and was fixed by the one line above; the
ARITHMETIC/RELATIONAL OPERAND reader (TS18048) reads [Checker.arithOperandType], whose
flow consult is gated on a UNION base **and refuses a `never` answer**, so it never saw
the re-narrowing. [Checker.operandFlowNarrowsToNever] is the suppression there, and it
must **CLAIM** the operand (return true, caller emits nothing) rather than merely
decline — the first cut returned false and the TS18048 simply became a TS2365 about
`number | undefined` one line down. Census on the parent, 5 false positives over 11
shapes; after the first line 1 remained; after the second, 0.

**THE GATE, RE-PRICED — AND IT NOW NEEDS EXACTLY ONE THING.** `armBGR` (the
`canUseTypeEngine` nullish-union gate opened, the RETURN and ASSIGNMENT readers given
the flow consult for a primitive target, AND (CHK.61)(b)'s checking half — an optional
member's access type carrying `| undefined`) measures **6 distinct rows on the 8
profiles**, down from 7 before this round's fix removed `checker.ts:30269`. **All six
are the LOOP JOIN**, and (b)'s own two rows (`emitter.ts:1479`,
`organizeImports.ts:862`) are GONE:

| row | shape |
|---|---|
| `checker.ts:35649:17` | a `let index: number \| undefined` assigned on every path of an if/else INSIDE a `for` |
| `moduleNameResolver.ts:824:26` | `if (host.f && host.g) { for (…) { host.f(x) } }` |
| `moduleNameResolver.ts:2265:17` | `if (… && p.a.b.exports) { for (…) { use(p.a.b.exports) } }` |
| `tsserverLogger.ts:28:5` | an assignment narrow that must survive `while (true)` |
| `project.ts:502:33`, `:528:37` | `Debug.assertIsDefined(host.require)` before a `for` |

**THE LOOP JOIN RE-PRICES 8 ROWS -> 3, AND THE BLOCKER IS `getUnionType`, NOT A FLAG.**
(CHK.63)(b) refused it at 8 ours-only rows and predicted that "refusing a `never` join
removes all five" and that the other three needed `narrowWalkTruncated` and the cut to
be separate flags. Ported onto today's HEAD with the flags separated, the `never`
family is **CLOSED** — 8 rows -> 3 — and the surviving three are NOT a flag problem:

  * `checker.ts:43282:21`, `utilities.ts:11586:63`, `utilities.ts:11704:47`, all reading
    `ConditionalTypeNode | Node | undefined` / `ClassExpression | Node | undefined`,
    i.e. **a narrowed SUBTYPE beside the declaration's own constituents**. tsc's
    `getTypeAtFlowLoopLabel` unions with `UnionReduction.Subtype`; **INV.5(a) interns
    our unions by member-id list and performs NO subtype reduction**, so `A | B` with
    `A ⊆ B` survives and a later discriminant test can no longer filter it. Once the
    label explores back edges at all, branch states from a PREVIOUS iteration's body
    reach a join that today never sees them.
  * Three refusals were built and measured against it, one per attempt, and **none of
    them is the answer**: refuse a `never` join (works, keeps 3), refuse a join with an
    antecedent that IS `declaredType` by identity (no effect), and refuse a join that is
    not a STRICT SUBSET of the declaration's constituents (no effect). The last two
    prove the offending union is built DOWNSTREAM of the label, at a branch join over
    the newly-reachable states — which is why no policy AT the label can fix it.
  * The design regenerates from HEAD with `python3 build/chk65/loop.py baseR`;
    `build/chk65/snap/Checker.kt.loopbase` is the built tree.

**AND THE COMBINED ARM SPLITS THE REMAINDER CLEANLY.** gate + readers + (b) + the loop
join measures **4 distinct rows** (6 of 8 profiles captured — the two missing ones carry
only compiler rows already present): the 3 subtype-reduction rows above, plus
`checker.ts:35649`, which the loop join does **not** close. That last one is an
assignment-RHS classification gap (`index = index! + 1`, `index = cutoffIndex =
result.length`), not a loop one.

**GATES.** Suite **16,339** / 0 failed / 3 skipped (+13, exactly the new subtests);
**no corpus baseline moved.** 8-profile grid `503774c23b4535130ffdebabef430cf0`,
byte-identical PER PROFILE to the recorded parent capture, added=0 removed=0.
`cost_gate.py` exit 0, `output.errors` **46**, every counter the standing residual to
the digit (`typeOfExpr.calls` +1.42%, `globals.lookups` +1.53%, `globals.misses`
+1.75%, `narrow.walks` +0.84%, `typeOfExpr.distinct` +0.97%, `typeNode.cacheable`
+0.39%). `huge_methods --fail-over 0` exit 0, **783** classes, 0 over.
`partition-equivalence` EQUIVALENT all 78 (floor **56 ms**, one draw).
`capture-equivalence` DIVERGED **968** in 43 of 76, `types=968 definitions=0
narrowRendersMoreAny=0` — unchanged; both ARM DIGESTS moved and the dump against
(CHK.63)'s is **0 lost, 0 gained, exactly 1 changed**: `checker.ts @1803172..1803191`
(`checker.ts:30269`, the `symbol.lastAssignmentPos` of the `&&` chain) goes `undefined`
-> `never`, which is tsc's own answer for that state. knip **48** and jsonrepair **4**,
every row byte-identical against (CHK.63)'s third-commit capture.

**HOW VACUITY WAS RULED OUT, PER PIN.** Parent `3c6a8e33`, rebuilt in this session,
with the fixtures already in place: **7 of 13 RED**, and they are exactly the 7
positives. Each names its reader — P1/P2/P3/P4 the RETURN reader (TS2322), P5 the
RELATIONAL operand reader and P6 the ARITHMETIC one (TS18048), P7 the `&&`-conjunct
form of the same. The 6 controls are green on BOTH binaries and every one of them
reports identically on tsc 7.0.2 where it is meant to report.

**FOUR ABLATION ARMS, ONE MISTAKE EACH, EACH `cmp`-DIFFED AGAINST ITS OWN SNAPSHOT,
RESTORE VERIFIED BY `cmp` PLUS A REBUILT md5 (`d0997340` both times).**

| arm | injected mistake | class | RED |
|---|---|---|---|
| a1 | the non-union subtraction never fires | `6635a19a` | **7** — every positive, both readers |
| a2 | the operand suppression DECLINES instead of claiming | `781b93fc` | **3** — the three operand pins, on their `2365` assertion |
| a3 | the operand suppression removed entirely | `3902f0e4` | the same 3 tests, on their `18048` assertion |
| a4 | the suppression widened from `never` to ANY narrowing | `7039d1e3` | **0 -> 1** after the separating control was added |

**THE KINDS OF ZERO, NAMED.** **a4 was UNPINNED, not redundant** — the fourth
consecutive round with that shape. The separating fixture is an operand that IS
narrowed and is STILL nullish (`number | null | undefined` minus `null`), which no
other pin here contains; with it, a4 is a unique RED. **a2 and a3 are a round-927 pair
at TEST granularity and are separated at ASSERTION granularity**: both redden the same
three tests, a2 on `have(none { it.code == 2365 })` and a3 on
`have(none { it.code == 18048 })`, so they are two observables and the pins say which.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The queue's decomposition was wrong for the fifth round running, and this time in
    the encouraging direction**: (b)'s checking half is not "refused", it is DONE and
    exact, and its 4-row price is 3 loop rows plus one defect that had nothing to do
    with it. What found the defect was the ARM, not reading — the census that isolates
    it (11 shapes over one interface) took ten minutes once the arm's grid named a file
    and a column.
  * **A loop-join fixture graded through `zzzTake(r)` is VACUOUS**: the CALL-ARGUMENT
    reader is live only when the source is a PARAMETER, so a fixture built on a local
    `let` reads 0 rows on a broken AND a fixed binary. The instrument that works is a
    deliberate mis-assignment (`const p: boolean = r`), which PRINTS the flow type; on
    it the loop arm matches tsc on 14 of 15 shapes.
  * **A nullish-union loop fixture is vacuous for a SECOND reason** — it sits under
    (CHK.63)'s own gate, so both arms are silent. Use `string | number`.
  * **Three refusals for the loop join's residue, three no-ops, and the third one is
    what named the mechanism**: a rule that refuses any join that is not a strict subset
    of the declaration's constituents changed NOTHING, which proves the offending union
    is not the label's. That is a negative result worth more than the two arms before it.
  * `bash script.sh & tail --pid=$!` inside a `run_in_background` Bash call is the
    round-831 double-detach: the harness reaped it, the grid died at profile 1, and the
    output directory was EMPTY with an exit status of 0 on the wrapper.

### Round (CHK.63)(a)(c) — the four residues are **ONE reader gap plus TWO flow-walk defects**, three of which were SHIPPED false positives; the gate re-prices **6 rows -> 4**, and the loop join is **REFUSED with its price and its blocker named**

**THE HEADLINE.** (CHK.64) left four residue rows and the queue read them as four
mechanisms. Measured with a per-READER census, they are **two flow-walk defects and
one reader gap**, and — the part that mattered for the round — **three of the four
were reachable on the SHIPPED binary at the CALL-ARGUMENT reader, as FALSE
POSITIVES.** Two are fixed and pinned there; the third is refused with a price. The
gate itself is not opened: it now costs **4** rows, all four owned by work that is
still open.

**THE CENSUS THAT DECIDED THE ROUND, AND THE VACUITY THAT NEARLY KILLED IT.** The
first census fixture built every residue shape over a `const` initialised from a call
and read it at all four readers: **it reported nothing at all**, on our side, for
every shape INCLUDING its own negative control. The argument reader is live for a
nullish union only when the source is a **PARAMETER** — `const zzzU = zzzGet(k);
zzzTake(zzzU)` is silent here and TS2345 in tsc, which is a separate gap. Re-cut
against parameter sources, the same fixture answered immediately:

| shape | ARGUMENT reader on the parent binary |
|---|---|
| (iii) an assignment inside the guarded branch | **FALSE POSITIVE** |
| (iii) the plain `if (id === undefined) { id = t }` form | **FALSE POSITIVE** |
| (iv) definite assignment across an if/else | correct |
| (loop) an assignment narrow that must survive a loop | **FALSE POSITIVE** |
| (vi) a non-null assertion | correct |

So (iii) and the loop family are FLOW-WALK defects, live today, with nothing to do
with the gate; (iv) and (vi) are correct in the walk and were only ever the READER.

**(a) AN ASSIGNMENT INSIDE A NULLISH GUARD MUST OVERWRITE THAT GUARD'S OWN
NARROWING.** [Checker.narrowByAssignmentRhs]'s two resolved-RHS arms (Identifier,
round 463; PropertyAccess, round 464) FILTER the antecedent, and
[Checker.narrowUnionByRhsAssignment] answers a non-union receiver unchanged — so
inside the guard, where the antecedent is the bare `undefined` the condition just
produced, the assignment was a **no-op** and the branch join re-minted
`string | undefined`. [Checker.assignmentReduceBase] applies round 416's rule there:
an assignment OVERWRITES, so the post-state is reduced from the DECLARED type. This
is the commonest "default it if it is missing" idiom in TypeScript and it was a
shipped false positive.

**(a2) AND ITS OWN ABLATION FOUND THE SECOND HALF.** The arm that deletes the "a
nullish assigned type keeps the antecedent" refusal read **0 RED** and a
byte-identical 8-profile grid — the third consecutive round with an UNPINNED-not-
redundant zero. The separating fixture needs a **THREE-member** declaration
(`string | null | undefined` with a `null`-typed right-hand side): with
`T | undefined` alone both bases answer the same type. The refusal was deleted.

**(c) A `!` IS NOT RESPECTED THROUGH PARENTHESES.** `return (t)!` against
`string | number` was a false TS2322 while `return t!` one line away was silent: the
nullish-stripping arm admits its operand by KIND (Call / Identifier / PropertyAccess,
rounds 439/456/479) and a ParenthesizedExpression is none of them.
[Checker.nonNullOperandStrips] reads through parentheses and adds the LOGICAL
operators, whose value is one of their own operands — which is the
`server/project.ts:746` shape (`return (info && info.getLatestVersion())!`). `,` is
excluded (a comma's value is its LAST operand) and that divergence from tsc is pinned
as residue with the value we answer.

**(b) THE LOOP JOIN IS REFUSED, AND ITS BLOCKER IS NAMED.** `narrowTypeFromFlowCore`
answers `declaredType` at a `FlowLoopLabel`, so **every loop erases every assignment
narrowing established before it** — `r = ""; while (…) { } take(r)` is a false
positive even when the loop never mentions `r`. Built: the label unioned like a
branch label, with a back edge that re-enters it contributing `never`
(`narrowLoopCut`). That is EXACT for this flow algebra — every back edge is either a
monotone narrowing, in which case iterating from the entry state never grows past it,
or it passes an assignment, which computes its post-state from the DECLARED type and
cuts the recursion itself — and it makes **all ten** loop shapes of the round's
fixture agree with tsc 7.0.2 exactly, the `while (…) { r = undefined }` control
included. **It costs 8 ours-only rows on every profile and is REVERTED.** The
decomposition, which is the reusable part:

  * **5 rows are a `never` the loop label was MASKING.** `emitter.ts` narrows
    `modifiers` with `every(modifiers, isModifier)`, a GENERIC predicate we do not
    infer; its NEGATION subtracts everything and answers `never`, and the loop label's
    `declaredType` was resetting it. Propagating it correctly gives
    `Property 'length' does not exist on type 'never'`. Refusing a `never` join back to
    `declaredType` removes all five.
  * **3 rows are a union that is LESS reducible than the declaration.** A join taken
    over a TRUNCATED antecedent contains `declaredType` beside an already-narrowed
    member, so a later kind test reads `ConditionalTypeNode | Node | undefined`
    (`utilities.ts resolveNameHelper` x2, `checker.ts:43282`). Refusing a truncated
    join needs `narrowWalkTruncated` and the CUT to be separate flags; a first cut of
    that regressed the nested-loop shape and the attempt was stopped there under the
    two-attempt rule.

  The refusal is recorded rather than the mechanism deleted: `narrowLoopCut`'s design
  and its fixpoint argument are in `build/chk63/snap/Checker.kt.gapB-refused`.

**THE GATE, RE-PRICED — AND THE READER HALF IS WORTH 2 ROWS.** `armG` (the
nullish-union gate opened, nothing else) still measures **6** rows on this round's
binary: gap (a) does NOT remove `parser.ts:2642`, because the RETURN reader never
consults the flow walk for a primitive target. Adding that consultation to the RETURN
and ASSIGNMENT readers (`armGR`, measured, NOT landed) takes it to **4 distinct
rows**, and every one is owned:

| row | owner |
|---|---|
| `parser.ts:2642` | **GONE** — (a) plus the reader |
| `server/project.ts:746` | **GONE** — (c) |
| `emitter.ts:1479`, `organizeImports.ts:862` | (CHK.61)(b)'s checking half |
| `checker.ts:35649`, `tsserverLogger.ts:28` | the REFUSED loop join — both reads sit inside a loop whose earlier iteration assigns the reference |

So (CHK.63) now needs exactly two things and no more: **(b)'s checking half and the
loop join**, and the reader consultation must land WITH the gate (it is unobservable
without it).

**GATES, per commit, all foreground, one at a time.** Suite **16,318** / **16,325** /
**16,326**, 0 failed, 3 skipped (+8, +7, +1 — exactly the new subtests); **no corpus
baseline moved on any of the three.** 8-profile grid
`503774c23b4535130ffdebabef430cf0` on all three, byte-identical PER PROFILE against
the recorded parent capture (parent `Checker.class` md5 `eec8ea8f`, rebuilt in this
session and used for every vacuity check). `cost_gate.py` exit 0 three times,
`output.errors` **46**; every counter is the standing residual to the digit
(`typeOfExpr.calls` +1.42%, `globals.lookups` +1.53%, `globals.misses` +1.75%,
`narrow.walks` +0.84%, `typeOfExpr.distinct` +0.97%, `typeNode.cacheable` +0.39%) —
the round costs nothing measurable. `huge_methods --fail-over 0` exit 0, **783**
classes, 0 over. `partition-equivalence` EQUIVALENT all 78 (floors 79 / 63 / 56 ms,
one draw each). knip **48** and jsonrepair **4**, EVERY ROW byte-identical across all
three commits.

**`capture-equivalence` MOVED TWICE AND BOTH MOVES ARE CLASSIFIED PER ELEMENT.**
DIVERGED **968** in 43 of 76, `types=968 definitions=0 narrowRendersMoreAny=0` —
unchanged throughout. Against last round's own dump (`build/chk64/arm-fix4.tsv`),
commit 1 is **0 lost, 0 gained, 2 changed**, both DROPPING a spurious `| undefined`
from a property-path assignment inside an `if (!x.p)` guard in `checker.ts`; commit 2
is **0 lost, 0 gained, 1 changed**, a hover going `any` -> the concrete union, and tsc
7.0.2's own LSP answers that same constituent set; commit 3 moves **nothing** (both
digests unchanged).

**HOW VACUITY WAS RULED OUT, PER PIN.** Every pin was executed against a PARENT binary
rebuilt in this session with the fixture already in place. `AssignmentInsideAGuard…`:
**4 of 8 RED** on `eec8ea8f`, and the 9th pin (a2) **1 of 9 RED** on `0557ff4e`; the
green ones are labelled controls. `NonNullAssertionThroughParensAndLogical…`: **4 of 7
RED** on `40d1d6aa`. Every positive is at a reader the test names, and (c)'s four are
at a UNION target deliberately — against a PRIMITIVE target the whole family is
invisible, which is (CHK.63)'s own gate.

**NINE ABLATION ARMS, ONE MISTAKE EACH, EACH `cmp`-DIFFED AGAINST ITS OWN SNAPSHOT,
EVERY RESTORE VERIFIED BY `cmp` PLUS A REBUILT md5.**

| arm | injected mistake | class | RED |
|---|---|---|---|
| a1 | the reduce base is never consulted | `33dc9963` | **4** — exactly the four (a) positives |
| a2 | the nullish-only antecedent guard dropped | `7124ee49` | **0 — UNDISCRIMINATED**, and the 8-profile grid is byte-identical too |
| a3 | the nullish-ASSIGNED refusal restored | `1fb8b365` | **1** — uniquely the (a2) pin (it read 0 before that pin existed) |
| a4 | *withdrawn* — that call site belonged to the refused (b) work | — | — |
| b1 | parentheses are not read through | `3c6889d3` | **4** — all four (c) positives |
| b2 | a logical operand does not strip | `864ecf3b` | **3** — uniquely the three logical pins |
| b3 | the COMMA operator admitted as well | `7dade5d0` | **1** — uniquely the residue pin |
| b4 | only the LEFT operand of a logical must strip | `555e4651` | **0 — UNDISCRIMINATED** |

**THE KINDS OF ZERO, NAMED.** **a2 is UNDISCRIMINATED and NOT shown redundant** — its
guard is the REASONED bound that a non-nullish antecedent must keep the pass-through
(reducing from the declaration there would widen a live type-guard narrowing back to
the declaration), and both instruments that could see it are silent, so the KDoc now
says so instead of claiming coverage. **a3 was UNPINNED, not redundant**, and is the
round's own instance of the pattern; it is now a unique RED. **b4 is UNDISCRIMINATED**:
requiring the RIGHT operand to strip as well is a conservatism these fixtures cannot
separate. a1/a2/b1..b4 were taken on the commit-2 tree and a3 on the commit-3 tree.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The queue's four residues were not four mechanisms and were not even four
    ITEMS — two of them are ONE refused change, and two were SHIPPED false
    positives that had nothing to do with the gate.** The census that shows it is one
    fixture with the same guard in front of five readers, which is (CHK.64)'s own
    method reused; what it cost was noticing that the fixture must use PARAMETER
    sources, because a `const` initialised from a call is silent at the argument
    reader too — a second, unrelated gap.
  * **A `never` can be MASKED by a conservative join, and making the join precise is
    what exposes it.** Five of the loop change's eight rows are a pre-existing defect
    (a negated GENERIC type-guard call answering `never`) that `declaredType` at every
    loop label was resetting. That is the mirror of round 830's law: a conservative
    answer is not only imprecise, it can be load-bearing for something else's bug.
  * **A killed restore leaves NO class file and a scratch-project run then prints
    ZERO ROWS, which reads exactly like a clean fixture.** The 10-minute tool timeout
    killed an ablation grid mid-restore; the next probe answered "no diagnostics"
    against a classpath with no `Checker.class` in it at all. Round 851's trap with a
    new trigger — check the class file exists before believing any scratch run.
  * **`ours.sh`'s exit status is the GREP's**, so a run with no rows exits 1 and a run
    against a broken build also exits 1. The only reliable tell is the raw output's
    `diagnostics: N error(s)` line.

### Round (CHK.64)(i)+(ii) — the five "narrowing gaps" are **two gaps at ONE reader**, both landed; (CHK.63)'s price falls **11 rows -> 6**

**THE HEADLINE.** (CHK.64)'s queue entry lists five mechanisms. Measured, the
first two are **one reader** and two different things its filler could not do:
round 784's gate sends the ASSIGNMENT and RETURN readers to
[Checker.currentLocalTypes] for a primitive target, and that map is filled by the
legacy if-arm helper [Checker.extractNullNarrowing], which (i) answers ONE
`(name, type)` pair and has no `&&` arm, and (ii) is only ever asked about a
THEN-branch. Everything else about those shapes was already right: a MEMBER
ACCESS, a CALL ARGUMENT and a DECLARATION after an `&&` guard *or* after an early
exit are all correct on the parent binary, because they consult the flow walk.
Both are now closed, and **`scripts/pristine_oracle.py` was not needed: every
positive was checked against tsc 7.0.2 directly.**

**THE PRICE OF (CHK.63), RE-TAKEN.** The `armG` arm (the nullish-union gate
opened, nothing else) measured **11** ours-only rows on the eight profiles last
round. On this round's binary it measures **6**:

| row | cause | status |
|---|---|---|
| `sourcemap.ts:164/165/166` | (i) the `&&` condition | **GONE** |
| `core.ts:2191`, `path.ts:585` | (ii) the early exit | **GONE** |
| `emitter.ts:1479`, `organizeImports.ts:862` | (CHK.61)(b)'s absence | fixed by (b) |
| `parser.ts:2642` | (iii) an assignment inside the guarded branch | open |
| `checker.ts:35649` | (iv) definite assignment across an if/else | open |
| `tsserverLogger.ts:28` | (iv family) an assignment narrow that must survive a LOOP | open |
| `server/project.ts:746` | **(vi), NOT IN THE QUEUE** — a NON-NULL ASSERTION `!` is not respected at the return reader | open |

So (CHK.64)'s own residue is **4 rows**, and a SIXTH mechanism turned up that the
queue never listed. (CHK.63) is now worth opening for 6 rows, 2 of which (b) pays
back.

**WHAT (i) IS.** [Checker.extractNarrowingsFromCondition] flattens the
(left-nested) `&&` spine ITERATIVELY and returns a LIST, at most one entry per
name; `||` is not decomposed. All three consumers take the list — the two
`checkTypeAssignabilityInStmt` dispatchers and the spine's `ctaM3NarrowThen`,
whose value type becomes a `List`. **A "narrowing" to `any` is refused**:
`typeofTypeGuardToType` answers `anyType` for `"object"`/`"function"`, so
decomposing `typeof x === "object" && x !== null` installed `any` — 13 captured
hovers went from tsc's own `object`/`unknown` to `any`, and it would DELETE a
true positive. **The single-condition path still installs `any` and that is a
shipped false NEGATIVE** (`if (typeof zzzO === "object") { zzzQs = zzzO }` is
silent here and TS2322 in tsc), recorded as residue and not touched.

**WHAT (ii) IS.** At the IfStatement's spine LEAVE, an `if` with no `else` whose
then-branch DEFINITELY EXITS installs the NEGATED condition's narrowings for the
rest of the enclosing frame. `ctaAlwaysExits` is conservative;
`negateCondition` is syntactic. The install is REFUSED unless the enclosing frame
opened its own `localTypes` scope, which is what bounds it — a statement-position
block SHARES its parent's map and has no pop to revert a write.

**THREE THINGS THE GATES FOUND THAT READING THE CODE DID NOT.**

  * **`narrowedDeclared` is shared down the frame chain with NO undo log.**
    Recording the declared type into it added **21 ours-only rows PER PROFILE**,
    every one an assignment whose TARGET was read as another function's
    same-named binding (`type` as `Type`, `source` as a `Map<…>`). The frame now
    takes a COPY of its own at the first such write. Dropping the record instead
    left **4** rows — an assignment BACK to the narrowed reference.
  * **The last row standing was a SHIPPED defect this change made reachable.**
    With NESTED narrows on one name the narrowing frame wrote `narrowedDeclared`
    UNCONDITIONALLY, recording the OUTER narrow's result as if it were the
    declaration, so `if (b) { if (isNs(b)) { b = undefined } }` was a false
    TS2322 — reproducible with no early exit anywhere (`build/chk64/cb`). All
    three writers are now FIRST-WINS.
  * **A negated TYPE-GUARD CALL is refused.** `if (!some(components)) return []`
    negates to `some(components)`, whose predicate is generic
    (`array is readonly T[]`) and whose `T` we do not infer, so the narrow was
    LESS precise than the declaration and `const reduced = [components[0]]`
    hovered `any` — **20** captured spans in `path.ts`/`utilities.ts`, **3** with
    it refused.

**GATES, per commit, all foreground, one at a time.** Suite **16,296** then
**16,308**, 0 failed, 3 skipped (+10 and +12 — exactly the new subtests);
**no corpus baseline moved on either.** 8-profile grid
`503774c23b4535130ffdebabef430cf0` on BOTH code commits, byte-identical PER
PROFILE against a parent capture taken in this session (parent `Checker.class`
md5 `d0f72b51`, rebuilt here). `cost_gate.py` exit 0 both times, `output.errors`
**46**; the change costs `narrow.walks` **+0.84%**, `typeOfExpr.distinct`
+0.97%, `typeNode.cacheable` +0.43% over the recorded baseline — it installs
narrowings where there were none — on top of the standing residual
(`typeOfExpr.calls` +1.42%, `globals.lookups` +1.53%, `globals.misses` +1.75%);
all far inside the +-2% gate. `huge_methods --fail-over 0` exit 0, **783**
classes, 0 over. `partition-equivalence` EQUIVALENT all 78 (floors 75 ms
[53, 93, 75, 58] and 64 ms [88, 64, 61, 56] — one draw each). knip **48** and
jsonrepair **4**, EVERY ROW byte-identical against a parent arm rebuilt in this
session.

**`capture-equivalence` IS AGAIN THE ONE GATE THAT MOVED, AND THE MOVE IS
CLASSIFIED PER ELEMENT AGAINST A PARENT DUMP TAKEN IN THIS SESSION.** DIVERGED
**968** in 43 of 76, `types=968 definitions=0 narrowRendersMoreAny=0` —
unchanged. Both ARM DIGESTs moved; the new instrument `XTSC_CAPEQ_DUMP`
(committed first, round 789's rule) makes that classifiable, because the digest
answers *whether* and only a per-span dump answers *which*. Whole round:
**0 rows lost**, **+174 definitions gained** (spans that resolved to nothing now
navigate), **2,137** type rows changed — **1,274** drop a spurious `| undefined`,
**300** go `any` -> concrete, **485** render a narrower/named type (12 of 12
sampled agree with tsc 7.0.2's LSP where the parent did not), **1** GAINS
`| undefined` where tsc agrees with us (`ClassLikeDeclarationBase.name?`), and
**3** go to `any` — all three a member access on a CORRECTLY narrowed receiver
whose further `&&`-conjunct guard (`isNamedDeclaration(child) &&
isPropertyName(child.name)`) we do not apply, i.e. the queue's own gap (v).
Final digests `full=7848756790733502552 narrow=5188741781646622612`.

**HOW VACUITY WAS RULED OUT, PER PIN.** Every pin was run against a PARENT binary
rebuilt in this session with the fixture already in place. `AndCondition…Test`:
**5 of 9 RED** on `d0f72b51` — the two multi-operand pins, the right-conjunct
pin, the return-reader pin and the VALUE pin; the 4 green are labelled CONTROLS
and count as coverage only for the arms that redden them.
`EarlyExit…Test`: **8 of 12 RED** on `a8d4e445` (the (i) commit). Of the 4 green,
three are labelled controls/residue and the fourth is labelled a CONTROL for a
reason worth keeping: **the `!` arm of `negateCondition` is structurally
unobservable today** — truthiness narrowing only removes `null`/`undefined`, so
its subject is a nullish union, which `canUseTypeEngine` refuses against the
primitive target this whole leg exists for; against a UNION target round 784's
gate hands the read to the flow walk, which was always right. It starts
discriminating the moment (CHK.63) opens.

**TWELVE ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT, EACH
`cmp`-DIFFED AGAINST ITS OWN SNAPSHOT AND EVERY RESTORE VERIFIED BY `cmp` PLUS A
REBUILT md5** (`eec8ea8f` / `a8d4e445`).

| arm | injected mistake | class | RED |
|---|---|---|---|
| a1 | no `&&` decomposition at all | `bb62d106` | **5** — exactly the five (i) positives |
| a2 | only the FIRST conjunct survives | `a6cf1e22` | **2** — uniquely the two multi-operand pins |
| a3 | `\|\|` decomposed as well | `4cf051a8` | **1** — uniquely the `\|\|` negative control |
| a4 | the `anyType` refusal dropped | `56d36769` | **1** — uniquely the `typeof … "object"` guard |
| a5 | the SPINE site takes only the first entry | `8583305a` | **2** — the spine is the load-bearing consumer |
| a6 | the two DISPATCHER sites take only the first | `2424930f` | **0 — UNDISCRIMINATED** (see below) |
| b1 | the whole early-exit install removed | `0568e654` | **7** |
| b2 | `ctaAlwaysExits` accepts ANY then-branch | `225225e3` | **1** — uniquely the does-not-exit control |
| b3 | a `Block`'s last statement is not looked into | `7b17469d` | **7** |
| b4 | the `localScoped` refusal dropped | `eed8e3a0` | **1** — uniquely the while/nested-block RESIDUE pin |
| b5 | the declared type not recorded | `870f5189` | **1** — uniquely the assign-back pin |
| b6 | recorded into the SHARED map (no own copy) | `fe114f78` | **0 -> 1** — UNPINNED, then fixed |
| b7 | `narrowedDeclared` back to LAST-wins | `0c0d8b5a` | **1** — uniquely the nested-narrows pin |
| b8 | the call-predicate refusal dropped | `4f9f4c6b` | **0 — UNDISCRIMINATED, capture-only** |
| b9 | the `!==` flip made a no-op | `6b97cbfd` | **7** |
| b10 | an `if … else` allowed to narrow after it | `8f37e002` | **1** — uniquely the if/else RESIDUE pin |

**THE KINDS OF ZERO, NAMED.** **a6 is UNDISCRIMINATED and NOT shown redundant**:
the two `checkTypeAssignabilityInStmt` sites were reverted to a single narrowing
and nothing moved, and a direct CLI probe of that arm over the round's own
censuses (`build/chk64/c2`, `c5`) produced no row either — the spine anchors
every `IfStatement` I could construct, function-body and FILE-level alike, so the
legacy arms are truncated ((cta-m3j)) and are consistency insurance, not measured
coverage. **b6 was UNPINNED, not redundant** — the third consecutive round with
that pattern; the fixture is two same-named bindings in one file and the arm now
reddens uniquely on it. **b8 is UNDISCRIMINATED and its effect is real but lives
on the CAPTURE channel**; two diagnostic fixtures were built (a non-generic and a
generic predicate) and under the embedded lib BOTH narrow precisely, so no
diagnostic pin in this suite can reach it — recorded in the test's own KDoc
rather than claimed. **b4 and b10 redden a RESIDUE pin**, which is the useful
shape: they prove those two residues are REFUSALS with a price, not inabilities.
**b1/b3/b9 share a 7-pin red set** and are not separated by these fixtures; both
are load-bearing (b3's mistake makes every braced exit invisible), recorded as
one observable in round 927's sense.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The queue's "five mechanisms, not one" is half right and the useful half is
    the other one.** (i) and (ii) share a READER, which is why closing (ii) took
    twenty minutes once (i) was in: the same `currentLocalTypes` install, at a
    different position. The census that showed it costs one fixture — put the
    same guard in front of a member access, an argument, a declaration, an
    assignment and a return, and read which columns are already right.
  * **`build/chk61b/n1`, the round's inherited repro, is SILENT on both
    compilers** — its `&&` operands are `string | undefined` against primitive
    targets, i.e. the (CHK.63) gate hides the gap the fixture was built to show.
    A NON-NULLISH union (`number | string` + `typeof`) makes all four rows appear
    on the shipped binary with no patch at all, and that is how (i) and (ii) were
    both found. **A nullish fixture is the wrong instrument for anything below
    that gate.**
  * **Two of the three real defects this round fixed were found by a GATE, not by
    reading.** The `narrowedDeclared` leak (21 rows/profile) and the last-wins
    nested-narrow defect (1 row, and a shipped FP class) were both invisible in
    the source and obvious in an 8-profile diff. The `anyType` widening and the
    generic-predicate degradation were both found by the CAPTURE sweep and by
    nothing else.
  * **`capture-equivalence`'s ARM DIGEST is a yes/no answer to a question that
    needs a list.** Classifying its move meant adding `XTSC_CAPEQ_DUMP` and
    diffing two binaries span by span; the classification is what turned "both
    digests moved" into "0 lost, +174 definitions, 1,274 spurious `| undefined`
    dropped, 3 regressions". Committed first, so the ablations could not delete
    it (round 789).

### Round (CHK.61)(b) — the display half **LANDED**; the checking half is **REFUSED with its price finally taken**, and the refusal turned up a **systematic FALSE NEGATIVE** that is not (b)

**THE HEADLINE, IN TWO PARTS.** (b)'s DISPLAY half is in: an optional member's
hover now carries `| undefined` and then narrows, at tsc 7.0.2's own answers, with
`added=0 removed=0` on all eight profiles and every library row byte-identical —
because the leg is confined to the CAPTURE, which production never computes. (b)'s
CHECKING half is REFUSED, and for the first time its price is measured rather than
asserted: **15 ours-only rows on the eight profiles**, of which **9 are net new**.

**(b) IS NOT SOUND ALONE, AND THE QUEUE'S "3 rows" WAS THE WRONG NUMBER BECAUSE IT
WAS THE WRONG ARM.** `build/chk61/patch_b.py` (add `| undefined` at
`computeRawTypeOfPropertyAccess`'s three `prop != null` returns) does not merely add
rows — on the round's own four-line repro it **DELETES a true positive**:
`const zzzA: string = zzzInst.zzzOpt` reports `Type 'number' is not assignable to
type 'string'` on the shipped binary and **NOTHING** with patch_b, because the
source becomes a nullish union and `canUseTypeEngine` refuses those against a
primitive target. So (b) is only expressible together with opening that gate, and
the honest arms are:

| arm | 8-profile ours-only rows | on the round's repro |
|---|---|---|
| `p_head` (shipped) | 0 (md5 `503774c2…`) | 2 of tsc's 4 rows, one with the wrong TYPE |
| `armG` — the nullish-union gate opened, nothing else | **11** | 6 of 6 at tsc's wording |
| `armBG` — patch_b **and** the gate | **15** | **4 of 4, at tsc's own positions and wording** |

`armBG` reproduces tsc EXACTLY on the repro, and that is what the refusal costs.
Two of `armG`'s 11 are FIXED by (b) (`emitter.ts:1479`, `organizeImports.ts:862` —
a `var` whose initializer is an optional member, inferred `boolean`/`string` here
because the `| undefined` was dropped), and (b) adds six of its own.

**THE SUPPRESSOR IS ONE LINE, AND WHAT IT HIDES IS A LARGE FALSE NEGATIVE THAT HAS
NOTHING TO DO WITH (b).** `canUseTypeEngine`'s

```kotlin
if (sourceType is Type.Union && targetIsPrimitive) {
    val hasNullish = sourceType.types.any { … Null or Undefined … }
    if (!hasNullish) return true
}
```

means **`T | undefined` is silently assignable to `T`** at a variable
DECLARATION, an ASSIGNMENT and a RETURN whenever the target is a primitive. On a
six-line fixture tsc emits 6 rows and we emit 2; the ARGUMENT position and a UNION
target are the two that work. This is a shipped FN, not a (b) artefact, and it is
now queued as **(CHK.63)** with the full row list.

**THE FIVE NARROWING GAPS ARE FIVE MECHANISMS, NOT ONE — SO "close the shared cause
then (b)" WAS NOT AVAILABLE.** Reproduced in a 30-line fixture with EXPLICIT
`| undefined` members, i.e. on the shipped binary with no patch at all:

  1. an `&&` if-condition narrows **neither** operand into the then-branch
     (sourcemap.ts:164/165/166 — three rows), while a single condition does;
  2. `if (x === undefined) continue;` does not narrow the rest of a loop body
     (core.ts:2191, path.ts:585);
  3. an assignment inside the guarded branch (`if (id === undefined) { m.set(t, id = t) }`)
     does not narrow after the `if` (parser.ts:2642);
  4. definite assignment across an if/else (`let i: number|undefined` assigned in
     both arms) does not narrow (checker.ts:35649);
  5. the optional-METHOD shapes — an outer `if (h.a && h.b)` surviving into a nested
     `for`+`if` (moduleNameResolver.ts:824, project.ts:502/528), a three-deep chain
     (moduleNameResolver.ts:2265) and an `&&` chain whose earlier conjunct narrows a
     later one (checker.ts:30269, TS18048).

**AND THE `&&` GAP IS NOT WHERE IT LOOKS.** The FLOW WALK handles `&&` correctly —
`if (a && b) { a.length }` and `if (a && b) { take(a) }` are both right. The gap is
that the RETURN and ASSIGNMENT readers do not consult it for a PRIMITIVE target
(round 784's documented gate, `targetType is Interface|Reference|Object|Union|Intersection`),
so they fall back to `currentLocalTypes`, which the LEGACY if-arm machinery
(`extractNullNarrowing`) fills — and that helper returns ONE `(name, type)` pair and
does not decompose an `&&` at all. Queued as **(CHK.64)**. A declaration with a
primitive target does narrow, which is why the gap is invisible one line away.

**WHAT LANDED, AND WHY IT IS FREE.** `typeCaptureOptionalMemberType` adds the
constituent and then re-runs `getNarrowedTypeForReference`, so `if (o.p)` — and an
`&&` chain in either operand position — still hovers `number`. It is on the FLOW
WALK, not the legacy machinery, which is exactly why the display half can be right
where the checking half would not be. A UNION receiver is decided PER CONSTITUENT
(`memberIsOptionalOnReceiver`): tsc types `({p?: number} | {p: string}).p` as
`string | number | undefined`, and asking `getPropertyOfType` about the union would
make the verdict a function of constituent ORDER (round 916). Every expectation was
read out of **tsc 7.0.2's own LSP** (`scripts/lsp_hover.py`), which is also how the
two RESIDUE rows (`super.<opt>` and an INTERSECTION receiver, both `number` here
against tsc's `number | undefined`) are recorded as divergences rather than as
opinions.

**GATES, per commit, all foreground, one at a time.** Suite **16,281 / 16,283 /
16,286**, 0 failed, 3 skipped (+8/+2/+3, exactly the new subtests) — **no corpus
baseline moved on any of the three**. `cost_gate.py` exit 0 on all three (read from
the gate, not a pipeline), `output.errors` **46**, every counter digit-identical to
(CHK.62)'s standing residual (`typeOfExpr.calls` +1.41%, `globals.lookups` +1.52%,
`globals.misses` +1.74%). `huge_methods --fail-over 0` exit 0, **783** classes, 0
over. **8-profile grid `503774c23b4535130ffdebabef430cf0` on both code commits,
byte-identical PER PROFILE against a parent capture taken in this session** (parent
`Checker.class` md5 `e7963e28`, rebuilt here). `knip` **48** and `jsonrepair` **4**,
EVERY ROW byte-identical against a parent arm rebuilt in this session.
`partition-equivalence` EQUIVALENT all 78 (floors 72 ms [88, 56, 72, 63] and 66 ms
[56, 62, 66, 75] — one draw each, the spread is the harness's).

**`capture-equivalence` IS THE ONE GATE THAT MOVED, AND ITS MOVE IS AN IMPROVEMENT
CLASSIFIED PER SPAN.** `DIVERGED` **1,005 -> 985 -> 968** in 43 of 76,
`types` tracking it, `definitions` **360,414** UNCHANGED, `narrowRendersMoreAny` 0.
Both ARM DIGESTs re-recorded per commit (final `full=2642712547047802314
narrow=6791141519233628706`). The whole delta was enumerated at
`XTSC_CAPEQ_PRINT=200000` and classified per element: **all 38 moved spans are the
alias-display first-wins family** (`ModuleName`/`ModuleExportName`,
`BindingOrAssignmentPattern`/`DestructuringPattern`,
`AccessExpression`/`PropertyAccessExpression | ElementAccessExpression`), i.e.
(INC.27)'s interning-key family shuffled by a changed first-touch order — **not one
of them is an optionality rendering**, and the second commit added **zero** new
divergences while removing 17.

**NINE ABLATION ARMS, ONE MISTAKE EACH (d8 excepted and labelled), EVERY CLASS md5
DISTINCT, EACH `cmp`-DIFFED AGAINST ITS OWN SNAPSHOT AND EACH RESTORE VERIFIED BY
`cmp`.**

| arm | injected mistake | class | RED |
|---|---|---|---|
| d0 | the widening removed entirely | `082f7042` | **4** — the three optional-member rows + the union row |
| d1 | widened but never RE-NARROWED | `e2e6045b` | **4** — uniquely the four guarded controls |
| d2 | the optionality gate dropped (widen every member) | `a21c94f2` | **1** — uniquely the REQUIRED control |
| d3 | a union decided by ALL instead of ANY | `724071bb` | **1** — uniquely the union row |
| d4 | the union asked of ITSELF (round 916's one-constituent answer) | `5d9ca935` | **0 -> 1** — UNPINNED, then fixed |
| d5 | the `super` refusal dropped | `0bd50a31` | **0 — REDUNDANT** |
| d6 | the INTERSECTION refusal dropped | `4eeeb742` | **0 — REDUNDANT** |
| d7 | the already-has-`undefined` early return dropped | `8a099b44` | **0 — REDUNDANT** |
| d8 | MECHANISM PROBE, two mistakes: the `super` refusal AND the `any`-receiver guard | `893d3230` | **0 — mechanism NOT located** |

**d1 IS THE ARM THAT MATTERS AND IT PROVES THE CONFINEMENT.** The queue entry
predicted that adding the constituent would render `number | undefined` inside an
`if (o.p)` guard; d1 is exactly that mistake and it reddens exactly the four
guarded controls and nothing else — so the re-narrowing is load-bearing and the
controls are coverage for it, which is why they are labelled both ways.
**d4 REPEATS LAST ROUND'S c2 EXACTLY**: 0 RED against the optional-first union
fixture, because `getPropertyOfType`'s union arm happens to answer the first
constituent's symbol; the ORDER SIBLING (`{ zzzOrd: string } | { zzzOrd?: number }`)
makes it **1 RED, uniquely that row**. **d5/d6/d7 are REDUNDANT and are now
recorded as such in the KDoc rather than claimed** — each has a fixture exercising
its shape and each stays green with the guard removed, because a lower layer
already declines (`getUnionType` dedupes; `getPropertyOfType` has no Intersection
branch; a `super` receiver resolves no member). d8 was a deliberate two-mistake
probe to name the `super` mechanism and **failed to** — it is reported as a
non-result, not as coverage.

**HOW VACUITY WAS RULED OUT, PER PIN.** The three optional-member pins were run
against the PARENT binary rebuilt in this session (`e7963e28`) with the fixture
already in place: **3 RED**, reading `number` / `string` / `number`. The union pin
and its order sibling redden under d0/d3/d4. The four guarded controls and the
REQUIRED control pass on the parent for the trivial reason that the parent never
widens — they are labelled CONTROLS and are counted as coverage only for d1 and d2,
which redden them uniquely. The two RESIDUE pins (`super`, INTERSECTION) are
labelled residue and counted as coverage for nothing. Every expected string came
from tsc's LSP, not from this project's opinion.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The four-line repro of (b) is a repro of TWO defects and reading it as one
    cost the first hour.** `zzzInst.zzzOptStr` against `string` is MISSING on the
    shipped binary while `zzzInst.zzzOpt` against `string` merely has the wrong
    type — same fixture, same line shape, two different mechanisms (the dropped
    constituent, and the nullish-union gate). The tell was that patch_b made the
    first row DISAPPEAR.
  * **Reproducing the "five narrowing gaps" with EXPLICIT `| undefined` members
    needed no patch and no build.** They are all shipped defects that the gate
    hides; the (b) patch only makes them reachable. Half an hour of building arms
    was avoidable by declaring the member `number | undefined` instead of `number?`.
  * **The `&&` diagnosis was wrong twice before it was right.** "The `&&` narrowing
    is broken" survived one fixture battery (V1-V7, every `&&` form red, every
    single-condition form green) and was refuted by the next (W1/W2 — member access
    and argument, both `&&`-guarded, both correct). What varies is the READER, not
    the condition.
  * **A KDoc that claims a refusal is deliberate is a claim the ablation can
    check**, and here it was wrong three times out of three. The comment now says
    "measured redundant, kept because it states the question this leg is not
    answering" — with the note that `super` would become load-bearing the moment it
    got a carrier, which is precisely what (CHK.61)(a) did for `this` last round.

### Round (CHK.62b)+(CHK.61)(1)+(CHK.61)(a) — the price of the `this`-receiver line fell **1 -> 0** and **it landed**: three defects closed, and the reverted intersection leg was UNSOUND rather than unmasking

**THE HEADLINE: (CHK.61)(a) IS IN, AT `added=0 removed=0` ON ALL EIGHT PROFILES.** Its price
was 6 dashboard rows at (CHK.61), 3 after (CHK.62), **1** after this round's (CHK.62b), and
**0** after this round's (CHK.61)(1). All six were false positives from pre-existing gaps the
`any` was hiding; not one of them was `this`-specific, which is exactly what the refusals
predicted and what took four rounds to prove one gap at a time.

**(CHK.62b) — AN ASSIGNMENT WHOSE RHS IS A `this`-METHOD CALL DID NOT NARROW THE ASSIGNED
REFERENCE, AND IT IS A SHIPPED DEFECT, NOT A PATCH ARTEFACT.** `rhsIsDefinitelyNonNullish`'s
CALL arm reads the callee's return ANNOTATION, so it resolves the callee through
`resolvePropertyMethodDecl`, which TYPES THE RECEIVER and bails at `recvType === anyType`.
The queue recorded it as visible only under `patch_a`; that is true of `build/chk62/g2k`,
whose declared unions all come from `this.zzzFind()` — but the moment the reference's union
comes from anything else the row is on the SHIPPED binary:
`let p = zzzFindFree(); p ??= this.zzzCreate(); return { p }` reported
`p: ZzzProj | undefined` where tsc 7.0.2 is silent. The carrier is confined to that
FLOW-ONLY resolver (both callers are narrowing resolvers, so a resolution can only ever
SUPPRESS), which is what made it separable from (a).

**THE OBSERVABLE IS THE OBJECT-LITERAL MEMBER AND *ONLY* IT, WHICH A WRITE PROBE CANNOT
SEE.** At the same flow point `const q: ZzzProj = p` is SILENT on the broken binary while
`return { p }` is a TS2322 — so CLAUDE.md's standing advice ("assert the narrowed type with a
write probe") is the wrong instrument here, and a write-probe pin would have been VACUOUS.
The value pin that works is a deliberately WRONG target: `return { zzzProj }` against
`{ zzzProj: string }` makes the checker NAME the member type it built —
`{ zzzProj: ZzzProj | undefined; }` before, `{ zzzProj: ZzzProj; }` after, and tsc agrees the
member is `ZzzProj`.

**(CHK.61)(1) — THE REVERTED ACCEPTANCE LEG WAS *UNSOUND*, NOT UNMASKING A DEFECT
ELSEWHERE, AND A CENSUS OF THE NEWLY ACCEPTED PAIRS SAID SO IN ONE RUN.** The prior round
recorded `callHierarchy.ts:199 'parent' does not exist on type 'never'` as "an acceptance in
the relation feeds `typeGuardMemberDisjoint`". Printing every pair the new rule accepts on
the services profile named exactly FOUR, all one shape:
`FunctionExpression & { name: undefined; parent: … }` accepted against `{ name: Identifier }`.
The merge rule is "the intersected member is a subtype of EVERY declaration, so ANY relating
declaration suffices" — sound only when each declaration's type is spelled out INCLUDING its
optionality. We model an optional member as plain `T` ((CHK.61)(b)), so `FunctionExpression`'s
`name?: Identifier` was picked as the relating declaration where the real intersected member
is `undefined`; the next negative type-predicate narrow then subtracted that constituent and
left `never`. Widening the SOURCE declaration to `T | undefined` LOCALLY inside the rule
fixes it — it is a suppression rule, so pessimism about a source member can only decline to
suppress. **Ablation arm b1 is exactly the earlier attempt's mistake and it reddens exactly
one pin.**

**(CHK.61)(a) — LANDED.** `thisReceiverCarrierType` (`currentClassForThis`'s declared
instance type) is consulted at `computeRawTypeOfPropertyAccess` and at
`resolvePropertyMethodDecl`, only where the receiver already typed `any`/`error`. On
`build/chk60/br/b2.ts`: **3 of tsc 7.0.2's 7 rows before, all 7 after, at tsc's own
positions** (5,11) (6,11) (8,20). It also converts `WeakCallableSourceAnchorTest`'s
`this`-member REFUSAL pin into a positive one at tsc's `(2,62)` / `(3,44)` — round 765's law
firing in the useful direction, and the only suite failure the whole round produced.

**AND IT IS A LANGUAGE-SERVICE WIN, WHICH ONLY `capture-equivalence` SEES**: `definitions`
**360,376 -> 360,414**, i.e. go-to-definition on a `this.<member>` caret now RESOLVES. Both
ARM DIGESTs moved (`full=5591703872112101713 narrow=704838071822341252`) with `DIVERGED`
UNCHANGED at 1,005 spans in 43 of 76 (types=1005, definitions=0, moreAny 0) — a FULL-BUILD
fix, so the full-vs-narrow relationship is untouched; (INC.26)'s rule, re-recorded rather
than read as a regression.

**GATES, per commit, all foreground, one at a time.** Suite **16,262 / 16,267 / 16,272 /
16,273**, 0 failed, 3 skipped (+5/+5/+5/+1, exactly the new classes) — **no corpus baseline
moved on any of the four.** `cost_gate.py` PASSES on all (exit read from the gate, not a
pipeline), `output.errors` **46**, every counter within a hundredth of a percent of
(CHK.62)'s standing residual (`typeOfExpr.calls` +1.42%/+1.41%, `globals.lookups` +1.52%,
`globals.misses` +1.75%/+1.74%). `huge_methods --fail-over 0` exit 0, **783** classes, 0
over. **8-profile grid `503774c23b4535130ffdebabef430cf0` on all three code commits,
INCLUDING the one that lands (a) — `added=0 removed=0` on all eight**, verified by a
per-profile `diff` against a parent capture taken in this session. `knip` **48** and
`jsonrepair` **4**, EVERY ROW byte-identical. `partition-equivalence` EQUIVALENT all 78
(floors 56 / 57 / 66 ms, one draw each — the spread is the harness's).

**NINE ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT, EACH `cmp`-DIFFED AGAINST
ITS OWN SNAPSHOT AND EACH RESTORE VERIFIED BY `cmp` PLUS A REBUILT md5.**

| arm | injected mistake | class | RED |
|---|---|---|---|
| a0 | the (CHK.62b) carrier removed from the flow resolver | `77222597` | **3** — the three (CHK.62b) positives |
| a1 | the carrier present but answering null | `4d268baa` | **3** — the same three (a ROUND-927 PAIR with a0) |
| a2 | any resolved `this.m()` treated as non-nullish | `ac22c4c7` | **1** — uniquely the nullable-return row |
| b0 | `intersectionMergedSatisfiesTarget` removed | `ca09ebe3` | **2** — the two gap-1 positives |
| b1 | the source-side `| undefined` dropped (the earlier attempt's exact mistake) | `30b3908a` | **1** — uniquely the optional-vs-`undefined` row |
| b2 | the missing-required-property refusal dropped | `62244808` | **1** — uniquely that row |
| c0 | the (a) carrier removed from `computeRawTypeOfPropertyAccess` | `20b9f09f` | **3** — the three (a) positives |
| c1 | the carrier consulted SECOND instead of first | `103f0a7d` | **0 — REDUNDANT** |
| c2 | the carrier answers for EVERY identifier receiver | `532c340f` | **0 — UNPINNED, then fixed** |

**c1 IS A REDUNDANT GUARD AND c2 WAS A HOLE.** The fallback for a bare `this` is exactly
`anyType`, so consulting the carrier first or second is observationally identical — the KDoc
claiming the order is load-bearing was an over-claim and now says what is. c2 is the
opposite: widening the carrier from `this` to any identifier receiver is plainly wrong (an
`any`-typed parameter in the same body would acquire the class's type) and NOTHING pinned it.
The round added the fixture that does (`zzzM(zzzP: any) { const zzzA: string = zzzP.zzzReq }`)
and re-ran c2 against it: **1 RED, uniquely that row** (class `0ae9232b`). Two rows were also
relabelled from CONTROL to COVERAGE, because an arm reddens each uniquely.

**HOW VACUITY WAS RULED OUT, PER PIN.** Every positive was measured on the PARENT binary,
rebuilt in this session (`Checker.class` md5 `181c293e` — the exact digest (CHK.62) recorded
as its landed binary), through the CLI, against `tools/tsgo-7.0.2/lib/tsc` on byte-identical
source; then shown RED under the arm naming its rule. **One candidate pin was DROPPED for
vacuity before it was written**: the write probe `const zzzQ: ZzzProj = zzzProj` after the
`this`-method assignment is silent on the parent, so it would have measured nothing. Two rows
are labelled CONTROL and are NOT counted as coverage (no arm reddens them): the free-function
RHS, and the merged-member CONTRADICTION direction.

**WHAT DID NOT WORK, AND WHAT SURPRISED ME.**

  * **The first two bisection matrices concluded the wrong axis.** `m8` in the second
    (`let zzzProj: ZzzProj | undefined = zzzCreateFree(); zzzProj ??= this.zzzCreate()`) was
    silent, which read as "the DECLARATION's initializer is the axis" — it is silent because
    `zzzCreateFree(): ZzzProj` is already non-nullish, so the `??=` never mattered. A matrix
    cell that is silent for a TRIVIAL reason is indistinguishable from one that is silent for
    the reason you are testing; the third matrix, which held the declaration fixed, is what
    named the receiver.
  * **A `getNarrowedTypeForReference` debug diagnostic settled in one run what two matrices
    could not.** Printing `raw`/`narrowed` at the object-literal member showed
    `ZzzProj | undefined -> ZzzProj` for the free-function RHS and `-> ZzzProj | undefined`
    for the `this` one, which located the defect in the walk rather than in the reader.
  * **The (CHK.61)(1) revert note's diagnosis was inherited and wrong**, exactly like two of
    (CHK.62)'s four. "An acceptance in the relation feeds `typeGuardMemberDisjoint`" reads as
    a statement about someone else's code; the rule was simply unsound. **Censusing what a
    new rule ACCEPTS is a one-run instrument and it should have been the first move**, not
    the last.
  * **A KDoc-only edit was proven inert rather than re-gated**: `javap -c -p` minus `line N:`
    is byte-identical across it over 1,026,164 lines of disassembly, while the class md5
    moves (`da1d4552` -> `e7963e28`) because the LineNumberTable shifts.

- [ ] **(KIR.LOWER.3) AN ELEMENT ACCESS `a[i]` LOSES THE ELEMENT TYPE, SO EVERY MEMBER
  ACCESS ON THE RESULT GOES THROUGH THE DYNAMIC BAG — MEASURED **30.7 s -> 0.94 s (33x)** ON
  ONE n-BODY BY ADDING ONE ANNOTATION (2026-08-27, the scriptc head-to-head).** `const bi =
  bodies[i]` where `bodies: Particle[]` gives the local a type the lowering reads as the bag,
  so the hot loop compiles to **20 `jsGet` + 9 `jsSet`** per inner iteration — reflection on
  the JVM — while `const bi: Particle = bodies[i]` compiles to 0 dynamic ops on the SAME
  program with the SAME sink. The class already has real `double` fields; only the RECEIVER's
  type is lost, so this is an oracle/lowering gap and not a representation one. **It is the
  largest single KIR performance lever measured to date and no gate here can see it** — the
  sink is identical, the corpus is untouched, `kir-bench.sh` gates output and not shape.
  Instrument: `javap -p -c -cp <out> program.MainKt | grep -c 'jsGet\|jsSet'`, which must be
  0 for a program whose every receiver has a declared class type. Ask whether
  `ErasedTypes`/the oracle answers `JsArray<T>`'s element type at an `ElementAccessExpression`
  at all, or whether `getTypeOfElementAccess` is the (CHK.30) narrowing gap one layer down.
  Pin it as a SHAPE assertion (count the dynamic ops in the emitted bytecode), never as a
  wall figure.

- [ ] **(KIR.LOWER.4) `this.<member> = e` IN A CONSTRUCTOR LOWERS TO `jsSet`, WHICH IS
  REFLECTION ON THE JVM AND **THROWS** ON KOTLIN/NATIVE — AND PARAMETER PROPERTIES ARE
  REFUSED OUTRIGHT, WHERE `docs/kir-design.md` §7 SAYS THEY EXPAND TO A FIELD-ASSIGNMENT
  PROLOGUE (2026-08-27).** Measured: `class Particle { x: number = 0; constructor(x: number)
  { this.x = x } }` emits `jsSet(this, "x", box(x))` beside a real `public double x`, and the
  native binary dies with `JsTypeError: dynamic member write 'x' is not supported on
  Kotlin/Native` inside `<init>`. `constructor(public x: number)` fails the compile
  (`KIR_SUCCESS=false`). This is design-doc contradiction (1) — "`this` types as `any`" —
  never closed on the WRITE side; §7 fixed reads by taking the property's type on the CLASS
  and the same answer is available here. **A class with a constructor is unrunnable on the
  native arm until this lands**, which is why the n-body fixture needed a factory function.

- [ ] **(KIR.NATIVE.2) A TYPESCRIPT PROGRAM THAT DECLARES ITS OWN `function main()` FAILS THE
  NATIVE BUILD WITH "the lowering produced no entry point" (2026-08-27).**
  `KirNativePlugin.kt:149` picks the generated entry with `singleOrNull { name == "main" }`,
  so a user `main` makes it TWO and the `?: error(...)` reports absence where the truth is
  ambiguity. The lowering already renames every generated top-level declaration
  `f<index>_<name>` to avoid serializer collisions — the entry should be found by that
  identity rather than by spelling, and the error message should name the collision.

- [ ] **(BENCH.2) THE KIR BENCH HAS NO CPU AFFINITY AND NO PRINTED PLACEMENT STRATEGY, AND
  ROUND 824 SAYS WHY THAT IS NOT A ONE-LINE FIX: A "SINGLE-THREADED" xtsc RUN CONSUMES
  ~4.17 OF THIS BOX'S 8 CORES BECAUSE `CICompilerCountPerCPU` IS TRUE.** Perry's harness
  (`benchmarks/README.md`, reviewed 2026-08-28) pins with `taskset -c 0` on Linux /
  `taskpolicy -t 0 -l 0` on macOS and **prints which strategy was applied at the top of each
  invocation** — a positive control on the instrument, which is the part worth copying
  whatever the pinning decision turns out to be. **THE TWO HALVES DIVERGE HERE AND MUST BE
  DECIDED SEPARATELY.** The arms that are single-threaded AOT workloads (`nat`, `wasi`, and
  the node/bun arms' own timed loops) are the shape pinning was designed for. The JVM arms
  are NOT: pinning `java` to one core serialises C1/C2 against the compile thread and
  measures a different program — round 824 measured `-XX:CICompilerCount=2` taking a run
  4.20 -> 2.55 cores, i.e. the JIT threads are a real part of the arm. **So the deliverable
  is an OPTION plus a banner, never a silent default**, and the grading is an A/A at fixed
  arms: pinning is worth taking only if the per-arm spread FALLS, measured per arm, and it
  must be reported per arm because it can plausibly fall for `nat` and rise for `kir`.
  **AND IT RESTARTS THE SERIES**: a pinning change is a recipe change, so every pre-change
  `kir-bench` figure quoted in this file becomes incomparable exactly as `BENCH-ROWS-V2` did
  for the dashboard — bank the decision with a paired before/after in ONE round or not at all.

- [ ] **(BENCH.3) `kir-bench.sh` PRINTS ITS TABLE TO A TERMINAL AND NOTHING COMMITS IT, SO
  EVERY KIR NUMBER IN THIS FILE IS HAND-TYPED PROSE — WHICH IS THE EXACT FAILURE MODE FOUND
  IN THE HARNESS WE ARE COPYING FROM (2026-08-28, measured by reading Perry's own artifacts).**
  Perry's README quotes convolution at **Perry 354 ms / Rust 392 ms** and cites
  `benchmarks/honest_bench/REPORT.md` for that row; that report's PROSE says **268 ms /
  Rust 567 ms** on different hardware (M1 8 GB, not the README's M1 Max), its header names
  `v0.5.81` while its own hardware table says `perry 0.5.1355`, and **its tables are broken
  outright — every wall median in all three workloads reads `0.0`/`-0.0` ms with sigma 0.0-0.1,
  and the ratio lines contradict both the table and themselves** (`rust = 18.48x, zig = 1.00x,
  perry = 40.67x` over a column of zeros; the convolution table prints `bun = 1.00x` under a
  bottom line claiming Perry won). A regeneration zeroed the artifact and the hand-written
  prose survived it, unnoticed. **Their ONE self-consistent table is the one generated between
  `<!-- public-node-bun:start -->` markers from a versioned JSON at a named commit — and it is
  also the only one that publishes the rows they LOSE** (`prime_sieve` 28 ms against node's 6,
  `matrix_multiply` 85 against 33). Round 930's law with someone else paying for it.
  **WHAT TO BUILD:** `kir-bench.sh` writes a versioned JSON (arms, per-process samples,
  median/sigma/min/max, the `sink=` verdict PER ROW, the commit, the box, the arm set it
  actually ran) and a generator emits the markdown between markers in `docs/perf/`, losses
  included and labelled. **THE GENERATOR MUST REFUSE ITSELF**: a table whose medians are zero,
  whose ratio column disagrees with its own medians, or whose row count is below the arm count
  is not printed at all — that is precisely the artifact Perry shipped, and rounds 853/873/895
  say a generator that emits quietly where it cannot see is the thing that keeps being wrong
  here. Cheap and separable from (BENCH.2); do this one first.

- [ ] **(BENCH.4) THE TS-TO-NATIVE CATEGORY HAS EXACTLY ONE ARM THAT COULD TAKE OUR FIXTURES
  UNMODIFIED, AND IT IS PERRY ITSELF — NOT AssemblyScript (2026-08-28).** Perry's own peer
  classification (`benchmarks/README.md`) is worth adopting verbatim in our table header:
  **runtime peers** (same input language, same job — for them node/bun, for us tsgo),
  **TS-to-native peers**, and **calibration** (hand-written compiled code, "NOT peers ... they
  show the floor"), with each row labelled so a reader who is not us can tell which is which.
  It also records that of the three TS-to-native candidates, **porffor 0.61.13 and Static
  Hermes were not bench-ready** and only AssemblyScript-with-`json-as` ran their workload to
  completion. **THE RANKING FOR US IS THE OPPOSITE OF THE ONE I FIRST GAVE, AND THE
  EQUIVALENCE GATE IS WHY**: `mitt` and `smol-toml` are plain TypeScript, Perry is MIT and
  installs with `npm install -g @perryts/perry`, so it can compile OUR fixture bytes and print
  OUR `sink=` — an arm the existing gate can hold. AssemblyScript cannot: it is a TS-SYNTAX
  subset with its own semantics, so the fixture would have to be PORTED, and a ported program
  is a different program that the gate can only wave through. **So: Perry as a real arm behind
  its own opt-in flag (the `KIR_BENCH_NATIVE` shape — REFUSE, never skip, when the toolchain
  is absent); AssemblyScript only as CALIBRATION and only with the port's divergences written
  down; and if the fixtures do not compile under either, the item CLOSES with what refused
  them recorded** — Perry has no type checker (SWC parses, LLVM codegens; no conformance
  claim anywhere in its docs), so a refusal there is a fact about its lowering coverage and is
  worth having next to (LIB.4)'s thirteen rungs. Note the scale that sets: Perry's runtime
  completeness is ~97% of Node's own suite across 53 `node:*` modules plus ~50 npm packages.

- [x] **(DOC.1) DONE 2026-08-24 — `CLAUDE.md` 427 -> 320 KB (-25.1%) by MOVING 107 entries
  to the archive, nothing deleted, conservation PROVEN mechanically** (490+728 = 1,218 ->
  383+845 = 1,228; the +10 are entries distilled in place, full text archived). Moved: ~47
  per-walker, ~29 per-diagnostic, ~28 per-instrument perf narratives, and 6 exact
  duplicates (unique clauses folded into the survivor). Distilled 10, led by the INV.4
  check-spine cookbook **13.3 KB -> 1.7 KB**. Protected sections byte-identical (14,078 B,
  `cmp` clean).

- [ ] **(DOC.2) THE REMAINING `CLAUDE.md` LEVER IS DISTILLATION, NOT MOVING — 383 RESIDENT
  ENTRIES AVERAGE 780 BYTES AGAINST THE FILE'S OWN "1-3 LINES" RULE.** (DOC.1) established
  the arithmetic and it is in the header ladder: header 3.6 KB + protocol 14.1 KB + the
  protected (INC.*)/2026-08-2x set 61.8 KB = a **79.5 KB floor before one process trap is
  kept**, so the ~91 KB target cannot be reached by moving. **Only ~84 KB of the 336 KB
  added since 2026-07-26 was archive-assigned narrative** — the rest is in categories the
  rule KEEPS, but at 5-6 lines each where the rule says 1-3.
  **THE MECHANISM IS (DOC.1)'s OWN, ALREADY EXERCISED TEN TIMES AND SAFE**: archive the
  entry's full text, leave a resident form that states the trap/invariant and where to
  look, and drop the fix story. **Nothing is lost, so this is not a judgement call about
  value** — it is the format rule applied to entries that already passed the residency
  test. Target ~200 KB.
  **START WITH THE FREE 11.5 KB (DOC.1) NAMED**: 15 of the 72 date-protected entries are
  the KIR / Kotlin-native BACKEND arc, not the incremental language-service arc whose
  liveness justified the protection. Confirm with the owner whether that arc is parked; if
  so they are archive candidates outright rather than distillation ones.
  **DO NOT distil**: the measurement-protocol laws, the Gradle/daemon/memory traps, the
  narrowing-probe fixture conventions (their loss silently produces VACUOUS pins), or any
  entry whose invariant IS its detail. **Verify as (DOC.1) did** — conservation by exact
  string match, protected sections byte-identical by `cmp`, and a read-through; `git diff
  --stat` proves an edit landed, never that it is correct.
- [x] **(INC.1) A NARROWED DIAGNOSTICS QUERY — LANDED 2026-08-22.**
  `Project.diagnosticsOf(fileNames)`, 4,818 -> 1,107 ms warm, all 78 files of the compiler
  profile agreeing row for row. See the session note; the gate is
  `scripts/partition-equivalence.sh` and the prize was measured first by
  `scripts/incremental-cost.sh`.

- [x] **(INC.2) NARROWING THE INTERACTIVE CAPTURE QUERIES — REFUSED 2026-08-22, AND THE
  REFUSAL IS A MEASUREMENT.** It would have been **3.73x** (full capture median 4,614 ms
  against a narrowed 1,110; warm rotated on `binder.ts`, 7,787 spans: 4,719 vs 1,264).
  `scripts/capture-equivalence.sh` compared **381,666 spans over 76 files**, both arms,
  span for span: **45 spans in 11 files diverge — types 45, definitions 0.**
  **THE SHAPE:** a type reference INSIDE a foreign file's ANONYMOUS OBJECT TYPE LITERAL
  renders `any` under the partition where the whole-program build renders the declared
  type — `(state: { program?: any | undefined; compilerOptions: any })` for
  `{ program?: Program | undefined; compilerOptions: CompilerOptions }`. The outer
  signature survives; it is the literal's MEMBERS that collapse.
  **THE MECHANISM IS FIRST-TOUCH CACHE ORDER, NOT THE PARTITION, AND THE CENSUS PROVES IT
  RATHER THAN ASSUMING IT: in 5 of the 45 the FULL build is the one rendering `any` where
  the narrowed one renders `T`** (`(key: K, valueInNewMap: U) => any` against `=> T`).
  `symbolTypes` persists the first resolution (round 778's order-dependence), and which
  file touches a foreign type first differs between the arms. So the diff is a DETECTOR
  for a defect that is already there — see (INC.5) — and narrowing merely makes it
  observable.
  **IT DOES NOT REACH DIAGNOSTICS, AND THAT WAS MEASURED TOO, BECAUSE IT IS THE QUESTION
  (INC.1) RESTS ON.** A fixture whose error exists only while the literal's member keeps
  its declared type (`const n: number = make().program`, where `make(): { program: Program }`
  lives in a second file and `Program` in a third) is reported IDENTICALLY by the
  partition — `ProjectNarrowFalseNegativeTest`, and the whole-project sweep on the same
  fixture agrees. **Its FIRST shape was vacuous** — an argument-position error
  (`use({ program: 1 })`) this compiler does not report at all, so both arms agreed on an
  empty list and the pin passed while measuring nothing. Its own control caught that,
  which is the reason to write one.
  **SUPERSEDED BY (INC.2b), WHICH LANDED THE NARROWING ON 2026-08-22 AFTER (INC.5) AND
  (INC.6) TOOK THE 45 DIVERGENT SPANS TO 5 WITH THE WRONG-DIRECTION COUNT AT ZERO.** The
  refusal below stands as the reasoning it was, and its premise — 45 spans where a
  narrowed hover renders a worse type — no longer holds. What the refusal bought is the
  two defects it found on the way, and the two gates that now watch the whole thing.
  ORIGINAL VERDICT: **hover, completion, go-to-definition and signature help stay whole-program builds.**
  A tooltip that says `any` where the type is `Program` is a worse defect than a slow
  tooltip, and 45 wrong spans is 45 too many for a query whose only job is to tell the
  truth about a type. Re-run the sweep after (INC.5) and this lands for free — the harness
  and the script are committed, so the re-test is one command.

- [x] **(INC.6) THE LAST 4 WRONG-DIRECTION SPANS ARE GONE — LANDED 2026-08-22.** The
  capture sweep reads **5 divergent spans in 3 of 76 files** out of 381,666, and
  `narrowRendersMoreAny = 0`: the whole user-visible class is closed. The fix is one line
  plus its KDoc in `materializeModifierUtility` — the member copy's type is populated AT
  MINT TIME, ungated. **The diagnosis in the entry below HELD and was sharpened by the
  trace**: the copies being fresh is only half of it, and the half that explains why
  (INC.5)'s pin was green is that `getTypeOfSymbol` RESOLVES the member correctly every
  time and round 778's write gate refuses to RECORD it whenever the ambient context is
  non-empty — which inside a `namespace` body it always is. So (INC.5)'s force-then-read-
  the-cache is a no-op exactly there. Suite 15,640 / 0 / 3, no corpus baseline moved, cost
  gate's drift measured PRE-EXISTING against the un-fixed binary. The 5 REVERSED rows are
  diagnosed in the session note and are three separate display-only mechanisms, in four of
  which the NARROW arm is the better answer. ORIGINAL ENTRY: **THE LAST 4 DIVERGENT SPANS,
  AND THEY ARE WHAT STANDS BETWEEN (INC.2) AND A 3.68x LANGUAGE SERVICE.** After (INC.5) the capture sweep reads **9 divergent spans in
  4 of 76 files — 4 wrong-direction and 5 reversed**, out of 381,666. All 4 of the
  wrong-direction rows are `Readonly<BuilderState>` in `builderState.ts`, and the cause is
  named: `materializeModifierUtility` mints FRESH copy symbols on every materialization,
  so warming one dies with the instance, where `Pick`/`Omit` cleared precisely because
  `materializeMemberSetUtility` reuses the SOURCE symbols and their ids are stable. The fix
  is to populate `symbolTypes[copy.id]` AT MINT TIME in the materializer — which
  `getTypeFromTypeLiteral` and `getTypeFromMappedType` already do — and that is **not
  capture-scoped**: it would put diagnostic messages in play, so it needs the corpus as its
  gate rather than the sweep alone. (INC.5) deliberately stopped short of it.
  **The 5 REVERSED rows are a different family and may not be a defect at all**: 2 in
  `tsbuildPublic.ts` where the WHOLE-PROGRAM arm renders `(key: K, valueInNewMap: U) => any`
  and the narrowed one the better `=> T`, 2 in `watch.ts` (overload-set content), 1 in
  `watchPublic.ts` rendering a signature twice. None is a lost member resolution. Diagnose
  them before assuming they are one.

- [x] **(INC.2b) LANDED 2026-08-22, owner directive — the caret-scoped capture queries
  are narrowed.** Hover, go-to-definition, completion, signature help, the semantic sweep
  and document highlights hand the compiler the queried BUFFER as its check partition;
  `referencesAt` and the rename sweep do not, because their claim is program-wide.
  Measured `quickInfoAt` **5,004 -> 1,015 ms** end to end with three flat controls, and
  **4,581 -> 979 ms (4.68x)** within one process on `binder.ts`. The partition is DERIVED
  from the request's spans, which is what makes the pins discriminate. See the session
  note for the second gate this needed (`scripts/capture-channel-equivalence.sh`, for the
  three channels the old one never covered) and for the five display mechanisms it found.
  ORIGINAL ENTRY: **OWNER DECISION: LAND THE CAPTURE NARROWING NOW, OR AFTER (INC.6)?** The
  refusal recorded above was written against 45 divergent spans; after (INC.6) it is
  **ZERO** in the user-visible direction — `narrowRendersMoreAny = 0` over 381,666 spans —
  against **5.26x** measured this round on every hover, completion, go-to-definition and
  signature help. **What is left is 5 spans in 3 files, all display-only and all diagnosed in
  (INC.6)'s session note: 2 where the narrow arm renders the ALIAS name (`Intl.LocalesArgument`)
  and the full arm its expanded body, 2 where the FULL arm renders a generic interface
  member's return as `any` where the narrow renders the declared `T`, and 1 where the narrow
  arm renders an intersection member as the redundant `X & X`. In 4 of the 5 the narrow arm
  is the better answer.** So the correctness argument for waiting has inverted: the
  whole-program arm is now the one rendering a worse type more often, and the wiring is a
  one-line change per call site. **Not decided
  autonomously: it trades a measured correctness regression against a measured latency win,
  which is the owner's call.** Everything needed to execute either way is committed — the
  gate, the census and the call sites are named in (INC.2).

- [ ] **(INC.8) THE TWO DISPLAY MECHANISMS (INC.2b)'s SECOND GATE FOUND, AND NEITHER IS A
  PARTITION DEFECT.** `scripts/capture-channel-equivalence.sh` reads 286 divergent rows of
  21,507 in five mechanisms; three are worth closing and none can be closed on the capture
  path, because the renderer is shared with the diagnostics (the (INC.5) rule: never
  `typeToString`, ~13k baselines).
  (a) **x167 — a member's own type parameter renders `<K>` under one arm and
  `<K extends any>` under the other, and NEITHER renders the declared constraint**
  (`shouldAssertFunction<K extends keyof typeof assertionCache>`). That is a defect in BOTH
  arms, like (INC.6)'s `Readonly<T>`: the sweep only made it visible.
  **DIAGNOSED ONE LEVEL DEEPER 2026-08-23 by (INC.19), which also REFUTED the obvious
  guess.** It is NOT (INC.19)'s first-touch freeze: the fix that took the replay's lost
  constraints from 8 files to 5 left these 167 rows **byte-identical** (the whole
  channel unchanged at 286 spans / 49 files). A probe on the shape reads `TPWRITE
  name=K was=any now=any` — the constraint is **already `any` before
  `checkTypeArgumentConstraints` runs**, so nothing downstream can be blamed. It is a
  **namespace-local type alias failing to resolve in constraint position** — a NAME
  RESOLUTION defect, not an ordering one. Start there, not at the renderer.
  (b) **x116 — an alias's expansion carries `| undefined` TWICE**
  (`string | Locale | readonly (string | Locale)[] | undefined | undefined`). Two defects in
  one row: the duplication, and the fact that a first-touch `aliasDisplayMap` registration
  decides whether the alias name or its body is printed. tsc prints the alias.
  (c) **x1 — a signature parameter renders `any` under the narrowed arm.** The ONLY row in
  either channel where narrowing produces the answer a user would call wrong. Same family
  as (b); worth a trace before (a) or (b), because it is the one with a cost today.
  Not worth a round on its own; fold into whichever round next touches the display of a
  signature or an alias.

- [x] **(INC.3) THE FLOOR IS DECOMPOSED — step 1 DONE 2026-08-22, and it inverted its own
  lever order.** 1,219 ms on the compiler profile: **tail walkers 806.7 (66.2%)**, `init:*`
  setup 112.2 (9.2%), **BIND 240.6 (19.7%)**, crawl 27.4 (2.2%), `checkSpine` **0.1 ms**,
  residue 3.1 (the partition closes at 99.7%). `scripts/floor-decomposition.sh` is the
  instrument; the session note carries the four refuted beliefs — bind is not 515 ms (that
  is a per-WORKER contended term), the crawl is not 138 ms (parses are fully cached),
  `init:buildFileLocalTypeMaps` is not 3.56% (1.4%), and the two never-warming
  whole-program regex passes are already gone (0.44 ms). **What it leaves is (INC.7), a
  bigger lever than either of the two this entry used to rank first.**

- [x] **(INC.9) THE FLOOR RE-DECOMPOSED AND ITS LARGEST MECHANISM DEFERRED — LANDED
  2026-08-22.** Re-measured rather than scaled (the (INC.3) table was taken at a 1,219 ms
  floor; 68 gated walkers later it is a different table): of a ~523 ms floor, CHECK — the
  ~190 surviving `init` passes — is **304.2 ms (58.2%)**, BIND **197.8 (37.8%)**, crawl +
  config + imports + post 18.4 (3.5%). Bind is NOT the largest component, but it holds the
  largest single MECHANISM: `FlowGraphBuilder.build` at **126.1 ms = 24.1% of everything a
  narrowed query costs**, against a pass table whose biggest row is 66 ms.
  **`BinderResult.flowGraph` now builds on first ask** — floor **514 -> 378 ms**, narrowed
  query median **542 -> 422**, ratio at the median file **9.70x -> 12.43x**, and
  `partition-equivalence.sh` EQUIVALENT on all 78 files. This is exactly the candidate
  `docs/perf/warm-flow-graph-attribution.md` § 9.3 priced at **0.3%** and refused — a
  correct number about a FULL build, where every checked file's spine setup asks for its
  graph; under a partition the same rule reaches 122 of 123 files. **REFUSED in the same
  round, with the measurement: a cross-query BIND CACHE.** All of bind is now 72 ms of a
  378 ms floor, so the ceiling is 19%, and against it every `BinderResult` from one
  `Binder` SHARES its `(pos, end)`-keyed `nodeToSymbol`/`moduleInstanceStates` maps (they
  are the binder's fields, accumulated across files, and those keys collide across files),
  while `mergeSingleSymbol` adopts binder-owned symbols and `declarations.addAll` is not
  idempotent. Large, silent-failure-shaped, for 72 ms.

- [x] **(INC.10) ONE OF THE TWO PROGRAM-WIDE SETUP PASSES IS GONE; THE OTHER IS
  REFUSED WITH A THREE-POINT MEASUREMENT.** `init:trackAllImportReferences`
  (**29.44 ms**) is EMIT-ONLY work — its product `referencedAliases` has one
  reader, `isReferencedAliasDeclaration`, which has one caller, one line of
  `Transformer` reached only by `import x = require(…)` under `module: preserve`
  — so it now runs on that first ask and a `--noEmit` build performs it **0**
  times (was one per file per checker, i.e. N under `CheckerPool`). Floor pass
  table **305.3 -> 274.8 ms**, narrowed query median **422 -> 402**, ratio
  **12.43x -> 12.61x**, and the banked ms EXCEEDS the row (30.5 vs 29.44) because
  this walk resolves nothing, so the (INC.7) relocation discount has nothing to
  describe. **`init:buildFileLocalTypeMaps` (66 ms) IS REFUSED, and it was built
  before it was refused**: the deferral works and is cheap (78 -> 3 maps built on
  the floor arm, row 66.07 -> 0.01, query median 349, ratio **14.17x**,
  `partition-equivalence` EQUIVALENT, cost gate and corpus unmoved) and it moves
  the CAPTURE channel from **5 divergent spans to 2,722 in 46 of 76 files**. The
  pass's real product is not the 4,161 entries round 829 censused but the
  whole-program FIRST-TOUCH ORDER for type interning and `aliasDisplayMap`; keep
  the `TypeAlias` symbols eager and it is 6.81 ms / 462 spans, keep the whole
  DECLARATION branch eager and it is **64.94 ms / 5 spans** — i.e. the deferrable
  part is **1.13 ms of 66**. Do NOT re-open it from round 829's read-count
  census: read-ness of the ENTRY is the wrong question.

- [x] **(INC.12) THE WARM PROGRAM IS PRICED, AND STAGE 1 LANDED 2026-08-22.**
  **(P1) — a second query with the program UNCHANGED — is worth the WHOLE ~345 ms
  floor** (config+crawl+imports ~12, BIND 73-88, the ~190 program-wide `init` passes
  252-254), against a queried file's own checking of 47 ms at the median file.
  **(P2) — a query after ONE buffer changed — measured IDENTICAL to (P1)**
  (`diagnosticsOf` after editing the queried file 2,001 ms against 1,999 unedited),
  because outside the content-keyed parse cache there was no cross-query reuse at all.
  **LANDED: `Project.captures`** — a capture build memoized on its REQUEST, two entries,
  dropped by every edit: `quickInfoAt` then `definitionsAt` at one caret is ONE build
  (506 -> 0), `documentHighlightsAt` at every later caret in an unchanged buffer is zero
  builds (592 -> 19, the residue being the per-caret grouping), a repeated hover
  1,933 -> 0. Three ablations, each reddening a different pin set.
  `scripts/warm-program-cost.sh` is the instrument; `docs/language-service.md` §§ 13-14
  carry the table. **REFUSED with the measurement**: reusing the BIND (73-88 ms = 20% of
  a median query — not refused by (INC.9)'s per-file argument, but it needs a shape gate
  reusing the checker's own merge predicate plus a full-vs-reused differential sweep,
  see (INC.13)); and reusing the CHECKER (252-254 ms = 63%, the largest thing left, and
  the one that makes WHICH QUERY RAN FIRST observable — see (INC.14)).

- [x] **(INC.13) STAGE 2 LANDED 2026-08-23 — THE QUESTION A HOVER ASKS IS THE
  BUFFER'S, NOT THE CARET'S.** `Project.captureAround` names
  `SourceIndex.occurrenceNodes()` — deliberately `documentHighlightsAt`'s own
  population — so `quickInfoAt`, `definitionsAt`, `semanticsAt`/`fileSemantics` and
  highlights are **ONE build per buffer between them**. A second caret in `checker.ts`
  **2,142 -> 73 ms**, in `binder.ts` **481 -> 2**, `fileSemantics` after a hover
  **575 -> 17**; the FIRST query in a buffer pays for it, **+27% on `binder.ts`,
  +65% on `checker.ts`**, i.e. break-even at the second caret. **The oracle was built
  first and needed no baseline** (`scripts/caret-vs-file-capture.sh`, 904 sampled
  spans in 76 files: **EQUIVALENT**, and the widening prices at **+17 ms at the
  median file**). It does NOT widen for a caret on a node that is no occurrence — a
  call expression, a literal, a `this` — because a file-wide request would not carry
  it and an absent capture renders nothing with no error anywhere. Three ablations;
  A3 was BLIND until the fixture grew a member-name literal. **The 34x batching ratio
  `docs/language-service.md` advertised to hosts is GONE** — batching a buffer is now
  a convenience, not a cost decision.

- [x] **(INC.15) REUSING THE BIND FOR AN UNCHANGED PROGRAM — REFUSED 2026-08-23,
  AND THE REFUSAL IS A RE-PRICING, NOT A SOUNDNESS FINDING.** The mechanism checks
  out: on today's binary `--bindMutationCheck` reads **`binder Symbols checked
  15580, changed 0`** over a population that reaches transitively through
  `locals` + `nodeToSymbol` + every `members`/`exports` table, in the SAME run as
  `mergeSingleSymbol: adopts 406, mutates 175 (164 reaching an adopted symbol)` —
  every one of those 175 mutating merges lands on a LIB symbol, which is in no
  program `BinderResult`. `mergeModuleAugmentations` was read line by line as the
  queue entry asked: its four writes are `globals[name] = augSymbol` (a same-value
  put), `flags or …` (idempotent), `declarations.add` guarded by `if (decl !in …)`,
  and `mergeSymbolTable` into an `exports` table — and only the LAST of those is
  non-idempotent, because `mergeSingleSymbol`'s existing-name branch does a bare
  `merged.declarations.addAll(symbol.declarations)`. On this program it never fires
  against binder-owned state, which is what the zero says.
  **WHAT REFUSES IT IS THE POPULATION, RE-PRICED AGAINST (INC.13)'s FLOOR.** Bind is
  **66–74 ms of a 359–407 ms floor (18.4%)**, and of that **69 of 74 ms is
  `bindLexicalScopes`**. Against a QUERY it is 12.8% of `diagnosticsOf(binder.ts)`
  (547 ms), **10.7%** of a first hover in that buffer (655 ms), **3.1%** of a query
  about `checker.ts` (2,232 ms), and **2.75% of the whole 15-query editor sequence
  `warm-program-cost.sh` drives** (~10.2 s). And the eligible population is
  "the program is UNCHANGED since the previous build", which **excludes the first
  query after an edit — the error-reporting query the owner directive names — where
  it is worth exactly 0**.
  **AND IT IS THE WRONG ORDER: (INC.14) SUBSUMES IT BY CONSTRUCTION.** A reused
  `Checker` carries its own bind, so bind reuse is 20% of a floor that checker reuse
  removes 100% of, and the plumbing (a content-keyed cache threaded `Project` ->
  `ProjectCompiler` -> `compileParsed` -> `compileParsedCore` -> `cpcBindAndCheck`)
  would be thrown away by it. A third fact against doing it first: the checker's own
  merge predicate is `moduleLocalContributesGlobally`, which reads `umdGlobalNames`
  and `mergeSharedKeepNames` — both computed INSIDE `Checker`'s init — so the shape
  gate the queue entry demands can only be evaluated AFTER a build. The design is
  therefore necessarily "build once fresh, reuse only if that build reported clean",
  and the first query of a session never benefits either.
  **WHAT SURVIVES AS A LEAD, and it is bigger and better shaped**: `bindLexicalScopes`
  is **93% of the bind** and the INV.2(c) tables it builds are read per-FILE, so
  (INC.9)'s exact deferral template applies — see (INC.16).

- [x] **(INC.16) LANDED 2026-08-23 — THE INV.2(c) TABLES BUILD ON FIRST ASK AND A
  NARROWED QUERY IS 20.5% FASTER.** `bindLexicalScopes` was 93% of the bind and, after
  (INC.7) batch 4 and (INC.11), the largest single remaining mechanism in the floor.
  **Scope tables built on a floor build 123 -> 3; `FrontEnd` bind 70 -> 6 ms; floor
  median 333 -> 286 ms; narrowed-query median over all 78 files 346 -> 275 ms
  (−20.5%), the SUM 29,378 -> 23,909 ms.** `partition-equivalence.sh`'s own recipe
  reads floor 248 / median 313 / ratio **15.66x**.
  **THE BLOCKER WAS SERVED BY A PROJECTION, NOT BY GATING.** A `forcedBy` census
  confirms `init:computeAllEnumValues` was the SOLE forcer of all 78 program files.
  `declareLexical`'s two mint sites are NOT symmetric — the alias half wants a NAME
  (the binder hands it over), the enum half wants the scope-space SYMBOL (`compute-
  EnumSymbolValues` is id-keyed) — so only an `enum` in a fresh scope forces a build,
  and the projection costs two int compares per node on a walk that already runs and
  is content-cached. Refinement measured: 67 of 78 skipped, then 69, then **75**.
  **HAZARD (a) DID NOT FIRE AND WAS REMOVED ANYWAY.** An ID-FREE FINGERPRINT of every
  file's tables is IDENTICAL on all 78 across three runs — but that bounds frequency,
  not existence, so `Binder.lexOwnerSymbols` (a per-file `nodeId -> Symbol` table)
  replaces both reads of the shared `(pos,end)`-keyed `nodeToSymbol`. Order-independence
  is now structural; arm a4 reddens a pin built from two same-length sources whose
  namespaces collide on a node key.
  **LEFT OPEN (~20 ms)**: 3 files still force on the floor — those with a genuinely
  block-scoped `enum`, where the census needs the SYMBOL and not a name. Serving them
  means minting that symbol outside the scope walk, a larger change than this round's.
  The 45 real-lib `.d.ts` binds are forced by nobody and are worth only ~2 ms.
- [x] **(INC.14) A `Checker` NOW ANSWERS A WHOLE WORKING SET — LANDED 2026-08-23 as
  `Project.prepare(files)`, plus a partition-keyed `diagnosticsOf` memo beside it.**
  252-254 ms of every query's floor is the ~190 program-wide `init` passes, and the
  census said a checker shared by k queries answers all k exactly as k fresh ones do.
  **The refactor the entry called for was not needed, and the census's own model is
  why**: a checker asked a k-th query IS a checker whose partition is those k files,
  and that arrangement is expressible with no checker surgery — hand `recheckOnly` the
  working set once and capture all of it in the one walk. `prepare` is the census's
  SHARED arm made public.
  **THE ORDER GAP THE ENTRY NAMED IS CLOSED FIRST, AND IT CLOSED CLEANER THAN PROGRAM
  ORDER.** `checker-reuse-differential.sh` grew an `editor` arm — a deterministic
  shuffled query SEQUENCE with revisits, chunked into groups, compared POSITION BY
  POSITION, with the COLD arm run over the same sequence so "is the reference itself
  order-dependent?" is a control (`coldSelfDiverged`, which REFUSES the run) and not an
  assumption. 101 queries over 76 files, 25 revisits, **1,070,012 compared rows per
  run**: **0 divergent rows at k=3 (2.16x) and k=8 (3.88x)**, **1 at k=26 (5.18x)** and
  that one is byte for byte the row program order already found (`watchPublic.ts@24148`,
  the COLD arm inventing `X & X`), already inside `capture-equivalence.sh`'s 5-span
  baseline. `coldSelfDiverged = sharedSelfDiverged = 0` in all three — a revisited file
  is answered identically by a fresh checker AND by a reused one.
  **MEASURED, six mid-sized buffers (55-83 KB, 415 KB together; deliberately not
  `checker.ts`, whose 1.65 s of own checking would bury the floor), three rotations,
  replicated in a second run**: 18 semantic queries **5,230 -> 737 ms and 4,997 -> 704
  (7.1x both)**; six per-buffer `diagnosticsOf` **2,338 -> 526 and 2,376 -> 539**, with
  every re-ask **0**. The existing 15-query block is a CONTROL and did not move.
  **What a held prepared check costs, with a control rather than as an absolute: heap
  163 -> 167 MB, identical to the MB in all six rotations — ~4 MB for that working set.**
  Bound: ONE prepared check, replaced by the next `prepare`, dropped by any edit.
  **Three rules, each with its pin**: the prepared slot is SEPARATE from the two-entry
  capture LRU (an ordinary hover cannot evict what a prepare earned); serving is decided
  by CONTAINMENT of the asked spans against the prepared REQUEST's own spans, never by
  file membership (an answer never asked for is ABSENT, and a hover served from a check
  that did not carry its span renders nothing, silently); and a prepared check may NOT
  answer `diagnostics`/`diagnosticsOf`, because a capture build types nodes the checker
  had no reason to type. **Seven ablations, seven discriminating, each with its own RED
  set** — the first round this session with no arm recorded as a control.
  **REFUSED with its arithmetic: making the working set AUTOMATIC.** Growing the
  partition to `{queried} ∪ {recently queried}` on every miss costs `k·floor +
  k(k+1)/2·perFile` against a cold `k·floor + k·perFile`, i.e. a LOSS at every k with
  the floor at 342-365 ms and a median file at 31-47 ms; bounding the growth at B makes
  every miss `(B−1)·perFile` dearer (+42% at B=4 on a median file, far worse on
  `checker.ts`). A host knows its open buffers and this layer does not.
  `docs/language-service.md` §§ 3, 3a, 13, 14.

- [x] **(INC.17) THE RE-ENTRANT CHECKER — BUILT, MEASURED AT 3.06x, AND **REFUSED AS A
  DEFAULT PATH** 2026-08-23. STEP 1 (THE CENSUS) STANDS.** `prepare` collects the floor for
  files a HOST NAMED; a query about a file it did not name still pays the whole
  342-365 ms. Measured with `scripts/partition-census.sh` (a RUNTIME classification —
  `checkedResults` is a getter recording `PassTiming.currentPass`, so it cannot be
  wrong about who read it — six draws, three partition shapes, tsc's own 78 sources):

  | bucket | rows | floor ms | one-file ms |
  |---|---:|---:|---:|
  | partition-INVARIANT | **211** | **350.89** | 375.44 |
  | partition-DEPENDENT | **205** | **15.59** | 55.05 |
  | total | 416 | 366.47 | 430.49 |

  **The prize is 95.7% of the floor and the replay's own fixed cost is 0.69 ms** —
  204 of the 205 dependent passes cost that BETWEEN them, because 201 read the
  partition exactly once (`for (result in checkedResults)` and nothing else). The
  205th, `checkSubsequentVarTypes`, is 14.90 ms with an EMPTY partition: a MIXED pass
  doing program-wide work outside its partition loop, and splitting it is the whole
  difference between 15.6 and 0.7.
  **The model is SMALLER than (INC.14) priced.** No diagnostics prefix has to be
  reset: a program-wide pass iterates `binderResults`, so it ALREADY emitted the newly
  asked file's rows in the first build and `getDiagnostics()` merely filtered them out
  at the end. A replay re-runs the 205 with the new partition and re-filters.
  **WHAT BLOCKS IT IS THE INSTRUMENT.** On the tsc profile the full build's 46
  diagnostics are netted by exactly ONE pass (`checkSpine`; the new signed-delta
  census reads 46 against the build's own 46, its positive control), so
  `partition-equivalence.sh` — the designated detector — compares an essentially EMPTY
  population, and the other seven profiles are the same codebase. A replay that
  produced nothing from 204 of the 205 passes would be invisible to every gate here.
  **And the classification is not yet the one soundness needs**: it measures *reads the
  partition*, where the replay needs *its OUTPUT depends on the partition*, and the two
  come apart at every spine-produces / program-wide-pass-consumes pair.
  **UNBLOCKED 2026-08-23 by (INC.18)**, which re-armed the gate — 78 netting passes and
  72 of 76 files carrying a row, against the profile's 1 and 5 — and PROVED it can
  fail: a partition-dependent walker made silent under a narrow partition reddens the
  sensitivity arm while the realism arm stays green (arms a1/a2). **Two obligations
  survive.** The classification still measures *reads the partition* where soundness
  needs *its OUTPUT depends on the partition*; and (INC.18)'s arm a3 shows the one
  round-609 collector it tried is invisible to a DIAGNOSTICS gate in BOTH arms (it is
  `capture-equivalence.sh`'s to own), so a replay must be graded on both sweeps.

  **STEP 2 IS BUILT AND IT IS REFUSED. THE PRIZE IS REAL: 3.06x** on tsc's own 78
  sources — `replay=12572 ms` against `freshBuilds=38498 ms` over 75 questions.
  The mechanism is in the tree and OPT-IN by construction (`Recheck.kt`,
  `Checker.recheckAdditionalFiles`, `build(recheckHolder = ...)`); nothing in a
  shipped path passes a holder and `Project` does not know the type exists.
  **WHAT REFUSES IT is the second sweep, exactly as (INC.18)'s arm a3 predicted.**
  `scripts/replay-differential.sh` reads
  `compared: files=75 diagnosticRows=46 filesCarryingDiagnostics=5 typeSpans=373879
  definitionSpans=352713` and then **`DIVERGED: 8 of 75 file(s)`** — with the
  DIAGNOSTICS half completely untouched. The shape is a **lost type-parameter
  constraint**: the replay renders `<T extends Node, U>` where a fresh build renders
  `<T extends Node, U extends T>`. A wrong hover is worse than a slow one, and
  (INC.2) set the precedent by refusing capture narrowing over 45 divergent spans;
  8 divergent FILES is far past it.
  **WHAT LANDED ANYWAY**, so (INC.19) starts from an oracle rather than rebuilding
  one: the mechanism marked EXPERIMENTAL at every entry point, `ProjectRecheckTest`
  pinning what it ACTUALLY does (diagnostics equivalence, the build-count receipt,
  the behaviour-free arming — and deliberately NOT capture equivalence, which would
  be a false pin), `scripts/replay-differential.sh` + `ReplayDifferentialMain`, and
  the `checkSubsequentVarTypes` split the census demanded (15.59 -> 0.69 ms of
  replay cost), pinned on both sides by `PartitionCensusHookTest`.
  **THE ATTRIBUTION ARM THAT DID NOT WORK, so nobody re-runs it:** re-entering ALL
  passes over **7** targets burned **53 minutes of CPU without finishing**, against
  ~50 s of total compute for the 205-pass replay over **75** targets — ~100x, the
  signature of a pass that appends to a side table or re-emits per replay. Killed,
  not completed. (INC.19)'s instrument is a BISECTION, not that arm.

- [ ] **(INC.19) THE LOST CONSTRAINT IS FIXED AND IT WAS NEVER A REPLAY DEFECT —
  8 -> 5 DIVERGING FILES, AND THE SURVIVORS ARE A DIFFERENT CLASS (2026-08-23).**
  The queue entry this replaces said "the replay SET is too small — bisect it".
  The instrument was built (`aca8a60f`) and REFUTED that: three causes were
  measured, and the dominant one is reachable by no replay-set change at all.
  **(c), THE DOMINANT ONE — FIXED (`7b1cc323`).** `Type.TypeParam.constraint` is
  interned per node and WRITE-ONCE, and `checkConstraintsInStatements` resolved it
  BEFORE installing the type-parameter scope, so `U extends T` resolved its sibling
  against the outer scope, answered `errorType`, and froze. `checkSpine` (row 28,
  partition-scoped) races `checkTypeArgumentConstraints` (row 261, program-wide) for
  the field; unpartitioned, `checkSpine` always wins, which is why all ~13k corpus
  baselines are blind. Two sites hoisted, and the third — `withDeclTypeParamScope` —
  **must NOT be hoisted**: a self-referential alias (`type Shared<I, D extends
  Shared<I, D>>`) then recurses without bound and the `init` guard reports a
  spurious TS2589. It got the write-once guard instead, which it lacked, so it can
  no longer CLOBBER a correct constraint. Pinned by `ProjectRecheckConstraintTest`,
  verified 2-of-3 RED against HEAD with its control green.
  **(a) REAL BUT SMALL, NOT LANDED.** `init:computeAllEnumValues` is classified
  partition-INVARIANT and yet repairs `program.ts` when added to the replay set
  (replicated) — its row is a block-scoped `const enum`, the B83.5 population. Worth
  landing only once the replay ships.
  **(b) REAL, AND IT BOUNDS THE WHOLE DIRECTION.** `init:wireGlobalArrayTypes` does
  not TERMINATE when replayed; `init:mergeLibGlobals` makes the answer strictly
  WORSE (+1 file). So the replay set is a PER-PASS question, never a superset or
  subset one, and each addition must be measured.
  **WHAT IS LEFT: 5 files, 23 spans of 373,879, and no lost constraint among them.**
  They are lost generic INFERENCE — `Connection[][]` -> `any[][]`, `Map<string,
  SeenPackageName>` -> `Map<any, any>`, `(key: K, valueInNewMap: U) => T` ->
  `… => any`. Diagnose that class before touching the replay set again.
  **THE INSTRUMENT IS COMMITTED AND RESUMABLE**: `scripts/replay-bisect.sh`
  (`dump`/`sweep`/`try`/`narrow`), `PassTiming.replayExtraPasses`, and a RUN-TIME
  pass universe — a source grep of `pass("…"` reads **480** names against the
  dispatch's **417**, so a grep-derived bisection could never have closed. 19 of 210
  candidates are swept; `build/bench/replay-bisect/rest.txt` holds the other 191.
  **THREE SITES STILL RESOLVE A CONSTRAINT OUTSIDE ITS SIBLINGS' SCOPE** and are
  reported, not fixed: `Checker.kt:111069` (fresh non-interned params, so it cannot
  corrupt the cache), `Checker.kt:137404` (**inside `typeParamInternCache.getOrPut`**,
  i.e. a first-touch freeze BY CONSTRUCTION — the hardest, since the factory runs
  before any scope exists), and `Checker.kt:139240`.
  **DO NOT** wire the recheck into `Project` before this closes; `Recheck.kt`'s
  banner says so and `ProjectRecheckTest` pins that nothing reaches it by default.

- [x] **(INC.18) THE PARTITION GATE WAS VACUOUS ON EVERY PROFILE THIS REPO HAS —
  THE FIXTURE THAT RE-ARMS IT LANDED 2026-08-23, AND IT IS PROVEN ABLE TO FAIL.**
  The receipt is a COUNT — how many DISTINCT passes net a diagnostic, off
  `PassTiming.diagNetByPass` — and the contrast is the finding:

  | project | files | diagnostics | files carrying a row | passes netting one |
  |---|---:|---:|---:|---:|
  | `build/bench/tsc-project-*` | 78 | 46 | **5** | **1** (`checkSpine`) |
  | `test-fixtures/partition-gate` | 71 | 175 | **70** | **78** |

  So 73 of 78 per-file comparisons on the arm that has always run are empty against
  empty, and all eight dashboard profiles are that same codebase.
  **`scripts/partition-gate.sh` runs BOTH arms** — realism unchanged, sensitivity
  added — and the sensitivity arm REFUSES below its floors (40 netting passes, 40
  files carrying a row) rather than printing green.
  **`scripts/partition-gate-ablate.sh` is the proof it can fail**, one injected
  mistake at a time, with a both-GREEN control (`checkCloduleTest2`, a pass netting
  on neither project) and a both-RED control (`checkSpine`) that make the other arms
  attributable. See the session note for the table.
  **WHY IT IS HAND-WRITTEN.** `PassDiagMineMain` mined all 6,451 conformance cases
  for per-pass attribution (2,802 netting, **241 distinct passes**) and
  `scripts/partition_fixture_compose.py` greedy-covers that record — but past ~24
  files each case adds **exactly one** new pass, i.e. the tail walkers are one-shape
  walkers, and this repo does not vendor TypeScript source. The miner says WHICH
  shapes to write; the files are written from scratch.
  **IT RETRO-PRICES LANDED WORK**: (INC.7)'s 68 gated walkers and (INC.9)'s deferral
  were profile-green for a reason that says nothing — only the corpus, which has no
  partition, stood behind them. Unmeasured on this axis, not wrong, and re-runnable.
  `docs/partition-gate-sensitivity.md`.

- [x] **(INC.11) THE 66 ms IS REFUSED 2026-08-23, AND ITS PREMISE IS MEASURED FALSE —
  PART OF THAT COST BUYS *RESOLUTIONS*, NOT A FIRST-TOUCH ORDER.** The item said the
  65 ms buys only a program-wide first-touch ORDER for interning and `aliasDisplayMap`.
  A three-phase re-measurable arm (`FltmDefer` / `XTSC_FLTM_EAGER`, default = shipped,
  pinned inert) says otherwise: fully deferred is **1,665 divergent capture spans in 47
  files with `narrowRendersMoreAny = 321`** — 321 resolutions LOST TO `any`, which is
  not a naming question and cannot be fixed by any display change. (Its numbers beat
  (INC.10)'s 2,722 / 46 by 1.6x, and `TYPEALIAS`-only is 137 / 10 against 462 / 18,
  because an ask-triggered whole-file build still builds every file's map in check
  order on a FULL build.) **Do not re-open this as a display problem.**
  **SUB-PROBLEM (b) IS CLASSIFIED AND THE ITEM'S HYPOTHESIS ABOUT IT IS REFUTED**: the
  residual rows are NOT two `Type` instances but **ONE instance carrying two competing
  names**. A `Extract<ClassLikeDeclaration, Pick<T, "kind">>` whose conditional cannot
  decide (free `T`) answers its own CHECK TYPE — the interned union — and the generic
  site then wrote `aliasDisplayMap[union.id] = ("Extract", args)` unconditionally.
  **That was a SHIPPED, whole-program hover defect** (an unbound `T` in a tooltip) and
  is FIXED — an instantiation that returns one of its own arguments unchanged no longer
  registers a name for it. `AliasDisplayIdentityTest` pins it and needs `@useRealLibs`
  to reach the mechanism at all.
  **WHAT REMAINS, AND IT IS A CHANGE OF KEY, NOT OF POLICY**: the (a) half — 302 spans
  in `checker.ts` alone under full deferral — is two SYNONYMOUS non-generic aliases
  resolving to one interned type, decided first-wins. **tsc picks by the REFERENCE's
  declaration site, which an id-keyed global map cannot express**, so closing it means
  re-keying alias display, against round 754's deliberate `Type.Reference` exclusion and
  a union display order pinned byte-for-byte across ~13k baselines. That is a
  logical-parity conversation (`docs/logical-parity.md` § 2) and is NOT worth opening
  for a 66 ms the table above has already refused.
- [x] **(INC.7) DONE 2026-08-23 — 157 WALKERS GATED ACROSS FOUR BATCHES, AND BATCH 4
  CLOSED THE TECHNIQUE RATHER THAN THE FAMILY.** Batches 1-3 gated 68; batch 4 gated
  **89** more in two independently swept sub-batches. **Floor 1,207 -> 340 ms,
  narrowed query median 1,077 -> 367 ms, ratio at the median file 13.30x.** The batch-4
  diff is 89 loop headers and nothing else (`binderResults` 221 -> 132, `checkedResults`
  255 -> 344). The relocation discount now has FOUR points — 79.0 / 85.5 / 92.9 /
  **78.2%** (54.23 ms of rows for 42.41 ms of floor).
  **WHY IT IS DONE: 65% OF WHAT REMAINS IS REFUSED BY SHAPE.** 172 ungated passes /
  251.9 ms remain, and the top TEN rows are **165 ms** of it, every one refused —
  `init:buildFileLocalTypeMaps` 62.06 (writes `deepInstantiationBailed`),
  `checkTypeArgumentConstraints` 21.69, `checkBaseClassImprovedMismatch` 19.51
  (`diagnostics[i] =`), `checkInterfaceMultiBaseConflicts` 12.73,
  `checkSubsequentVarTypesPerFile` 10.70, `checkPropertyOverride` 9.61,
  `checkDerivedConstructorSuper` 9.04, `init:computeAllEnumValues` 8.75,
  `checkCircularClassBaseViaDefaultTypeArg` 6.91, `checkClassImplementsInterface` 5.94.
  Analyzer-CLEAN was only 54 ms in total. Of the 83 refused: **53** write a checker
  field or retract inside the private closure, 4 carry more than one `binderResults`
  reference, 4 hold a cross-file pre-loop accumulator, and **43 retract via
  `diagnostics.removeAll`**. **A successor must change the SHAPE of a retracting or
  field-writing pass — the loop header is exhausted.** See (INC.20).
  **TWO ANALYZER INVARIANTS WORTH MORE THAN THE BATCH** (both now in CLAUDE.md): a
  MULTI-LINE PARAMETER LIST truncates a function's span to its header, hiding the body
  and every field write in it — it wrongly cleared two passes THIS QUEUE HAD ALREADY
  REFUSED, so the refusal list is the oracle that catches the analyzer; and a
  `pass("…")`-REGISTERING helper is not a caller, so without excluding the 12
  `initCheckPasses*` registrars the clean set is **0**.

- [x] **(INC.20) LANDED 2026-08-23 — 13 PASSES, AND THE FLOOR PASS TABLE NEARLY HALVES:
  `PT.total both.floor` 219.98 -> 119.74 ms.** (INC.7) batch 4 refused 53 passes on
  "writes a checker field inside the private closure"; **the verdict was true and the
  inference from it was wrong** — for nine of them the write is a per-FILE AMBIENT
  install (`currentFileLocals` / `currentCheckFileName`), gone before the next file is
  walked, with the same resting value whether the loop ran 78 times or none. Sub-batch B
  used the (INC.17) template properly: two MIXED passes that build a program-wide INDEX
  then emit per file (**only the second loop moved**) and two per-file retractors — one
  of which, `checkPreEmitCountMismatchPins`, is IMPROVED rather than narrowed, since its
  TS-1 marker carries `fileName = null` and so survived the partition filter.
  **Banked 100.23 ms of 116.08 = 86.3%, the fifth discount point.** Floor 248 -> 162 ms,
  narrowed-query median 313 -> 207, ratio **15.66x -> 24.16x**. 19 pins; reverting the
  14 loop headers reddens 5 of 7 census assertions, and gating the two COLLECTION loops
  reddens exactly the three cross-file arms — the evidence the split is load-bearing.
  **THE VICTIM HAS A MECHANISM NOW, NOT A RESIDUE**: `checkReverseMappedIntersection-
  Constraint` 0.067 -> 19.431 ms, the only row outside the batch to move >0.2 ms, because
  round 895's `srcHas` builds its per-file n-gram filter LAZILY and the FIRST caller in
  pass order pays it for all 78 files. See (INC.21).

- [x] **(INC.21) LANDED 2026-08-23/24 — THE SCANNING FAMILY BANKS 99.9%, THE ARC'S FIRST
  ~100% DISCOUNT.** 19 whole-source-scanning passes gated TOGETHER (**19.064 -> 0.024
  ms**), four stragglers, and (INC.20)'s escalated reversal. `PT.total both.floor`
  **123.95 -> 97.12 ms**; floor **162 -> 137**; narrowed-query median **207 -> 166**;
  ratio **24.16x -> 29.86x**. **No row outside the batch rose** — the lazily-built
  n-gram filter had nowhere left to relocate to, and the three whole-program text gates
  that remain use a RAW `String.contains`, never round 895's filtered `srcHas`, so they
  cannot rebuild it. The list was derived by TWO independent instruments that agree.
  **THE STRAGGLERS TAUGHT THE OPPOSITE LESSON**: three keep their cost because a
  whole-program `.contains` gate sits ABOVE the loop — a question about the PROGRAM, so
  it must stay on `binderResults`, and gating the loop banks ~0.02 ms
  (`checkModulePreserve4Pin` is the control: narrowed and unmoved, 1.639 -> 1.699). What
  banks the ms is a **NAME PRE-GATE**, sound because it asks only what the pass can
  already do: 2.509 -> 0.002 and 2.064 -> 0.002.
  **THE REVERSAL'S OBLIGATION WAS DISCHARGED**: `checkSubsequentVarTypesPerFile`
  **11.740 -> 0.004 ms**, and the replay measured on both arms — 284 -> **304 of 417**
  re-entered passes for **+26 ms over 75 questions (+0.2%)**, divergence unchanged at
  5 of 75. **The replay's ADVANTAGE fell 1.91x -> 1.68x because the fresh build got
  cheaper** — every round that shrinks the floor shrinks the replay's reason to exist,
  which strengthens (INC.19)'s refusal of it as a default path.
  **REFUSED**: `checkModuleAugmentationReexportDuplicates` /
  `checkCjsExportAugmentationConflict` (their emitter adds a row on the augmentation's
  TARGET, so a partition holding only the target loses it — rows 0.15 and 0.00 ms, the
  refusal is free); a name pre-gate for `checkModulePreserve4Pin`; and routing the three
  raw `.contains` gates through `srcHas`, which would **COST ~17.8 ms to build 78 filters
  to save three ~2 ms scans** now that no pass builds one.

- [x] **(INC.22) REFUSED 2026-08-24, WITH THE SHARPEST MEASUREMENT THE ARC HAS OF THIS
  ROW — AND THE REFUSAL RE-AIMS THE DIRECTION.** `init:buildFileLocalTypeMaps` is
  **69.16 ms of a 90.15 ms floor pass table (77%)**, and partition-scoping it would take
  the floor **131 -> 57 ms**, the narrowed-query median **166 -> 116**, and the ratio
  **29.86x -> 42.61x**. The axis is new — (INC.10)/(INC.11) deferred PHASES, this varies
  **WHICH FILES** through the INV.6(6d) partition view, so a full build is unchanged BY
  CONSTRUCTION — **and the claim was verified in the BINARY**: a per-arm DIGEST over
  381,666 captured types and 360,152 definitions is IDENTICAL across arms, with
  `FltmDefer.lazyBuilds == 0` on every unpartitioned build as the corroborating count.
  **THE QUEUE'S PREMISE HAD EXPIRED**: (INC.11)'s "137 divergent spans" for the
  `TypeAlias`-only arm re-measures as **5 / 3 of 76** — byte-identical to baseline —
  closed by (INC.11)'s own fix and the (INC.5)/(INC.16)/(INC.19)-(21) work. So no
  `aliasDisplayMap` re-key was needed, and none was attempted.
  **WHAT REFUSES IT IS THE MEMBER CHANNEL, NOT DISPLAY**: `capture-channel`'s `moreAny`
  goes **168 -> 229**, i.e. **+61 member types collapsing to `any`** under a narrowed
  build — a WRONG ANSWER, the same class (INC.11) refused the full deferral over — and
  `partition-gate`'s SENSITIVITY arm diverges on a DIAGNOSTIC. Keeping the cheap
  `TypeAlias` phase program-wide (6.68 ms) solves the NAMING half completely (2,275
  divergent spans -> +1 row) and does nothing for the member half.
  **THE TRANSFERABLE RESULT**: the obstruction is not the pass's COST but that the pass
  IS the program's FIRST-TOUCH ORDER, and that order buys BOTH an alias name (cheap,
  fixable) AND member resolutions (not fixable without the expensive phase). See (INC.23).

- [x] **(INC.23) THE CENSUS IS DONE 2026-08-24, AND IT SHRANK (INC.22)'s REFUSAL BY TWO
  ORDERS OF MAGNITUDE.** "+61 member types collapse to `any`" is, classified per ELEMENT,
  **78 rows carrying exactly ONE member name — `[Symbol.unscopables]`** (the lib's
  `{ [K in keyof any[]]?: boolean }`) in 14 files. Everything else (1,379 rows, 196 names)
  is the (INC.11)(a) alias-display family, which collapses to **+1 row for 6.68 ms**.
  **ROUND 778's WRITE GATE IS REFUTED AS THE MECHANISM**: the writer hook reads
  `ambient=empty persisted=true` in BOTH arms and differs only in `truncated` — under a
  partition the first ask arrives from INSIDE the member-table resolution the mapped
  type's `keyof` needs, `resolveStructuredTypeMembersCore` returns leaving `properties`
  null, and the type degrades. **The whole narrowed compile has ONE truncated resolution
  of 822; a full build has 0 of 21,315.**
  **THE OBVIOUS FIX IS REFUTED WITH A POSITIVE CONTROL**: refusing to persist a truncated
  resolution changes nothing sweep-wide (same 78 rows, byte-identical digest) while the
  control shows the arm is live (`persisted=true resolves=1` -> `persisted=false
  resolves=2`) — the re-resolution re-enters the same guard.
  **AND `narrowRendersMoreAny` IS A SUBSTRING HEURISTIC THAT OVER-REPORTS**: **zero** of
  the shipped baseline's 168 "moreAny" rows loses a member type. A nonzero value is a
  LEAD; a zero still means what it always did.
  **(INC.22)'s THIRD OBSTRUCTION IS RETIRED**: the PURE partition-scoped arm is EQUIVALENT
  on both `partition-gate` arms — the "DIVERGED 1 file" belonged to its MIXED
  `TypeAlias`-program-wide configuration.

- [x] **(INC.24) LANDED 2026-08-24 — both capture runners fold their whole answer set into
  ONE number per arm, ordered by span key so it is a property of the ANSWERS and not of
  `HashMap` iteration.** From a clean tree it reproduces (INC.22)'s recorded
  `full=-3718897727265589316` over 381,666 types + 360,152 definitions exactly — round
  776's rebuild-the-baseline control, satisfied on an instrument. `Checker.fileLocal-
  TypeMapSnapshot` came with it, plus 4 pins.

- [x] **(INC.25) LANDED 2026-08-24 — AND IT WAS NEVER A PARTITION DEFECT. Floor 129 -> 58
  ms, narrowed-query median 173 -> 117, ratio 30.91x -> 43.07x, floor now HALF a median
  query instead of three quarters.** `resolveStructuredTypeMembersCore` returns silently
  on re-entry leaving `properties` null — correct for circular heritage, TRUNCATED for
  anything reading the key set — so `getKeyofType` read null as `string`, the mapped type
  bailed to `any`, and round 778's gate froze it. The fix answers such a `keyof` **from
  the DECLARATIONS**: no resolver call at all, only already-computed tables plus AST,
  under a visited set and a depth cap, REFUSING rather than returning a partial key
  domain (round 463). Terminating by construction; **no TS2589 at (0,0) anywhere**.
  **IT REPRODUCES ON A FULL BUILD WITH NO PARTITION**: three lines
  (`export const strArr: string[] = []` + a `number[]` sibling) render
  `[Symbol.unscopables]: any`, because `interface Array<T>`'s body is never spine-walked
  while a hand-written interface's is. **So this was shipped and always-present**, and
  the 78-file profiles hid it because `init:buildFileLocalTypeMaps` happened to resolve
  that member first — which is why three rounds read it as a partition problem.
  With it fixed, `narrowRendersMoreAny` returns **229 -> 168** (baseline) and the
  partition-scoped pass is now the shipped default, pinned with no mode install.
  **Ablation: counters identical DIGIT FOR DIGIT to the fixed binary** — the fix moves
  zero counters, so all standing drift is pre-existing.

- [x] **(INC.26) LANDED 2026-08-24 — AND THE ROUTE WAS NEITHER A NOR B, BECAUSE THE GATE
  ASSUMED THE FULL BUILD WAS THE REFERENCE AND IT WAS WRONG.** The census inverted the
  entry: the `Intl.LocalesArgument` case it led with is **2 rows of 2,275**, and the
  dominant direction is the reverse — **the FULL build attaches a name, the NARROW one
  renders the honest type**. The mechanism is aliases whose body is a single NAMED
  interface (`type FunctionBody = Block`, `type IsInterface = InterfaceDeclaration`,
  `type HasIllegalExpressionInitializer = PropertySignature` in tsc's own `types.ts`):
  we stamped the alias onto that interface's `Type.id`, and `typeToString` reads
  `aliasDisplayMap` BEFORE the structural fallback, so every occurrence program-wide
  rendered under the alias. **Four lines reproduce it with no partition, in the
  DIAGNOSTICS channel** (`Type 'FunctionBody'` where tsc 7.0.2 says `Type 'Block'`).
  **So both routes were treating a symptom** — Route A would have made narrowed hovers
  as wrong as full ones. The fix is the `symbol == null` test the sibling Intersection
  arm already applied; anonymous bodies still register.
  **ROUND 754 BIT AND WAS HANDLED CORRECTLY**: the first version reddened four `Table`
  rows, and **no logical-parity divergence was taken** — that baseline is pristine tsc's,
  so switching it off would move AWAY from tsc. The rule was narrowed to exclude a
  GENERIC named type instead, and arm (b) pins it: removing that exclusion reddens
  **exactly 2 of 504 tests, the new pin AND the corpus baseline, together.**
  **Gate: 2,275 -> 1,128 spans (-50%), 46 -> 43 files**, `narrowRendersMoreAny=0`.
  **TWO RECORDED DIGESTS MOVED BY DESIGN** — `capture-equivalence` full
  `-3718897727265589316` -> **`3349895618940861366`**, `capture-channel` full
  `4065921979171190360` -> **`-3278907782584108296`**. First time in the arc; a full
  build is what this corrects.

- [x] **(INC.27) REFUSED 2026-08-24 WITH A PROOF — B416's KEY CANNOT NAME A UNION THE WAY
  tsc DOES, AND THE OBVIOUS NARROWING MAKES THE GATE *WORSE*.** Census of the 1,128
  residual spans: **432** where several aliases claim one member set (arbitrary in BOTH
  arms), **~393** where a SOLITARY alias names a union at sites that never spell it
  (measured: `AssignmentPattern` has **0 references** in binder.ts, `MemberName` 0 in
  checker.ts), **~303** the (INC.28) family.
  **tsc gives THREE answers for one member set** (`ModuleName`, `ModuleExportName`,
  `Ident | Str`) because it keys its union cache by `getTypeListId + getAliasId`; **and
  its naming turns out to be IDENTITY PRESERVATION (`filterType`), not structural
  matching** — a join-built `A | B` renders structurally while a no-op narrow of
  `x: MyType` renders `MyType`, both in one pristine baseline.
  **INV.5(a) (round 545) interns our unions by member-id list ALONE**, so all of tsc's
  instances are ONE `Type` here — a proof that no id- or member-set-keyed table can give
  three answers from one key, and that **anything able to name the reconstructed union
  also names a union nobody named.**
  **THE NARROWING WAS BUILT AND MEASURED**: it collapses `full=name/narrow=name` **416 ->
  2** and takes the gate **1,128 -> 1,351 spans, 43 -> 46 files**, because the poison
  TRIGGER is itself coverage-dependent and a new `full=structural/narrow=name` bucket of
  657 appears. Nor can ambiguity be decided syntactically: of 407 collisions per compile
  the largest are aliases whose body is ANOTHER alias (`type FunctionLike =
  SignatureDeclaration`), so deciding it means resolving every union alias up front —
  (INC.22)'s eager `TypeAlias` phase, already refused twice.
  **Landed behaviour-free and PROVEN so**: KDoc, census hooks outside the write, 2 pins,
  and `capture-equivalence` returning BIT-IDENTICAL digests.

- [ ] **(INC.29) PUT THE ALIAS IN A UNION'S IDENTITY — the only route to tsc's union
  display, and it is an INV.5(a) change, not a display one.** (INC.27) proved the bound:
  tsc keys its union cache by `getTypeListId(types) + getAliasId(aliasSymbol, …)` and so
  holds distinct instances for one member set, while round 545's INV.5(a) interns ours by
  **member-id list alone**. **Until that changes, no naming rule can be correct** — every
  mechanism that can name a flow-reconstructed union also names one nobody wrote.
  **AND THE TARGET BEHAVIOUR IS NOT "MATCH THE ANNOTATION" BUT IDENTITY PRESERVATION**:
  tsc renders `MyType` for a narrow that removes nothing and the structural union for a
  join-built one, which is `filterType` returning its input unchanged — so the rule is
  "an operation that did not change the type does not change its name".
  **THE COST IS THE HAZARD.** Union interning is load-bearing for relation caching and for
  union display ORDER, which is pinned byte-for-byte across ~13k baselines; splitting the
  key mints more `Type` ids, and id drift reshuffles ~350 boundary tests (round 881's
  warning about moving id allocation). Price the id churn BEFORE building anything.
  **Do NOT re-open**: naming from the annotation ((INC.27), unstable and coverage-
  dependent), the eager `TypeAlias` phase ((INC.22), 6.68 ms and a diverging diagnostic),
  or closing the gate by making the NARROW arm match the full one ((INC.26): the narrow
  arm is the more correct one in every remaining family).
- [x] **(INC.28) LANDED 2026-08-24 — A GENERIC ALIAS'S OWN PARAMETERS WERE NOT IN SCOPE
  FOR ITS BODY, SO `type Box<T> = { v: T }` RENDERED `{ v: any; }` ON ORDINARY BUILDS.**
  `getDeclaredTypeOfSymbolWorker`'s type-alias arm resolved `decl.type` with NO
  type-parameter scope, the alias's own `T` answered `errorType`, and **`any` ABSORBS A
  UNION**, so a union body collapsed entirely. **Four lines reproduce it with no partition
  and it is not order-dependent**; the partition divergence was a CONSEQUENCE (a narrowed
  build skips `init:buildFileLocalTypeMaps`, so the first toucher is
  `withDeclTypeParamScope`, which DOES install the scope — and `declaredTypes` has no write
  gate, so first touch freezes). A writer hook printing `ambient=empty depth=sym1/node0`
  **refuted BOTH standing suspects** — round 778's write gate and truncation.
  **THE FIX IS A SPLIT FORCED BY MEASUREMENT**: `getTypeOfSymbolWorker`'s alias arm answers
  the parametric form; **`getDeclaredTypeOfSymbol` (what a REFERENCE resolves to) is
  deliberately untouched**, because handing references the parametric form costs two corpus
  false positives, both measured and reverted.
  **Gate 1,128 -> 1,003 spans with ZERO NEW divergent spans** (a strict subset, 125 fixed);
  suite **15,811 / 0 / 3**; ablation 2 of 4 pins RED, **with the two-arms-agree test staying
  GREEN because both arms agreed on the WRONG answer** — the reason a comparison is not a
  pin. Digests moved by design (second time in the arc): full `3349895618940861366` ->
  `8385940838610938556`, narrow `306524840298287433` -> `-7423700524621287041`.
  **173 of the 298 rows REMAIN and need the RELATION, not the display** — see (INC.30).

- [ ] **(INC.30) THE RELATION HAS NO "TYPE PARAMETER VIA ITS CONSTRAINT" RULE, AND THAT
  REFUSAL IS LOAD-BEARING AS A RECURSION BRAKE.** (INC.28) measured it: judging a
  `Type.TypeParam` alias argument by its APPARENT type in the B57.1b guard renders
  `Visitor` exactly as tsc 7.0.2 does and closes **173 of its 298 rows** — and costs a
  corpus false positive, because `checkTypeRelatedToCore` has no general rule relating a
  TypeParam source through its constraint (its `NonPrimitive` leg refuses it DELIBERATELY),
  and **that refusal is what brakes the recursion for
  `BuildTree<T, N extends number = -1, I extends any[] = []>`**. Recorded at the site.
  **So this is a RELATION-ENGINE item, not a display one**, and it belongs with the M3
  engine work rather than the (INC.*) arc: adding the rule needs a termination argument
  that does not rely on the absence of the rule. CLAUDE.md already records the two lenience
  directions a bare `Type.TypeParam` has in this relation (a union SOURCE relates to a bare
  TypeParam TARGET; a bare TypeParam SOURCE relates to most object targets) and that they
  CANCEL for one candidate and COMPOUND for a union — read that before touching it.
  **Do not attempt it as a rendering fix**: (INC.28) established the rows are a relation
  verdict, and (INC.26)/(INC.27) established that `typeToString` is shared with the
  diagnostics and pinned byte-for-byte across ~13k baselines.
  **AMENDED 2026-08-24 — (INC.42) REACHED THIS BOUNDARY FROM A SECOND DIRECTION AND DID NOT
  WEAKEN THE TERMINATION ARGUMENT.** It judges a bare `Type.TypeParam` argument LOCALLY against
  its own already-resolved constraint, inside B57.1b's guard and nowhere else, so **no new rule
  enters `checkTypeRelatedToCore`** and this item's obligation is untouched. Two things it
  measured that a future attempt inherits: the relaxation is confined to the DISPLAY path
  because on the CHECKING path it reads `output.errors` **46 -> 48** on the compiler profile,
  and this guard's role as a recursion brake lives in the **ENCLOSING** declaration rather than
  the referenced one (a flip census of `excessPropertyCheckIntersectionWithRecursiveType` says
  so — the only four decisions it flips there are two NON-recursive aliases referenced from
  inside the self-referential `BuildTree`). See (INC.43) for what the real rule would buy.

- [x] **(INC.31) DONE 2026-08-24 (`2fa8a39f`) — THE DOCUMENTED LANGUAGE-SERVICE COST TABLE
  WAS 10-24x STALE, AND THE ROWS THAT DID NOT MOVE ARE THE LOAD-BEARING ONES.** Every wall
  figure in `docs/language-service.md` §3/§10a/§10b/§10c/§14 was round-930, i.e. before
  (INC.2b) narrowed the capture path and before the floor fell 1,092 -> 58 ms. Re-taken on
  the compiler profile (78 files, 9,977,097 chars), warm, six warm-ups, two independent
  JVMs, every row reproduced: `diagnosticsOf(f)` median **1.1-1.2 s -> 108-113 ms**
  (~10x, p90 202-219), `completionsAt` **~4.7-5.1 s -> 194-202 ms** (~24x),
  `signatureHelpAt` **190-214 ms** (~23x), `documentHighlightsAt(binder.ts)` cold **~15x**,
  first hover on `binder.ts` 610 -> 290-306 ms. **Narrowed:full at the median file =
  43-47x** (108-113 ms against a 4,864-5,096 ms rebuild). **`referencesAt` (8.8-9.6 /
  13.2-13.9 s), `renameAt` (20.0-21.3 / 25.0-26.0 s) and a plain `diagnostics()`
  (4,864-5,096 ms) did NOT move and CANNOT** — their claim is about every file, so
  they never enter `captureIn`'s partition; that column is now marked on the page.
  **A REAL KEYSTROKE COSTS THE NARROWED PATH NOTHING EXTRA** (identical bytes 212 ms,
  appended comment 247, inserted statement 218, a statement introducing a TS2322 215).
  **Corrects the heap claim**: not "~1.9 GB peak, 512 MB not enough" but 1,077-1,125 MB
  peak in G1 old gen with **264 MB RETAINED** after a full GC — green at `-Xmx2g`, OOM at
  `-Xmx1g`. Instruments: `Inc31CostMain`, `Inc31ResidueMain`, `scripts/inc31-ls-cost.sh`
  (refuses on a missing profile, positive control on the rows). Every number on the page is
  now dated, stamped with its commit, and marked WALL TIME AND THEREFORE PINNED BY NO TEST.

- [x] **(INC.32) DONE 2026-08-24 (`689df5bb`) — THE CAPTURE MEMO EVICTED BY ENTRY COUNT, SO
  A ONE-SPAN REQUEST THREW OUT A 125,289-SPAN ONE.** `Project.captures` was an
  access-ordered LRU bounded at `CAPTURE_MEMO_ENTRIES = 2` by COUNT. Hover / definition /
  highlights / `fileSemantics` ask ONE file-wide question per buffer via `captureAround`;
  `completionsAt`'s two branches and `signatureHelpAt` call `captureIn` directly with ONE
  span (`Project.kt:1048/1094/1215`). So **hover -> completion -> signature help -> hover
  with NO edit in it** rebuilt the hover: `quickInfo.mid.afterTwoOtherChannels`
  **324 ms -> 4 ms**, every other row inside the band and the `rebuild.full` anchor at
  +1.7%. **NOT a larger limit** — the bound is now on WEIGHT, in two lanes that cannot evict
  each other: `CAPTURE_MEMO_CARET_SPANS = 4` decides caret-scoped, bounded at
  `CAPTURE_MEMO_CARET_ENTRIES = 4`; buffer-sized stays at `CAPTURE_MEMO_BUFFERS = 2`,
  unchanged since (INC.13). **Worst case: 2 buffer captures (UNCHANGED) + 16 answers =
  0.013% of ONE file-wide capture.** Invalidation re-audited, not assumed (`cached = null`
  at exactly three sites, all clearing `captures`). Ablation a1 (count eviction restored)
  3 of 13 RED; **a2 was needed because a stricter bound cannot fail a BOUND pin** — see the
  session note. Suite 15,811 -> **15,815 / 0 / 3**.

- [x] **(INC.33) REFUSED 2026-08-24 (`cf56bfe8`) — THE WIDENING IS PRICED AND IT LOSES: A
  CAPTURE REQUEST IS PRICED PER *ANCHOR* WHERE AN EDITOR NEEDS A PRICE PER *ANSWER*.** A widened
  file-wide hover costs **+286 ms on `binder.ts`** (300 -> 586, ranges disjoint in both batches)
  and **+25.1 s on `checker.ts`** (3,624 -> 28,751) to save a completion build of **204 / 2,078
  ms** — break-even **1.40** and **12.1 completions per hover IN A BUFFER WITH NO EDIT SINCE**,
  and the dominant completion path types a `.` first, which is an edit, which clears the memo.
  The cheapest shippable variant (occurrences + members, no scopes) is +96 ms on `binder.ts` for
  0.47 but **+3,326 ms on `checker.ts` for 1.60**, and makes EVERY hover ~32% dearer. **The
  second, independent refusal is RETENTION**: one widened entry holds 798,531 records for
  `binder.ts` and **54.4 M** for `checker.ts` — **48x / 205x** today's hover entry — of which
  49,879,917 are `CapturedName`s, because a free-name caret sees the lib globals and a widened
  request repeats that set at every one of 13,601 anchors (**O(anchors x globals)**, structural,
  and `CapturedScope`'s own KDoc already said so). **THE UNBLOCKER IS (INC.41), NOT A WIDER
  REQUEST** — a re-entrant capture against a retained checker ((INC.17)'s `ProgramRecheck`)
  answers a span nobody asked for up front with no new build. Instrument kept and re-takeable:
  `scripts/inc33-widen-cost.sh` + `Inc33WidenMain`'s KDoc (which is the authority for the table;
  the figures are WALL TIME on one box and pinned by no test). ORIGINAL ENTRY, whose reasoning
  stands and whose sub-question (a) is what was measured:
  **THE CARET CHANNELS ARE COLD PER CHANNEL PER BUFFER — a completion in an
  already-hovered buffer still BUILDS, measured 201-228 ms, and the prize is UNMEASURED.**
  (INC.32) stopped the caret channels evicting the hover; it did not make them SERVED. A
  hover's file-wide request carries `spans`, and a member completion asks `memberSpans`, so
  the memo hit cannot answer it and `captureIn` rebuilds. **That is CORRECT as it stands**
  — (INC.14): an answer that was never asked for is ABSENT, and an absent member answer
  renders nothing, silently — so this is not a bug to fix but a WIDENING to price. **The
  widening is the dear half, and it undoes a deliberate decision**: (API.4a) made
  `memberSpans` a SECOND span list precisely **so `fileSemantics` never enumerates
  members** — a file-wide `memberSpans` would ask about every member position in the
  buffer, and nothing here has measured what that costs the hover that would pay for it.
  **Two sub-questions, in order**: (a) what does adding `memberSpans` to the file-wide
  request cost the hover that pays for it (instrument: `scripts/inc31-ls-cost.sh`, rows
  `quickInfo.mid.first` and `completions.mid.afterHover`); (b) whether the three direct `captureIn` sites
  (`Project.kt:1048/1094/1215`) should consult `preparedAnswerFor` at all — today they
  cannot reach a prepared check, measured at 207 ms right after `prepare(6)`, 202 ms right
  after a hover in the same buffer and 194 ms cold, i.e. **the same build three ways**.
  Do NOT expect a win from (b) alone: without (a) there is nothing in the prepared answer
  for a completion to read.

- [ ] **(INC.34) `SourceIndex` DERIVED-POPULATION MEMOIZATION — MEASURED AND REFUSED
  2026-08-24; THIS ENTRY IS THE REFUSAL, NOT A TASK.** On a memo hit `captureAround` still
  re-derives the file's occurrence set — `occurrenceNodes()` (two tree walks, two sorts,
  memoized nowhere), a span per occurrence, a `HashSet` of every span. Decomposed by buffer
  size: **1.21 ms at 17.9 KB, 2.27 ms at 194 KB, 82.7 ms at 3.15 MB**, closing the
  arithmetic to **0.4%** of the measured 83 ms second-caret hover on `checker.ts`. **At the
  median file the whole prize is 1-2 ms — below this repo's floor for a round** — and it is
  not a `referencesAt` lever either (**~140 ms of a 9.3 s sweep = 1.5%**). It survives
  ONLY as a tail fix for buffers over ~1 MB (~78 ms per caret there). **The instrument that
  can re-open it is `Inc31ResidueMain`** (`-project` jvmTest, walk-vs-sort split included) —
  a refusal is only as durable as the instrument that can overturn it, so re-open this with
  a measurement from that runner and never from a leaf profile row (CLAUDE.md: a JFR owner
  total is a LOCATION, not a price).

- [x] **(INC.35) DECIDED AND CLOSED BY THE OWNER 2026-08-25 — OPTION (b), PER-BUFFER ONLY.
  NOT IMPLEMENTED: THE DECISION *IS* THE OUTCOME.** Project-wide `diagnostics()` stays
  WHOLE-PROGRAM at 4,864-5,096 ms per edit, and the editor's error reporting stays
  PER-BUFFER — which is what (INC.1)/(INC.2b) already deliver at **108-113 ms** and what an
  IntelliJ-style annotator actually renders. **Closure-based project diagnostics is REFUSED
  for this corpus**, on round 772's measurement rather than on taste: tsc's own sources are
  `export *` barrels, so touching a LEAF (`semver.ts`, 3 direct dependents) reports
  `incremental recheck of 77/78 file(s)` and costs a full warm rebuild, while `checker.ts`
  and `types.ts` do not qualify as incremental at all. **Option (a)'s reasoning is kept
  visible so no future round re-derives it**: a closure WOULD buy a well-layered application
  a great deal and buys the v1 benchmark nothing, so the two optimisation targets genuinely
  diverge here — which is exactly why the choice was the owner's and not the agent's.
  **RE-OPENABLE ONLY on an owner directive naming a LAYERED corpus to grade it on** (one of
  the (LIB.*) screened libraries); re-opening it against the dashboard profile is a round
  spent optimising for a benchmark that structurally cannot show the win.

- [x] **(INC.36) DONE 2026-08-25 — THE PROGRAM WAS PARSED *TWICE* AND BOTH COPIES WERE
  KEPT; RETENTION **264 -> 177 MB (-33%)**.** Step 1 ATTRIBUTED the 264 MB with a ten-step
  subtraction ladder over `liveAfterGc` (four processes agreeing to 0.6 MB):
  `Project.sourceIndexes` **114.7 MB (43.5%)**, the process-global `CrawlParseCache`
  **103.0 (39.0%)**, `RealLibSnapshots` 2.6, JVM baseline + lib text + the 9,827 answers
  43.7 — and **`cached`/`captures`/`prepared`/`narrowed`/`recheck`/`lineMaps` 0.0 MB
  COMBINED**, so every memo (INC.12)/(INC.14)/(INC.32)/(INC.40) added is free and
  `close()` frees nothing. The two big rows are ONE program parsed twice at the same
  content under the same `computeParserFlags`; the class histogram says it independently
  (**770,460 `Identifier`s** against 856,962 nodes in one copy = CLAUDE.md's 44.5%,
  DOUBLED). Step 2 deleted one copy: `Project.sourceIndexOf` indexes tokens around the
  compiler's own tree (`parsedSourceOrNull` -> `SourceIndex.around`), `sourceIndexes`
  falls **114.7 -> 27.5 MB**, `Identifier` HALVES to 388,790 and `referencesAt` returns
  the **same 9,827 hits**. **The residue is named, not hidden**: ~18 MB is `SourceIndex`'s
  own token arrays (`[I` + `[LSyntaxKind;`, byte-identical before and after — nothing else
  in the process holds one) and ~10 MB is a SECOND COPY OF THE SOURCE TEXT, which
  `SourceFile.text` makes nearly free to remove and which is left as a named next lever
  rather than landed after the gates ran. **REFUSED: option (b), bounding `sourceIndexes`
  by weight** — it costs re-parses (144-171 ms for `checker.ts`) to keep a duplicate that
  can simply not exist. **REFUSED: threading the parses through `ProjectCompiler.Result`**
  — `cached` is nulled on every edit and the hover path goes through `captureIn`, not
  `build()`, so the editor's own loop would keep duplicating the file being edited; it
  also lands trees in the `Result`s `captures` retains and, under `CrawlParseCache`'s OFF
  arm, would newly retain the whole program where the accessor degrades to today.
  `docs/perf/language-service-retention.md`; per-project marginal `103 + 115·N` measured
  BEFORE the fix and not re-drawn after it.

- [x] **(INC.37) DONE 2026-08-24 (`c1c165c6`) — THE OTHER HALF OF A QUERY IS DECOMPOSED, AND
  ITS TWO HEADLINE ANSWERS ARE BOTH NEGATIVE RESULTS.** `own(F) = build(recheckOnly={F}) −
  build(recheckOnly={a name not in the program})`, per wall and per pass, 78 files.
  **(1) `own(F)` IS LINEAR IN NODES AND `checker.ts` IS AT THE p10 OF PER-NODE COST** —
  6.27 µs/node against a population median of 9.71 over the 51 files >2,000 nodes; its
  1,726 ms is 275,478 nodes at a below-median price, so **there is no super-linearity and no
  structural lever inside the big file, only the constant factor per node.** Bytes is a
  **10x-noisy** proxy (76-739 µs/KB) that predicted `checker.ts` low by 1.2-4.3x — census
  per NODE. **(2) Σ`own(F)` = 6,841 ms against a whole-program check of 4,935 — a 1.39x
  RE-DERIVATION TAX**, and the walk partitions EXACTLY (Σ `spineNodes` = 856,962, the
  whole-program figure to the node), so the 1,906 ms is shared type resolution each query
  re-derives; see (INC.38). Query shape: floor 56 ms, `own(F)` median **52**, query median
  **108 ms** (reproducing (INC.31) independently), max 1,782 with the floor at **3.1%**.
  `checkSpine` is **89-92%** of `own(F)`; the ~400 tail walkers are 10.5% on `checker.ts`
  over 78 rows whose largest is 0.65% of the query (**closed** on round 830's arithmetic);
  the four disjoint type-system rows are **16.2%** of `checkSpine`, so 84% is the walk and
  the handler bodies. **Round 847's six-handler SET confirmed (65.7% vs 63.0%), its ORDER
  REFUTED** — see (INC.39). `docs/perf/file-check-decomposition.md`; instrument-only, suite
  unchanged at 15,815 / 0 / 3.

- [x] **(INC.38) DONE 2026-08-25 (doc-only) — THE 1.39x RE-DERIVATION TAX'S HOST-FACING
  RECOMMENDATION IS NOW WRITTEN DOWN, WITH ITS NUMBERS AND ITS LIMIT.** (INC.37): Σ`own(F)`
  over 78 files is 6,841 ms against a 4,935 ms whole-program check while the spine walk
  partitions to the node, so **1,906 ms is shared type resolution a full build amortises and
  every per-file query re-derives in its own fresh `Checker`**. Against a 108 ms median
  query that is ~24 ms = 22%; against `checker.ts` it is 0, because there the file IS the
  program. **THE CODE HALF SHIPPED ALREADY — (INC.40), `8d4e95b0`.** It asked whether a
  HELD plain-build `Checker` can serve `diagnosticsOf` across queries at one program state,
  the way `prepare` serves captures. It can, and it does: `Project.diagnosticsOf` now keeps
  the program its first narrowed build hands back and re-enters it, worth **2.25-2.30x**
  (104-108 ms -> 25 ms at `k = 1`), which is this tax being COLLECTED rather than re-paid,
  and it does not remove the recommendation below — it deletes the FLOOR across queries,
  not the per-file derivation a single build still pays once per file named.
  **THIS ROUND LANDS ONLY THE DOCUMENTATION HALF, NO CODE.** `docs/language-service.md`
  § 3a gained a new subsection, "Ask for the whole open set in one call — this is a rule,
  not a tip", right after the existing `diagnosticsOf` batching example. It states the
  arithmetic (one call pays one floor + one derivation; N calls pay N of each), quotes the
  measured numbers **from § 14's own six-buffer table (`2fa8a39f`, 2026-08-24)**: the same
  6-file set asked as one call costs **321-342 ms**, asked one file at a time it costs
  **748-771 ms** — matching what this item paraphrased as "342 against 771", now traced to
  its actual source rather than to the (INC.14) queue note, which does not carry those two
  numbers verbatim. States plainly this is wall time, pinned by nothing (the page's own
  standing caveat), and restates (INC.14)'s refusal of automatic working-set growth
  (`k·floor + k(k+1)/2·perFile` against a cold `k·floor + k·perFile` — a loss at every k)
  so the "why not just grow the set automatically" question is answered in the same place
  as the recommendation. **GATES: none — no Kotlin source touched, `git diff --stat` shows
  only `.md` files, so `jvmTest`/`cost_gate.py`/`huge_methods.py` were not run.**

- [ ] **(INC.39) (SPINE.1) FOR THE LARGE-BUFFER TAIL — 645 ms IS THE OBJECT ON `checker.ts`,
  AND THE PRIZE IS *NOT* MEASURED.** (INC.37): the three biggest spine handlers
  (`cpaSpineLeave` 22.9%, `spineCtaM3StatementAnchor` 17.4%, `ccetSpineLeave` 10.9%) are
  **645 ms = 51% of handler cost and 37% of `own(checker.ts)`**; a hypothetical 30% cut is
  ~195 ms = **11% of the 1,782 ms query** and ~9 ms of a 108 ms median one. **No cut has
  been priced — 30% is an illustration, not a measurement**, and the whole-program form of
  this item was REFUSED AND CLOSED at round 908 (the passes' own checking work is 91.4% of
  the probed region and every frame pop is at or below one probe boundary). **What is new is
  only the REGIME**: under a single-file partition the tail query is 97% one file's spine,
  so a per-handler lever that was 40% of a rebuild is now ~37% of the worst query.
  **TWO CAVEATS BEFORE ANY WORK.** (a) **The ranking must be re-taken for the target file.**
  It is population-dependent, not a property of the compiler: the top-three permutation
  differs on `binder.ts` / `parser.ts` / `checker.ts`, and `cpaSpineLeave` moves from round
  847's third place to first. (b) **The `dispatch` tier BYPASSES `spineEnterMask`**
  (`spineEnterNode`'s first line routes to `spineEnterNodeProbed` and returns), which is
  round 908's own recorded caveat and is NOT stated on
  `docs/perf/file-check-decomposition.md` § 6 — so that table prices the pre-888 regime for
  the ENTER half and is blind to what the mask already banked. `spineCtaM3StatementAnchor`
  is mask-gated (bit 5); the two LEAVE handlers above it are not. Re-read § 6 with that in
  mind before believing any enter-side share. Graded by the script's own `dispatch` arm
  before/after plus the corpus and `cost_gate.py`.
  **WHERE THIS SITS IN THE CARET-CHANNEL ORDER, STATED EXPLICITLY BECAUSE THE ARC HAS NOW
  REFUSED BOTH OF THE OTHER TWO.** (INC.33) refused WIDENING the prepared request (+286 ms /
  **+25.1 s**, and a 48x / **205x** retention blow-up); (INC.41) refused the RE-ENTRANT VALVE
  for captures (413 rows worse against (INC.2)'s 45-span bar). **The remaining NAMED candidate
  is wiring `completionsAt`/`signatureHelpAt` to `prepared` — (INC.32) defect 1, ~200 ms on
  every keystroke-adjacent query, no correctness question — and the queue must not imply it is
  a cheap win: it is in direct TENSION with (INC.33)**, which measured that `prepare` can only
  serve those channels if its request is widened, which is the thing it refused. So that
  candidate is not free and is not yet priced. **What is missing is the PREPARE-AMORTISED
  case** — pay the widening once for a working set, then answer many carets from it — which
  neither round measured: (INC.33) priced a widened request against ONE hover that pays for it,
  never against a session's worth of queries. Measure that (instrument kept and re-takeable:
  `scripts/inc33-widen-cost.sh` + `Inc33WidenMain`) before anyone builds the wiring. This item
  (per-handler spine cost) is orthogonal to all three and remains unpriced on its own terms.

- [x] **(INC.40) DONE 2026-08-24 (`4eff0799`, `8d4e95b0`) — THE "DECAYING" REPLAY IS
  **2.25-2.30x**, AND IT IS NOW SHIPPED FOR DIAGNOSTICS BEHIND A TYPE-LEVEL VALVE.** The
  3.06x -> 1.91x -> 1.68x lineage carried a whole-file `TypeCaptureRequest` in **both** arms —
  the request the correctness differential needs, +9-17 ms per query of cost common to both,
  which dilutes a ratio without trace. Re-priced capture-free in two JVMs: `k = 1` **104-108
  ms -> 25 ms** (2.25/2.30x), `k = 2` 1.72/1.81x, `k = 8` 1.26/1.25x, floor 54 ms cross-checked
  against `partition-equivalence`'s 61; with captures the same HEAD reads 1.34x. The replay's
  TOTAL lands on the whole-program check (4,728 against ~4,935 ms) — (INC.37)'s 1.39x
  re-derivation tax collected. `Project.diagnosticsOf` holds the program through
  `DiagnosticsOnlyRecheck`, a private one-way valve taking `Set<String>` and returning
  `List<Diagnostic>`; dropped by `updateFile`/`deleteFile`/`close`. **0 `DIVERGE-DIAG` and
  0 `DIVERGE-DEF` on both arms** against 43 `DIVERGE-TYPE` — see (INC.41). +9 pins, suite
  15,824 / 0 / 3; `docs/language-service.md` § 4a.

- [x] **(INC.41) REFUSED 2026-08-24 (`6a54f258`) — CLASSIFIED AGAINST tsc's OWN LSP, AND THE
  REPLAY IS THE WRONG ARM: 413 ROWS IN 36 OF 43 FILES GET *WORSE*, 8 GET BETTER, FOR 88 ms ON
  A ROW A USER MEETS OCCASIONALLY.** The clause that kept the valve shut — "the fresh arm is
  not automatically the correct one" — was inferred from (INC.26) and never tested; tested, it
  is FALSE for this population. `compared 373,879` spans over 75 files -> **796 divergent
  (0.213%) in 43 FILES** (41 basenames — tsc has THREE `utilities.ts`), reduced per ELEMENT and
  nesting-aware per (INC.23) to **37 distinct `(fresh, replay)` pairs**, of which **192 rows
  carry more than one differing element**, so a row count over-reports. **REPLAY WORSE 413 / 36
  files; BOTH WRONG 375 / 17; REPLAY BETTER 8 / 4; EQUIVALENT 0.** All 37 causes sampled
  through `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` = **100% coverage BY CAUSE**.
  **THE MECHANISM IS THE TRANSFERABLE HALF: THE REPLAY IS NOT A *DIFFERENT* DEFECT, IT IS
  *MORE OF* (INC.26)'s ALIAS-DISPLAY RACE, AND IT WORSENS WITH SESSION LENGTH.**
  `aliasDisplayMap` is id-keyed FIRST-WINS over INV.5(a)'s member-id-list interning, so a
  registered alias renames that interned union everywhere; the replay carries the seed build
  **plus every earlier recheck**, so more aliases are registered and more unions get renamed.
  393 of the 413 are that shape (tsc and the fresh arm render `Identifier | PrivateIdentifier`,
  which `utilitiesPublic.ts:857` literally writes; the replay renders `MemberName`). **A
  differential taken after ONE query therefore UNDERSTATES a first-wins display defect.**
  (INC.27) already refused the mitigation with a proof. The other **20** are genuine LOST
  RESOLUTIONS (`Connection[][]`, `Map<string, SeenPackageName>`, a bare `T` -> `any`) and are
  the only part that is a bug in the replay itself.
  **THE PRIZE WAS MEASURED FIRST, as this entry demanded** (`Inc41HoverPriceMain`; both arms
  asked the SAME single caret, 40 targets x 4 ABBA rotations, 6 warm-ups, vacuity control
  160/160): arming 188 ms; ONE hover fresh **121 ms** (p90 234); ONE hover replayed **33 ms**
  (p90 143); **3.67x, 88 ms**. **But the row is only "the first hover in a file, at a program
  state some earlier query already built for, with no edit since"** — `quickInfoAt` memoises
  per BUFFER (~2-4 ms for a second caret) and any edit drops the handle, so the keystroke loop
  gets nothing, and `completionsAt`/`signatureHelpAt` get nothing either ((INC.32) defect 1).
  **AGAINST (INC.2)'s BAR — 45 divergent spans of 381,666 (0.012%) — 413 of 373,879 (0.11%) is
  NINE TIMES IT, in the same silent direction.** REFUSED.
  **WHAT WOULD CHANGE IT, IN ORDER, AND NEITHER IS FREE.** (1) Wire
  `completionsAt`/`signatureHelpAt` to `prepared` (~200 ms per keystroke-adjacent query, no
  correctness question) — **but (INC.33) measured that `prepare` can only serve them if its
  request is WIDENED, and refused that at +25.1 s on `checker.ts` and 54.4 M retained records**,
  so it needs its own measurement of the prepare-amortised case (pay once, query many) before
  anyone builds it. (2) Close the 20 lost resolutions; what then remains is purely the naming
  race, which is an owner-level logical-parity conversation, not a round.
  **THE 375 BOTH-WRONG ROWS ARE NOT PART OF THIS ITEM** — they are an ordinary-build defect,
  queued as **(INC.42)**. Authority and re-take instructions:
  `docs/inc41-replay-capture-classification.md`; instruments `Inc41ClassifyMain`,
  `scripts/inc41_classify.py`, `scripts/lsp_hover_project.py`. No compiler behaviour changed;
  suite unchanged at 15,824 / 0 / 3. **ORIGINAL ENTRY:**
  **THE 43 `DIVERGE-TYPE` FILES ARE THE STANDING CAPTURE-CHANNEL STATE, THEY ARE
  THE WHOLE REASON (INC.40)'s VALVE IS DIAGNOSTICS-ONLY, AND SINCE (INC.33) THEY ARE THE **NAMED
  UNBLOCKER FOR THE ENTIRE CARET-CHANNEL LATENCY STORY.***
  `replay-differential.sh` at HEAD: every diagnostic row and all 352,713 definition spans
  agree between a re-entered answer and a fresh narrowed build's, while the CAPTURED TYPE
  channel diverges in **43 of 75 files** (the banner's "5 of 75" was stale, pre-(INC.26)/(INC.28);
  43 is the pre-existing state, verified on a clean tree before (INC.40) touched anything).
  **The rows are overwhelmingly the union-alias display family (INC.26)/(INC.27)** — the replay
  renders `ModuleExportName` where a fresh build renders `StringLiteral | Identifier` — which
  (INC.27) PROVED is an interning-KEY question, and **in which the fresh arm is not
  automatically the right one** ((INC.26)'s law: a full-vs-narrow differential silently assumes
  the full arm is the reference). The residue is lost generic INFERENCE (`Connection[][]` read
  as `any[][]`, `Map<string, SeenPackageName>` as `Map<any, any>`), silent in the dangerous
  direction. **Closing these is what would let `quickInfoAt`/`definitionsAt`/`completionsAt`
  through the same valve** — the caret channels (INC.33) says are cold per channel per buffer.
  What it is worth is UNMEASURED: (INC.40) priced only the diagnostics arm, and the capture
  arm's own with-capture ratio at HEAD is 1.34x, so the prize must be re-priced for the caret
  channels before any work — not inherited from the 2.25x row. Classify per ELEMENT
  ((INC.23): `narrowRendersMoreAny` over-reports and a nonzero is a LEAD, never a finding).
  **WHAT (INC.33) ADDED, AND IT IS WHY THIS ITEM IS NOW THE ONLY ROUTE.** The obvious
  alternative — widen the file-wide request so one build serves every caret channel — was
  PRICED AND REFUSED: **+286 ms on `binder.ts` and +25.1 s on `checker.ts`** against a **204 /
  2,078 ms** completion (break-even **1.40** / **12.1** completions per hover with no edit
  since), plus a retention blow-up of **48x / 205x** (54.4 M records for one `checker.ts`
  entry). **A request is priced per ANCHOR; an editor needs a price per ANSWER**, and a
  re-entrant capture against a retained checker is the only shape with that property — so
  closing these 43 rows is not one option among several, it is the route. **TWO CONSTRAINTS ON
  RIDING IT.** (i) The prize still has to be measured for the caret channels, per the paragraph
  above. (ii) **A re-entrant capture does NOT by itself unblock free-name completion**:
  `CapturedScope` repeats the lib globals at every anchor (**O(anchors x globals)** — 49,879,917
  names for `checker.ts`, and a widened `scopes.file` arm read **+19.4 s** there), so that
  channel needs its own fix whichever mechanism serves it.

- [x] **(INC.42) PARTIALLY DONE 2026-08-24 (`73811153` + `624812c2`) — A REAL ORDINARY-BUILD
  DEFECT IS FIXED, AND IT IS *NOT* THE 213 ROWS THIS ITEM WAS AIMED AT.** What landed: a bare
  `Type.TypeParam` alias argument was judged by `checkTypeRelatedTo`, which has no "TypeParam
  source via its constraint" rule, so it read as a constraint **FAILURE** where the honest
  answer is **UNDECIDED** — the reference answered `errorType` and rendered `any`. Three lines
  reproduce it with no partition (`type R1<T extends Nd> = T | readonly Nd[]; type A1<X extends
  Nd> = (n: number) => R1<X>` renders `(n: number) => any`; tsc 7.0.2's LSP renders
  `(n: number) => R1<X>`), and a constraint matrix isolated the predicate: an UNCONSTRAINED
  inner parameter is always correct, and **every** row whose inner parameter carries a
  constraint failed — including where the two constraints are IDENTICAL. The argument is now
  judged locally against its own already-resolved constraint, behind two measured gates
  (`aliasBodyDisplayDepth`, `aliasGuardIsRecursionBrake`); no new rule enters
  `checkTypeRelatedToCore`. Suite 15,831 / 0 / 3 (+7 pins), zero corpus baselines moved, both
  capture digests re-recorded by design. See the session note.
  **WHAT IS NOT DONE: the 213 rows. `Inc41ClassifyMain` re-run reads 796 rows / 37 pairs /
  213 GAINED-INFERENCE — UNCHANGED.** Do not read this checkbox as the mission closing; the
  residual is re-scoped and re-queued as **(INC.43)** with what those rows actually are.
  ORIGINAL ENTRY: **`Visitor` / `VisitResult<T>` HOVER AS `(node: TIn) => any` ON *EVERY ORDINARY
  BUILD*, AND THE CAPTURE SWEEPS ARE STRUCTURALLY BLIND TO IT — 375 ROWS IN 17 FILES, 213 OF
  THEM THIS ONE CAUSE.** Found as a by-product of (INC.41)'s classification: of the 796
  divergent rows, **375 are BOTH WRONG** — the fresh arm and the replay agree, and tsc 7.0.2
  disagrees with both. **That is not a replay defect and not a partition defect. It is on the
  shipped build, at every caret, today.** The largest cause by far is `Visitor` /
  `VisitResult<T>`: we render `(node: TIn) => any` where tsc renders `Visitor` (213 rows).
  Two smaller causes in the same bucket, both a *widened* rendering where tsc narrowed:
  `ModuleName` -> tsc's `StringLiteral` (74) and `ImportAttributeName` -> `StringLiteral` (62),
  plus 17 rows where a 3-member expansion should be tsc's `JsxOpeningElement`.
  **WHY NOTHING HERE HAS EVER SEEN IT, AND WHAT THAT DICTATES ABOUT THE PIN.**
  `capture-equivalence.sh` and `capture-channel-equivalence.sh` are **DIFFERENTIALS** — they
  compare two arms of our own compiler — so a defect present in BOTH arms is invisible to them
  **by construction**, which is (INC.28)'s law verbatim (its two-arms-agree test and its
  negative control both stayed GREEN against the unfixed binary while two real pins went RED).
  The diagnostics channel is silent too: a wrong-but-plausible type is never an error.
  **So the pin MUST ASSERT THE VALUE, never that two arms agree** — and the ground truth is
  obtainable rather than guessable: `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, through round 924's
  `scripts/lsp_hover.py` or (INC.41)'s `scripts/lsp_hover_project.py` (which points at an
  EXISTING project; read its sources with `newline=""` — the profile is CRLF).
  **PRIZE: UNMEASURED, AND DELIBERATELY SO — THIS IS A CORRECTNESS ITEM, NOT A LATENCY ONE.**
  It buys no milliseconds; it makes a hover right. **START BY SEPARATING THE CAUSES**: the
  `Visitor` rows are a lost/attached ALIAS on a function type, while the `ModuleName` and
  `ImportAttributeName` rows are the reverse of (INC.41)'s replay defect (we NAME where tsc
  NARROWS), so they are probably not one fix — and note (INC.28) already touched
  `VisitResult<T>`'s neighbourhood (its writer hook printed `name=VisitResult ... type=any`),
  so re-read that session note before starting. `docs/inc41-replay-capture-classification.md`
  § 3 carries the per-cause table; `scripts/inc41_classify.py` re-derives it, and a change is
  an improvement only if the BOTH-WRONG **element-pair** count falls ((INC.23)'s rule: count
  distinct pairs, not rows — 192 of the 796 rows carry more than one differing element).
  Any change to union or alias display touches ~13k pinned corpus baselines.

- [x] **(INC.44) `referencesAt` IS NARROWED — LANDED 2026-08-29, AND THE CLAIM IT REPLACES
  ("its claim is about every file, so there is nothing to narrow to", `docs/language-service.md`
  § 10b and § 14's gap 1, written three times over three rounds) WAS A CATEGORY ERROR: THE
  CLAIM IS PROGRAM-WIDE, THE **EVIDENCE** IS NOT.** Every reference search typed **381,672
  spans** — every identifier plus every member-name literal in every program file — on a
  whole-program check, and then discarded all but the ones whose declaration set met the
  caret's. An occurrence can only be an answer if it SPELLS a name the symbol is reachable by,
  so the population is selectable BEFORE it is typed; `captureIn` already derives the check
  partition from the request's own spans, so narrowing the request narrows the build with no
  new mechanism at all.
  **THE CLOSURE IS ANCHORED BY A FACT ABOUT THE ONLY TWO ALIASING FORMS.** `import { p as q }`
  and `export { p as q }` write BOTH spellings in the file that DECLARES the alias, so
  iterating "select the files containing a name I am looking for, read the aliases they
  declare, repeat" reaches a fixed point without ever opening a file the search had no other
  reason to open. Everything else is REFUSED (`SyntaxRoles.isAliasEscape`) and falls back to
  the whole-program sweep: a default export, the local a default import binds, an `export =`,
  an `import x = require(…)`, a namespace binding, and any closure reaching the spelling
  `default`.
  **THE FILE FILTER MAY NOT BE A PLAIN SUBSTRING TEST, AND FINDING OUT WHY IS THE ROUND'S
  TRANSFERABLE LESSON.** `StringLiteralNode.text` is the COOKED value (`rawText` is the
  source), so `o["pl\ain"]` names the member `plain` while the file spells `pl\ain` — and
  `\a` is an IDENTITY escape, so ANY backslash inside a literal can hide a name, not only
  `\u`. A file may therefore be skipped only when it contains no backslash at all (49 of
  tsc's 78; the other 29 hold 78.2% of the characters), and the exact filter stays
  `occurrenceText(node) in names` — so the PARTITION is exact either way and only the
  indexing cost moves.
  **MEASURED, both arms interleaved in ONE process at the same caret** (`partition` is a
  counter and is the column that transfers; the ms are wall time on one box):
  `createTypeChecker` **2 of 78 files**, 5,359 ms first / 130 ms repeat against **11,112 ms**;
  `emitFiles` 2 of 78, **553 ms** first / 141 repeat against **9,532**; `transformNodes` 3 of
  78, **528 ms** / 135 against **9,320**; `checkSourceElement` **1 of 78** but that file is
  `checker.ts` (31.6% of the program), **1,940 ms** against **9,291**; and the worst realistic
  case, `SyntaxKind` at **49 of 78** and 9,827 hits, **4,904 ms** against **9,078** — so the
  narrowing never loses, because a refusal is the old path exactly.
  **POPULATION CENSUS, which is why this works**: over the 31,455 distinct names in tsc's own
  compiler sources the MEDIAN name is written in **1 file** and occurs **3 times**; p90 is 5
  files / 22 occurrences. Weighted by where a caret LANDS the median is 28 of 78 files —
  `node`/`type`/`kind` dominate the occurrence count — so a search for a very common word
  narrows little and a search for a name a user actually asks about narrows enormously.
  **GRADED BY A DIFFERENTIAL, not by an argument.** `Project.narrowReferenceSweeps` is the
  in-binary OFF arm ((API.6)'s shape) and `scripts/reference-narrowing-differential.sh` runs
  both over a real project, element for element: **EQUIVALENT** — 60 carets drawn by stride over all 381,775 occurrences, **59 of them actually narrowed** (the control), **0 diverged**, 12,248 hits compared element for element; mean partition **17.5 of 78 files**, aggregate 182.0 s narrowed against 561.6 s whole-program (**3.09x** on a draw that lands proportional to occurrence count, i.e. on the hottest names). `Project.narrowedSweepFiles` is the
  CONTROL — a caret that refuses falls back and then agrees with itself, so without a count of
  the carets that actually took the new path a run in which everything refused would print
  EQUIVALENT having tested nothing (round 790's dead verifier).
  **THE ESCAPE GUARDS ARE CONSERVATISM TODAY, AND THE ROUND SAYS SO RATHER THAN CLAIMING A
  FIX.** Ablation a3 (nothing is an escape) reddens only the three REFUSAL pins; the
  equivalence assertions above them pass, i.e. the narrowed answer would still be right on
  every fixtured shape. They are kept because the gap they anticipate is **measured**: tsc
  7.0.2's own language server answers **6** references on a `export { renamed as default }`
  declaration — both `d` occurrences in the importing file included — where this API answers
  **2** (`scripts/lsp_member_refs.py`). The day that divergence closes is the day the guard
  becomes load-bearing, and `ProjectReferenceNarrowingTest` pins it so that day is loud.
  **+12 pins, four-arm ablation, four DISTINCT red sets** (a1 alias closure -> the two alias
  pins; a2 escape-aware file filter -> the escape pin; a3 `isAliasEscape` -> the three refusal
  pins; a4 the `default` spelling -> the export-renamed-to-default pin).
  **NEXT, and it is NOT free**: `renameAt` rides the same sweep and is 20-26 s, but it reads
  the build's DIAGNOSTICS as well as its captures — a partition filters those to its own
  files, so the before/after multiset comparison has to be narrowed on BOTH sides, and
  `verifyRename` additionally scans for occurrences already spelling the NEW name, which the
  selection must therefore carry.

- [x] **(INC.47) LANDED 2026-08-29 AS A CANONICAL SERIALIZATION RATHER THAN SCC HASHING,
  AND ITS PRIZE IS REFUTED — the walk is LINEAR and the escape class is EMPTY, but the
  stability rate is 67% on BOTH arms with all 40 per-case verdicts IDENTICAL.** There was
  no SCC left to hash: discovering each reachable type once and naming it by its discovery
  INDEX makes a reference — forward, back or self — cost one lookup, so cycles need no
  special case. `types.ts` went from **122.52 ms for ONE export and a node-budget STOP** to
  **6.21 ms for 871 exports**; whole-program 131 -> **16 ms**; structural nodes 2,019,605 ->
  **38,502**; escapes `[types.ts]` -> **[]**; both controls held (78/78, 24/24).
  **THE 87.5% CEILING WAS A MIS-READ LABEL** on `Inc46StabilityMain`'s own summary line
  (`if (escaped)` counts every case that TOUCHED an escaping file, not one that moved only
  because of it) — exactly ONE of the 8 had the escape as its only mover, so the real
  ceiling was 70%, and after this even that case moves. **IT LANDS ON SOUNDNESS**: the old
  walk's DEPTH CAP of 24 hashed everything below it as one constant, i.e. a MISSED
  invalidation live since (INC.46)(3) began serving stale-free project diagnostics; both
  new pins are RED on the pre-(INC.47) binary. **DO NOT RE-OPEN SCC HASHING.**
  ORIGINAL ENTRY: SCC-AWARE HASHING — the one lever between the measured 67% and the 87.5%
  ceiling, and (INC.46)'s named successor.** `types.ts` is the only file of the 78 that still
  ESCAPES, and it accounts for **8 of the 13 fallbacks** in the 40-commit corpus. Its walk is a
  node-budget stop that no budget closes: **129.6 ms at 2,000,000 nodes and 741 ms at
  12,000,000, still stopping** — the file-boundary cut cannot help INSIDE a file, and `types.ts`
  declares ~874 mutually recursive interfaces in one. **The mechanism is Tarjan over the in-file
  type graph, hashing each strongly-connected component as a UNIT** rather than trying to
  memoize closed subtrees that never close. **Grade it on the instruments that already exist**:
  `scripts/inc46-fingerprint-cost.sh` (cost, stability 78/78, partition agreement 24/24) and
  `scripts/inc46-stability.sh` (the rate — the number that must move, and the refusal threshold
  is that it does not).

- [x] **(INC.48) LANDED 2026-08-29 — `Project.saveState()`/`restoreState()`, and a restart
  is **60x**: 155-175 ms against 9,625-9,844 ms in a COLD process, 94 ms against 5,855 warm,
  with a 47 KB snapshot and every arm agreeing row for row.** It writes no file (the host
  decides where its caches live); it validates the compiler build id, the config path, a
  CONTENT hash per file AND per `.json` input; and a restored state is not trusted until
  one build has re-crawled the project, because a file ADDED while the process was down is
  in no content hash — ablated, that is the pin the naive implementation fails.
  ORIGINAL ENTRY: THE EXPORT SURFACE DIES WITH THE PROCESS, AND tsgo's DOES NOT — an IDE
  restart pays a FULL build where tsgo pays a `.tsbuildinfo` read.** Ours is `Project.surface`,
  in-memory, dropped at `close()`. Theirs is serialised and re-read, which is what makes their
  **182 ms no-op** possible from a cold process at all. **The prize is bounded and known**: it
  turns a post-restart first query from a full build into the (INC.46) gate, i.e. ~5.2 s ->
  ~230 ms whenever the tree has not moved under the editor. **The hazards are the ones the
  fingerprint already documents** — it must be keyed on CONTENT (the crawl reads every file
  anyway; an mtime/size key is round 871's trap), it must carry the compiler options and the
  program's file list or a config change serves a stale surface, and a version stamp must
  refuse a file written by a different build. **Measure the serialise/deserialise cost against
  the 136 ms it replaces before building the invalidation.**

- [ ] **(INC.54) THE FLOOR AFTER (INC.53), IN PRIORITY ORDER — AND THE FIRST ONE IS THE
  SAME QUESTION ONE LAYER UP.** (INC.53) took the `Checker` constructor's ~494 property
  initializers from ~20 ms to ~10 by moving three whole-program indices onto first ask, and
  refused the fourth with its price. What is left of a 63-72 ms floor, measured
  2026-08-29 (`scripts/floor-decomposition.sh`, and read as a mean of two draws because a
  single row on this floor swings ~40%):
  **(a) THE PASS TABLE, ~19-24 ms — the largest block now.** Top rows:
  `init:computeAllEnumValues` **6.9 ms** (already optimised once by (INC.52) and still
  #1), `init:moduleTypeNameIndex` 2.6, `checkModulePreserve4Pin` 1.7 (a known (INC.21)
  straggler — a whole-program `.contains` ABOVE the loop, so gating the loop banks ~0.02
  and only a NAME PRE-GATE banks the ms), `init:computePerFileVisibility` 1.4,
  `checkJsxImportResolutions` 1.2, `init:buildPerFileScopes` 1.0. These are `pass("…")`
  bodies, so unlike (INC.53)'s they ARE visible to `--passTiming` — the open question is
  whether an `init:` pass that builds a program-wide TABLE can be built on FIRST ASK the
  way a field initializer could. **Check the read sites first**: (INC.53)'s three were
  affordable precisely because each had exactly ONE, and round 609 forbids gating a
  program-wide COLLECTOR onto the partition.
  **(b) THE CRAWL RE-READS AND RE-DECODES EVERY FILE ON EVERY QUERY — 10-12 ms wall,
  **44-56 ms of CPU** across the crawl's workers, for 9,977,097 chars — although the PARSE
  is already fully content-cached (`78 reused / 0 fresh`).** The bytes are read only to
  compute the content key. For a host that OWNS its VFS (an IDE) that is redundant, but
  skipping a read is a soundness change (a file changed on disk without `updateFile` would
  be missed) — so it is an opt-in `Project` policy, not a compiler default, and it is
  (INC.48)'s "a content hash cannot see an ADDED file" hazard in a second costume. Largest
  remaining FRONT-END row and it scales with project size, which is what an IntelliJ-sized
  project would feel.
  **(c) `parseBuiltinLib`, ~8-11 ms — REFUSED by (INC.53) with its split measured** (binds
  3.2-5.3, decl-set walk 1.9-2.8, resolution + 45 `mergeSymbolTable` 3.1-5.3) and BLOCKED
  on round 884's `mergedSymbols` clone-on-write: the checker merges into and mutates lib
  symbols, so neither the bind nor the merged table is shareable across checkers today.
  Do not re-open it before that lands, and do NOT re-open the data-class-keyed node sets —
  that hypothesis is measured wrong.

- [ ] **(INC.49) — NARROWED BY (INC.48): THE *RESTART* HALF IS CLOSED, AND WHAT IS LEFT IS
  THE FIRST-EVER OPEN.** With a snapshot restored, a cold process answers its first query in
  **155-175 ms** rather than 9.6 s, because the JIT ramp barely touches a path that never
  checks the whole program — so "cold start" is only the artifact-stack problem below for a
  project this host has NEVER seen. Re-take the cell with that split before spending an
  artifact decision on it. ORIGINAL ENTRY: COLD START IS THE LANGUAGE SERVICE'S WORST NUMBER
  BY FAR — 23,266 ms against tsgo's 1,631 ms, and it is an ARTIFACT-STACK problem rather
  than a compiler one.** Measured
  this round on tsc's own 78 sources: the first `diagnostics()` in a fresh JVM is **23.3 s**,
  the same build warm is **5,352 ms**, so **~18 s is JVM start plus the JIT ramp**. That is the
  first thing an integrator sees and it is 14x tsgo's whole cold check. **Nothing in the
  (INC.\*) arc can move it** — the levers are the ones already priced elsewhere and never
  pointed at this query: the GraalVM PGO image (**-21.2% check-only, and 1.93x FASTER than
  tsc 6.0.3**, `docs/perf/aot-native-image.md` § 10), the JDK 25 AOT cache (1.64x, and its
  fail-safe guard), and CRaC (a warmed checkpoint restoring in ~30 ms with the FIRST compile at
  full warm speed — refused as unshippable only because the restored process keeps the
  checkpoint's working directory, which `SystemVfs.workingDirectory` can now re-install).
  **Decide which artifact the embedding API ships on, then re-take this one cell.**

- [x] **(INC.50) MEASURED AND REFUSED BY ITS OWN THRESHOLD 2026-08-29 — LAYERED CODE IS
  **NOT** MATERIALLY ABOVE 67%: `cronstrue` reads **50%** and `marked` **72%**, bracketing
  tsc's 67%, and the higher arm carries a bias TOWARD stability (18 ours-only rows degrade
  some of its types to `any`).** The rate tracks what a codebase's commits TOUCH rather
  than how layered it is — cronstrue's edits are to the locale classes that ARE its
  surface. `scripts/inc50-stability-lib.sh` is the harness (any repo via LIB/REPO/TSCONFIG/
  PKGJSON) and it found (INC.51) in one run. **The per-hop closure stays refused**: the
  residual third are commits that genuinely move a signature, so only re-checking fewer
  DEPENDENTS can serve them, which is what (INC.35) measured at 100% of tsc's characters.
  ORIGINAL ENTRY, whose question is now answered: THE 67% IS NOT IMPROVABLE ON *THIS* CORPUS BY ANY MECHANISM, SO THE ONLY OPEN
  QUESTION IS WHETHER ORDINARY LAYERED CODE HAS A HIGHER RATE.** (INC.47) removed every
  escape and moved the rate by nothing, with all 40 verdicts identical — so the residual
  33% is 13 commits that each genuinely move an exported signature, and no fingerprint
  refinement can serve them. The `(LIB.*)` screened libraries (`knip`, `jsonrepair`,
  `cronstrue`) are the corpus, `scripts/inc46-stability.sh` is the instrument (it takes a
  corpus dir and a profile dir), and the deliverable is the RATE on code that is not one
  compiler's own sources. ORIGINAL ENTRY: IS THE CLOSURE WORTH BUILDING ON *LAYERED* CODE? tsgo IMPLEMENTS PER-HOP
  PRUNING AND IT BUYS THEM NOTHING HERE — 1,654 ms against a 1,631 ms COLD check.** That is an
  independent corroboration, from another implementation, of the measurement that closed
  (INC.35): on tsc's own sources a file-level AND a symbol-level use graph both re-check ~100%
  of the program at the median edit. **But it is a claim about ONE codebase, and theirs is the
  design that would pay if the claim does not generalise**: on a signature change they walk the
  reverse-reference graph and re-check a dependent only if ITS signature also moved, where we
  fall back to a whole-program build. **The (LIB.\*) screened libraries are the corpus that
  could decide it** (`knip`, `jsonrepair`, `cronstrue` — layered, unlike the dashboard
  profile). **Refuse it unless the measured stability rate on a layered corpus is materially
  above the 67% measured here**; the point of the item is the measurement, not the mechanism.

- [ ] **(BENCH.5) EVERY tsgo COMPARISON IS NON-LIKE-FOR-LIKE UNTIL THE 46-vs-65 DIAGNOSTIC GAP
  IS DECOMPOSED, AND THIS REPO ALREADY HAS THE LAW.** `kir-bench.sh` runs an equivalence gate
  BEFORE any timing, precisely because a wall-clock harness reads a program that does LESS as
  the fastest arm. `docs/perf/incremental-vs-tsgo.md` does not satisfy it: on the compiler
  profile we report **46** rows where tsgo 7.0.2 reports **65**, so every ratio in that page
  flatters us by an undecomposed margin. **The deliverable is the 19-row decomposition** — how
  many are genuine false negatives of ours, how many are tsgo-only divergences from pristine
  tsc (round 938's law: tsgo is NOT pristine, and `scripts/pristine_oracle.py` is the arbiter),
  and how many are a `lib`/options difference. Only then is a timing comparison between the two
  compilers quotable as a compiler comparison rather than as an architecture one.

- [x] **(INC.46) PROJECT-WIDE DIAGNOSTICS BY *EXPORTED-SIGNATURE STABILITY* — ALL THREE
  STEPS LANDED 2026-08-29. Cost 136 ms whole-program / ~0 ms per edit; stability **67%**
  over 40 real commits (floor; ceiling 87.5% once `types.ts`'s in-file SCC is hashed);
  `Project.diagnostics()` graded **EQUIVALENT 40/40 with served=27**. The successor is
  SCC-AWARE HASHING — Tarjan over the in-file type graph, hashing each strongly-connected
  component as a unit — which is the one lever between the measured floor and the ceiling,
  and it is the only thing standing between this and every edit being incremental.
  ORIGINAL ENTRY: PROJECT-WIDE DIAGNOSTICS BY *EXPORTED-SIGNATURE STABILITY*, NOT BY A
  DEPENDENCY CLOSURE — THE OWNER'S IDEA, AND IT DISSOLVES (INC.35)'s BLOCKER RATHER THAN
  WORKING AROUND IT.** Owner, 2026-08-29: *"if we do `import *` in a certain file and then
  recompile this file, don't we have the information of all the resolved imported symbols
  this file is using?"* We do — `capturedDefinitions` is span -> declaration location, a
  by-product of a build we already run. **But a symbol-level use graph was MEASURED THIS
  SESSION AND IT DOES NOT HELP**, and that refutes the queue's own standing explanation as
  well as the hypothesis:

  | graph over tsc's 78 compiler sources | median edit re-checks |
  |---|---|
  | file-level (what round 772 measured) | 99% of files / **100% of chars** |
  | **symbol-level** (94.9% of imported names placed to a declaring file) | 95% of files / **100% of chars** |

  **The `export *` BARREL WAS NEVER THE CAUSE** — (INC.35) and round 772 both say or imply
  it was, and both are wrong about the mechanism. `checker.ts` genuinely uses symbols from
  `types.ts` / `core.ts` / `utilities.ts` / `debug.ts` / `parser.ts`, everyone uses `core.ts`
  and `debug.ts`, and the relation is transitive. Knowing WHICH symbols a file imports buys
  nothing when the answer is "most of them, from most files".

  **WHAT DOES CRACK IT IS THE SECOND HALF OF THE IDEA: ask whether the symbols a file uses
  have CHANGED, not which they are.** An edit to a function BODY leaves every exported
  signature intact, so no dependent needs re-checking and the closure collapses to `{the
  edited file}` however dense the graph is — transitivity fires only when an edit actually
  moves an exported TYPE. **91.6% of the program's characters sit inside brace-delimited
  bodies** (stripper length-preserving, positive control passed), so most edit POSITIONS
  cannot change a signature. Read that as a proxy and not a rate: it is optimistic because an
  INFERRED return type leaks a body change back into the signature, and pessimistic because
  it counts `interface`/`type` bodies, which ARE signature, as body text. **The honest rate
  needs an edit corpus and this checkout cannot supply one** (`typescript-repo` is a depth-1
  shallow clone and is a build-pinned input — do not deepen it; fetch a separate clone).

  **AND THIS IS WHY IT MATTERS MORE THAN (INC.35): A SIGNATURE HASH PAYS ON *DENSE* CODE
  TOO.** (INC.35) is owner-closed because a closure only pays on LAYERED code and the
  dashboard profile is the opposite; this mechanism can be built and graded on tsc's own
  sources, i.e. **it needs no corpus choice and no owner call**.

  **THE PRIZE IS ALREADY MEASURED AND NEEDS NO NEW RUN.** A body-only edit to file F would
  cost a narrowed build of `{F}` plus a merge, against a full rebuild: **108-113 ms median
  (p90 202-219) against 4,864-5,096 ms — a factor of 45** ((INC.31)/(INC.37), 2026-08-24,
  `d018af0a`, § 14). `checker.ts`, the 31.6% file, is 1,744-1,763 ms against the same 4.9 s.

  **THE COST HALF IS THE UNMEASURED ONE, AND ITS INPUT IS CENSUSED: 3,398 exported
  declarations over the 78 files** (mean 44, median **6**, max **874** in `types.ts`). So the
  per-build work is ~3,400 `getTypeOfSymbol` + fingerprint calls, against a rebuild that
  already makes ~800 k `getTypeOfExpression` calls — almost certainly single-digit ms, but
  **that is an argument and not a measurement; hook it and read it before building anything
  downstream of it.**

  **THE HAZARD THAT WOULD SINK IT, AND IT IS NOT THE OBVIOUS ONE.** The tempting hash source
  is `typeToString(getTypeOfSymbol(exported))` — a resolved type rather than syntax, which is
  the right SOUNDNESS instinct (a syntactic hash misses an inferred return type). **It is
  still the wrong source, for two reasons this repo has already documented in another
  context.** (i) `typeToString` is **not a pure function of the type**: `aliasDisplayMap` is a
  FIRST-WINS global keyed by `Type.id` ((INC.11)/(INC.26)/(INC.41)), so the same type renders
  differently depending on what was resolved first — spurious invalidation, which is SAFE but
  may be frequent enough to eat the whole prize. (ii) B58.1 renders `errorType` as **`"any"`**,
  so a type that DEGRADES to a resolution failure hashes identically to a genuine `any` —
  **a missed invalidation, i.e. a stale diagnostic, silently**, which is the only direction
  that matters. **The hash must therefore be an ID-FREE STRUCTURAL FINGERPRINT** (member names
  + modifiers + recursively fingerprinted member types, cycle-guarded), never a display
  string and never anything keyed on `Type.id`, which is a per-build sequence.
  **(INC.16) already built exactly such a fingerprint for the INV.2(c) lexical tables — copy
  its shape rather than inventing one.**

  **WHAT ELSE MUST BE IN THE HASH, or the invalidation is unsound**: the SET of exported names
  (an added or removed export changes resolution in every importer, with no type moving); the
  targets of `export *`; and a whole-program escape for any file declaring GLOBALS or
  augmenting a module — **5 of the 78 carry `declare global` / `declare module "…"` /
  `export as namespace`** (regex-approximate; re-derive it from the binder, not from text).

  **ORDER OF WORK, and it is measure-first by construction.**
  (1) **DONE — the threshold is MET and the walk's SHAPE was the real question.** Built,
  measured and pinned: **136 ms whole-program** on a 5,215 ms rebuild, and **0 ms on 23 of
  24 narrowed builds** (a narrowed build fingerprints only its partition), so the per-EDIT
  cost of the gate is under a millisecond. **Two controls decide feasibility and neither is
  a cost figure**: two builds of identical text agree **78/78** (the id-freedom claim), and
  a narrowed build's fingerprint equals the whole-program one **24/24** (the CONVERGENCE
  claim — without it every first edit falls back forever). **THE COST INPUT CENSUSED ABOVE
  IS THE WRONG QUANTITY**: cost tracks the transitive type CLOSURE, not the export COUNT,
  and the two are near-inversely related — `utilities.ts`'s 692 exports are 1.6 ms and
  `types.ts` is 129.6 ms. **AND THE OBVIOUS WALK DOES NOT TERMINATE**: a path-only cycle
  guard is exponential in DAG width (159 s inside one build), and closed-subtree
  memoization is still not enough because tsc's type graph is one giant SCC (6 of 78 files
  unfinished inside a 2,000,000-node budget). What works is CUTTING at the file boundary —
  a type declared elsewhere is unchanged by construction while only this file is edited, so
  it is keyed by its declaration's `(fileName, pos, end)` and not descended into. See the
  (INC.46)(1) session note and `Checker.ExportFingerprinter`. **Escape set: 2 of 78** —
  `types.ts` (budget stop) and `checker.ts` (an exported name with no file-level symbol,
  UNDIAGNOSED and the first thing to look at, since it is the file an editor edits most).
  (2) **DONE — 67% MEASURED, AND NOT REFUSED.** `scripts/inc46-stability.sh` fetches its own
  blob-filtered depth-3000 clone of microsoft/TypeScript (never `typescript-repo`, which is
  build-pinned) and replays **40 real no-merge commits** touching `src/compiler`, whole tree
  at the parent against whole tree at the commit. **27 of 40 stable = 67%**, right at the
  stated threshold — and **8 of the 13 that moved did so ONLY because `types.ts` ESCAPES**,
  so the achievable band is **67% floor, 87.5% ceiling** with ONE named lever between them.
  **THE FIRST READING WAS 32% AND WAS AN ARTIFACT**: `declaresGlobalSurface` scanned whole
  source for `export as namespace` (a construct with no AST node), `checker.ts` says those
  words twice IN COMMENTS, and since it is the most-edited file that one false positive was
  worth **35 points**. Anchoring the match to the start of a line fixed it. **`types.ts`'s
  escape is STRUCTURAL and measured**: a node-budget stop at 2,000,000 (129.6 ms) AND at
  **12,000,000 (741 ms, still stopping)** — the file-boundary cut cannot help INSIDE a file,
  and `types.ts` declares ~874 mutually recursive interfaces in one file. The lever is
  **SCC-aware hashing** (Tarjan, hash each component as a unit); the budget stays bounded at
  2,000,000 and the file is recorded in `ExportSignatures.whole`, which costs a full rebuild
  and never a stale diagnostic.
  (3) **DONE — `Project.diagnostics()` IS INCREMENTAL.** `Project.surface` +
  `incrementalDiagnostics`, `ProjectCompiler.build(exportSignatures = …)` and two new
  `Result` fields. Five preconditions, each CHECKED rather than argued (a baseline exists;
  every edited file was in that program; none ESCAPES; the narrowed build finds the same
  program; no fingerprint moved), each with its own pin. **GRADED EQUIVALENT — 40 of 40
  real commits agree row for row with a fresh whole-program build, `served=27`**, the
  control that keeps the agreement from being vacuous (a harness whose `served` is 0
  REFUSES). 11 pins in `ProjectIncrementalDiagnosticsTest`, paired ANSWER and COST families
  because a cost-free pin set passes against the old always-rebuild behaviour.
  **GRADE IT AS A DIFFERENTIAL, which needs no baseline**: after an edit, incremental
  project-wide diagnostics must equal a full rebuild's, row for row, over a SEQUENCE of edits
  — and the sequence must contain a signature-CHANGING edit and a body-only one, or the gate
  is vacuous in exactly the way (INC.45)'s arm b2 was (a clean fixture made a
  diagnostic-multiset comparison compare empty against empty and pass).
  **Note what it is NOT**: this is tsc's own `--incremental` design (a per-file signature in
  `tsbuildinfo`, hashed from the declaration emit, with dependents skipped when it has not
  moved). We have no declaration emitter — `declaration`/`emitDeclarationOnly` are parsed
  options with no emitter behind them — which is *why* the fingerprint goes over resolved
  types directly instead.

- [x] **(INC.45) `renameAt` IS NARROWED TOO — LANDED 2026-08-29, AND ITS THREE OBSTACLES
  WERE ALL REAL.** The rename sweep performed the same whole-program capture (INC.44) removed
  from `referencesAt` and paid the same 20-26 s for it. It now takes the same spelling closure
  and hands the resulting file set to the compiler as a check partition. The three things that
  made it not a copy of the reference change were each answered rather than assumed:
  **(1) THE DIAGNOSTIC MULTISET.** `verifyRename` compares `(file, code)` bags before and
  after applying the plan; a partition filters diagnostics to its own files, so the two builds
  must SHARE one. `RenameSweep.partition` carries it and the after-build takes it rather than
  deriving its own from the spans it happens to ask about. The soundness argument is written
  into `narrowedRenameSweep` and is the one a reviewer should attack first: a rename edits only
  files the plan names, all of which are in the partition, and an unedited file's meaning can
  change only through a name it imports — which it must then SPELL.
  **(2) THE NEW NAME HAD TO WIDEN THE SELECTION.** `verifyRename`'s third check — the only one
  that can see a rename which compiles and means something else — scans for occurrences ALREADY
  spelling the new name. Selected on the old name's closure alone it finds nothing and passes
  VACUOUSLY, i.e. the narrowing would have switched the safety net off rather than paying less
  for it. The new name joins the SELECTION and not the CLOSURE: it is not a spelling of the
  symbol being renamed, so letting it contribute alias links or escapes would make the
  partition a function of a name that names something else.
  **(3) AND A PLAN COMPARISON CANNOT SEE (2).** On a fixture whose new name is fresh both arms
  agree with or without the widening, so the pin is a COUNT — `Project.narrowedRenameFiles`
  reaches a file the reference partition at the same caret does not.
  **THE ABLATION FOUND A BLIND PIN SET AND THE FIX IS IN THE FIXTURE, NOT THE ASSERTION.** Arm
  b2 (the after-build forgets the sweep's partition) reddened **NOTHING** on the first run:
  every fixture was a CLEAN program, so both bags were empty whatever either build walked and
  the comparison was empty-against-empty. Adding one file that carries a diagnostic and spells
  none of the renamed names takes b2 to **2 RED**. **Arm b3 — never narrow at all — is
  UNDISCRIMINATED and is recorded as such**: the change is equivalence-preserving by
  construction, so no assertion about an ANSWER can see it; what stands in its place is a
  single pin on the shipped DEFAULT with no mode install in it ((INC.16)'s lesson), and the
  cost measurements, which are wall time and pinned by nothing.
  **GRADED** by `scripts/rename-narrowing-differential.sh`, which compares whole `RenamePlan`s
  — a data class, so equality covers every edit's file, span and text, the refusal and the
  conflict list — and prints `applicable=` beside `narrowed=` because two REFUSALS compare
  equal and a run with no applicable plan in it has compared two empty edit lists.
  **EQUIVALENT** — 8 carets by stride over all 381,775 occurrences, **7 narrowed**, **6
  producing an APPLICABLE plan** (the second control), 1,691 edits compared plan for plan,
  **0 diverged**; 56.5 s narrowed against 114.2 s whole-program (**2.02x** on a draw that
  lands proportional to occurrence count, i.e. on the hottest names). **Draw few carets**:
  a rename holds a whole-program sweep per arm and a 20-caret run at `-Xmx6g` was
  OOM-KILLED — the tell is a harness that stops after its header with no verdict line.
  **+6 pins** (`ProjectRenameNarrowingTest`, plus the shipped-default pin in
  `ProjectReferenceNarrowingTest`); three ablation arms, b1 -> 1 RED, b2 -> 2 RED (after the
  fixture repair), b3 undiscriminated with a reason. **MEASURED per symbol**, both arms
  interleaved in one process: `emitFiles` **2 of 78 files, 1,304 ms against 15,933**;
  `transformNodes` 3 of 78, **1,025 ms against 14,871**; `checkSourceElement` 1 of 78 (but
  that file is `checker.ts`), **4,725 ms against 15,198** — so an ordinary rename is
  **~1.0-1.3 s against ~15 s (12-14.5x)**.
  **SUCCESSOR, per the WORK ORDER note — a round must name one.** With (INC.44)/(INC.45)
  landed, the ONLY interactive operation left that is whole-program in every case is
  project-wide `diagnostics()` at 4,864-5,096 ms per edit, and it is **owner-closed as
  (INC.35)** with a stated re-open condition this session's directive does not meet: "RE-
  OPENABLE ONLY on an owner directive naming a LAYERED corpus to grade it on". Round 772's
  measurement is why — tsc's own sources are `export *` barrels, so a reverse-dependency
  closure reports `77/78` for a LEAF and buys nothing on the very profile every gate here
  uses. **The (LIB.\*) screened libraries (knip, jsonrepair, cronstrue) are the corpora that
  could grade it**, so the decision the owner would be making is which one, not whether the
  mechanism works. **BUT (INC.46) SUPERSEDES THAT CHOICE ENTIRELY, AND ALSO MEASURES THE
  BARREL EXPLANATION ABOVE TO BE WRONG**: a symbol-level use graph re-checks 100% of tsc's
  characters at the median edit, exactly as the file-level one does, so those files' density
  and not their `export *` is what defeats a closure. An exported-SIGNATURE hash pays on
  DENSE code as well as layered, so it is gradable on the dashboard profile and needs no
  corpus and no owner call — take it BEFORE re-opening this. The other named successors are
  (INC.39) (the per-handler spine cost under a single-file partition, still unpriced on its
  own terms) and (INC.33)'s unmeasured half — the PREPARE-AMORTISED case for wiring
  `completionsAt`/`signatureHelpAt` to `prepared`, which neither (INC.32) nor (INC.33)
  measured.
  **Suite 16,440 / 0 / 3** (+18 over the
  session's re-verified 16,422 baseline, exactly the new pins).

- [ ] **(INC.43) THE 213 ROWS (INC.42) DID NOT CLOSE — AND THEY ARE NOT WHAT THE QUEUE HAS
  BEEN CALLING THEM.** Re-measured after (INC.42) landed: `Inc41ClassifyMain` reads **796 rows
  / 37 pairs / 213 GAINED-INFERENCE, UNCHANGED**, and REPLAY-WORSE did not grow. **Read out of
  the classifier's own dump rather than assumed, the p000 rows are NOT hovers on `Visitor`**:
  they are carets on `visitEachChild` / `visitFunctionBody` / `discardVisitor` — **function
  names whose rendered OVERLOAD SET carries a parameter declared `Visitor`**. So the string
  comes from the **CHECKING** path (`getTypeFromTypeReference` on a bare `Visitor`), which
  (INC.42) deliberately does not reach, and **both arms render an unbound parameter**:
  `(node: TIn) => any` fresh, `(node: TIn) => T | readonly Node[]` replayed. tsc renders
  `Visitor`.
  **REACHING IT IS BLOCKED THREE TIMES, EACH COST MEASURED — READ THESE BEFORE PROPOSING
  ANYTHING.**
  (1) **(INC.28)**: handing a reference the alias's PARAMETRIC form costs two corpus false
  positives (`typeArgumentDefaultUsesConstraintOnCircularDefault`,
  `excessPropertyCheckIntersectionWithRecursiveType`).
  (2) **(INC.42)**: relaxing B57.1b's constraint guard on the CHECKING path (i.e. dropping
  `aliasBodyDisplayDepth`) reads `output.errors` **46 -> 48** on the compiler profile — an
  overload-resolution defect at `checker.ts:2503` that a no-longer-`any` `VisitResult<T>`
  exposes, plus a TS2322 at `watchPublic.ts:576`. Two dashboard false positives against 213
  hovers is not a trade.
  (3) **Even with both closed**, we would render `(node: TIn) => VisitResult<TOut>` where tsc
  renders `Visitor` — B50.5 deliberately does not register an alias NAME for a result that is a
  pure function type (`isPureFunctionType`, pinned by `nestedCallbackErrorNotFlattened_ts`).
  **VERDICT: this is a RELATION-ENGINE item ((INC.30)) plus an alias-NAMING one, NOT a display
  bug**, and the honest order is (1) before (2) before (3). Do not attempt it as a rendering
  fix — (INC.26)/(INC.27) established that `typeToString` is shared with the diagnostics and
  pinned byte-for-byte across ~13k baselines.
  **PRIZE: UNMEASURED, AND DELIBERATELY SO — a correctness item, not a latency one.** It buys
  no milliseconds; it makes a hover right. The pin must assert the **VALUE** against
  `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` (round 924's oracle,
  `scripts/lsp_hover.py` / `scripts/lsp_hover_project.py` — read the profile's sources with
  `newline=""`, it is CRLF), never that two arms agree: the capture sweeps are DIFFERENTIALS
  and are blind to anything both arms get wrong ((INC.28)'s law).
  **AUTHORITY: `docs/inc41-replay-capture-classification.md` § 6a**, with § 3's per-cause table
  and § 7's grading rule — a change is an improvement only if the **element-pair** count falls
  ((INC.23): 192 of the 796 rows carry more than one differing element, so a ROW count
  over-reports). The two smaller causes in the same bucket are a different question and are
  probably not one fix: `ModuleName` -> tsc's `StringLiteral` (74 rows) and
  `ImportAttributeName` -> `StringLiteral` (62), where we WIDEN and tsc narrows.

- [x] **(INC.4) LANDED 2026-08-22 — `ProjectCompiler.build` now refuses it, 4 pins
  including the DEFAULT-`noEmit` case and both negative controls. ORIGINAL ENTRY:
  `recheckOnly` + EMIT IS UNSOUND AND `ProjectCompiler.build` DOES NOT REFUSE IT.** The Transformer queries the checker it is handed (`isReferencedAliasDeclaration`
  and friends), so under a partition it asks a checker that walked a SUBSET and elision
  goes wrong. Every driver gates incremental on `--noEmit` and `Project` always passes
  `noEmit = true`, so nothing today is wrong — but the parameter is public and the next
  caller will not know. `require(noEmit || recheckOnly == null)`, with the message naming
  the caller's mistake, exactly as `compileParsed` already does for `checkedSink`.

- [x] **(INC.5) LANDED 2026-08-22 — 45 divergent spans -> 9, and the 40 wrong-direction
  rows -> 4. See the session note; what is left is (INC.6). ORIGINAL ENTRY: WHAT A HOVER REPORTS DEPENDS ON PROGRAM ORDER — A PRE-EXISTING DEFECT
  (INC.2) MADE VISIBLE, AND IT IS NOT ABOUT PARTITIONS.** `symbolTypes` persists the first
  resolution of a symbol's type, and resolving a type reference inside an anonymous object
  type literal answers differently depending on which file asks first: in the same program,
  the whole-program build renders `(key: K, valueInNewMap: U) => any` for a span where a
  narrowed build renders `=> T`, and elsewhere the reverse. **Neither arm is canonically
  right; they are two draws from an order-dependent cache.** Today the order is fixed by
  the crawl (`ProjectCompiler.walk` sorts, and CLAUDE.md records that three orders of the
  same 78 files move `typeNode.bypassed` ~1% with every diagnostic bit-identical), so a
  user sees ONE answer consistently — which is why this has never been reported. It is
  still a wrong answer where the collapse is to `any`.
  **THE INSTRUMENT ALREADY EXISTS**: `scripts/capture-equivalence.sh` reads 45 divergent
  spans out of 381,666 in one run, and the full-vs-narrow pair is a differential ORACLE
  for it — no baseline needed, because the two arms must agree. Start there rather than by
  reading the resolver: the census names the 11 files and the exact spans.
  **THE SEAM IS NAMED BY THE DIVERGENT ROWS THEMSELVES, AND IT IS NOT NAME RESOLUTION.**
  One row loses a KEYWORD type (`{ fileName: string }` -> `{ fileName: any }`) and another
  a mapped-type modifier (`Required<{ reportInferenceFallback(node: Node): void }>` ->
  `Required<{ reportInferenceFallback?: any | undefined }>`). A name resolving in the wrong
  file's scope cannot lose `string` or `-?`; an UNRESOLVED MEMBER TABLE can. So this is
  round 833's hazard one layer up — *a target type's member table is LAZY, so a verdict
  depends on whether an earlier line in the file happened to resolve that type* — with
  `typeToString` as the reader and A DIFFERENT FILE'S CHECK as the "earlier line" that a
  whole-program build always happens to perform.
  **THE FIX IS THEREFORE SMALL AND SURGICAL, AND IT BELONGS IN THE CAPTURE PATH ONLY:**
  force `resolveStructuredTypeMembers` on the type about to be rendered (and on the member
  types it recurses into) before `typeToString`. Doing it inside `typeToString` itself
  would change DIAGNOSTIC MESSAGES program-wide and put ~13k corpus baselines in play for
  a language-service defect; doing it where the capture records its display string cannot
  move a single diagnostic, which is what makes it landable in one round.
  Then re-run `scripts/capture-equivalence.sh`: expect the 40 `any` rows to clear and the
  5 REVERSED rows (where the full build is the one showing `any`) to need their own
  diagnosis — they are the same order-dependence seen from the other side.
  Closing it also unblocks (INC.2)'s 3.73x.


- [x] **(LIB.1) knip MEASURED 2026-08-22 — 2,634 xtsc errors against tsgo's 23, and 94.1%
  of them are ONE missing feature.** `webpro-nl/knip` at `main`, `packages/knip`: **498
  files, 35,663 lines**, `moduleResolution: nodenext`, `"type": "module"`,
  `verbatimModuleSyntax`, every relative import written with an explicit `.ts` extension.
  Front end: xtsc `--noEmit --listAll` reports **2,634 in 7,131 ms**; tsgo 7.0.2 reports
  **23, all environmental** (no `@types/picomatch`, `webpack`, `@jest/types`,
  `codeclimate-types`) — knip itself is clean under the oracle.
  **TWO CODES ARE 2,478 OF THE 2,634 (94.1%): TS1295×1,959 and TS1287×519**, both saying
  the file is CommonJS. **xtsc does not derive a file's module format from the nearest
  `package.json` `"type"`,** so under nodenext every knip file is classified CommonJS and
  every import and export trips the `verbatimModuleSyntax` guard. The attribution was
  CONFIRMED, not inferred: deleting that one option from the tsconfig reads
  **2,634 -> 156**, and tsgo re-run on the same config still reads 23. Queued as (CHK.29).
  **THE RESIDUAL IS 156 = 0.31 FP/file, BETTER THAN THE 0.9/file `docs/kir-library-readiness.md`
  RECORDS FOR `yaml`, AND IT IS THAT PAGE'S TWO KNOWN FAMILIES**: TS7006×89 (57% — an
  object-literal METHOD's parameters are not contextually typed from the annotated return
  type; (CHK.30)), TS2339×23 (union member access where narrowing did not apply), then
  TS2322×16, TS2552×9, TS18048×7, TS2353×3, TS2769/TS2349/TS2304×2, TS2591/TS2345/TS18047×1.
  **THE OVERLAP WITH tsgo's SET IS ZERO IN BOTH DIRECTIONS — so there are also 23 FALSE
  NEGATIVES**, including two genuine TS2322 and a TS2722 in `src/util/glob-core.ts` that
  tsgo reports and we do not. A residual FP count is not a conformance number until the
  misses are counted too.
  **WHAT WORKED AND IS WORTH RECORDING: module resolution.** All **1,921** relative
  specifiers carry an explicit `.ts` extension (`allowImportingTsExtensions` +
  `rewriteRelativeImportExtensions`) and every one resolved — the type errors name real
  imported types (`Configuration`, `TsConfigJson`, `Plugin`), so (KIR.EMIT.1)'s work holds
  on an unfamiliar codebase.
  **BACKEND: the project probe never reaches the lowering** (it will not emit a program the
  checker rejected), so it was measured on ONE self-contained file —
  `src/util/graph-sequencer.ts`, 131 lines, no imports: `typeErrors=0`, then
  `refused: graph-sequencer.ts:22:74 a spread element is out of the spike subset`.
  Censused against the 17 refusal messages in `lower/`: **destructuring parameter 255 files
  (51%), spread 163 (33%), destructuring declaration 121 (24%), `async`/generators 112
  (22%), computed property name 63 (12%), optional element access 29 (5%)** — the union is
  **237 of 498 files (48%)** before counting anything downstream. `async` is decisive on its
  own: knip's entry point IS `export const main = async (options) => …`.
  **BUT knip IS UNREACHABLE FOR REASONS THAT ARE NOT THE LOWERING, AND THAT IS THE FINDING
  THAT MATTERS FOR PLANNING.** It depends on **two native Rust N-API binaries** —
  `oxc-parser` (32 import sites) and `oxc-resolver` — which are not TypeScript and cannot be
  lowered from; on **10 `node:` builtins** (`fs`×21, `fs/promises`×5, `util`, `path`,
  `module`, `crypto`, `url`, `process`, `perf_hooks`, `child_process`) against a
  `KirIntrinsics.libraryClass` table of exactly **six** entries (`Array`, `Map`, `Set`,
  `RegExp`, `Date`, `Error`); and on `createRequire`×9 plus `jiti`, i.e. evaluating config
  files at run time. **A program whose job is to read the filesystem and parse source with a
  native parser needs a Node-API layer on the JVM, which is a bigger project than the
  lowering.** So knip is the right instrument for the FRONT END and the wrong driver for the
  backend ladder — see (LIB.2).
  **REPRODUCTION** (both halves, ~10 s):
  `java -cp <core-classes>:$(bash scripts/lib/dep-classpath.sh --print) com.xemantic.typescript.compiler.MainKt --noEmit --listAll <knip>/packages/knip`
  and `KIR_PROBE_FILE=<knip>/packages/knip/src/util/graph-sequencer.ts ./gradlew :xemantic-typescript-compiler-kir:jvmTest --tests '*LibraryProbe*' --rerun -i`.
  Oracle: `npm i typescript@7` in a side root, then `tsc --noEmit -p <knip>/packages/knip`.

- [x] **(CHK.29) LANDED 2026-08-25 — the lookup exists; `TS1295+TS1287` on knip go
  **2,478 -> 0** and the library goes 2,634 -> 309 (one draw, no `node_modules`, so 147
  of the 309 are environmental `@types/node` rows). The producer was the missing half:
  `packageJsonTypes` had a CONSUMER and one producer that reads the corpus's parsed
  source set, and a real project has no `package.json` among its INPUTS —
  `ProjectCompiler` now walks the `Vfs` up from each program file's directory, memoized
  per directory, gated on `isNodeNext`. Two corrections tsgo forced: a manifest with no
  `"type"` ESTABLISHES the scope at CommonJS (the walk stops at the first one it meets),
  and the manifest is parsed as JSON — knip's own has `repository.type: "git"` FIRST, so
  a regex answers CommonJS for a `"type": "module"` package. Pins:
  `ProjectPackageJsonTypeTest` (11, `-project`). Residue queued as (CHK.36)-(CHK.38).
  ORIGINAL ENTRY: A FILE'S MODULE FORMAT IS NOT DERIVED FROM THE NEAREST `package.json`
  `"type"` — 2,478 FALSE POSITIVES ON ONE LIBRARY, AND NOTHING IN THE CORPUS CAN SEE IT.**
  Under `module`/`moduleResolution: nodenext` (and `node16`), tsc decides whether a `.ts`
  file is an ES module or CommonJS by walking up to the nearest `package.json` and reading
  its `"type"` field. We do not, so a `"type": "module"` package is classified CommonJS and
  every ESM import/export in it trips `verbatimModuleSyntax`: **TS1295×1,959 + TS1287×519**
  on knip, measured, i.e. 94.1% of that library's error count from one absent lookup
  ((LIB.1)). **THE CORPUS IS STRUCTURALLY BLIND**: tsc's own sources are not
  `"type": "module"`, `usesUnsupportedOption` never skipped these fixtures because the
  option is not in the removed list, and the 8 dashboard profiles all inherit tsc's layout —
  so a green corpus, a green `cost_gate.py` and an `added=0 removed=0` grid are the EXPECTED
  answers here and none of them is evidence. **The pin has to be a project fixture with a
  `package.json` beside the sources** (`-project`'s `ProjectCompiler` path, not `diagnose()`,
  which has no package.json and no directory), asserting both directions: `"type": "module"`
  is silent, and its ABSENCE under nodenext still reports TS1295. Check what else reads the
  format while you are there — `impliedNodeFormat` also decides `esModuleInterop` behaviour,
  the `.mts`/`.cts` extension overrides, and whether a `require()` of an ES module is an
  error, so the fix is one lookup with several consumers.

- [x] **(CHK.30) DONE 2026-08-25 — AND ITS DIAGNOSIS WAS WRONG. The 89 TS7006 were NOT a
  contextual-typing defect: a type imported from a `node_modules` PACKAGE resolved to
  `any`.** knip (`webpro-nl/knip@main`, fetched and reduced this round): **156 -> 66
  errors, TS7006 89 -> 1, and NO row appeared that was not there before.** The entry's own
  example was a victim rather than an instance — `PluginVisitorObject = VisitorObject`,
  and `VisitorObject` comes from `'oxc-parser'`. Its literal-method form, written out by
  hand, has always been correct (`interface V { m?: (n: N) => void }` + `{ m(node) {…} }`
  is silent on a pre-fix binary; the fixture that reproduces is 15 lines and its only
  unusual feature is a `node_modules` package). **The mechanism**: the crawl resolves the
  specifier correctly and the package's `.d.ts` really is in the program, but the CHECKER
  re-derives which file a specifier names by string-matching it against the program's file
  NAMES, and that corpus-era matcher cannot express a bare specifier at all. Fixed by
  carrying the crawl's own `(importer, specifier) -> file` answers
  (`ParsedSource.moduleResolutions`) as the last leg of all ten alias ladders.
  **A SECOND, SMALLER DEFECT LANDED WITH IT**: a concise-body arrow's OWN return
  annotation was not a contextual type for its body in either the implicit-any or the
  property-access walker (a BLOCK body always had it, at the return edge — so
  `(): V => { return {…} }` was right and `(): V => ({…})` was not). Worth 4 more knip rows
  and the curried-factory idiom `(dep: D): Handler => (a, b) => …`.
  Pins: `ProjectPackageTypeResolutionTest`, `ContextualReturnAnnotationTest`.

- [x] **(CHK.39) DONE 2026-08-25 — the pull landed: the item's probe went 0/6 -> 6/6 for the
  ASSIGNABILITY family and for every hover, and the residue is ONE WALKER rather than one shape.**
  `pullContextualTypeAt` is tsc's `getContextualType`, PULLED from the parent chain because the
  spine carries no contextual ambient at all (round 911); it writes the contextual parameter types
  at TWO sites and the ablation partitions them exactly — `checkFunctionBody` is the EMITTING half
  (a statement nested in a function body is emission-owned by that legacy walk: the spine's own
  anchor runs `recordOnly` for it and truncates every diagnostic, so the frame alone is correct
  and invisible) and `ctaFnBodyFrame` is the CAPTURE half a hover reads. B85.1a is load-bearing
  there — an OPTIONAL contextual parameter is `T | undefined`, and the bare type was this round's
  one measured false positive, on three profiles. **(CHK.39b) landed with it**: an object-literal
  METHOD's body was not walked by the assignability walker AT ALL in a `.ts` file
  (`walkFunctionBodiesInExpr`'s `if (jsLike)` — a gate about `this` that was deciding whether the
  body is checked). A KIR soundness defect surfaced and was fixed (a call of a function VALUE is
  arity-ADAPTING, never a direct `FunctionN.invoke` — JS assignability accepts a LOWER-arity
  function, which is what mitt's driver does). **(CHK.39c) is REFUSED and re-queued as (CHK.41).**
  `typeNode.bypassed` +31.26% rebaselined (~+21 ms, and the unspent lever is a per-node memo of
  the pull); knip 66 -> 66 with every row identical; 8-profile grid `added=0 removed=0`. Pins:
  `ContextualParameterTypeTest` (18), `ProjectContextualParamHoverTest` (4, expectations read out
  of tsc's own LSP).

- [x] **(CHK.41) DONE 2026-08-26 — the GUARDED REASSIGNMENT now reduces the DECLARED union,
  and the item's own premise was two-fifths right: the +15 knip rows are FIVE mechanisms.**
  `narrowByAssignmentRhs` gained the two right-hand sides no arm of it could type — a CALL
  WHOSE CALLEE IS THE WALKED REFERENCE (`c = c()`, typed from the ANTECEDENT, which the guard
  has already narrowed, because `getTypeOfExpression` never narrows and
  `resolvedCallReturnTypeForFlow` needs a `FunctionDeclaration`) and a type ASSERTION
  (`c = (await c(x)) as T`, whose type is syntactic, (CHK.43)) — reducing the DECLARED union,
  never the antecedent (round 416's rule; arm a4b, 5 RED). knip **66 -> 66 byte-identical**
  with a rebuilt BEFORE arm, grid `added=0 removed=0` on all eight (a CONTROL, not evidence).
  Pins: `GuardedReassignmentNarrowingTest` (9), every positive paired with the negative half.
  **THE TWO CONTEXTUAL SOURCES STAY REVERTED** — recovered from (CHK.39)'s own captures and
  reproduced with ANNOTATED parameters, their +15 rows are ava 3 + eleventy 3 (fixed here),
  release-it 2 (`typeof x.y?.z === 'string'` must narrow `x.y`), mdxlint+remark 4 (the
  `flatMap` callback's return-type inference), graphql-codegen 1 (a nested-ternary predicate)
  and yarn 2 (a `Plugin` NAME collision, not narrowing) — see the round note's table.

- [x] **(CHK.44) DONE 2026-08-26 — the axis was not `local`-vs-`parameter` but **declared in a
  BLOCK**, and a block-scoped union receiver is now typed from the INV.2(c) lexical tables.**
  (CHK.41)'s "3 of 4 shapes, only a parameter is checked" was wrong in both directions: a
  FILE-LEVEL `const`/`let` IS checked (its first probe was named `top`, which collides with the
  DOM global), and what fails is any declaration inside a block — function, method, arrow,
  nested function, nested block and file-level block alike, for `const`/`let`/`var`. B83.5 is
  the cause end to end: nothing binds such a declaration, so `getTypeOfIdentifier` answers
  `anyType` and every gate below it bails. `cmamBlockScopedReceiverType` reads the declaration
  back out of `lexicalScopeSymbol` (`LexicalScope.symbols` only) at the ONE call that asks
  whether a property exists on the receiver. **Two refusals are MEASUREMENTS**: a nullish union
  costs 11 compiler-profile / 16 harness rows tsgo does not report, and a NON-union declared
  type costs 3 services/server/harness rows — while `const`-ness is NOT a guard (dropping it is
  `added=0 removed=0`). Grid `added=0 removed=0` on all eight vs a rebuilt parent, knip 66 -> 66
  byte-identical, suite **15,979/0/3**, `cost_gate` PASSES unrebaselined. Pins:
  `BlockScopedReceiverTypeTest` (20). **FOUR POPULATIONS REMAIN SILENT and are queued as
  (CHK.45)** — see below.

- [x] **(CHK.45) DONE 2026-08-26 — (a) CLOSED, and THREE of the four populations turned out not
  to be block-scoping gaps at all.** (a) was the union elaboration's ALL-MISSING whitelist
  (`allWellResolved` / `allAnonPlainObjects`), not "a different emitter": a parameter and a
  file-level `const` of the identical type were equally silent, and what refused it was the
  FUNCTION type in `A | F`. Fixed by a per-member trust predicate
  (`cmamAllMissingTrustedMember`) admitting function/constructor types, primitives, literals,
  tuples and anonymous type literals, and refusing — each a measured false negative — a
  heritage interface, a class instance, a `Type.Reference`, an intersection, a type parameter,
  an enum-flavoured object and a content-free anonymous object. Calibration: deleting the gate
  ENTIRELY is grid-clean and corpus-clean and still costs **2 knip false positives**, both a
  cross-file heritage interface (B153). (b) SPLITS — its file-level half is (a) and is closed,
  its body-local half is the B83.5 gap. **(b)-body-local, (c) destructuring and (d) nested
  single-object receivers survive as three INDEPENDENT gaps, none of them about block scoping**;
  see the round note's table for the 3x5 measurement. Suite **15,998/0/3**, grid `added=0
  removed=0` on all eight, knip **66 -> 66** byte-identical with a rebuilt before-arm,
  `cost_gate` PASSES unrebaselined. Nine ablation arms; a5/a8 recorded as redundant guards.
  Pins: `AllMissingUnionMemberTest` (19).

- [x] **(CHK.46) DONE 2026-08-26 — ALL THREE CLOSED, and in TWO of them the TYPE was never
  missing: what was absent is a CONSUMER.** (c) a destructured name is typed as a receiver
  nowhere and fails two different ways — `getTypeOfSymbol` has no `BindingElement` arm for a
  BOUND pattern, `getTypeOfIdentifier` answers `anyType` for an UNBOUND one — fixed by finding
  the `BindingElement` syntactically (`cmamDestructuredReceiverType`), with the UNION reading
  routed to the flow-consulting union block and every other type to the two `any` bails.
  (d) a nested access with a single-OBJECT leaf had no emitter (`cmamCheckNestedObjectReceiver`,
  behind (CHK.45)'s trust predicate plus two MEASURED refusals — an array-like, and an `in`
  guard on the path, which is legal and which `narrowByInOperator` answers with the UNCHANGED
  type); `checkMergeTypeMethodChain` now defers to it on the one row they both own.
  (b) an un-annotated body-local `const` had no type at all (B83.5) — `const` only, and a
  WHITELIST of initializer forms, because a `new X(…)` costs three corpus baselines through a
  type-only import shadowing a lib global. Suite **16,050/0/3**, grid `added=0 removed=0` on all
  eight, knip **66 -> 66** byte-identical, both against a parent rebuilt in-session; `cost_gate`
  rebaselined once (+0.13pp of a +2.09% that was +1.96% before this round). 28 ablation arms;
  two pins were VACUOUS and only an arm saw it; the generic refusal is a round-927 PAIR. Three
  gaps stay open and are in the round note, not pinned. Pins:
  `DestructuredReceiverTypeTest` (21), `NestedAccessReceiverTest` (15),
  `UnannotatedLocalReceiverTest` (16).

- [x] **(CHK.47) DONE 2026-08-26 — (i) CLOSED and it was THREE mechanisms, not one; (ii) HALF
  closed; (iii) TRIAGED into five groups, one of them already closed. knip 66 -> 49, seventeen
  false positives, every one confirmed silent in tsgo.** (i)'s fourth shape (an ORDINARY
  ANNOTATED body-local `const` shadowing a file-level one) was not in the item at all, and the
  destructured-parameter shape belongs to `spineExEnterNode` (the B431 expando anchor) rather
  than the property-access family. (ii) the NESTED composition is closed
  (`cmamBlockScopedPathType`); the DESTRUCTURING one stays open at
  `typeCaptureDestructured`'s VariableDeclaration arm, which is shared with the (API.3d)
  capture channel. (iii) the eleven are really TEN in FIVE groups — see the round note; only
  the `let` binding wakes with the CORRECT type, and it is (CHK.44)'s measured
  3-false-positive population. 14 ablation arms; one leg deleted as dead; one arm's only
  uniquely-its-own failure is a knip ROW. Suite **16,067/0/3**, grid `added=0 removed=0` on
  all eight, `cost_gate` unrebaselined. Pins: `ShadowedReceiverTypeTest` (9),
  `BlockScopedPathReceiverTest` (8).

- [ ] **(CHK.48) THE (CHK.47) LEFTOVERS — one composition, five refusal groups, two
  emissions.** (a) the DESTRUCTURING composition `const c = h; const { inner } = c;
  inner.zzznope`: `typeCaptureDestructured`'s VariableDeclaration arm reads
  `getTypeOfExpression(initializer)` and answers `any`; the helper is SHARED with the (API.3d)
  capture channel, so the substitution must be local to `cmamDestructuredReceiverType` and
  needs a depth guard against `cmamDestructuredReceiverType -> cmamBlockScopedPathType ->
  cmamDestructuredReceiverType`. (b) refusal group 4 — a `let` binding is the ONLY one of the
  ten that wakes with the correct type, and it is exactly (CHK.44)'s 3-false-positive
  population, so it needs the reaching-definition question answered rather than a dropped
  guard. (c) group 1 (a union source, a class instance) needs type CONSTRUCTION — lifting the
  guard yields `Inner` for `Holder | Inner` and `typeof Cls` for `Cls`. (d) an ARRAY-pattern
  binding is typed as a receiver nowhere. (e) TS18048 is not emitted beside our TS2339 for an
  optional destructured member. Grade any attempt on the 8-profile grid AND knip; the standing
  calibration is now knip **49** and grid `added=0 removed=0`.

- [x] **(CHK.47-ORIG) SUPERSEDED — kept verbatim because its (i)/(ii)/(iii) framing is what the
  round corrected. THE THREE (CHK.46) LEFTOVERS, all measured, none a block-scoping gap.**
  (i) **an outer-binding COLLISION defeats the shadow** — a body-local `const { inner } = h`
  under a file-level `const inner: Deep` reports `Deep` for an `Inner`, and a destructured
  parameter named like a file-level function reports `typeof alpha` for a `string`. PRE-EXISTING
  (measured on the (CHK.46) parent binary): `getTypeOfExpression` never answers `anyType` for
  such a name, so `fileLocalTypeMapFor` / `lookupPerFileForNode` win before any receiver helper
  is consulted. It is a wrong MESSAGE where tsc also errors, so grade it on the message and not
  on a count. (ii) **the COMPOSITIONS** — `const c = h; c.inner.zzznope` and
  `const c = h; const { inner } = c; inner.zzznope` are silent because the ROOT answers `any`;
  (CHK.46)(b) substitutes at the Identifier bail, which the nested path does not go through.
  (iii) the eleven pinned REFUSALS, each a false negative tsc reports — the cheapest are
  probably the rest element (its type is the source minus the named members) and the array
  pattern (`typeCaptureDestructured` answers null for a non-object pattern). Grade any attempt
  on the 8-profile grid AND knip; the standing calibration is knip **66** and grid
  `added=0 removed=0`. (d) **a NESTED access whose leaf type is a single OBJECT type** —
  `c.inner.zzznope` is silent for a parameter, a file-level `const` and a body-local alike,
  while the same shape with a UNION leaf reports; the missing piece is a single-Object emission
  for a NON-Identifier receiver, and it needs (CHK.45)'s trust predicate PLUS a narrowing
  decision (an `in` guard ADDS a property, so it must consult the flow or refuse). Largest of
  the three and the most valuable for the language service. (c) **a DESTRUCTURED binding** —
  `const { inner } = h; inner.zzznope` is silent everywhere including for a destructured
  PARAMETER, i.e. a binding-element name is typed as a receiver nowhere; note the
  property-access family currently uses `currentParamBindingNames` as a blanket refusal.
  (b) **an un-annotated BODY-LOCAL** — B83.5 leaves it unbound and no initializer is typed for
  it, in all five initializer forms (a declared const, `new C()`, an object literal, a string
  literal, a single interface). Grade any attempt on the 8-profile grid AND knip; the standing
  calibration is knip **66** and grid `added=0 removed=0`.

- [x] **(CHK.45-ORIG) SUPERSEDED 2026-08-26 by the two entries above — kept verbatim because its
  (a)/(b)/(c)/(d) diagnosis is the thing (CHK.45) corrected. THE FOUR BLOCK-SCOPED RECEIVER POPULATIONS (CHK.44) LEFT SILENT, each measured
  against tsgo 7.0.2 and each a distinct mechanism.** (a) **a member on NO constituent** —
  `const c: A | F = u; c.nope` is decided by the general receiver path, not by
  `cmamCheckUnionReceiverNarrowing`, so it never sees (CHK.44)'s type; this is also why every
  pin in `BlockScopedReceiverTypeTest` reads a member present on SOME constituent, and a
  `.nope` fixture pins nothing. (b) **an UN-ANNOTATED local** — `const c = x; c.nope`, and the
  inferred-`new C()` / string-literal / object-literal forms with it; needs the initializer
  typed under the cpa ambient. (c) **a DESTRUCTURED local** — `const { files } = x;
  files.nope`. (d) **a NESTED access on a block-scoped local** — `c.files.nope`, which exits at
  `cmamCheckNonIdentifierReceiver` and is a different gap again. **And the two REFUSALS above
  are the real prize**: both are the same missing mechanism — narrowing of a block-scoped
  REFERENCE (a truthiness/`??=` guard, a discriminated-union ternary, a type-guard call inside
  a `while` condition's `&&`) — so closing THAT is what makes (a)-(d) and the nullish/non-union
  populations safe at once. Grade any attempt on the 8-profile grid AND knip: the 11+16+3 rows
  are the calibration, and the corpus adds two more (`discriminateWithOptionalProperty4`,
  `narrowingPastLastAssignment`) the moment the type reaches `currentLocalTypes`.

- [x] **(CHK.40) DONE 2026-08-26 — all five gaps closed, and (e)'s diagnosis was WRONG in
  a way that made the fix bigger and better: an `async` function-like whose return type is
  INFERRED returns `Promise<T>`, not `T`.** (e)'s parameters were contextually typed all
  along; the RETURN TYPE was not, in eight places, and the defect is symmetric — one
  seven-shape fixture reads **3 false positives and 4 false negatives**, tsgo reporting
  exactly the complement. (c)'s root was one layer below the TS7006 walker
  (`getTypeOfSymbolWorker` typed a STRING-named method `any`, a residue round 937 named and
  left); (a)/(b)/(d) are one new arm, the contextual type of a `return` POSITION.
  Grid `added=0 removed=0` on all 8 against a rebuilt parent, suite **15,928/0/3**, knip
  **66 -> 66** with every row identical, `cost_gate` PASSES with no rebaseline. Nine ablation
  arms, each with uniquely-its-own failures. **(a)/(b)/(d) are pinned as TS7006 SUPPRESSION
  plus a HOVER and not as a diagnostic, because of (CHK.42) below.**

- [x] **(CHK.42) DONE 2026-08-26 — SHIPPED. A FUNCTION BODY NESTED IN A `return` EXPRESSION IS NOT CHECKED AT ALL —
  the ONE expression position that does not reach `walkFunctionBodiesInExpr`, and the fix
  is TWO LINES that are already measured.** Found and measured during (CHK.40) against an
  obviously wrong `const q: number = "s"` nested one level down: a file-level var-decl
  initializer ✓, a var-decl initializer inside a function body ✓, a CALL ARGUMENT ✓, an
  object-literal property value ✓, `return (node) => {…}` ✗, `return { m(node) {…} }` ✗,
  `return (…)` parenthesised ✗. Neither `ReturnStatement` arm calls the walker — the legacy
  statement walk at `checkTypeAssignabilityInStatements` nor the spine anchor's twin — and
  both are needed for (CHK.39)'s reason (the anchor runs `recordOnly` for a nested statement
  and truncates, so the legacy arm is what EMITS). **MEASURED WITH THE ARM IN: both (CHK.40)
  probes reach FULL PARITY with tsgo 7.0.2 (8/8 and 5/5, exact line:column and message), the
  corpus stays 15,928/0/3, and knip stays 66 with every row identical.** The cost, and the
  only reason it is not shipped: the 8-profile grid gains **3 distinct rows** —
  `checker.ts:10950:25` (which is (CHK.43) below, a SHIPPED false positive the walk merely
  exposes) and `importFixes.ts:1281:17` / `1304:13`, an object literal with `any`-typed
  members reported not assignable to a 2-member union (`FixAddNewImport |
  FixAddJsdocTypeImport | undefined`), UNCHARACTERIZED. So this item is: characterize the
  importFixes pair, fix it and (CHK.43), then land the two lines. Reproduction of the walk's
  own value is one `git diff` — the arm and its positive control are in the (CHK.40) session
  note. **OUTCOME: the importFixes pair was ONE defect and it was ours and SHIPPED — an
  un-annotated parameter whose contextual type cannot be determined was registered nowhere, so
  the deliberately-shadowing callback parameter resolved to the ENCLOSING function's binding.
  Fixed with a `anyType` shadow pre-pass; with it and (CHK.43) the grid is `added=0 removed=0`
  on all eight and the walk is shipped.**

- [x] **(CHK.43) DONE 2026-08-26 — A CHAINED `x as unknown as T` IN A `return` KEEPS THE **INNER**
  ASSERTION'S TYPE WHEN THE RETURN ANNOTATION IS A ≥3-MEMBER UNION — a SHIPPED false
  positive, reachable today at top level.** Four lines:
  `interface A { a: number } interface B { b: number }` +
  `function m4(): B | A | (B|A)[] { const r: any = 0; return r as unknown as B[]; }` reports
  `TS2322: Type 'unknown' is not assignable to type 'B | A | (B | A)[]'`; tsgo 7.0.2 is
  silent. The differential is sharp and already taken: a SINGLE `as B[]` is silent, a
  2-member union target (`B | A`) is silent, a non-union array target (`(B|A)[]`) is silent
  — so the checker takes the INNER `as unknown` and the ≥3-member union is what stops
  something downstream from bailing. It is one of the 3 rows blocking (CHK.42) and it is
  independent of it. **It has nothing to do with type parameters** — its first sighting was
  as an "an outer function's `T` does not resolve in a nested function expression" theory,
  which one probe falsified. **OUTCOME: the trigger is NOT ">= 3 members" but "the target union
  carries an ARRAY member" (`A | (B|A)[]` fires). Root cause: `inferSimpleExprType`'s assertion
  arms fell back to the OPERAND's type whenever `resolveSimpleTypeName` could not render the
  asserted one; for `x as unknown as T` that is the type being asserted away. Both assertion
  spellings fixed; grid `added=0 removed=0` on all eight for this change alone.**

- [ ] **(CHK.36) THE "A CommonJS FILE CANNOT IMPORT AN ES MODULE" FAMILY IS NOT
  IMPLEMENTED AT ALL — TS1479 / TS1471 / TS1286 / TS1203 / TS1202.** Audited during
  (CHK.29): `grep 'code = 1479|1471|1286|1203|1202'` over `commonMain` finds NONE of
  them, so the format decision now being correct opens no new false-positive surface
  from this family — and it is also why a nodenext project's genuine interop errors are
  FALSE NEGATIVES here. Cheap to size: point the (LIB.1) loop at a dual CJS/ESM package
  and diff against `tools/tsgo-7.0.2/lib/tsc`. Note the codes are only reachable once
  (CHK.37) exists, because deciding that an IMPORTED file is ESM is what they test.

- [ ] **(CHK.37) `ModuleResolver` DOES NOT CONDITION `exports`/`imports` ON THE
  IMPORTING FILE'S FORMAT — the `"import"` vs `"require"` condition is unmodelled.**
  Measured during (CHK.29): the resolver reads neither `isESModuleFormat` nor
  `effectiveModule` (one grep, zero hits). For a dual-published package that is not a
  cosmetic difference — it decides WHICH FILE a bare specifier resolves to, so an ESM
  importer can be handed the CommonJS build's `.d.ts` and inherit its whole shape. This
  is the (CHK.29) residue with real blast radius; size it on a library with a
  conditional `exports` map before implementing.

- [ ] **(CHK.38) `esModuleInterop` IS GATED ON THE GLOBAL OPTION AND NEVER ON THE TWO
  FILES' FORMATS.** All 56 `Checker.kt` sites read `options.esModuleInterop`; tsc
  additionally makes a synthetic default available to an ESM file importing a CommonJS
  one under node16/nodenext (`allowSyntheticDefaultImports` is implied by the FORMAT,
  not only by the flag). Blast radius UNMEASURED — recorded during (CHK.29)'s scope
  audit rather than guessed at. It can fail in either direction, so the probe must be a
  default import from a CJS package with the flag OFF and the importer ESM.

- [x] **(LIB.2) ANSWERED 2026-08-22 BY (LIB.3)'s SCREEN — and the screen added a second
  criterion the entry did not predict: the library closest to COMPILING and the library best
  for BENCHMARKING are different ones. ORIGINAL ENTRY: THE NEXT LIBRARY MUST BE PICKED BY
  WHAT IT *IMPORTS*, NOT BY ITS SIZE —
  knip cost a session to learn that.** (LIB.1)'s method is right and cheap (two commands,
  ~10 s) but it was pointed at a library the backend can never reach, because the
  disqualifier is not a language construct: **native N-API dependencies and `node:` builtins
  have nothing to lower TO.** Before adopting a candidate, census its non-relative imports
  first — `grep -rhoE "from '[^.'][^']*'"` over `src` answers in one second — and refuse
  anything importing a `.node` binary or a `node:` builtin outside a table we intend to
  write. `yaml` (76 files, no dependencies) is still the right second conformance corpus for
  the FRONT end, and `docs/kir-library-readiness.md` records it moving 80 -> 24 purely from
  defects other libraries exposed. For the BACKEND ladder the candidate wants to be pure
  computation over data — a parser, a formatter, a codec — which is exactly why `mitt` and
  `smol-toml` worked.

- [x] **(LIB.3) SIX CANDIDATE CLI LIBRARIES SCREENED AND THEIR ERRORS ROOT-CAUSED —
  2026-08-22. 126 false positives over four libraries, and FIVE families carry 67 of them.**
  This is (LIB.2)'s screen, executed. All six are TS-source with a CLI; the import census
  disqualified `sql-formatter` (imports `nearley` inside `src`) before any compiler ran.
  Measured with `@types/node` present on both sides, each library's OWN tsconfig (marked's
  minus `verbatimModuleSyntax`, since (CHK.29) already owns that), diffed against tsgo 7.0.2
  per `(file, line, code)`:

  | library | files | lines | deps | tsgo | xtsc | ours-only | refused-construct files |
  |---|---|---|---|---|---|---|---|
  | **cronstrue** | 52 | 8,812 | none | **0** | **0** | **0** | **2 (3%)** |
  | marked | 13 | 3,706 | none | 0 | 15 | 15 | 10 (76%) |
  | jsonrepair | 10 | 2,746 | none | 1 | 16 | 16 | 9 (90%) |
  | fflate | 3 | 3,904 | none | 2 | 17 | 17 | 3 (100%) |
  | yaml | 78 | 10,878 | none | 0 | 78 | 78 | — |

  **THE OURS-ONLY HISTOGRAM (126): TS9008×19, TS2322×14, TS2345×13, TS9023×11, TS2391×9,
  TS2554×8, TS2339×7, TS2591×6, TS2683×4, TS6196×2, TS2366×2, then twelve codes at 1.**
  The five root-caused families are (CHK.31)-(CHK.35) below, in the order their blast radius
  justifies. **THE TAIL IS NOT ROOT-CAUSED AND MUST NOT BE QUOTED AS IF IT WERE**: ~59 rows
  remain, led by TS2322×14 (of which SIX are one shape, `SourceToken | undefined` against
  `SourceToken | null` in `yaml/compose/resolve-props.ts` — an excess `undefined` we add and
  tsgo does not) and TS2339×7. Captures for every row are reproducible in ~10 s per library
  by the (LIB.1) commands.
  **THE RANKING LESSON, WHICH IS NOT THE ONE (LIB.2) PREDICTED: the library closest to
  COMPILING and the library best for BENCHMARKING are different libraries.** `cronstrue` is
  the only one the checker already passes and the only one whose lowering runs — but each of
  its calls is small work, so it benchmarks as a loop over many expressions rather than as one
  heavy invocation. `marked` (markdown -> HTML over a large document) is the workload worth
  publishing a number for, and is 15 checker errors plus a 76%-of-files backend gap away.
  `fflate` would be the best number of all — DEFLATE is tight numeric loops, where a JVM
  should beat Node outright — and is **structurally blocked**: 183 typed-array uses
  (`Uint8Array`×167) against a runtime with none, plus 14 `Worker` references. Do not start
  there; revisit after typed arrays exist.

- [x] **(LIB.4 — the LOWERING half DONE 2026-08-28) `cronstrue` COMPILES TO JVM BYTECODE; WHAT
  STOPS IT RUNNING IS THE NOMINAL HALF.** Its English entry point (11 files, published source
  unmodified) reads `successful=true` with the checker at **0 errors, agreeing with tsgo 7.0.2
  exactly**, and then fails at RUN time on one thing, twice: `Can not set JsObject field
  ExpressionDescriptor.i18n to program.en` — a generated CLASS instance cannot flow into an
  INTERFACE-typed slot. See (LIB.6). **THE QUEUE'S FIVE RUNGS WERE HALF THE LADDER: thirteen
  capabilities were needed** (corpus 17-29), and the reason the list was short is that the
  earlier session peeled it *by patching a throwaway copy*, which walks past whatever the patch
  removed — re-probing the UNMODIFIED library after each fix is what found the other eight.
  `docs/kir-library-readiness.md` § "UPDATE 2026-08-28" has the table and the five defects the
  arc surfaced, four of them silent wrong answers invisible to every gate in this repo.

- [ ] **(LIB.6) THE NOMINAL HALF — A CLASS INSTANCE CANNOT REACH AN INTERFACE-TYPED SLOT, AND IT
  IS THE ONLY THING BETWEEN `cronstrue` AND A RUNNING PROGRAM.** An `interface` erases to the
  property bag and a `class` is a nominal JVM class, so `i18n: Locale = new en()` fails at run
  time with an `IllegalArgumentException` from `reflectiveSet`. `docs/kir-structural-typing.md`
  already MEASURED the plan — candidate (1), each interface a JVM interface and each class
  implementing every interface it is structurally assignable to, **158 closure edges on tsc's own
  sources, max fan-out 9** — and it was never built because § 7 priced the dynamic half at 12x
  and it was taken first. **A cheaper shape exists and should be priced against it before
  starting**: make a generated class EXTEND `JsObject` (it is `open`, has a no-arg constructor,
  and the shape classes already do exactly this) and route a bag-receiver METHOD call through
  `jsInvoke`, whose reflective fallback already finds a real JVM method. That is two changes
  rather than a whole-program closure, and it changes what `instanceof` and the spill machinery
  see — which is why it is a decision rather than a rung.

- [ ] **(LIB.7) A NAMESPACE IMPORT HAS NO RUNTIME OBJECT — `import * as ns from "./m"` refuses
  with `cannot lower the reference 'ns'`.** `cronstrue`'s ALL-LOCALES entry point
  (`cronstrue-i18n.ts`) needs it: `allLocalesLoader.ts` does `for (var property in allLocales)`
  and `new (allLocales as any)[property]()`. The English entry point does not, which is why
  (LIB.4) got past it. Needs a module NAMESPACE object — a `JsObject` whose properties are the
  module's exports — built once per imported module and reachable as a value.

- [ ] **(CHK.69) AN ASSIGNMENT *BEFORE* A `var`'s DECLARATION DOES NOT COUNT TOWARD DEFINITE
  ASSIGNMENT — a two-function repro, ours-only against tsgo 7.0.2.**
  ```ts
  export function assignedBeforeDeclaration(): number {
    probe = 7;
    var probe: number;
    return probe;      // TS2454 here, tsgo silent
  }
  export function assignedAfterDeclaration(): number {
    var other: number; other = 7; return other;   // silent BOTH sides — the control
  }
  ```
  The mirror is silent, so it is that direction specifically. `var` has no TDZ, so an assignment
  above the declaration is ordinary and the binding is definitely assigned at the `return`.
  Found while writing corpus 18, which had to route around it.

- [x] **(CHK.31 — DONE, round (CHK.31)) `// @ts-ignore` AND `// @ts-expect-error` DO NOT SUPPRESS ANYTHING — MEASURED
  IN BOTH DIRECTIONS, AND THIS IS THE HIGHEST-BLAST-RADIUS ITEM IN THE SCREEN.** A four-file
  repro settles it: `// @ts-ignore` above a TS2322 leaves the TS2322 emitted, `// @ts-expect-error`
  likewise, and an `@ts-expect-error` above a line with NO error fails to produce tsgo's
  **TS2578 `Unused '@ts-expect-error' directive`** — so we are wrong in both directions at once.
  On `fflate` this is **all 9 TS2391 rows** (`Function implementation is missing`), and the
  correspondence is exact: `src/index.ts` contains exactly 9 `@ts-ignore` comments, one above
  each declaration-only class member the library deliberately suppresses.
  **THE TRAP IS THAT IT LOOKS ALREADY DONE**: `CompilerOptions.kt:562` parses both spellings as
  comment directives, and `Checker.kt:16167` consults one for a narrow node/commonjs
  suppression, so a grep says the feature exists. It is not a general diagnostic filter.
  **What the fix needs, beyond the filter itself:** the directive attaches to the NEXT line, so
  it wants the leading-comment channel the parser already records (`NodeBase.leadingComments`)
  rather than a source scan; `@ts-expect-error` must additionally RECORD whether it suppressed
  anything and emit TS2578 when it did not; and a file-level `// @ts-nocheck` is a third
  spelling with **zero** hits in `commonMain` today. **Corpus risk is real and must be measured
  before landing**: any baseline whose fixture carries one of these directives currently records
  the UNSUPPRESSED diagnostics, so run the 8-profile grid and the corpus, and expect the
  `logicalParityDivergence` mechanism to be the wrong tool — a suppressed diagnostic is a
  MEANING change, not a form one.

- [x] **(CHK.32) LANDED 2026-08-26 — the ANONYMOUS half. A PRIMITIVE SOURCE IS NOT RELATED TO A STRUCTURAL OBJECT TARGET THROUGH ITS
  APPARENT TYPE — 13 TS2345 ROWS, AND IT GENERALISES BEYOND `string`.** `jsonrepair` types its
  whole scanner against `interface Text { length: number; charAt(i): string; charCodeAt(i): number;
  substring(s, e?): string }` and passes a `string` to it; every one of its 7 TS2345 rows is that
  call. Minimal repro, both halves failing where tsgo is silent:
  ```ts
  declare function isWhitespace(text: Text, index: number): boolean
  export function viaString(s: string) { return isWhitespace(s, 0) }        // TS2345, tsgo silent
  declare function wantsToFixed(x: { toFixed(d?: number): string }): string
  export function viaNumber(n: number) { return wantsToFixed(n) }           // TS2345, tsgo silent
  ```
  The control in the same file — an object source against `{ length: number }` — passes, so the
  defect is specifically the PRIMITIVE side: relating `string`/`number` to an object type must
  go through `getApparentType` (the `String`/`Number` wrapper interface), which the relation is
  not consulting on this path. `getApparentType` already exists and CLAUDE.md records it as the
  way to reach a primitive's members, so this is a missing consult rather than missing
  machinery. Check the mirror direction while you are there (an apparent-typed source in a
  RETURN position, and `boolean`/`symbol`/`bigint`), and note the fix is in the RELATION, so
  the corpus is the gate.
  **OUTCOME.** The NAMED-interface half was already working (a round-B69.8 leg has handled
  `target is Type.Interface` all along); the gap is the ANONYMOUS target, and it is closed in
  every direction the item names — a 14-row matrix over primitive x target-shape x position
  had 8 ours-only rows against tsgo 7.0.2 and now agrees row for row.
  **THE `jsonrepair` ATTRIBUTION IS WITHDRAWN**: measured before and after with rebuilt arms,
  that library reads **11 -> 11 rows, byte-identical**, and its 7 TS2345 are the DOM `Text`
  name collision now queued as (CHK.49). `PrimitiveApparentTypeRelationTest` (20 pins),
  suite 16,087 / 0 / 3, `output.errors` 46, grid `added=0 removed=0` on all eight.

- [x] **(CHK.49) DONE 2026-08-26 — A MODULE-LOCAL DECLARATION OF A LIB GLOBAL NAME WAS
  MERGED *INTO* THE LIB SYMBOL, PROGRAM-WIDE AND IN BOTH DIRECTIONS.** `mergeSingleSymbol`
  ADOPTS, so the merge mutated the LIB symbol and EVERY file saw the fusion — not only the
  declaring one. Fixed by dropping the lib key set from BOTH `init:mergeSharedKeepNames` and
  `computePerFileVisibility`'s `nonModuleVisible` (one observable: the merge retire alone is
  **969** compiler-profile errors, the visibility half alone is inert, together **46**), plus
  a VALUE second chance for the meaning a TYPE-only shadow does not hide, plus node-keying
  `resolveHeritageBaseSymbol`'s Identifier root. **`jsonrepair` 11 -> 4**; suite
  16,101 / 0 / 3, zero corpus baselines moved, grid `added=0 removed=0` on all eight.
  The item's "it is `interface`-SPECIFIC — a `type` alias is correct" was measured WRONG:
  all five declaration forms collide. `LibGlobalNameShadowTest` (14 pins). See the session
  note for the population census and the ten-arm ablation.

- [x] **(CHK.50) DONE 2026-08-26 — THE CARRIER MERGED AND THE CONTENTS DID NOT, AND
  **SEVEN OF EIGHT** DECLARATION FORMS WERE WRONG.** `declare global` parses as a
  ModuleDeclaration named `global`, so step 1 merged the carrier symbol and nothing merged
  its `exports`. The item's "the `var` form works, so the value half is fine" is measured
  WRONG: `var` was correct only in the DECLARING file (cross-file it was silently `any`), and
  `function`/`namespace`/`class` were `any` in both scopes — TS2304-suppressed by
  `globalAugmentationNames` and typed by nothing, which is the dangerous direction. Fixed by
  `init:mergeGlobalAugmentations` (legality mirrors `spineCheckGlobalAugmentation`'s TS2669
  predicate; a global-SCRIPT block contributes nothing, as in tsgo) plus a `buildPerFileScopes`
  seed of the ADOPTED names, an ambient-BY-CONTEXT implicit-export rule for
  `declare global { namespace NodeJS { … } }`, and a `globalThis` refusal ((CHK.53)).
  **(CHK.51)'s named cost is PAID** — `globalAugmentedInterfaceNames` deleted, `el.zzzNotThere`
  on an augmented `HTMLElement` now TS2339 as tsgo says. Both matrices match tsgo row for row;
  `DeclareGlobalAugmentationTest` (11 pins), suite 16,118 / 0 / 3, `output.errors` 46, grid
  `added=0 removed=0` on all eight, **jsonrepair 4 -> 4 byte-identical, knip 49 -> 54** (one
  genuine fix, six pre-existing overload rows that `any` had been hiding — (CHK.54)). See the
  session note for the eight-form census and the ten-arm ablation.

- [x] **(CHK.55) DONE 2026-08-27 — (b) AND THE "THIRD, SEPARATE ROW" ARE **ONE
  MECHANISM** AT TWO CALL SITES; (a) IS DELIBERATELY LEFT OPEN AS (CHK.56).**
  `getTypeOfExpression` widens an object literal's literal-valued properties, so a target
  property with a literal type rejects. At `allArgumentsMatch` (the DIAGNOSTIC path) round
  728's rescue existed but refused an INTERFACE with heritage and a UNION with >1 non-nullish
  constituent — a false TS2769, and `knip`'s last overload row; at `signatureAcceptsArgs`
  (SELECTION) there was **no rescue at all**, so `resolveCallOverload`'s `arityMatches[0]`
  fallback answered — matrix row H, a wrong TYPE with no diagnostic anywhere. One fixture
  (`readFileSync(p, { encoding: 'utf8' })`) shows both at once, which is what identifies
  them as one. The heritage refusal was never necessary: `resolveInterfaceMembersCore`
  folds base members into the derived type's own `members`/`properties`. A THIRD
  interaction was found by trying to falsify an ablation arm — for a union parameter the
  relation SUCCEEDS through a weak constituent, so the rejecting path where the rescue
  lives is never taken and (CHK.54)'s weak rule refuses without ever asking about the other
  constituent; the weak refusal is now guarded by the rescue. `OverloadObjectLiteralParamTest`
  (11 pins), suite **16,144 / 0 / 3**, no baseline moved, `output.errors` 46, grid
  `added=0 removed=0` on all eight, **knip 49 -> 48** (exactly `src/util/git.ts:17:55`),
  jsonrepair 4 -> 4 byte-identical. See the session note for the 10-row matrix and the
  seven-arm ablation, including the arm that reads 0 RED and the KDoc claim it retracts.

- [x] **(CHK.56) DONE 2026-08-27 — THE SUBLINE WAS THE EASY HALF AND THE "WHICH OVERLOAD"
  HALF WAS A **tsgo RENDERING**, NOT tsc's.** `allArgumentsMatch` now asks the weak rule
  (opt-in `applyWeakRule`, so only the overload-MATCH loop does and the four TS2793
  implementation-signature gates are untouched), and all four overload arg-check helpers
  move together or the chain names an overload the match loop thought fine. The item read
  the elaboration as the work: correct that tsc's subline is TS2559's *no properties in
  common* wording rather than an assignability line, and it is minted beside the existing
  walk on the path where the relation SUCCEEDED — but the `The last overload gave the
  following error.` framing it recorded is tsgo's, printed at 2, 3 and 4 candidates alike,
  where PRISTINE tsc prints `Overload N of M, '<sig>', gave the following error.` per
  candidate (42 baselines against 4, and `tsxStatelessFunctionComponentOverload4` carries
  a *no properties in common* subline inside exactly that chain). Our chain has had the
  pristine shape since B418, so **no "which overload" policy was needed** and the item's
  own wrong-overload risk never arose. Two rules measured rather than guessed: a UNION
  parameter names a CONSTITUENT only when exactly one survives dropping `null`/`undefined`
  (two or more take the assignability wording naming the whole union), and an
  OBJECT-LITERAL argument is refused outright because tsc's freshness/excess check
  pre-empts the weak one and squiggles the offending property. `weakParamRefusesArg` was
  indeed the ready-made predicate. **It ADDED no row anywhere**: 8-profile grid capture
  md5 `503774c2…` (byte-identical to (CHK.54)/(CHK.55)), knip 48 -> 48, jsonrepair 4 -> 4.
  `OverloadWeakParamDiagnosticTest` (11 pins, every position and message asserted as tsc's
  own value), suite **16,155 / 0 / 3**, `output.errors` 46. The measured residue — the
  weak rule does not distribute over a UNION target in the B482 walkers — is (CHK.57).

- [x] **(CHK.57) DONE 2026-08-27 — THE WEAK RULE NOW DISTRIBUTES OVER A **UNION** TARGET IN
  BOTH WALKER POSITIONS, AND THE ITEM'S OWN TWO-CONSTITUENT EXAMPLE WAS A **DEAD** ABLATION
  ARM.** [Checker.weakUnionRefusalConstituent] composes the two helpers this entry named and
  is wired into the single-signature CALL argument site and
  [Checker.tryEmitTopLevelWeakVarDecl] as a branch DISJOINT from the bare-target one, so the
  bare path is byte-identical. Both measured shapes now match tsc 7.0.2 exactly — code,
  message, line and column — as do the `| undefined`, interface-, alias- and
  `Partial<…>`-constituent, non-fresh-object-source and REST-parameter variants.
  **Three shapes refuse deliberately, each measured**: two or more non-nullish constituents
  (tsc's TS2345/TS2322 naming the whole union needs the RELATION to reject); an
  object-literal ARGUMENT ((CHK.56)'s boundary — tsc's excess check squiggles the property
  two columns right); and a CALLABLE source, because our TS2559/TS2560 split is wrong at the
  BARE target and distributing would have inherited a wrong-CODE row. **The entry's "it ADDS
  rows … expect it to fire on real code" is measured FALSE**: knip 48 -> 48 and jsonrepair
  4 -> 4 byte-identical, grid md5 `503774c2…` unmoved on all eight, `output.errors` 46 — and
  (CHK.54) is why, since SELECTION already refuses these signatures, so `readFileSync` picks
  the `string` overload and the argument site never asks. Suite **16,169 / 0 / 3**, no corpus
  baseline moved. `WeakUnionTargetDiagnosticTest` (14 pins). Residue queued as (CHK.58); see
  the session note for the seven-arm ablation and the two arms that read 0.

- [x] **(CHK.58) DONE 2026-08-27 — FOUR OF THE SIX CLOSED, AND THE ORACLE OVERRULED THE
  ENTRY ON A FIFTH.** (1) The **RETURN and ASSIGNMENT** positions had no weak walker at all:
  twelve tsc rows that were missing now land byte-exact and the one row the return position
  had (TS2322 naming the whole union) is corrected to TS2559 naming the constituent. The
  anchors were corroborated by PRISTINE, not taken from tsgo — a return squiggles the
  `return` KEYWORD (`~~~~~~`), an assignment the LHS REFERENCE (one `~` under the `c` of
  `c = d` in `assignmentCompatWithObjectMembersOptionality2.errors.txt`). (2) **TS2560 is
  "calling it would have worked", not "the source is callable"** — four of six callable
  shapes carried the wrong code, and **the relation asked must carry the WEAK RULE ITSELF**,
  since tsc's weak check lives inside `isRelatedTo` and ours does not. (4) The **enum
  display** is `E.A` for a multi-member enum and `E` for a one-member one — **one rule, and
  the queue's "our display is wrong" reading was half wrong: at the position the corpus
  tests, the old answer was RIGHT**, because a one-member enum's literal type IS the enum
  type. (5a) A **`new C()` var-decl initializer** is now a source, so the var-decl and
  argument positions refuse the same things. Suite **16,199 / 0 / 3** (+30, four new
  classes), **no corpus baseline moved** — load-bearing, since three of the four fixes
  change an existing row. `output.errors` **46**, cost gate exit 0 unrebaselined (largest
  counter **+1.40%**; the FIRST implementation measured +6.89% `typeOfExpr.calls` for
  byte-identical output — order is a cost decision), grid md5 `503774c2…` unmoved on all
  eight, `partition` EQUIVALENT/78, `capture` 1,005 / 43 of 76 / moreAny 0, **knip 48 -> 48
  and jsonrepair 4 -> 4 byte-identical**. Twelve ablation arms; five read 0 and each is a
  DIFFERENT kind of zero (provably-unobservable, redundant, undiscriminated, DEAD ×2) —
  see the session note. Residue re-queued as (CHK.59).

- [x] **(CHK.59) DONE 2026-08-27 — THREE OF FIVE CLOSED; THE ANCHOR RULE IS "TS2560 MOVES TO
  THE EXPRESSION", AND (CHK.58)'S DIAGNOSIS OF THE ENUM HOLE WAS WRONG IN A WAY THAT MATTERED.**
  (1) The CALLABLE source at the var-decl / return / assignment positions is closed: tsc's
  `elaborateDidYouMeanToCallOrConstruct` re-reports at the EXPRESSION exactly when the call
  result is related to the target, which is the SAME predicate
  [Checker.weakCallResultSatisfiesTarget] already used to pick TS2560 — so the emitter needed
  one extra CALL-ONLY anchor and nothing else. The var-decl position additionally gained a
  fallback to the shared value walker (an IDENTIFIER or ARROW source was silent there and
  reported at the other two). A FUNCTION EXPRESSION stays refused, measured: tsc anchors one
  at its own NAME. (2) The enum member is closed at all four positions — and NOT because
  `getTypeOfExpression` answers `any` (it does not): an enum type is a member-LESS
  [Type.Object], so it enumerated to the EMPTY set and the vacuous-`{}` guard refused it.
  (4) The nested object-literal leaf is closed, as TWO defects: the walker ORDER, and the
  WIDENING of a string/numeric leaf (a boolean leaf and the top-level position do not widen).
  Suite **16,223 / 0 / 3** (+24), **no corpus baseline moved**, `output.errors` 46, grid
  `503774c2…` unmoved, knip 48 -> 48 and jsonrepair 4 -> 4 byte-identical, ten ablation arms
  and not one read 0. Items 3, 5 and 6 are re-queued with three new residues as (CHK.60).

- [x] **(CHK.60) PARTLY DONE 2026-08-27 — ITEM 6 (THE ENUM FALSE POSITIVE) CLOSED AND ITEM 4
  MAPPED; ITEMS 1, 2, 3, 5, 7 RE-QUEUED AS (CHK.61).** An enum MEMBER is a string or number
  LITERAL in tsc, so its apparent type is the `String`/`Number` wrapper; (REL.1)(b)'s
  member-LESS `Type.Object` made `propertiesRelatedTo` reject **every** target declaring a
  property, weak or not. `structuredTypeRelatedTo`'s object/object leg now retries an
  enum-literal source as its apparent PRIMITIVE **after** the structural comparison has
  answered false, which routes it through the legs a `string`/`number` source already takes.
  **13 ours-only rows removed** over a 30-row matrix against tsc 7.0.2; suite 16,234 / 0 / 3,
  no baseline moved, all 20 cost counters digit-identical to the rebuilt parent, grid
  `503774c2…` unmoved, knip 48 -> 48 and jsonrepair 4 -> 4 byte-identical, six ablation arms.
  Item 4 (`this.<member>`) was MEASURED rather than fixed and the queue's own diagnosis
  corrected — see the session note and (CHK.61) below.
  ORIGINAL ENTRY: **THE WEAK-TYPE RESIDUE AFTER (CHK.59) — TWO INHERITED, ONE DELIBERATE,
  THREE NEW, ALL MEASURED.** Fixtures under `build/chk59/ora`, `pin`, `dbg`.
  1. **TWO OR MORE NON-NULLISH CONSTITUENTS** — unchanged since (CHK.56) and still a different
     mechanism: tsc words them as ordinary assignability naming the WHOLE union, which needs
     the RELATION to reject where the weak rule lives in the walkers. **A second, separate hole
     is beside it**: at the ASSIGNMENT position we are silent for that shape altogether
     (`build/chk58/pinora/q16.ts(3,1)` and `q13.ts(3,1)`), which is the ordinary assignability
     walk. Price it before starting.
  2. **A FRESH OBJECT LITERAL AGAINST A BARE WEAK ARGUMENT IS TS2559 HERE AND TS2353 IN tsc**
     ((CHK.56) row r3) — ARGUMENT-ONLY; the return and assignment positions already match tsc
     (TS2353 at the property, both spans pinned). Closing it is what would let the
     object-literal refusals in (CHK.56)/(CHK.57)/(CHK.58)/(CHK.59) be dropped.
  3. **A GENERIC INSTANTIATION SOURCE IS SILENT IN EVERY POSITION** (`build/chk58/ora4/y7.ts`
     `(3,23)` and `(4,7)`, naming `ZzzG7<number>`). [Checker.weakSourcePropertyNames] answers
     null for a [Type.Reference] BY DESIGN — its members are lazy and a missed property is a
     FALSE TS2559 — so this is a deliberate conservatism to be RE-PRICED, and it is SYMMETRIC
     across positions, which is what makes it safe to leave. Do not break the symmetry.
  4. **NEW — A `this.<member>` ASSIGNMENT TARGET IS SILENT FOR THE WEAK RULE AT EVERY SOURCE
     SHAPE**, callable and not (`build/chk59/dbg/d1.ts`: tsc reports `(2,62)` for an arrow and
     `(3,44)` for a plain `number`). Not the anchor change and not the weak rule:
     [Checker.getTypeOfExpression] answers `any` for `this.<optional member>` — the probe
     `const p: string = this.zzzHandler` is SILENT here where tsc says `Type 'ZzzS9 |
     undefined' is not assignable to type 'string'`. That is a receiver-typing hole with a
     surface far wider than TS2559. `WeakCallableSourceAnchorTest`'s refusal pin records it.
  5. **NEW — AN OPTIONAL `any` PROPERTY RENDERS `p?: any | undefined` WHERE tsc RENDERS
     `p?: any`.** `any` ABSORBS `undefined` in tsc's union construction and our
     [Checker.getUnionType] does not reduce that pair, so it is a [Checker.typeToString]
     divergence reachable from every position that renders a target through the TYPE rather
     than the ANNOTATION (a var-decl row is byte-exact only because its walker renders
     `formatTypeForDisplay(ann)`). Union member text is pinned byte-for-byte across ~13k
     baselines, so this is a LOGICAL-PARITY conversation, not a display tweak.
     `WeakEnumSourceDisplayTest`'s residue pin records it.
  6. **NEW — AN ENUM MEMBER AGAINST A WEAK TARGET IT *SHARES* A PROPERTY WITH IS SILENT IN tsc
     AND EMITS TS2345/TS2322 HERE** (`build/chk59/pin/qc.ts`: `zzzQ0Cg(ZzzQ0C.A)` against
     `{ length?: number }`). The weak rule correctly declines both; what fires is the ORDINARY
     relation, which does not relate a string-enum member to an object target through its
     `String` apparent type. This is an FP class, not a missing row — sequence it above 1-3.
  7. **A BIGINT LEAF** (`{ zzzIn: 12n }`) still falls through to TS2322 where tsc reports
     TS2559 `Type 'bigint'` at the key — [Checker.weakSourcePropertyNames]'s `BigIntLike` arm
     does not resolve to an object here. One line, deliberately not taken this round.

- [x] **(CHK.62b) DONE 2026-08-27 — AN ASSIGNMENT WHOSE RHS IS A `this`-METHOD CALL DID
  NOT NARROW THE ASSIGNED REFERENCE, AND IT WAS A **SHIPPED** DEFECT, NOT A PATCH ARTEFACT.**
  `rhsIsDefinitelyNonNullish`'s CALL arm resolves the callee through
  `resolvePropertyMethodDecl`, which TYPES THE RECEIVER and bails at `recvType === anyType`;
  `thisReceiverCarrierType` supplies `currentClassForThis`. The entry's "invisible without
  patch_a" is true only of `build/chk62/g2k`, whose declared unions all come from
  `this.zzzFind()` — `let p = zzzFindFree(); p ??= this.zzzCreate(); return { p }` reproduces
  on the shipped binary. Took (a) from 3 rows to **1**. `ThisMethodCallAssignmentNarrowTest`.
  RESIDUE: a PROPERTY-access RHS (`p ??= o.zzzFld`) still does not narrow — measured NOT
  `this`-shaped (`zzzObj.zzzFld` fails identically), so it is a separate item.

- [x] **(CHK.61)(b) PARTLY DONE 2026-08-27 — THE **DISPLAY** HALF LANDED; THE **CHECKING**
  HALF IS REFUSED WITH ITS PRICE MEASURED, AND WHAT IT UNCOVERED IS RE-QUEUED AS (CHK.63)
  AND (CHK.64).** An optional member's hover now carries `| undefined` and then RE-NARROWS
  (`typeCaptureOptionalMemberType`, `memberIsOptionalOnReceiver`), at tsc 7.0.2's own LSP
  answers, including a UNION receiver decided PER CONSTITUENT. Confined to the CAPTURE, so
  every diagnostic gate is byte-identical and only `capture-equivalence` sees it (DIVERGED
  1,005 -> 968, all 38 moved spans classified as the alias-display first-wins family).
  RESIDUE, pinned with the value we answer: `super.<opt>` and an INTERSECTION receiver both
  read `number` where tsc reads `number | undefined`.
  **THE CHECKING HALF IS REFUSED AND THE "3 rows" IN THE OLD ENTRY WAS THE WRONG ARM.**
  `build/chk61/patch_b.py` alone DELETES a true positive (`const a: string = o.optNum`
  reports `Type 'number' …` on the shipped binary and NOTHING with it), because the source
  becomes a nullish union and `canUseTypeEngine` refuses those against a primitive target.
  Measured this round on the 8 profiles against a parent capture taken in the same session:
  the gate opened ALONE is **11** ours-only rows, patch_b **and** the gate is **15** (of
  which patch_b FIXES two of the gate's own — `emitter.ts:1479`,
  `organizeImports.ts:862`). `armBG` reproduces tsc EXACTLY on the four-line repro. The
  five narrowing gaps are FIVE mechanisms, not one, and every one reproduces on the SHIPPED
  binary with an EXPLICIT `| undefined` member — they are (CHK.64), and the gate is
  (CHK.63). Re-open (b)'s checking half only after those.

- [x] **(CHK.65) DONE 2026-08-28 — A DOMAIN OF EXACTLY ONE LITERAL, MINUS THAT LITERAL,
  IS **EMPTY**; A SECOND `!== undefined` GUARD ON THE SAME PROPERTY PATH DID NOT NARROW,
  AT TWO READERS, AND IT WAS SHIPPED.** [Checker.narrowUnionByLiteral]'s NON-union
  `keep = false` arm answered its input unchanged — right for an INFINITE primitive
  domain, wrong when the input IS the literal being subtracted, which is exactly what a
  preceding guard's ELSE branch leaves on a property path. An IDENTIFIER subject goes
  through the M1.9 if-arm machinery and was always correct, which is what hid it. The
  second reader is the ARITHMETIC/RELATIONAL operand one ([Checker.arithOperandType]'s
  flow consult is gated on a UNION base and refuses a `never` answer);
  [Checker.operandFlowNarrowsToNever] must CLAIM the operand or the TS18048 merely
  becomes a TS2365. `ASecondGuardOnAPropertyPathNarrowsAgainTest` (7 positives naming
  their reader, 6 controls). Grid byte-identical, suite +13, no baseline moved. It also
  removes the gate's `checker.ts:30269` row — see (CHK.63).

- [x] **(CHK.70)(a) DONE 2026-08-28 (`2ed1779b`) — AND IT WAS *NOT* THE GATE'S LAST ROW.**
  Landed as the ORDER-FREE rule (EVERY assignment reachable backward from a back edge is a
  non-nullish compound one), which is what keeps it on tsc's side of the compound arm's own
  antecedent-base-type rule; five shipped false positives, tsgo-confirmed. Rebuilding the
  combined arm on top of it left `harness/tsserverLogger.ts:28:5` UNMOVED — that row was
  (CHK.70)(c), the LITERAL arm of `narrowByAssignmentRhs` (`acb6d92b`). The original text
  is kept below because its DESIGN was right and its ATTRIBUTION was wrong.
- [ ] **(CHK.70)(b) IS STILL OPEN — an IDENTIFIER subject's narrow is loop-blind at the
  DECLARATION reader with a PRIMITIVE target.** Unchanged by this round: the gate opened the
  RETURN and ASSIGNMENT readers, not the declaration one's `currentLocalTypes` path.
- [x] **(CHK.71)(b) DONE 2026-08-28 — THE BLOCKER WAS NOT B83.5 BUT A *FOURTH* SHADOW
  SHAPE, AND IT LANDS ON ITS OWN.** A BLOCK-scoped declaration inside a NESTED function
  shadowing an ENCLOSING FUNCTION's local was covered by none of round 351
  ([Checker.applyBodyLocalShadowing], top-level decls), round 460
  ([Checker.applyAmbiguousBlockScopedLocals], two decls in one body) or round 455
  ([Checker.applyNestedGlobalShadow], a GLOBAL/file-level collision) — whose condition is
  literally `outerBound && !currentLocalTypes.containsKey(nm)`, i.e. the inherited case
  inverted. A shipped ours-only TS2322 at every assignment to the inner name, reported
  against the WRONG declaration's type, with no optional chain anywhere near it; twelve
  lines reproduce it and tsgo 7.0.2 is silent. `added=0 removed=0`, suite 16,422/0/3,
  cost_gate exit 0, knip 49 / jsonrepair 4 unchanged.
- [ ] **(CHK.71)(a) THE OPTIONAL-CHAIN RECEIVER HALF — RE-DERIVED, RE-MEASURED, REFUSED
  AGAIN, ON A *DIFFERENT* ROW.** `a?.b` looks its member up on `a` WITH its nullish
  constituents, so every optional chain over a `T | undefined` receiver answers `any`; the
  fix is an `optionalChainReceiverType` strip in `computeRawTypeOfPropertyAccess` and
  `getTypeOfElementAccess` (four lines plus a helper — scratch only, re-derive it; a copy of
  the measured tree is `build/chk71/Checker-both.kt`). **The two `moduleNameResolver.ts`
  rows that refused it last round are GONE** — they were (CHK.71)(b) — and with both halves
  the 8-profile grid is `added=0 removed=0` at the standing digest. What refuses it now:
  **knip 49 -> 50**, an ours-only FP at `compilers/compilers.ts:60:49` (TS18047
  `'match' is possibly 'null'` in `return match?.[1] ? [\`… ${match[1]} …\`] : []`) — tsc
  narrows a receiver to non-null in the TRUE branch of a truthy test on an optional chain
  and we do not, which was invisible while `match?.[1]` answered `any`. **So the blocker is
  now OPTIONAL-CHAIN TRUTHINESS NARROWING, a nameable and reducible mechanism, not B83.5.**
  Second, smaller cost: the capture channel gains **236 definitions** in both arms and
  exactly one is order-dependent (`resolutionCache.ts @39543..39549`, present in FULL and
  absent in NARROW), taking `capture-equivalence.sh`'s standing `definitions=0` to 1 — the
  (INC.2) first-touch family, in a population that did not exist before. The RESULT half
  (`a?.b` is `typeof a.b | undefined`) is still a separate, much larger change.
- [ ] **(CHK.73) — DIAGNOSED AND PRICED 2026-08-29, AND IT IS NOT WHAT THIS ENTRY SAID.
  THE BLOCKER IS THE STATIC SIDE OF A CLASS, NOT RESOLUTION, AND THE ROUND-409 TS2315
  HAZARD IS NOT IN PLAY.** Built against a probe project with a REAL `@types/node`
  (`npm i @types/node@20` under `tools/node/bin` — the bench profile's `@types` directory
  is EMPTY, so CLAUDE.md's "the compiler profile carries the real @types/node" is stale).
  Measured, tsgo answers 3 rows where we answer 1. **THREE separate defects, in order:**
  **(i)** `resolveAlias`'s `ImportDeclaration` arm has no `resolveImportTargetFallback`
  leg, which (CHK.30) states is mandatory for a BARE package specifier;
  **(ii)** `getTypeOfSymbolWorker` has NO `SymbolFlags.Module` arm, so even a fully
  resolved module symbol with a populated `exports` table falls through to `anyType` —
  the alias resolution the entry blamed has worked for a long time
  (`createModuleSymbol` even digs a `.d.ts`'s single `declare module "…"` out);
  **(iii)** `@types/node` is AMBIENT (`declare module "fs"`), so no file resolves at all
  and the crawl correctly reports `fs` unresolved — `import x = require("fs")` already
  takes a `globals[specifier]` second chance and `import * as` did not.
  **WITH (i)+(ii)+(iii) THE BINDING AND ITS MEMBERS TYPE EXACTLY AS tsgo**
  (`fsStar.statSync` -> `StatSyncFn`, `fsStar.readFileSync('x')` -> `Buffer<ArrayBuffer>`),
  i.e. hover and completion on `fs.` work — **but a general `SymbolFlags.Module` arm moves
  21 corpus baselines** (the internal-module family: `aliasUsageIn*`, `typeValueConflict*`,
  `moduleAndInterfaceWithSameName`, `typeofInternalModules`), and containing it to an
  import alias's TARGET still leaves **4**: `aliasUsageInObjectLiteral`,
  `aliasUsageInFunctionExpression`, `aliasUsageInTypeArgumentOfExtendsClause`,
  `extendingClassFromAliasAndUsageInIndexer`. **All four are ONE cause and it is a
  MEANING regression, so this may not land as it stands**: a module object exposes an
  exported CLASS as its constructor (`new () => Model`), and this checker types a class
  VALUE as its INSTANCE type — a ctor-less class has no construct signature to match.
  **So the prerequisite is the static side of a class value**, and the `statSync` silence
  that remains is a THIRD thing again — calling a value whose type is a callable
  INTERFACE, which fails identically through a NAMED import and is therefore not about
  namespaces at all. ORIGINAL ENTRY: A DEFAULT OR NAMESPACE IMPORT TYPES AS `any` — AND
  THAT, NOT `statSync`, IS THE ONE knip ROW THE GATE ADDED (48 -> 49).** Measured inside knip's own project with a
  probe file, three spellings of the SAME function: `import { statSync } from 'node:fs'`
  answers `Stats | undefined` (correct, whole overload set present), while
  `import fs from 'node:fs'; fs.statSync(…)` and `import * as fs from 'node:fs'` both answer
  **`any`** — and `fs` ITSELF is `any` (`const c = fs; const q: number = c` is silent), so
  it is the BINDING's type, not a member lookup. `path.join(…)` and `fs.readFileSync(…)` go
  the same way. That is what makes `glob-cache.ts:62` unreachable: the ternary
  `stat?.isDirectory() ? stat.mtimeMs : Number.NaN` cannot type as `number` while `stat` is
  `any`, and (CHK.70)(f)'s refusal of an `any` ternary is correct as written — **no
  narrowing or overload work closes that row**. `resolveAlias` deliberately never resolves a
  NamespaceImport alias (round 444) and the flow path has its own resolver
  ([Checker.resolveNamespaceMemberFnDecl], round 477) precisely because the TYPE path has
  none. **The blast radius is the whole question**: tsc's own sources hold 23-147
  `import * as ns from "./_namespaces/…"` sites per profile against only ~5-14 non-relative
  ones, so a general fix re-opens round 409's TS2315-flood hazard. The containment worth
  measuring FIRST is an AMBIENT-module-only second chance (`declare module "node:fs"`),
  which excludes every relative barrel by construction and is the population a real library
  actually uses.
- [x] **(CHK.72)(a) DONE 2026-08-28 — THE FLOW WALK'S CALL SHORTCUT DID NO OVERLOAD
  SELECTION.** [Checker.resolveFlowCalleeDecl] answers `valueDeclaration ?:
  declarations.firstOrNull()`, so its two return-annotation consumers answered about the
  FIRST signature: [Checker.resolvedCallReturnTypeForFlow] installed the wrong overload's
  return (a WRONG TYPE) and [Checker.callRhsHasNonNullishReturnAnnotation] stripped a
  `| undefined` the selected overload genuinely has (a FALSE NEGATIVE). Both now route
  through [Checker.getReturnTypeOfCallExpression], gated on a BODYLESS resolved declaration.
  Universal — an implementation-bearing overload set, an interface method pair and a
  `declare namespace` member all read first-wins on the parent, and ARITY alone did not
  discriminate. `added=0 removed=0`, suite 16,417/0/3, `output.errors` 46, knip 49 /
  jsonrepair 4 unchanged. The first version merely REFUSED the non-nullish claim for an
  overload set and cost one ours-only TS2322 on every profile (`esDecorators.ts:1309`) —
  `factory.getGeneratedNameForNode` is two overloads that both return `Identifier`.
- [ ] **(CHK.70) — THE ORIGINAL TEXT, KEPT FOR ITS DESIGN.**
  **(a) A COMPOUND ASSIGNMENT INSIDE A LOOP HAS NO POST-STATE RULE.**
  `harness/tsserverLogger.ts` is `let result: string | undefined = …; result = "";
  while (true) { … result += source; } return result` — tsc's loop fixpoint unions the
  entry (`string`) with the back edge's `+=` post-state (`string`) and answers `string`;
  ours sees an assignment to the name on a back edge, so [Checker.loopBodyMayAffectName]
  claims it and the label washes to `string | undefined`. (CHK.67) deliberately does not
  unwrap a compound assignment, so [Checker.narrowByAssignmentRhs] has no arm for `+=`.
  **The cheap shape is a PARTIAL fixpoint that still walks no back edge**: collect the
  back-edge assignments the scan already finds (bounded), take each one's post-state from
  the DECLARED type alone — which is what makes the (CHK.66)(b) KDoc's one-pass argument
  true — and union them with the entry answer. That needs a `+=` arm first; without one
  the union is `declaredType` and buys nothing.
  **(b) AN IDENTIFIER SUBJECT'S NARROW IS LOOP-BLIND IN BOTH DIRECTIONS.** Measured on the
  parent AND on `dcaf1594`: `function f(x: string|number) { if (typeof x === "string") {
  while (cond()) { x = 1; } const p: string = x; } }` is SILENT where tsc 7.0.2 emits
  TS2322 — a shipped FALSE NEGATIVE. Round 784's gate sends the DECLARATION/ASSIGNMENT/
  RETURN readers to [Checker.currentLocalTypes] for a primitive target, and that map is
  statement-ordered with no loop notion at all, so neither (CHK.69) nor anything before it
  can see the loop. It is also why an identifier fixture is VACUOUS for every loop-narrowing
  pin — use a PROPERTY PATH (see `ALoopThatCannotAffectAReferenceKeepsItsNarrowTest`).

- [x] **(CHK.63) OPENED 2026-08-28 (`7a488783`) — `added=0 removed=0` ON ALL EIGHT
  PROFILES.** Six edits, six distinct ablation red sets: the source gate, the RETURN and
  ASSIGNMENT readers' flow admission, (CHK.61)(b)'s checking half, a `never` refusal at the
  return reader (an UNREACHABLE `return undefined` suppressed itself — the corpus baseline
  `functionReturn.ts` caught it), a nullish strip at the weak-type assignment target, and
  (CHK.70)(f)'s conditional-RHS arm. Suite 16,411/0/3, no corpus baseline moved, cost_gate
  rebaselined at `narrow.walks` +11.17% / `narrow.memoServed` +6.61%. Two costs are named
  and queued rather than absorbed: knip 48 -> 49 ((CHK.72)) and 611 capture spans to `any`
  ((CHK.71)). ORIGINAL TEXT:
- [x] **(CHK.63) `T | undefined` IS SILENTLY ASSIGNABLE TO `T` AT A DECLARATION, AN
  ASSIGNMENT AND A RETURN WHENEVER THE TARGET IS A PRIMITIVE — A SYSTEMATIC FALSE
  NEGATIVE, AND ITS SINGLE SUPPRESSOR IS ONE `if`.** `canUseTypeEngine`'s
  `if (sourceType is Type.Union && targetIsPrimitive) { … if (!hasNullish) return true }`
  refuses a NULLISH union source against a primitive target, with the comment "narrowing
  we don't implement". On a six-line fixture tsc emits 6 rows and we emit 2.
  **RE-PRICED 2026-08-28 ON TOP OF (CHK.69): THE COMBINED ARM — gate + RETURN/ASSIGNMENT
  readers + (CHK.61)(b)'s checking half + (CHK.67) + (CHK.69) — IS `added=0 removed=0` ON
  SEVEN PROFILES AND `added=1` ON `tsc-harness`.** The single row is
  `harness/tsserverLogger.ts:28:5`; (CHK.66)(b)'s residue `checker.ts:43282:21` and the
  four `moduleNameResolver`/`server/project.ts` rows are all CLOSED. **And it is now
  AFFORDABLE**: `narrow.walks` +11.2%, `narrow.memoServed` +6.6%, every other counter
  <= 1%, wall flat (26.8 s against 26.9 s) — the ~20x blocker is gone.
  **It is NOT opened**, because 1 ours-only row on a dashboard whose v1 exit is zero FPs
  is a decision to take at 0; the remaining row's cause is named and queued as (CHK.70).
  Rebuild the arm with `python3 build/chk69/arm3.py 1234` against
  `build/chk69/snap/Checker.kt.ship`; grid tag `chk69_comb2`, parent `chk69_parent`.

- [x] **(CHK.61)(b)'s CHECKING HALF — MEASURED CORRECT AND COMPLETE 2026-08-28, WAITING
  ONLY ON THE GATE.** Part `4` of `build/chk65/arm.py` gives
  [Checker.computeRawTypeOfPropertyAccess] a `| undefined` constituent for an OPTIONAL
  member at its three return sites. On the five-reader census fixture
  (`build/chk65/f1`) it reproduces tsc 7.0.2 **EXACTLY** — five rows, same codes, same
  messages, same 1-based columns — and it REMOVES the gate's own `emitter.ts:1479` and
  `services/organizeImports.ts:862` (a `var` whose initializer is an optional member, so
  tsc infers `T | undefined` for the variable and we inferred `T`). It CANNOT land alone:
  without the gate it deletes a true positive (`const a: string = o.optNum` becomes a
  nullish union that `canUseTypeEngine` refuses against a primitive target), and it is
  therefore part of (CHK.63)'s single commit, not an item of its own.

- [x] **(CHK.66)(a) DONE 2026-08-28 — A FLOW JOIN REDUCES SUBTYPES; `string | number | "a"`
  WAS A SHIPPED DIVERGENCE AT A PLAIN BRANCH LABEL.** [Checker.getUnionType] performs no
  subtype reduction (INV.5(a) interns by member-id list alone), so
  [Checker.narrowTypeFromFlowCore]'s `FlowBranchLabel` arm kept a narrowed member beside
  the declaration's own — four lines, no loop, no partition, confirmed against tsc 7.0.2.
  `flowJoinUnion` applies tsc's `UnionReduction.Subtype` at the TWO flow joins only, gated
  on a member the DECLARATION does not itself contain and requiring a STRICT subtype
  (`subtypeRelation` is declared here with ZERO readers, so only assignability exists).
  `AFlowJoinReducesANarrowedSubtypeTest` (7 positives naming their reader, 2 controls).
  Grid byte-identical, suite +9, no baseline moved.

- [x] **(CHK.66)(b) THE LOOP JOIN — **REFUSED 2026-08-28 WITH ITS MECHANISM MEASURED**,
  AND SUPERSEDED BY (CHK.69), WHICH DELIVERS ITS ROWS FOR NOTHING.** The ~20x is
  MEMOIZATION being switched off: `narrowLoopCutUsed` forbids storing anything computed
  under the cut and propagates to the walk root, so a loop body's paths are ENUMERATED
  instead of folded. Deleting that term (unsound; the ceiling) returns **89.2%** of the
  added `globals.lookups` and **91.8%** of `typeNode.cacheable`. A SOUND cut-keyed memo —
  a rolling hash of the in-progress label set carried as an extra equality field on every
  `NarrowFlowMemo` entry — recovers **0.003%**, because the cycle almost never closes ON
  the loop label; it closes on the walk's OWN PREFIX, which is path-dependent. And the
  ceiling itself is still +115% `globals.lookups`, +395% `typeNode.cacheable` and 44.1 s
  against 26.7 s cold, so the direction is refused in its BEST case. **(CHK.69)** answers
  the label by FOLLOWING ITS ENTRY whenever no back edge assigns the reference — the same
  fixpoint, no traversal — and closes 4 of the gate's 5 rows plus the `checker.ts:43282`
  residue. Arms kept at `build/chk69/m1.py` / `m2.py`.

- [x] **(CHK.67) DONE 2026-08-28 — A CHAINED ASSIGNMENT NARROWS TO ITS RIGHTMOST OPERAND;
  `x = y = z` WAS A SHIPPED FALSE POSITIVE (`2cbb3847`).** The queue named TWO unclassified
  shapes at `checker.ts:35649`; a six-shape census against tsc 7.0.2 shows
  `index = index! + 1` was ALREADY handled by the (CHK.33) computed-primitive arm, and the
  chained `index = cutoffIndex = result.length` is the whole gap. Every arm of
  [Checker.narrowByAssignmentRhs] classifies the RHS syntactically and `y = z` matches
  none of them — a `BinaryExpression` whose operator IS `=`, which (CHK.33) excludes by
  construction. Reachable with NO gate and NO loop at the UNION-target declaration reader.
  `unwrapAssignmentChainRhs` descends the `=` chain through parens; a COMPOUND assignment
  is deliberately not unwrapped. `AChainedAssignmentNarrowsToItsUltimateRhsTest` (6
  positives naming their reader, all RED on the rebuilt parent; 2 controls green on both).
  Removes `checker.ts:35649:17` from (CHK.63)'s `armBGR`, 6 rows -> 5.

- [x] **(CHK.64)(i)+(ii) DONE 2026-08-28 — THE FIVE "NARROWING GAPS" ARE **TWO GAPS AT ONE
  READER**, AND BOTH ARE CLOSED; (CHK.63)'s PRICE FALLS **11 ROWS -> 6**.** Round 784's gate
  sends the ASSIGNMENT and RETURN readers to [Checker.currentLocalTypes] for a primitive
  target, and the legacy filler [Checker.extractNullNarrowing] could neither read an `&&`
  (i) nor look anywhere but a then-branch (ii). Everything else about those shapes was
  already right — a MEMBER ACCESS, a CALL ARGUMENT and a DECLARATION are correct on the
  parent binary in BOTH families, which is the census that collapsed five mechanisms into
  two. `AndConditionNarrowsEveryOperandTest`, `EarlyExitNarrowsTheRestOfTheBlockTest`.
  Three defects the GATES found and reading did not: a `typeof x === "object"` conjunct
  installed `any` (a WIDENING, 13 captured hovers); recording the declared type into the
  frame's SHARED `narrowedDeclared` leaked across FUNCTIONS (21 ours-only rows per
  profile); and a negated GENERIC type-guard call degraded the element type (20 captured
  spans to `any`). One SHIPPED defect fell out and is fixed: nested narrows on one name
  recorded `narrowedDeclared` LAST-wins, so `if (b) { if (isNs(b)) { b = undefined } }` was
  a false TS2322.
  **RESIDUE — 2 of the 4 are CLOSED by (CHK.63)(a)(c) 2026-08-28, and the other 2 are
  ONE refused change:**
  1. `parser.ts:2642` — (iii) an assignment INSIDE the guarded branch. **CLOSED by
     (CHK.63)(a)** — it was a SHIPPED false positive at the call-argument reader, not a
     gate-only row.
  2. `checker.ts:35649` — filed as "(iv) definite assignment across an if/else"; measured,
     the if/else is CORRECT and the read is inside a `for` whose earlier iteration assigns
     the reference. **It is the LOOP family**, i.e. the same item as 3.
  3. `tsserverLogger.ts:28` — an assignment narrow that must survive a LOOP. **REFUSED
     2026-08-28 with its price: the loop-join union costs 8 ours-only rows per profile**,
     5 of them a `never` from a negated GENERIC type-guard call that the loop label's
     `declaredType` was masking, 3 a join over a TRUNCATED antecedent that is LESS
     reducible than the declaration. See the (CHK.63)(a)(c) session note and
     `build/chk63/snap/Checker.kt.gapB-refused`.
  4. `server/project.ts:746` — a NON-NULL ASSERTION `!`. **CLOSED by (CHK.63)(c)** — the
     operand is read through PARENTHESES and over a LOGICAL operator; the same defect at a
     UNION target was a shipped false positive.
  Plus (v), the optional-METHOD `&&` chains, which are a DIFFERENT mechanism: an `&&`
  whose EARLIER conjunct narrows a LATER one (`isNamedDeclaration(child) &&
  isPropertyName(child.name)`). It has no measured armG row of its own and it IS the cause
  of the round's 3 remaining capture regressions, so it is the next one to take.
  Smaller residues, each pinned with our own answer: a `while`/`do` body and a plain
  nested `{ … }` block share their parent frame's `localTypes` map, so an early exit
  inside one does not narrow; an `if … else` is refused even when the then-branch exits;
  a PARENTHESISED `!` operand is not unwrapped; and the SINGLE-condition
  `typeof x === "object"` path still installs `any`, a shipped false NEGATIVE
  (`build/chk64/c4`).

- [ ] **(CHK.62c) A PROPERTY-ACCESS ASSIGNMENT RHS DOES NOT NARROW THE ASSIGNED REFERENCE
  (2026-08-27, measured while closing (CHK.62b)).** `let p = zzzFindFree(); p ??= zzzObj.zzzFld;
  return { p }` reports `p: ZzzProj | undefined` where tsc 7.0.2 is silent, and `this.zzzFld`
  behaves identically — so this is NOT the `this` axis (CHK.62b) closed.
  `rhsIsDefinitelyNonNullish`'s `PropertyAccessExpression` arm classifies only an ENUM member
  and a literal; everything else falls through to no-narrowing. The obvious generalisation
  (resolve the member and test it for nullishness) is the round-385/(CHK.62) hazard — it types
  the receiver on the flow hot path — so it needs the same three-gate treatment
  `flowCallDiverges` got. Repro `build/chk62b/p4`.

- [ ] **(CHK.61b) THE ENUM RESIDUE AFTER (CHK.60) — FIVE ITEMS, EACH WITH ITS MEASURED ROW.**
  1. **AN UNEVALUATED ENUM MEMBER IS STILL REFUSED**: `enum E { A = zzzNonConst }` against
     `{ toFixed?() }` is silent in tsc and TS2345 here (`build/chk60/ue/u2.ts(6,8)`).
     `enumLiteralApparentPrimitive` demands POSITIVE evidence of the member's computed
     value, and ablation arm a2 measured that defaulting to numeric fixes this row and
     reddens nothing. **It was refused because a neighbouring shape shows the hazard**: a
     TEMPLATE-valued string member (`build/chk60/ue/u3.ts`) does not fold in our evaluator,
     so a numeric default would relate a STRING member to `Number`-shaped targets — a false
     NEGATIVE. **The sound version is to fix the FOLD first**, or to default to numeric only
     when no member of the owning enum evaluated to a string.
  2. **THE WHOLE ENUM TYPE AS A SOURCE IS ACCEPTED VACUOUSLY** — `zzzX(zzzse)` against
     `{ zzzNope?: number }` is TS2559 in tsc (`build/chk60/mx/m1.ts(25,6)`) and silent here,
     as is a MIXED enum against `{ length?: … }` (`m1.ts(29,6)`). Both are (REL.1)(b): a
     member-less source against a member-less comparison passes both ways. tsc models a
     literal enum AS the union of its members, which is the thing this repo does not have.
  3. **AN INDEX-SIGNATURE TARGET ACCEPTS AN ENUM SOURCE** — `zzzI(ZzzSE.A)` against
     `{ [k: string]: any }` is TS2345 in tsc (`m1.ts(19,6)`) and silent here, before and
     after (CHK.60): `objectTypeRelatedTo` answers true for an empty-`properties` target
     before the retry is reached, and arm a5 measured that reordering does not change it.
  4. **`object`, `() => void`, `Promise<T>` AND `T[]` TARGETS** reject an enum source in tsc
     (`m2.ts(20..23)`) and are silent here — a fourth face of the same vacuity.
  5. **A BIGINT LEAF** (`{ zzzIn: 12n }`) still falls through to TS2322 where tsc reports
     TS2559 `Type 'bigint'` at the key — `weakSourcePropertyNames`' `BigIntLike` arm does
     not resolve to an object here. Inherited from (CHK.60) item 7, one line.

- [ ] **(CHK.53) `namespace globalThis { … }` IS NOT A NAMESPACE DECLARATION AND WE MODEL IT
  AS ONE — (CHK.50)'s measured refusal.** tsc treats `declare global { namespace globalThis {
  var test: string } }` as an augmentation of the GLOBAL SCOPE ITSELF: `test` becomes a bare
  global and `globalThis` never becomes an ordinary symbol. (CHK.50) published it as one and
  the corpus case `extendGlobalThis` reddened with a TS2339 on `globalThis.tests` that
  pristine tsc does not report, so the name is now skipped outright — which leaves that shape
  exactly where (CHK.50) found it: `globalThis.<anything>` is unchecked. **Two halves**: the
  block's members should become bare globals, and `globalThis` itself should be a type whose
  members are the global scope. The second half is what pristine's baseline is really about,
  and it is the only instrument that sees any of this (no profile and neither library carries
  the shape). `DeclareGlobalAugmentationTest`'s `a namespace globalThis block is not published
  as a global symbol` pins the refusal; the positive half is deliberately NOT pinned
  (round 765).

- [x] **(CHK.54)+(CHK.54b) DONE 2026-08-26 — THE AXIS IS THE **WEAK-TYPE RULE**, NOT
  OPTIONALITY, AND A SECOND, INDEPENDENT RULE WAS HIDING BESIDE IT.** Measured over a
  14-row overload matrix against tsc 7.0.2: the item's own shape
  `(x, y?: null)` / `(x, y: "u")` called with `("a", "u")` already selected correctly on
  the PARENT binary, and making the parameter non-optional reproduces the defect
  identically. What decides it is that overload 1's parameter is a **weak type**
  (all-optional, signature-free) and our relation says a string literal is assignable to
  one — because the weak rule lives in the B482 *walkers*, not in `checkTypeRelatedTo`.
  `signatureAcceptsArgs` now asks `weakParamRefusesArg`, per union constituent exactly as
  tsc's `typeRelatedToSomeType` does. **(CHK.54b)**: tsc additionally hoists a
  **specialized** signature (a parameter whose type ANNOTATION is a literal type NODE)
  ahead of every plain one — `reorderCandidates` / GH#1133 — which we did not, so
  `f(x: string): A` before `f(x: "a"): B` answered `A` for `f("a")`. Pins:
  `OverloadWeakParamSelectionTest` (8), `OverloadSpecializedOrderTest` (7), every positive
  asserting the selected overload's RETURN TYPE as a value. Suite 16,133 / 0 / 3, no
  baseline moved, all 20 cost counters digit-identical to the parent, grid
  `added=0 removed=0` on all eight, **knip 54 -> 49** (exactly the five
  `Buffer<ArrayBuffer>` rows), jsonrepair 4 -> 4 byte-identical. Residue queued as
  (CHK.55). See the session note for the matrix and the ten-arm ablation.
  ORIGINAL ENTRY: AN OPTIONAL-PARAMETER OVERLOAD IS SELECTED WITHOUT CHECKING THE ARGUMENT
  AGAINST IT — SIX ROWS ON `knip`, AND A SIX-LINE REPRO.** `readFileSync(p, 'utf8')` resolves
  to the `Buffer`-returning overload whose parameter is `options?: { encoding?: null } | null`,
  a type `"utf8"` is not assignable to; tsgo picks the `string` one and is silent. Reproduced
  hand-written, on the PARENT binary and the landed one identically, with NO `declare global`
  in the fixture — so it is pre-existing and independent of (CHK.50), which merely made it
  visible by giving `Buffer` a real type where it had been an unresolved `any`:
  ```ts
  type ZzzEnc = "utf8" | "ascii"
  interface ZzzBuf { zzzB: number }
  declare function zzzRead(p: string, options?: { encoding?: null } | null): ZzzBuf
  declare function zzzRead(p: string, options: { encoding: ZzzEnc } | ZzzEnc): string
  declare function zzzRead(p: string, options?: { encoding?: ZzzEnc | null } | ZzzEnc | null): string | ZzzBuf
  const zzzS: string = zzzRead("f", "utf8")            // ours: TS2322 'ZzzBuf' -> 'string'
  const zzzT: string = zzzRead("f", { encoding: "utf8" }) // ours: TS2769 + TS2322
  ```
  The `{ encoding: "utf8" }` form additionally emits TS2769, so the two are probably one
  defect seen from both ends. **The population is large and silent today**: every `@types/node`
  read/exec API is written this way, and until (CHK.50) the wrong pick was invisible because
  the wrongly-chosen return type was `any`. Sequence it before any further library screening —
  it is the largest remaining knip family.

- [x] **(CHK.51) DONE 2026-08-26 — THE AXIS IS **HERITAGE**, NOT "LIB", AND THE FIREWALL THAT
  HIDES IT IS WORTH **43 ROWS** ON THE COMPILER PROFILE.** The item's own repro (`Date`) already
  reported, as did `Map`, `Set`, `Promise`, `RegExp`, `Error`, `JSON`, `Math`, `Symbol`,
  `Iterable`, `ArrayBuffer`, `EventTarget` and every primitive — all heritage-free — while a
  HAND-WRITTEN `interface D1 extends B1` was as silent as `Text`. What refuses is
  `cmamCheckResolvedObjectType`'s "skip if class/interface has base types", and deleting it
  outright measures **89 against 46** on the compiler profile, every new row a NARROWING gap
  (`canHaveSymbol(e) && e.symbol`). So the relaxation demands POSITIVE evidence: a new predicate
  requires every type in the transitive base closure to be an interface whose declarations are
  ALL lib declarations, none named by a `declare global { interface … }` block, each with a
  resolved member table. `Text`, `Node`, `Element`, `HTMLElement`, `CustomEvent<number>` now
  match tsgo 7.0.2 on code, message and column. Pins: `LibHeritageMissingMemberTest` (6, with
  `@useRealLibs` + `@lib: es2020,dom` — the embedded lib has no DOM and every one of them would
  otherwise pass vacuously). Residue queued as (CHK.52).
  ORIGINAL ENTRY: A MISSING MEMBER ON A *REAL LIB* INTERFACE IS NOT REPORTED — `declare const
  t: Date; t.zzzNope` IS SILENT WHERE tsgo SAYS TS2339 (found 2026-08-26 while writing
  (CHK.49)'s cross-file pin, which had to be re-pointed at an ASSIGNMENT because of it).

- [ ] **(CHK.52) A MISSING MEMBER IS *STILL* UNREPORTED ON FOUR RECEIVER FAMILIES, AND THEY ARE
  FOUR DIFFERENT MECHANISMS — (CHK.51)'s measured residue, tsgo reports all of them.**
  (a) a **PROGRAM interface with heritage** and (b) a **MIXED closure**
  (`interface Mine extends HTMLElement`) are both the heritage firewall still standing, and
  both are blocked on the same thing: the 43 rows a naive removal adds are the checker's
  NARROWING gaps, above all the INTERSECTION narrow tsc performs when a type predicate names a
  SIBLING rather than a subtype (`canHaveSymbol(node: Node): node is Declaration` on an
  `e: Expression`). **Those 43 rows are a free, already-captured map of that gap** — start
  there, not at the firewall. (c) an **ARRAY or any numeric-index receiver** (`number[]`,
  `Array<T>`, `ReadonlyArray<T>`, `Uint8Array`) is `cmamEmitMissingProperty`'s
  `if (numberIndexInfo != null) … return`, which is over-broad: a NUMERIC index signature does
  not cover a non-numeric name, and tsc reports `arr.zzzNope`. (d) a bare **FUNCTION type**
  (`() => void`) has no properties and a non-empty `callSignatures`, so it falls out of the
  `{}` emitter's gate and returns. And a **CLASS instance with a base** is silent even with the
  firewall removed entirely, i.e. a FIFTH mechanism this round did not locate. (c) and (d) look
  independently closable and cheap; (a)/(b) are the expensive half.

- [ ] **(CHK.33) A DESTRUCTURING PARAMETER BREAKS ARITY, AND THE MESSAGE PROVES IT: `Expected
  1-0 arguments, but got 1` — 8 ROWS IN `marked`, ON A LIBRARY tsgo REPORTS ZERO ERRORS FOR.**
  `marked`'s renderer methods are all written `html({ text }: Tokens.HTML | Tokens.Tag):
  RendererOutput`, and every call `renderer.html(token)` is rejected. **This is round 921's
  documented hazard reaching a diagnostic for the first time**: CLAUDE.md already records that
  `getParameterSymbols` DROPS every binding-pattern parameter, so `Signature.parameters` is
  EMPTY while `minArgumentCount` still counts the pattern — which is exactly an inverted range
  of min 1, max 0, printed verbatim. **The inverted range is a free assertion**: no correct
  signature can have `minArgumentCount > parameters.size`, so `require` it where signatures are
  built and this class of defect stops being silent. Fixing arity may not be the whole item —
  the same drop shifts the positional zip of type annotations onto the surviving parameters
  (CLAUDE.md's `f({a}: O, b: string)` example types `b` as `O`), so pin BOTH the arity and the
  parameter TYPES, and prefer `sig.declaration`'s own list as the reference the way
  `typeCaptureSignatureParameters` already does.

- [ ] **(CHK.34) `isolatedDeclarations` OVER-REPORTS — 32 ROWS ON A LIBRARY THAT SHIPS WITH THE
  FLAG ON AND IS CLEAN UNDER tsgo.** `yaml` sets `"isolatedDeclarations": true` and tsgo finds
  **0** errors; we emit TS9008×19, TS9023×11, TS9007×1, TS9009×1. One member is identified:
  `nodes/YAMLMap.ts:232` is the IMPLEMENTATION signature of an overload set, which needs no
  return annotation under `isolatedDeclarations` because the overload signatures above it carry
  one — so the rule is being applied to a signature the flag exempts. TS9023
  (`Assigning properties to functions without declaring them`) fires 11 times at
  `visit.ts:108-109` and is unexamined. **Sequence this AFTER (CHK.31)-(CHK.33)**: it is the
  biggest row count in the screen and the narrowest trigger — it costs nothing on a project that
  does not set the flag, where the other four families cost every project. The 8 profiles do not
  set it either, so `cost_gate.py` and the grid are structurally blind here and `yaml` is the gate.

- [ ] **(CHK.35) A FUNCTION EXPRESSION ASSIGNED THROUGH AN INDEX SIGNATURE GETS NO CONTEXTUAL
  SIGNATURE — 5 ROWS, AND IT IS (CHK.30)'s SIBLING.** In `marked/Instance.ts:118`,
  `extensions.renderers[ext.name] = function(...args) { … ext.renderer.apply(this, args) … }`
  gives **TS7019** for `args` (rest parameter implicitly `any[]`) and **TS2683**×4 for `this`
  (implicitly `any`), where tsgo is silent — because the index signature's value type supplies
  both the parameter list and the `this` type, and we are not reaching it. (CHK.30) is the same
  failure one container over (an object-literal shorthand METHOD's parameters), so **check
  whether one contextual-signature path serves both before writing either** — if it does, the
  two items are one. Same standing trap applies: a contextual parameter type that does not reach
  `populateParameterLocalTypes` is invisible to the body walkers, so a probe must FAIL if the
  change is inert.

- [ ] **(KIR.LOWER.2) THE SAME ABSENT-DECLARATION TRAP MAY BE LIVE IN `ErasedTypes` — a LEAD, not a
  finding.** `ErasedTypes.mapObject` ends `if (declaration == null) return jsObjectType()`, which
  (KAPI.4) measured to be reached by a `Promise<string>` on the API side: a `Type.Reference`'s own
  symbol carries no declaration, so a named library type outside `libraryClass`'s table erases to a
  property BAG rather than being refused. On the lowering that is not a wrong TYPE but wrong CODE —
  a `.then` on it would read a bag slot — and it is untested because neither corpus library uses a
  Promise. Check whether the target-symbol fallback changes any erasure on the two libraries
  (`scripts/kir-bench.sh`'s equivalence gate is the instrument), and if it does not, add the
  refusal: a named type with no reachable declaration is one this backend does not know.

- [ ] **(KAPI.2) THE PLATFORM HALF: pin that the emitted JVM classes match the exported
  metadata.** `(KAPI.1)` declares a library's API as Kotlin metadata for `commonMain`; a
  `jvmMain` compilation links against the CLASSES the KIR backend emits, and nothing asserts
  the two agree on package, name and erased JVM signature. The failure is the worst-shaped
  one available — the consumer's common code type-checks and its platform code does not link
  — so the instrument is a pin that compiles a JVM consumer against the emitted classes and a
  common consumer against the klib FROM ONE EXPORT, and fails when either resolves something
  the other does not. Expect real divergences to fall out: the JVM lowering names a file's
  facade after the file (`MittKt`) where the metadata puts every declaration in one package,
  and module variables are reached through generated `name$get` accessors rather than as
  properties. `docs/kir-kotlin-metadata.md` §6 item 1.

- [x] **(KAPI.3) A RUNTIME METADATA KLIB — LANDED 2026-08-22, same session.** A SECOND metadata
  klib declares `JsObject` and `JsArray` under their real fully qualified names, is written by
  the same machinery and goes on the exported library's compile classpath — opt-in through
  `runtimeKlib =`, so the self-contained artifact stays available. Measured on the two real
  libraries: `mitt(all: JsObject?): JsObject` and **`parse(toml: String, options: JsObject?):
  JsObject`**, both pinned, and a consumer that reads `document.get("title")` compiles against
  the pair. **The gate is the load-bearing part**: a bag needs POSITIVE evidence — the
  lowering's own `isOwnStructuralDeclaration` (a structural kind declared in a program file
  that is not a `.d.ts`), an anonymous object type by construction, and nothing else — because
  a `Date` is a `JsDate` at run time and typing one as a bag offers members the value does not
  have. An INTERSECTION is one bag only when EVERY member is positively one, which is stricter
  than `ErasedTypes.mapIntersection` and forced: with no library-type table, `Date` and an
  unmappable constraint give the same answer, so the permissive reading types `Date & Tag` as a
  bag (a pin holds both directions). The facade is stated by hand — Java reflection cannot see
  nullability and `kotlin-reflect` here is older than the runtime's metadata — so the drift is
  CAUGHT rather than prevented: `KirRuntimeApiTest` reflects over the real classes, with two
  negative controls proving the check can fail. What is left is the library-type table (`Map`,
  `Set`, `Date`, `RegExp`), now (KAPI.4). ORIGINAL ENTRY:**
  Measured today: `smol-toml` exports `parse(toml: String, options: Any?): Any?`, which is the
  difference between "a TOML parser returns something" and "a TOML parser returns something you
  can read". Arrays and object types erase to `Any?` for one reason only — `JsArray`/`JsObject`
  are JVM Kotlin with no COMMON metadata artifact — so the work is to produce one for the
  runtime's public surface and put it on the export's classpath (the parameter already exists,
  `compileMetadataKlib(..., classpath)`). The trap to design against is drift: a hand-written
  common facade of a JVM class is a second copy, so whatever produces it needs a pin that
  reflects over the real class and fails when a member disagrees — `scripts/kir_native_runtime.py`
  is the precedent for deriving one runtime from the other rather than forking it.

- [x] **(KAPI.4) A LIBRARY-TYPE TABLE — LANDED 2026-08-22, same session.** `KirRuntimeApi.libraryType`
  mirrors `KirIntrinsics.libraryClass` entry for entry, so `Map`/`ReadonlyMap`/`WeakMap`,
  `Set`/`ReadonlySet`/`WeakSet`, `RegExp`, `Date` and `Error` name the same runtime class on an
  exported signature as in the compiled program, and the facade declares all five beside
  `JsObject`/`JsArray` (the drift pin covers them, and now checks CONSTRUCTORS as well as members,
  with a third negative control). Measured: `mitt`'s parameter is `JsMap?` — its `EventHandlerMap`
  is an alias of a `Map` — where a bag would have been less precise than what the program holds.
  **It also found the gate's own defect: an ABSENT DECLARATION IS NOT EVIDENCE OF AN ANONYMOUS
  SHAPE.** A `Promise<string>` reached the object mapping with no declaration to walk and read as a
  property bag; two rules fix it and both are pins now — a `Type.Reference`'s own symbol carries no
  declaration where its TARGET's does (which is how `Emitter<Events>` is recognised as the
  program's own interface), and a type with a NAME but no reachable declaration is a library type
  this backend does not know. ORIGINAL ENTRY: `Map`, `Set`, `Date`, `RegExp`
  and `Promise` are runtime classes with no entry on the exported API, so they are `Any?` where
  `JsObject`/`JsArray` are now real — and, worse, they are what makes (KAPI.3)'s intersection
  rule demand positive evidence rather than reading an unmappable member as a constraint.
  `ErasedTypes` already keys such a table BY NAME (`libraryType`), which is the shape to copy;
  the declarations go in `KirRuntimeApi`, where the drift pin already covers whatever is added.


**WORK ORDER NOTE (restored 2026-08-14, round 903).** This section had been ARCHIVED out of the file
during a trim, and nothing noticed for ~15 rounds because rounds 886-902 were self-directing: each
session note named its own successor. **Round 902 ended with a CLOSURE and named none, so round 903
opened with no pool at all** and had to rebuild one by surveying `docs/perf/`. That is the failure
this section exists to prevent. **A round that refuses a candidate must leave at least one named
successor here, with its price and its next instrument** — a refusal is a successful round only if
the arc can continue from it.

**THE LIVE ARC IS (API.\*), ON OWNER DIRECTIVE (2026-08-17, round 909): DELIVER THE PROJECT AND
LANGUAGESERVICE EMBEDDING APIs.** It takes precedence over the (WARM.\*)/(SPINE.\*) perf items below,
which round 908 closed out anyway — the checker-side pool is empty. Shape decided by the owner: a
**Kotlin embedding API first** (LSP / tsserver protocol layered later, not now), in the new
`xemantic-typescript-compiler-project` module. The perf items stay below as the record; (ART.1) /
(ART.2) remain the only open perf work and (ART.1) has been corrected.

**TOP OF QUEUE ON OWNER DIRECTIVE (2026-08-21): (BENCH.1) below runs before the (API.\*) arc
resumes.**

- [x] **(KIR.PERF.2) THE REGULAR-EXPRESSION ENGINE — LANDED 2026-08-21, and it measured
  **−27.5%** of the toml parse rather than the −18% predicted (47.05 -> 34.10 us/parse,
  2.08x Node -> **1.52x**), with mitt flat at 61.25 and both Node arms flat. Per pattern
  against `java.util.regex`: **16.7x / 13.0x / 3.0x / 3.2x**. It beat its own prediction
  because two smaller members came with it — `replace(/_/g,'')` on a LITERAL path, and
  `split` no longer building a fresh `Regex(source)` per call (which also silently ignored
  the expression's flags). **It also found a divergence in the OTHER engine**: Java's `$`
  matches before a final line terminator where JavaScript's does not, so
  `/^\d+$/.test("12\n")` answered `true` here — `jsEndAnchorTranslated` closes it. Carried
  verbatim to Kotlin/Native, where it measured **−22.5%**. `KirRegexEngineTest`, 20 pins.
  ORIGINAL ENTRY:** `java.util.regex` costs **9.5 us per
  document** on `smol-toml` — 20% of the 47.05 us JVM parse, matching § 2's independent
  JFR reading, and **42% of Node's ENTIRE parse budget**. The engine gap alone (9.5 vs
  V8's 3.0 us) is **27% of the whole JVM-vs-Node difference**. It is the pattern SHAPE,
  not the call count: `^\d+$` is 14.7 ns and `^\d(?:_?\d)*$` is 94 ns, because a
  repetition whose body is not a single deterministic character compiles to Java's
  backtracking `Loop` node — and TOML's digit separators are literally `(_?\d)*`. A
  hand-written scan of the same two patterns, gated to agree on the document population
  plus fourteen adversarial inputs, is **9.4 ns and 6.7 ns — 25x and 12x**.

  **TWO CHEAP FIXES ARE ALREADY REFUSED, measured, before being built**: rewriting the
  groups as `(?: )` for `test` (legal, since `test` cannot observe groups) buys **0.6%**,
  and `matches()` in place of `find()` buys nothing.

  **WHAT TO BUILD:** not a per-pattern special case but a matcher for the REGULAR subset
  these patterns live in — no backreferences, no lookaround — compiled once per
  `(source, flags)` beside the existing `Pattern` cache, with `java.util.regex` kept LIVE
  as the differential oracle (the round-792 shape: never a legality gate). Worth
  **−8.6 us = −18%**, taking `smol-toml` from 2.08x Node to **~1.70x**.
  **AND IT COMPOUNDS ON NATIVE**, where `kotlin.text.Regex` is 5.2x `java.util.regex` and
  35x V8, i.e. ~30% of the native parse. `docs/perf/kir-backend-levers.md` § 5.

- [x] **(KIR.NATIVE.1) ALL THREE SUB-ITEMS LANDED 2026-08-21** — (a) the nominal half's
  first slice (see (KIR.PERF.1)), (b) the regex engine, carried to native verbatim and worth
  **−22.5%** there, and (c) the native arm inside `kir-bench.sh`'s own equivalence gate.
  **(a) WAS then verified on Native rather than assumed**: `mitt` compiles, links and runs
  with the shape classes and the right sink — the plugin reports `checked 2 file(s)` and
  konanc accepts the generated classes, so CLAUDE.md's "Native's IR validator REJECTS the
  public fields the JVM backend accepts" does not bite this shape — and it measures **348
  ns/emit against 354.75, i.e. FLAT**. That is the opposite of §6's expectation and the
  mechanism says why: the JVM's −10.7% comes from C2 inlining the override at a monomorphic
  call site and folding the constant name away, and Kotlin/Native has no JIT to do either,
  so the shape's `get` stays a real virtual call. **The nominal half pays on Native only
  once the property access is a direct field read** — the next slice — rather than a
  virtual `get` over fields. ORIGINAL ENTRY: THE NATIVE BACKEND EXISTS AND IS 4-7x THE JVM — AND THE REASON IS
  BOXING, WHICH MAKES (KIR.PERF.1) A CORRECTNESS-OF-DIRECTION QUESTION RATHER THAN A JVM
  OPTIMISATION.** Both libraries now compile to `-opt` Kotlin/Native binaries through the
  same `KirProgramLowering` (`scripts/kir-native.sh`), agreeing with the other three arms
  on the sink: **mitt 353.25 ns/emit against the JVM's 60.75, toml 163.30 us/parse against
  45.50**. Priced primitive by primitive from one source on both backends, every dynamic
  operation is 4-29x: `jsAdd` **0.95 -> 28.05 ns**, `jsCall1` 0.86 -> 12.93, boxing one
  `Double` 0.86 -> 8.61. **On the JVM C2 scalar-replaces most of those boxes; Kotlin/Native
  has no escape analysis, so every `Any?` position is a real allocation.** The open work,
  in order: (a) the nominal half, which is worth far more here than on the JVM; (b) the
  regex engine, (KIR.PERF.2); (c) a native arm in `kir-bench.sh`'s equivalence gate, which
  this round ran by hand — **(b) and (c) are DONE as of 2026-08-21**: the regex engine
  landed and is carried to native verbatim (**−22.5%**, 163.30 -> 126.55 us/parse, 7.26x
  -> **5.70x** Node, with mitt flat at 354.75 as the control), and `kir-bench.sh` now
  carries the native arm itself under `KIR_BENCH_NATIVE=1` — built by the same
  `kirNativeCompile` task, gated on the same `sink=` and timed in the same interleave.
  **(a), the nominal half, is what is left, and the native numbers are its case**:
  §6's per-primitive table says every dynamic position is a real allocation here, and
  the 36.75 us the regex engine removed leaves boxing as the whole remainder.
  Gradle wiring is DONE (owner-approved):
  `:xemantic-typescript-compiler-kir:kirNativeCompile`, with `scripts/kir-native.sh`
  a wrapper over it.
  Traps that cost the session and are recorded so they are not re-derived:
  `docs/perf/kir-backend-levers.md` § 6.

- [x] **(KIR.PERF.1) THE NOMINAL HALF — FIRST SLICE LANDED 2026-08-21, and `mitt` is
  **−10.7%** (61.00 -> **54.50 ns/emit**, 1.35x -> **1.54x FASTER** than Node), with ranges
  DISJOINT ([209..219] against [243..249]) and both Node arms flat. An object LITERAL whose
  property names are statically known now becomes a generated JVM class with one real field
  per property, EXTENDING `JsObject` — so the erasure is untouched, a shape instance IS a
  bag, and structural assignability never enters into it. That is what made the slice
  affordable where `docs/kir-structural-typing.md` §7's 12x price is for changing what an
  object type erases to. `smol-toml` is FLAT: its ten shapes fire, but `JsObject.get` had to
  become virtual and the parser builds its tables dynamically, so the gain on the scanner
  context and the loss on the tables cancel. **What is left is one further slice and one
  hard problem**: a local whose initializer IS a shape construction can keep the shape as
  its IR type and read the field DIRECTLY (the lowering already emits that for a declared
  class), and a shape arriving as a PARAMETER — which is how `smol-toml` passes its context
  — needs the whole-program inference §7 describes. `docs/perf/kir-backend-levers.md` §2b.
  ORIGINAL ENTRY, whose container half is closed by *four* refutations:** A per-owner leaf
  census of the toml JVM arm charges **47-52%** to the property bag — and, censused by
  OPERATION this round, that is **3,333 bag operations per parse** (2,555 `get`, 737 `set`
  of which **63.5% OVERWRITE**, 41 `has`, over 109 bags minted) at **~4.9 ns each**, which
  is exactly what a `String`-keyed `LinkedHashMap` probe on a cached hash costs. The row
  SURVIVES round 896's division test; its neighbour did not — `jsTruthyBooleanOrNull`
  reads 7.2-7.4% of samples over 298 calls per parse, i.e. **8.2 ns for
  `value != null && value`**, impossible by ~20x, so it was refused without a build.

  **THE READ SIDE IS UNIMODAL, WHICH IS WHAT DECIDED IT.** §2 measured the population as
  bimodal; that is true of ALLOCATION and false of READS, and a lookup cost is weighted by
  reads. **93.6% of every property read lands on a bag of exactly THREE keys** (99.1% on
  four or fewer; the 5-18 tail is 0.9%), and the names are the emitted string LITERALS,
  i.e. interned. That is the most favourable population an identity-compared scan could be
  handed — so the cleanest possible scan was built and MEASURED:

  | design | result |
  |---|---|
  | parallel arrays, promoted by SIZE | +21%, refused |
  | parallel arrays, promoted at the first UNDECLARED key | +31%, refused |
  | **identity scan, NO promotion, single-shaped `get`, everything else cold** | **no effect** |
  | **`LinkedHashMap` sized to the censused mean** | **no effect** |

  **THE LAST TWO ARE "NO EFFECT" AND NOT "A REGRESSION", AND THAT DISTINCTION COST A
  REPLICATION TO GET RIGHT**: the array bag read 738 ms against a baseline batch of 692,
  which looked like +6.6% — and a second baseline batch on the SAME BYTES read **735**.
  The baseline drifts 6.2% between batches, so the screen cannot resolve an effect this
  size, and round 858's law arrived on a fourth instrument. What the screen CAN say is
  that neither candidate is a win, which against a **−44%** premise is a refusal whatever
  the sign. `docs/perf/kir-backend-levers.md` §2a.

  **SO THE GUARDED SLOT HINT IS REFUSED TOO, WITHOUT BUILDING IT** — the design this entry
  used to propose. Its whole claim was that an O(1) indexed compare beats the scan the
  first refutation used; measured, that scan is LEVEL with a hash probe on the population
  that matters, so the hint is competing for the difference between level and level. Its
  cost is real — the shaped representation plus the declared member order reaching the
  lowering, which `CheckedFacts` does not expose. And landing that producer with no
  consumer would be round 887's shape exactly, so it is not a half-step worth taking
  either.

  **WHAT IS LEFT IS THE NOMINAL HALF, AND IT IS NOT A CONTAINER CHANGE**: a property read
  that is a `getfield` rather than any kind of lookup, worth **~16.3 us of a 33.65 us
  parse (~48%)**. **THE OBLIGATION TypeScript IMPOSES, unchanged:** assignability is
  STRUCTURAL, so a nominal encoding needs a witness per declared shape plus generated
  implementations, with a bag still reachable for `any`, for an index signature, and for a
  shape the closure cannot name. `docs/kir-structural-typing.md` §7 prices it at 12x the
  dynamic one. It is worth far more on Kotlin/Native, where §6's per-primitive table shows
  every `Any?` position is a real allocation — see (KIR.NATIVE.1)(a).

  **Measure it with `scripts/kir-bench.sh` and refuse it on the same standard as the other
  four: ranges disjoint, both Node arms flat.** The screening harness for a runtime-only
  candidate is five processes of the compiled program with the classes held fixed; its
  band is ~±5%, which is why the +2.3% arm is reported as "not a win" rather than as a
  regression.

- [x] **(KIR.EMIT.1) LANDED 2026-08-21 — `rewriteRelativeImportExtensions` is implemented
  in the emit, at all four specifier positions (ESM import/export declarations via a
  post-pass over the FINAL statement list, every `require` this transformer builds via
  `normalizeModuleSpecifier`, and a dynamic `import()` in the CallExpression arm). The
  post-pass position is load-bearing: the specifier TEXT is also how the transformer ASKS
  the checker about the target module, so rewriting earlier asks about a `.js` file the
  program does not contain. mitt's EXTENSIONLESS `./mitt` stays a benchmark expedient —
  tsgo leaves it alone too, so rewriting it would be a divergence, not a fix.
  `RewriteRelativeImportExtensionsTest`, 10 pins. ORIGINAL ENTRY: OUR ESM OUTPUT IS NOT RUNNABLE ON NODE AS EMITTED — a relative
  specifier keeps the extension it was written with.** tsgo 7.0.2 rewrites `./parse.ts` ->
  `./parse.js` under `rewriteRelativeImportExtensions` and we emit `'./parse.ts'` verbatim;
  Node ESM resolves a specifier LITERALLY and refuses both that and mitt's extensionless
  `'./mitt'`. `scripts/kir-bench.sh` post-processes the emit to run the arm at all, which is
  a benchmark expedient and NOT a fix. **Invisible to every gate we own** — the corpus pins
  emitted BYTES against tsc baselines, and no baseline asks whether Node can load the result.

- [x] **(KIR.EMIT.2) LANDED 2026-08-21.** The decision belongs to the LOWERING, which
  still holds the TypeScript type: `asString` — the one funnel for `+` and for a template
  span — asks whether every nullish member the operand's type admits is `undefined`, and
  picks `jsToStringNullAsUndefined` if so. A type admitting BOTH, and `any`, keep `"null"`,
  so the wrong answer is narrowed to the shapes the §3.1 collapse cannot separate at all
  rather than swapped for the opposite wrong answer. `KirNullishStringTest`, 5 pins.
  ORIGINAL ENTRY: `undefined` RENDERS AS `"null"` IN A STRING CONCATENATION.**
  `a + '|' + b` with `b` undefined prints `x|null` where JavaScript prints `x|undefined` —
  a `string | undefined` erases to `String?` and Kotlin's own `plus` renders the null. Found
  by `KirDynamicCallArityTest`, which was retargeted to avoid pinning it; the fix belongs in
  the concatenation lowering, not in the call path.

- [x] **(BENCH.1) THE THIRD JS ARM — ANSWERED 2026-08-21: the arm lands ON tsgo's (1.01x /
  1.02x), so the front end is performance-neutral and the whole 2.5x is the BACKEND. The
  harness is `scripts/kir-bench.sh` and the arm is now the standing control.** ORIGINAL ENTRY:
  THE THIRD JS ARM — OUR OWN EMITTED JavaScript, ON THE SAME NODE, AS THE CONTROL
  THAT SEPARATES "OUR COMPILER" FROM "OUR BACKEND".** The 2026-08-21 KIR runtime benchmark measured
  two arms — tsgo -> JS -> Node against xtsc `-kir` -> JVM bytecode -> java — and they disagree by
  library and by SIGN: **mitt 86.0 -> 66.5 ns/emit (JVM 1.29x FASTER), smol-toml 22.6 -> 56.4
  us/parse (JVM 2.50x SLOWER)**, medians of 5 interleaved processes, both arms producing identical
  `sink` accumulators and byte-identical acceptance output. **Two candidate causes are tangled in
  that 2.50x and no arm separates them**: the code our FRONT END produces, and the KIR backend's
  object model. The third arm holds the runtime fixed (Node) and varies only the compiler —
  `-core`'s Transformer/Emitter to JavaScript text, against tsgo's JavaScript, same sources, same
  drivers.

  **What each outcome MEANS, stated before the run (a prediction is what makes a refutation
  legible).** Arm 3 landing on arm 1 says the front end is performance-neutral and the whole 2.50x
  belongs to the backend, confirming the leaf profile by a second instrument rather than by
  inference. Arm 3 landing SLOWER than arm 1 is a genuinely new finding about our JS emitter and
  invisible to every gate we own — **the corpus pins emitted BYTES against tsc's baselines, and byte
  parity says nothing about how fast the resulting program runs on a modern JIT.**

  **The harness exists and is reusable** — drivers, projects, timing shape and the interleaved
  5-process protocol are in the 2026-08-21 session note; the only new piece is emitting the two
  bench projects with `-core` instead of tsgo. **Two traps it must carry.** (i) Node ESM needs a
  real extension: tsgo rewrites `./parse.ts` -> `./parse.js` under
  `rewriteRelativeImportExtensions` and leaves mitt's extensionless `./mitt` alone, so whatever our
  emitter does with a specifier has to be checked rather than assumed. (ii) **An arm that fails to
  RUN must fail loudly** — a JS file that throws on import prints nothing and a wall-clock harness
  reads that as a fast arm; assert the acceptance output byte-for-byte in every arm before timing
  anything, which is what caught nothing this round only because it was done first.

- [x] **(API.1) `Project`: open, diagnostics, in-memory edits — LANDED, round 909.** New module
  `xemantic-typescript-compiler-project` (jvm(), `explicitApi()`, `api(project(":…-core"))`);
  `Project.open` / `configPath` / `files` / `diagnostics()` / `diagnostics(file)` / `updateFile` /
  `deleteFile` / `close()` + `internal OverlayVfs`; 30 pins. **A query on a dirty project is a FULL
  rebuild and that is the compiler's property** — `ProjectCompiler.Result` retains no AST/binder/
  checker — so warmth comes from the CONTENT-keyed `CrawlParseCache` alone. Do not build "incremental"
  on it; the seam does not exist yet.

- [x] **(API.2) Position→node lookup — LANDED, round 910**, in two halves: a public `LineMap` /
  `TextPosition` + `Project.positionAt` / `offsetAt` (which read through the overlay and deliberately do
  NOT build, so a host can convert coordinates on a dirty project for free), and
  `Project.nodeInfoAt` (public, value-typed) over an `internal nodeAt` / `SourceIndex`. 53 pins.
  **The queue entry's "cheap and self-contained" was half wrong**: see the two span findings in the
  round-910 note and in CLAUDE.md — `Node.end` is the end of the FOLLOWING token, so `[pos,end)` is not
  a containment test, and the fix is a token snap-back rather than the sibling arithmetic this entry
  originally implied. **Unblocked by ONE word in core**: `computeParserFlags` is now public, because
  INV.1(e) ("the parse a crawl produces is provably the parse the core would produce") is exactly the
  guarantee an out-of-core parse needs, and duplicating it would be drift no test in the consuming
  module could see. Original entry, for the record:

  <details><summary>original (API.2) text</summary>

  **Position→node lookup, the unblocker EVERY editor feature needs.** There is no
  `getTouchingToken` equivalent anywhere in core: `computeLineStarts` is `private` to `Parser.kt:10119`
  and `positionToLineCharacter` is a private top-level fun (`TypeScriptCompiler.kt:6073`), both
  offset→line only, i.e. the direction diagnostics need and not the one an editor does. Needs: a
  public line/offset map, and a node-at-offset walk (`forEachChild`-driven, narrowest-enclosing, with
  the token-boundary rule tsc's `getTouchingPropertyName` uses). **Cheap and self-contained — it needs
  no checker state**, which is why it comes before quick-info.

  </details>

- [x] **(API.3a) QUICK INFO — LANDED, round 911, AND THE DESIGN BELOW IS NOW CONFIRMED BY MEASUREMENT
  RATHER THAN BY READING.** Captured-during-walk vs asked-post-hoc on ONE `Checker` instance: top-level
  annotated `const` **`string` / `string`** (the honest control — post-hoc is not wrong about
  everything), body local shadowing a global **`number` / `string`**, `typeof`-narrowed parameter
  **`string` / `any`**, parameter at its use **`number` / `any`**, arrow-body parameter **`string` /
  `any`**, class-method parameter **`number` / `any`**. **Five of six differ, and the prediction in this
  entry was wrong in the WORSE direction**: the narrowed case does not degrade to `string | number`
  (narrowing merely lost), it degrades to **`any`** — nothing durable binds a parameter at all — which is
  the one answer that is SILENT at every use site, so a post-hoc hover would have looked plausible and
  meant nothing. **THE HOOK'S REAL LESSON, now in CLAUDE.md: a per-node hook on the spine sees NONE of
  the checking ambient**, because the anchors install-and-restore it per dispatch — the position's scope
  is `ctaFrames.last()`, and the capture must reproduce `ctaM3StmtAnchorCore`'s prologue plus
  `withCtaFrameLocals(frame)`. Without that it answered `bodyLocal=string`, `narrowed=any`,
  `parameter=any`. Threaded as an explicit parameter on the `recheckOnly` model (nothing on
  `CompilerOptions`, no process-global mode); node identity is the RAW `(pos, end)` pair, so round 910's
  span semantics stay entirely in `-project`'s `SourceIndex`. **OFF IS FREE and gated as such**:
  `cost_gate.py` +0.00% on all 20 counters, the production cost being one null-valued field read and a
  predicted branch per node, with the NODE as the argument (round 900). Public surface stays value-typed:
  `QuickInfo` + `Project.quickInfoAt`.

- [x] **(API.3b) Go-to-definition — LANDED, round 913.** The entry read: *"the capture mechanism now
  exists and this is the same shape one field over: record the resolved `Symbol`'s `declarations`
  (each a pos/end-bearing node) at the captured position instead of its type, and answer
  `DefinitionLocation(fileName, start, length)`. **Read (API.3a)'s ambient lesson first** — a symbol
  resolved without `withCtaFrameLocals` is the same wrong answer one indirection along."* **The
  premise is WRONG in its most useful sentence, and the correction is the round's product: the
  ambient lesson does NOT transfer, because a definition's walk-scoped input is not the ambient at
  all.** `withCtaFrameLocals` restores `currentLocalTypes`, which holds TYPES and no symbols, so it
  cannot answer "what does this name refer to" for anything. What does is `spineCurrentScope` — the
  INV.2(c) lexical chain — and the spine **maintains that per NODE**, pushing it BEFORE a node's own
  enter handlers, so it is already correct at an arbitrary node and needs no reconstruction. What
  (API.3a) and (API.3b) genuinely share is only that both inputs are gone once the walk is over
  (`spineScopeClear` nulls the chain per file), which is what still makes capture mandatory:
  post-hoc, a body local resolves to a same-named FILE-LEVEL const and a parameter to nothing at all.
  Landed: `CapturedDefinition`/`CapturedDeclaration` in the core (recorded by the SAME hook as the
  type — one request, two facts), `DefinitionLocation` + `Project.definitionsAt` in `-project`,
  import-alias hop through `resolveImportedSymbolGeneral`, and an exact NAME span computed in the
  core by a forward token scan of the declaring file's own text. **19 pins, four-arm ablation, all
  gates green.**

- [x] **(API.3c) Batch a whole file's spans into ONE build.** The core `TypeCaptureRequest` already
  takes a SET of spans and `Project.quickInfoAt` deliberately does not cache its build (a capture build
  types nodes the checker had no reason to type, so its diagnostics are not reusable — pinned). So
  "semantic info for file X" is already one compile away from being one compile; exposing it turns
  hover-per-keystroke from N builds into 1. **This is the item that makes the API practical for an
  editor** and it needs no new mechanism. **LANDED round 914** —
  `Project.semanticsAt(fileName, offsets)` (the primitive) and `Project.fileSemantics(fileName)` (the
  sweep, expressed on it), answering `SemanticInfo(start, end, kind, quickInfo, definitions)`: ONE
  build for any span count, both answers per span, distinct spans sorted `(start, end)`. Measured
  **1 compile / 100 ms against 34 compiles / 3,373 ms and 68 compiles / 6,209 ms** on a
  34-identifier fixture. **THE PREMISE'S ONE ERROR, and it is the round's technical product: "it
  needs no new mechanism" is true of the CAPTURE and false of its KEY.** `TypeCaptureRequest`'s
  packed `(start, end)` key was left un-finalized with a note saying to finalize it "should a caller
  ever request spans in bulk" — and bulk is exactly what this item is: `Long.hashCode` folds
  `(a shl 32) or b` onto `a xor b`, and a node's `end` is its `start` plus a token or two, so a whole
  file's spans collapse onto a few dozen hashes (measured: **>400 spans onto <40 hashes**, round
  889's defect verbatim). It now goes through `packIdPair`, pinned by a measuring test with a raw-pack
  negative control. **26 pins, all gates green.**

  <details><summary>the design decision, recorded round 910 and confirmed round 911</summary>

  **(API.3) Quick info + go-to-definition — THE DESIGN IS NOW DECIDED BY EVIDENCE: *POSITION-DIRECTED
  CAPTURE*, NOT A POST-HOC QUERY, BECAUSE THE CHECKER'S ANSWER TO "WHAT IS THE TYPE HERE" IS A FUNCTION
  OF WALK-SCOPED AMBIENT STATE AND A POST-HOC CALL WOULD BE SILENTLY WRONG FOR EXACTLY THE INTERESTING
  CASES (round 909, by reading `getTypeOfIdentifier`).** `Checker` does all its work in `init`, so the
  instance still HOLDS its tables afterwards and "hand the Checker back and call `getTypeOfExpression`"
  looks free. It is not: `getTypeOfIdentifier` (`Checker.kt:108777`) consults, IN ORDER,
  `currentLocalTypes` (its own comment: *"populated during TS2322 checking walk"*),
  `currentParamBindingNames`, `currentCheckFileName` -> `fileLocalTypeMaps`, `currentFileLocals`, the
  inference-namespace chain, and only THEN the node-keyed `lookupPerFileForNode`. At rest
  `currentLocalTypes` is an empty `HashMap` (`:636`) and the two `current*` file fields are null, so a
  post-hoc query **skips the first five reads** and falls through to globals. **For a
  FUNCTION-BODY LOCAL that does not merely lose narrowing — it can resolve to an unrelated same-named
  global**, which is the `useCaseSensitiveFileNames` failure documented in that very function
  (a destructured param resolving to another file's function, FP TS2345 x9). Two of the ambient reads
  are FILE-scoped and cheaply re-installable from outside; `currentLocalTypes` is
  STATEMENT-POSITION-scoped, built first-wins as the walk proceeds and deliberately leaking across
  blocks in statement order — **it cannot be reconstructed for an arbitrary position without
  re-walking to that position, which is the whole argument for capture.** So: hand the compiler the
  position(s) BEFORE the build and capture type+symbol at those nodes while the real ambient is
  installed. Correct by construction, and it **batches** — one build can capture every identifier in a
  file, so "semantic info for file X" is one compile rather than N. Cost, stated: a query is a compile
  (~5.2 s warm on tsc's own sources, far less on a normal project, repeats warm through
  `CrawlParseCache`); too slow per keystroke, fine for hover-on-demand.
  **IMPLEMENTATION CONSTRAINT A NEW AGENT WILL OTHERWISE LOSE A ROUND TO: a capture handler is a spine
  handler, so it must extend `SpineDispatch.enterClosure` or round 888's `spineEnterMask` means it is
  NEVER CALLED**, and `python3 scripts/spine_closure_audit.py` must be run after touching any
  `spine*EnterNode`. **PUBLIC SURFACE STAYS VALUE-TYPED** (`QuickInfo(kind, displayString, span,
  docs)`, `DefinitionLocation(fileName, start, length)`) — no AST, no `Symbol`, no `Type`.
  **THE FIRST STEP IS STILL A MEASUREMENT, NOT CODE:** pin the above by asking a post-init `Checker`
  for the type at three positions — a top-level `const`, a function-body local, and a guard-narrowed
  reference — and record which answer wrong. That experiment becomes the regression pin for the capture
  path.

  **THE STARTING FACTS** (unchanged, and they are what make capture cheap): everything an editor needs
  is `private` in `Checker.kt` and nothing hands back live state — `getTypeOfExpression` (`:108501`),
  `getTypeOfSymbol` (`:106667`) and `typeToString` (`:120389`) are all `private fun`, and
  `BinderResult.nodeToSymbol` is public but no `BinderResult` ever escapes a compile. Capture needs only
  an `internal` seam plus a handler; it publishes none of them.

  **THE THREE ALTERNATIVES, AND WHY THEY ARE NOT THE NEXT STEP.** (a) **post-hoc query-shaped** —
  narrow `Checker` entry points answering one question after `init`: **superseded by the finding above**,
  because it is silently wrong for body locals and narrowed references (the ONE hover case a user
  notices is `let`/`const` inside a function). Directed capture is (a)'s cheapness without its defect.
  (b) **snapshot-shaped** — return a `ProgramSnapshot` holding ASTs + binder output + the live
  `Checker`: **REJECTED for now, and the reason is this repo's own history** — it freezes as versioned
  API exactly the structures the perf arc keeps rewriting (rounds 889-908 changed packed-key hashing,
  container types and memo layouts, and moved maps onto `LongKeyMap`/`IntKeyMap`, which deliberately
  have NO iterator). Publishing them constrains the work that just delivered -10.5%. It also does not
  even solve the ambient problem: a snapshot hands back the same post-hoc trap. (c) **the full
  inversion** — a lazy, re-entrant checker (`docs/ARCHITECTURE-RETHINK.md:850` names it as the LSP
  prerequisite): **the right end state and the wrong next step**, the largest job in the repo. Do not
  let hover gate on it — and do not let it be "unblocked" by an API that has already published the
  internals it must change.

  </details>

- [x] **(BUG.1) The compiler disagrees with itself about a lone `\r` — DONE, round 915.** The
  convention is now stated ONCE, as `lineBreakWidthAt` in a new `LineStarts.kt`, and every
  offset→line conversion in the compiler goes through it. The sweep the item asked for found **five**
  such converters where the entry named two, four of them wrong: `Checker.lineStartsFor`, its inverse
  `Checker.posOfLineCol`, `TypeScriptCompiler.positionToLineCharacter` (plus its inline TS2688 twin),
  the `Transformer`'s JSX dev-runtime coordinates (EMITTED output, not a diagnostic), and
  `CompilerOptions.computeLineAndColumn` — which implemented a THIRD convention, `\r` as zero-width.
  `-project`'s `LineMap` was already correct and stays a reimplementation, pinned by a differential.
  **The finding that outlives the fix**: `parseMultiFileSource` — the `// @directive` splitter behind
  the whole generated corpus — begins by replacing every `\r\n` and `\r` with `\n`, so the corpus was
  not merely unlucky, it was structurally incapable of carrying a `\r` to the Parser; only the
  project/`Vfs` path can, which is the path the `(API.*)` arc sits on. `LineTerminatorConsistencyTest`
  (core) + `ProjectPositionTest`'s lone-`\r` differential are the gate; 5 pins redden under ablation.

- [x] **(API.3d) Member go-to-definition — LANDED, round 916.** The gap round 913 recorded
  deliberately: *"a scope lookup of a member name finds whatever unrelated binding happens to share
  the spelling, and a confidently wrong navigation target is worse than none. Member definitions need
  the receiver's type resolved and its property symbol found, which is a separate mechanism and not
  this one."* It is now that separate mechanism, in the SAME capture hook and with no new public type:
  `typeCaptureMemberSymbols` resolves a member name through its RECEIVER and hands the resulting
  symbols' declarations to the existing `CapturedDeclaration` path, so a member answer is simply a
  non-empty `definitions` list where one used to be empty. **ANSWERS**: `o.p` / `o.m()` / `this.p` /
  `super.p` / `C.staticP`; a member of an IMPORTED interface (in the declaring file); an INHERITED
  member (the BASE's declaration); a MERGED member (one location per contributing declaration); a
  member of a UNION or INTERSECTION receiver (one per constituent, in constituent order); `N.x` and
  the qualified TYPE `N.T` for a namespace, module alias or enum; a LIB member (in `lib.*.d.ts`, the
  policy `definitionsAt` already documented for a free name). **REFUSED, each with a reason in the
  KDoc**: an element access (`o["p"]` — the argument is a literal, and only identifiers are offered a
  definition); an object-literal key being declared (`{ p: v }` — the useful target is the CONTEXTUAL
  type's property, a third mechanism); a member's own declaration name (it already IS the
  declaration); a chained namespace segment (`A.B.x`); an unresolvable member (silence, never the
  nearest same-named anything). **THE ROUND'S TWO FINDINGS**: the ambient the hook already installs is
  exactly enough — `this` needed `currentClassForThis`, which round 911's install already restores and
  which is deliberately NULL in a static member — and going through the compiler's own
  `resolveStructuredTypeMembers` rather than a hand-rolled table read is what makes the inherited and
  generic cases right for free. **13 pins, five-arm ablation each reddening a DISTINCT set, all gates
  green.**

- [x] **(API.4a) The completion ANCHOR + MEMBER completions — LANDED, round 917.** (API.4) was
  decomposed rather than taken whole; this is the standalone half that needed the genuinely new
  mechanism. **THE ANCHOR** (`SourceIndex.completionAnchorAt` / `CompletionAnchor`, `-project`, where
  round 910's caret already lives) answers a TOKEN-level question, because a completion request has no
  node at the caret by construction: it reports a `CompletionKind` (MEMBER / FREE_NAME / NONE), the
  typed PREFIX, and a replacement span covering the whole word rather than only the prefix. **The
  recovery rule for an incomplete `o.` is that there is nothing to recover**: this parser's `Dot ->`
  arm always builds a `PropertyAccessExpression`, synthesizing a zero-width `Identifier("")` and
  reporting TS1003, so the receiver is a real node at end of file, before a `}` and across a newline
  alike — the anchor descends to the character BEFORE the dot and walks back out to the access whose
  own dot that is (`realEnd(expression) <= dotStart < name.pos`, which at most one node in a path can
  satisfy). A `.` the parse did not turn into an access answers empty rather than guessing a receiver
  from bracket-balanced text. **THE MEMBERS** ride (API.3d)'s resolution one question wider —
  `TypeCaptureRequest.memberSpans` (a SECOND span list, so `fileSemantics` never enumerates) ->
  `CapturedMembers` / `CapturedMember(name, kind, typeText, optional, readonly, accessibility)`.
  **`Project.completionsAt(fileName, offset): CompletionList`.** Free names are an explicit
  `CompletionRefusal.FREE_NAMES_NOT_IMPLEMENTED`, never a silent empty list.

- [x] **(API.4b) FREE-NAME completions — LANDED, round 918; KEYWORDS REFUSED with a reason.** It did
  land by deleting one refusal: `CompletionRefusal.FREE_NAMES_NOT_IMPLEMENTED` is gone and no
  signature moved. **THE MECHANISM** is a THIRD span list (`TypeCaptureRequest.scopeSpans` ->
  `CapturedScope` / `CapturedName(name, kind)`), unioned into `keysByFile` exactly as `memberSpans` is,
  and it is the ONE capture that also admits a NON-`Expression` node — a free caret is anchored at the
  innermost node ENCLOSING it, routinely a Block or the source file. **THE ENUMERATION IS
  `spineScopeLookup`'s OWN WALK, RUN TO EXHAUSTION** — every level's `symbols` then its `existing`,
  innermost first, first sighting wins — then the merged/lib GLOBALS filtered through
  `globalsForFile` (INV.3(c)). That identity is the correctness argument: *a name the list offers is a
  name `definitionsAt` will resolve, and a name it hides is hidden because something nearer binds the
  spelling.* **TWO DIVERGENCES FROM THE ENTRY AS WRITTEN, both deliberate and both ablated.** (i)
  `LexicalScope.existing` IS read: round 748's `symbols`-only rule is about a RESOLVER whose soundness
  is that it cannot change how an existing name resolves, and an enumeration reading `symbols` only
  offers no file-level declaration and no import at all (arm A5, 8 red). (ii) `lexLevelHasName`'s
  UNTRUSTED-level skip is NOT applied: it belongs to a chain with a second, export-filtered threaded
  population to fall back on, and this chain has none — applying it answers nothing inside every
  namespace body (arm A3, 1 red, uniquely its own). **A FREE-NAME ITEM CARRIES NO `typeText`**, decided
  on measurement: at a caret in a real file of the compiler profile the list is **1,628 items**, the
  enumeration itself **0.39-0.64 ms**, adding a type to every item **+2.6-14.3 ms** — and **618 of
  1,629 (37.9%) would render `any`/`error`**, because a free name may name a TYPE. **KEYWORDS ARE
  REFUSED**: a useful list is context-sensitive and the anchor is token-level, so an unconditional one
  offers items that do not compile — the thing the member half already refuses to do. **22 pins**
  (18 `-project`, 4 core `ScopeCaptureMeasurementTest`), **seven-arm ablation, six DISTINCT sets**;
  A7 (drop the writable-name filter) read **0 red** and is recorded in-file as an UNDISCRIMINATED
  guard rather than claimed. All gates green.

  **WHAT IS ALREADY YOURS, do not re-derive it.** The anchor: `completionAnchorAt` already returns
  `FREE_NAME` with the correct prefix and replacement span at every free position, and already answers
  `NONE` inside strings, templates, comments and numeric literals — `CompletionAnchorTest` pins all of
  it, including the caret at the very end of the file. The public value types, the refusal enum, the
  `memberSpans` channel and the "off is free" wiring. The build-free short-circuit (a refused kind does
  not compile) — you will be REMOVING that for FREE_NAME, which makes free-name completion a compile
  where member completion already is one.

  **WHAT MUST BE BUILT, and the one structural fact that decides its shape.** The scope chain is
  **CLEARED PER FILE**: `spineCurrentScope` is nulled by the spine's per-file teardown, which is what
  `DefinitionCaptureMeasurementTest` measures — so the enumeration must happen DURING the walk, at the
  requested position, exactly as `typeCaptureRecordDefinition` does. There is no post-hoc option. The
  natural shape is a third span list (`scopeSpans`) beside `memberSpans`, keyed the same way, recording
  a `CapturedScope` at the node the anchor names — and the anchor must therefore hand in a NODE for a
  free position too, which today it does not (it returns `receiver = null`). Deciding WHICH node a free
  caret names is the first sub-problem: the caret is between nodes, so the honest candidate is the
  nearest enclosing statement or block, and its scope is the scope in force for the position.

  **THE SIZE PROBLEM IS REAL AND IS MEASURED.** CLAUDE.md round 902: `LexicalScope.symbols` holds 1.51
  symbols averaged over SCOPES but **290.94 averaged over a real PROBE**, because the ascent walks
  outwards and 35.5% of probes land on levels holding a mean of **815**. A completion list is that
  whole ascent, flattened — so it is hundreds of items on a real program, every one of which costs a
  `getTypeOfSymbol` + `typeToString` if the item is to carry a type the way a member item does.
  **Decide whether a free-name item carries `typeText` at all before building it**; making it optional
  (null for a free name, present for a member) is a strictly additive change to `CompletionItem` and
  is the cheap escape.

  **SHADOWING AND DEDUP.** Innermost wins: a name bound at two levels must appear ONCE, as the inner
  binding, which is the opposite of the member walk's merge (a member declared twice is one item
  merged from both). `lexLevelHasName`'s ascent is the traversal to copy, with its two live rules —
  `LexicalScope.symbols` only, never `existing` (round 748), and the untrusted Module/Enum levels are
  SKIPPED (INV.4(c)(ii)). Keywords are a separate, purely syntactic list keyed on the anchor's
  position and want their own `CompletionItem.kind`.

  **THE PIN THAT DISCRIMINATES** is (API.4a)'s discriminator inverted: a caret inside a function body
  whose local shadows a same-named binding in ANOTHER FILE must offer the local ONCE and must not
  offer the other file's; and the member pins must stay green, i.e. a free-name enumeration must not
  leak into a member position — the failure round 913 refused and round 916's arm A2 catches.

- [x] **(BUG.2) The `-project` token index de-synchronised at the first `${…}` — LANDED, round 919.**
  Found by (API.5)'s cost measurement, not by a test. `SourceIndex.scanTokens` ran a context-free
  `Scanner.scan()` loop and the parser re-scans the `}` that closes a template substitution
  (`reScanTemplateToken`); without that, the `}` reads as a CloseBrace, whatever follows reads as
  operators, and the CLOSING BACKTICK opens a fresh `NoSubstitutionTemplateLiteral` that runs to the
  next backtick **anywhere in the file**. Unlike a SPLIT (which only adds ends and is why the slash and
  greater-than re-scans are still deliberately absent) a MERGE de-synchronises the stream **for the
  rest of the file**, so every later node's `realEnd` snaps back, `pathAt` cannot descend into it, and
  `nodeInfoAt` / `quickInfoAt` / `definitionsAt` / `completionsAt` all answer about a huge enclosing
  node. Measured on tsc's own `checker.ts`: **50,684 tokens for 3,151,772 characters, the longest
  62,089**, and a caret on a top-level function's name resolving to the whole file's `Block`. The fix
  tracks substitution nesting exactly as `Parser` does (a `TemplateHead` pushes, braces inside are
  counted, the closing `}` is re-scanned into a middle or a tail). `TemplateTokenSyncTest`, 5 pins,
  arm A6.

- [x] **(API.5) FIND REFERENCES + DOCUMENT HIGHLIGHTS — LANDED, round 919.** `ReferenceLocation(
  fileName, start, end, isDeclaration)`; **`Project.referencesAt(fileName, offset)`** (the program)
  and **`Project.documentHighlightsAt(fileName, offset)`** (one file). **ZERO core changes** — the
  whole feature is (API.3c)'s batch turned inside out, above the compiler. **THE IDENTITY QUESTION,
  which the brief said to verify rather than inherit, VERIFIED AND ANSWERED: a DECLARATION-LOCATION SET
  is a sound proxy for "the same symbol", but the relation is INTERSECTION, not equality.** Measured on
  a probe fixture before any code was written: the import alias, its `import { }` clause, every use and
  the export are ONE set (the capture's alias hop already unifies them); two merged `interface I`
  blocks give every occurrence the SAME two-declaration set (equality would not split them); three
  same-spelled `collide` bindings over two files give three DISJOINT sets. Equality FAILS on one shape
  only, and it is a real one: a member of a UNION receiver resolves to one declaration per constituent,
  so `u.p` and a single-constituent `a.p` would be different groups. **THE ONE HOLE, stated and pinned
  rather than papered over:** a MEMBER's own declaration name is bound by no scope and has no receiver,
  so the capture resolves it to nothing (which is exactly why `definitionsAt` answers empty there). It
  is recovered from the sweep's own evidence — an occurrence that resolved TO that span proves the
  caret is a declaration — which leaves exactly one truthful gap: **a member declared and never used
  answers EMPTY rather than a list of one** (tsc answers one). Free names are unaffected. **REFUSED
  with reasons:** read-vs-write (`[x] = pair` / `({x} = o)` / `for (x of xs)` are writes under an array
  literal, an object literal and a `for` head, so a rule built from `x = 1` and `x++` reports them as
  READS and a host cannot tell a complete answer from an incomplete one — the same grammar-position
  mechanism keywords are refused for); lib files are not swept for uses; element access. **MEASURED on
  the compiler profile** (78 files, 9,977,097 chars, **381,670 identifiers**, real libs, warm): plain
  rebuild 5.5-5.9 s; `documentHighlightsAt` **6.0-7.2 s** (1 build); `referencesAt` **8.3-9.9 s** clean
  (1 build) and **13.0-13.5 s** dirty (2 — `files`' build first); the sweep is 2.5-4 s on top of the
  rebuild WHATEVER the caret (168 hits in 1 file and **9,827 hits across 49 files** for `SyntaxKind`
  cost the same); **peak heap ~1.9 GB, so 512 MB is not enough**. Key spread needed nothing: both
  packers were already finalized (round 914's `packIdPair`). **19 pins**, eight-arm ablation, **every
  arm a DISTINCT set**. `docs/language-service.md` § 10b.

- [x] **(GATE.2) A REAL-SOURCE INVARIANT GATE for the language-service position APIs — LANDED, round
  920, and it found FOUR MORE DEFECTS on its first run.** (BUG.2) was live for nine rounds behind a
  green suite because **a hand-written fixture for a lexical API does not contain what real source
  contains**; round 919 fixed the template case and did not build the instrument. This is it.
  **`TokenIndexInvariants`** (commonTest) asserts ten rules true of ANY correct implementation — the
  tokens partition the text and the scan reaches EOF; every gap holds only trivia; a string literal
  never crosses a line break; a non-literal token is short; **every identifier the PARSER found starts
  a token of exactly its length** and `realEndOf` answers that end; a descent to an identifier's own
  position reaches it; a path strictly nests; and offset↔coordinate round-trips against an
  INDEPENDENT restatement of round 915's terminator rule. **The parse is the oracle** — it is the
  context-sensitive lexer this index approximates, so a merge is exactly "an identifier with no token
  starting at it". **THREE CORPORA, and the choice is the point.** Hermetic and permanent
  (`TokenIndexGateTest`): an adversarial shape corpus plus **the real `lib.*.d.ts` sources**
  (`RealLibFiles.files`, 2.39 MB of TypeScript nobody wrote for this test, already embedded, no
  vendored tree and no licensing question). Local-only: `build/bench/tsc-project-*` via
  `scripts/round920-token-gate.sh` + `RealSourceTokenGateMain`, which **REFUSES (exit 2) rather than
  skips** — a gate reading a local artifact that passes quietly where the artifact is absent is round
  853's and round 873's failure mode. **FOUND, all four real, all fixed:** (A) **a backtick inside a
  regular expression** (tsc's own `` /\r\n|[\\`…]/g ``) opened a template literal running to the
  next backtick anywhere in the file — a **25,761-character token** that swallowed the twelve
  identifiers after it, i.e. (BUG.2) in its second costume; (B) a **parenthesis-less arrow parameter**,
  an **index-signature parameter** and a **`catch` variable** were built with the default `[0, 0)`
  span, so no descent could enter them — **328 sites in tsc's 78 sources**, the API's single most
  common wrong answer; (C) `declare global`'s **`global`** name carried an EXACT end where every other
  node carries the following token's; (D) **JSX tag names** did the same, and (E) the synthetic
  **`new`** name of a construct signature was at `[0, 0)`. **THE FIX FOR (A) IS THE MECHANISM WORTH
  KEEPING: ask the parse.** A `RegularExpressionLiteralNode` and a `JsxText` each carry their own RAW
  text, so `pos + text.length` is exact; `SourceIndex` collects them and emits them verbatim, resuming
  the scanner past each. The undecidable "does this `/` divide or quote" is therefore never asked —
  whatever the parser decided, the index reproduces, so the two cannot disagree. **AFTER: 1,327 files,
  101,287,620 characters, 11,299,274 tokens, 3,936,158 identifiers, ZERO violations**, against 50 of
  78 files failing on the compiler profile alone before. **COST**: the oracle is +32 ms on 9,977,097
  chars = **+9.9% of `SourceIndex.of`** (358 vs 326 ms), paid only by a host's position query;
  `cost_gate.py` **+0.00% on all 20 counters** because nothing in the compile path builds an index.
  **POSITIVE CONTROL**: `SourceIndex.of(…, useParseAsLexerOracle = false)` is the in-binary OFF arm —
  the shape `--spineMaskOff` has — and the gate's own control asserts it reddens.

- [x] **(API.7) THE SYNTACTIC-ROLE MECHANISM + THREE OF THE FIVE STANDING REFUSALS — LANDED, round
  922.** The backlog was promoted as ONE item on round 921's premise that all five wanted the same
  missing "where is this caret in the grammar" mechanism. **Three did and two did not, which is the
  round's product.** BUILT: `SyntaxRoles` (`-project`), a PULL-BASED parent-chain ascent —
  `referenceUse(node)` for a node's role, `grammarPositionOf(path)` / `keywordsFor(path)` for a
  caret's — plus a sibling ascent in `Checker.kt` for the half of accessibility that needs symbols and
  heritage (the home is decided PER QUESTION, not forced). Pull rather than push on round 875's
  measurement (a maintained status is 11.1x the work); identity comparisons throughout, because AST
  nodes are `data class`es (round 471). **CASHED: (a) member-completion ACCESSIBILITY** — `private`
  only inside the declaring class, `protected` there or in a derived one, statics alike, the ascent
  reaching out of a nested arrow and the heritage walk following an IMPORT; biased PROVE-TO-HIDE, so
  every unknown leaves the member offered, which is the only answer to round 917's stated objection.
  **(b) KEYWORD completions**, bounded explicitly to STATEMENT / EXPRESSION / TYPE positions with
  `await`, `yield`, `super`, `return`, `break`, `continue` and the module-level declaration starters
  each gated, and every continuation keyword refused outright. **(c) READ-vs-WRITE**
  (`ReferenceLocation.use`), with the write set stated completely and `UNCLASSIFIED` as a fourth state
  rather than a default. **STILL REFUSED, with the reason CORRECTED**: an element access (`o["p"]`)
  and a contextual object-literal key (`{ p: v }`) were never blocked on a grammar position at all —
  recognising either shape is one test on the node's parent — and what each lacks is SEMANTIC (a
  capture channel plus member-lookup-by-text; a contextual type, which is walk-scoped and absent
  outright in a ternary branch). **TWO EXISTING ANSWERS CHANGED** and their round-917 / round-918 pins
  were updated in place: member completions no longer include inaccessible members, and a free-name
  list now carries keyword items (`kind = "Keyword"`). **+45 pins** (32 parse-only), **fourteen-arm
  ablation, all fourteen a DISTINCT set**, all gates green. `docs/language-service.md` §§ 10a, 10b.

- [x] **(API.13) § 14 AUDITED BY EXECUTION AND PINNED — LANDED, round 930; four of its
  claims were false and one of them was a DEFECT.** `docs/language-service.md` § 14 is the
  page a host author and a next agent read instead of twenty session notes, and it was
  three rounds old with a fixed defect still listed as open. Every claim in it was re-run
  — a fixture through the API, `tsc --lsp -stdio` as the oracle where the claim is parity,
  the cost table re-taken on the compiler profile — and the half that a test can defend is
  now `LanguageServiceStateTest` (+15 pins). **THE ONE DEFECT: `definitionsAt` on a
  `super.p` member answered NOTHING** while `quickInfoAt` at the same caret answered
  correctly — § 9's own table and § 14's maturity row both promised the base's declaration
  — because the receiver leg carried a `this` carrier and no `super` one. Fixed (8 lines,
  mirroring `typeCaptureThisMemberType`'s existing super branch) and measured against tsc,
  which navigates to `Base.pb` in the overridden shape and `Base.mb` in the inherited one.
  **THREE CORRECTIONS**: an enum member's declaration name does not "report nothing", it
  reports **`any`** (below, and still open); an object literal's own method
  "refuses a rename loudly" only once a CONTEXTUAL TYPE supplies it — with none it
  **renames completely** from either end, which the correction had in turn to be measured
  to find; a computed key is
  not silently missed, it is **reported in two of its three shapes** and silent only where
  the contextual member is optional. **ONE CLAIM CONFIRMED THE HARD WAY**: a template
  element access really is silent — the rename applies, the template keeps the old name,
  and the resulting program compiles clean. **THE COST TABLE'S BUILD COLUMN IS NOW PINNED
  and its wall column is marked not pinnable**, with `scripts/round930-ls-cost.sh` +
  `LanguageServiceCostMain` as the re-take (one process, one project, three rotations —
  the only comparison CLAUDE.md admits). Re-taken: rebuild 5.0–5.5 s (§ 3 said ~5.2, § 14
  said 5.5–5.9 — both drifted, in opposite directions), highlights 6.3 s on `checker.ts`
  and 5.0–5.5 s on `types.ts` (the row is a statement about a FILE, which is why it looked
  wrong), references 8.3–10.2 clean / 13.2–14.8 dirty, rename 14.3 s (`createTypeChecker`)
  – 21.0 s (`SyntaxKind`). `scripts/lsp_definition.py` is new, the fourth oracle.
  Suite 14,981 → 14,996 / 0 failures / 3 skipped; `cost_gate.py` +0.00% on all 20
  counters; `huge_methods.py --fail-over 0` clean on both modules; the round-920 token
  gate re-run (1,327 files, 101,287,620 chars, zero violations — which is § 14's own
  "101 M characters" claim, verified).

- [ ] **(CHK.5) COMPUTED KEYS — STAGES (a) AND (b) ARE LANDED (rounds 937/938); (c), (d),
  THE INDEX-SIGNATURE AXIS AND FIVE NEWLY MEASURED DUPLICATE GAPS REMAIN.**
  **(a) THE MEMBER-BUILDING SITES — DONE, round 937.** `interface I { [K]: number }`,
  `class C { [K]: number }` and `type T = { [K]: number }` now declare the member, in the
  property, method, get- and set-accessor forms, for every key spelling round 935/936
  resolves. It was NOT one site: six had to be levelled onto one namer, and two of them
  (`checkImplementsClauses`, `classMemberNamesTransitive`) compare a class's AST names to
  a target built from the resolved TYPE, so levelling the type side made a PRE-EXISTING
  Identifier-only drift reachable — two false positives with no computed key in them
  (`interface I { 1: string }` + `class C implements I { 1: string }`, and the same through
  a `static 1`) were closed as part of it. `checkComputedLiteralKeyMembers` now retracts
  before it emits, because the general relation reaches its TS2322 verdict once the key
  binds. Session note has the 40-row table and the 10-arm ablation.
  **(b) A DUPLICATE MEMBER DECLARATION — DONE, round 938, and it corrected its own
  premise.** This compiler ALREADY emitted TS2300 x2 + TS2717 for a plain
  `interface I { p: number; p: string }`, byte-identical to tsc, and for a type literal, a
  class, an enum, two getters, a numeric name and a class property-vs-method. Two things
  were wrong and both are closed: the member map was LAST-WINS where tsc keeps the FIRST
  (eight measured rows, including round 937's spurious TS2322, which was this defect and
  not a computed-key one), and neither duplicate SCAN could name a computed key — the class
  one knew `["a"]`/`[0]`, the interface one had no computed arm at all. Both now ask one
  namer. **The rule that decides the diagnostic came from a PRISTINE baseline, not from
  tsgo**: TS2300/TS2687 are the BINDER's checks and a LATE-BOUND key never reaches them
  (`dynamicNamesErrors` — `interface T0 { [c0]: number; 1: number }` gets NOTHING, `T3` gets
  TS2717 alone), where tsc 7.0.2 emits TS2300 for both; following tsgo reddens that corpus
  test. Same parting on the class `drop(1)` rule. `checkComputedLiteralKeyMembers` now
  retracts before it emits. Session note has the 21-row table and the 9-arm ablation.
  **(b2) NEW — FIVE DUPLICATE GAPS MEASURED IN ROUND 938 WITH tsc's ANSWER, EACH SMALL AND
  EACH SEPARATE.** (i) a MERGED-interface TS2717 — `interface I { p: number }` +
  `interface I { p: string }` is TS2717 at the second in tsc and silent here, because both
  duplicate scans are per-DECLARATION by construction (the first-wins TYPE is already
  right); (ii) an INTERFACE property-vs-METHOD pair is TS2300 x2 in tsc and silent here —
  `checkDuplicateInterfaceMembers` collects `PropertyDeclaration`s only, where its class
  twin collects four kinds; (iii) TS1117 for a late-bound OBJECT-LITERAL key
  (`{ p: 1, [K]: 2 }`) — `getPropertyKeyName`/`evaluateComputedPropertyName` is a THIRD
  namer with its own `__@computed:` scheme and its own numeric normalization, so widening
  it is not the one-line delegation the other two were; (iv) the required-vs-OPTIONAL
  TS2717 (`p: number; p?: number` — tsc says `number | undefined`); (v) **`C.p` reads the
  INSTANCE member's type when a static and an instance member share a name** — that is the
  unfinished `staticMembers` dual-population ("no behavior change yet" in
  `resolveInterfaceMembersCore`), not a duplicate rule, and it is the one of the five that
  is a WRONG TYPE rather than a missing diagnostic.
  **(c) A CONST IMPORTED FROM ANOTHER FILE, AND A CLASS `static readonly` KEY.**
  `import { IK } from "./k"; interface I { [IK]: number }` and `[C.B]` where
  `class C { static readonly B = "p" }`: both bind in tsc, both are still a false positive
  here (measured again round 937, on the DECLARATION side as well as the literal one). The
  syntactic walk cannot cross a file by construction; the route is the frozen binder tables
  (`resolveAlias`), which are deterministic and therefore allowed under round 935's law.
  **(d) THE `unique symbol` TYPE — unchanged, and round 937 CONFIRMED why it cannot land
  alone.** `declare const S: unique symbol` types as plain `symbol` here, so `[S]` and
  `[S2]` are ONE name. Round 936 predicted that naming the key on the literal side alone
  would invert the defect; round 937 measured the SAME inversion already live for a plain
  const (`const x: I = { [K]: 1 }` was TS2353 `'[K]'`, a false positive) and closed it by
  landing both sides together. (d) needs a `unique symbol` type keyed by the DECLARATION
  (tsc's `__@<desc>@<id>`, a name that survives a rename and an import) and both sides in
  ONE commit.
  **(e) NEW — THE INDEX-SIGNATURE AXIS, measured round 937 and belonging to neither (a) nor
  (d).** A computed key whose type is `string` (`let LW = "p"`), a literal UNION, or a
  dotted path through a VALUE (`obj.k`) gives tsc's interface, class and type literal a
  STRING INDEX SIGNATURE rather than a named member — `interface I { [LW]: number }` makes
  `i.p` a `number` in tsc, and `class C { [LW]: number }` likewise, where `c.p` is still
  **TS2339, a false positive** here. Late binding must keep REFUSING these keys; closing
  them is index-signature modelling. Round 936's `{ [L]: number; }`-vs-`{}` display row is
  the same gap seen from the display side.
  **(f) DONE, round 940 — THE TS2741 KEY NAME, the family's ONE measured PRISTINE divergence (round 939).**
  For a missing late-bound member we print `Property 'p' is missing in type '{}' but required
  in type 'I'` where tsc prints `'[K]'`. **Pristine names the key AS WRITTEN wherever it names
  one** — `'[E.A]'` (`assignmentCompatWithEnumIndexer`), `'["a"]'`
  (`duplicateIdentifierComputedName`, an ACTIVE gate), `'[c1]'` (`dynamicNamesErrors`, ACTIVE),
  `'[Symbol.toPrimitive]'` (`symbolProperty21`) — so pristine and tsgo AGREE here and we are
  the outlier. Round 937 recorded it against tsgo; round 939 confirmed the convention against
  pristine and verified our answer live at HEAD. No baseline covers the exact shape
  (`const K = "p"; interface I { [K]: number }; const x: I = {}`), which is why the suite is
  green. **LANDED round 940** at [formatPropertyDisplayName] — the ONE renderer the
  missing-property emitters already route the symbol through, so all twelve of its callers
  moved together — asking round 938's `computedKeyWrittenText`, which answers null for a
  spelling it cannot reproduce exactly. Pinned three ways (`[K]`, `[E.A]`, `["a"]`) with
  the negative controls that a NON-computed member keeps its bare name and a quoted string
  member keeps B291's quoted display; ablation arm A5 reddens exactly the three.
  **WHAT MUST NOT BE UNDONE**: the WELL-KNOWN-symbol route is deliberately not
  `computedSymbolKey` in general (tsc is SILENT for every computed key it cannot late-bind,
  measured over seven of them), and `getMemberName` itself stays unchanged — B451 records
  it as feeding ~20 callers including duplicate detection and abstract tracking, so the
  widening lives in `declaredMemberName` at the member-BUILDING call sites.

- [x] **(CHK.6) THE COMPUTED-KEY FAMILY RE-JUDGED AGAINST *PRISTINE* — DONE, round 939, and
  the verdict is that rounds 933-938 landed NOTHING pristine contradicts.** Rounds 933-937
  established their ground truth by running `tools/tsgo-7.0.2/lib/tsc`, the only reference
  compiler that RUNS on this box; round 938 then found the two references parting on this
  family's own territory, which left every row no corpus baseline covers resting on an oracle
  this project deliberately does not follow. The pristine oracle turned out to be on disk all
  along — `typescript-repo/tests/baselines/reference`, generated by the pinned pristine commit
  — and is now `scripts/pristine_oracle.py` (`--code` / `--pattern` / `--fixture`, every hit
  labelled ACTIVE vs not-generated, plus `--extract DIR`, which writes pristine's own input
  back out so our binary can be run over exactly what pristine saw). **34 landed decisions
  classified: 22 PRISTINE-CONFIRMED, 10 CORPUS-SILENT, 1 tsgo-ONLY, 1 PRISTINE-DIVERGENT** —
  the TS2741 key name, a message FORM round 937 had already recorded, now (CHK.5)(f).
  **The corpus protects much more of this family than the notes claimed**: `dynamicNames`,
  `dynamicNamesErrors`, `duplicateIdentifierComputedName`,
  `destructuredLateBoundNameHasCorrectTypes`, `checkDestructuringShorthandAssigment2`, the
  three `duplicateObjectLiteralProperty_computedName*` and **7 of the 10 TS2717 baselines in
  the whole corpus** are ACTIVE byte-exact gates sitting on these exact decisions.
  **And the strongest evidence is a negative**: `--extract` materialises pristine's own input,
  so our binary was run over **300** ungated pristine fixtures carrying a computed member key
  and differenced (line, code) against pristine's baseline. **277 of 300 emit nothing pristine
  does not**; of the 23 that do, four are (CHK.7) and NOT ONE of the other nineteen is
  attributable to rounds 933-938 — they are unimplemented checks in other families (`using`
  declarations, the private-modifier grammar, index-signature PARAMETER types, super-call
  ordering, a `declare global { interface SymbolConstructor }` that does not merge,
  `Symbol.hasInstance` narrowing, a `never` discriminant, module resolution). The four that
  ARE pristine divergences are older than the family, proved by the diff rather than argued.

- [x] **(CHK.7)(i) AND (iii) — LANDED, round 940, both FALSE POSITIVES, both CLOSED; (ii)
  AND (iv) RE-MEASURED AND RE-QUEUED BELOW, because round 939's entry was wrong about both
  in the direction that decides what to build.** (i) TS1117 was keyed on a computed key's
  SPELLING, so `var s: symbol; ({ [s]: 0, [s]() {}, get [s]() {} })` was TS1117 x2 here and
  silent in `symbolProperty1`/`2`/`3`; the namer now abstains — but ONLY when the key's own
  declaration is IN HAND and late binding still refused it, because a blanket abstain
  regresses `duplicateObjectLiteralProperty_computedName3` (an ACTIVE gate whose keys arrive
  through an `import * as keys`, which pristine binds by TYPE and round 935's syntactic
  resolver cannot follow across a file). (iii) An accessor followed by a PROPERTY is TS2300
  at the property alone — tsc's `PropertyExcludes = None` means a property declared last
  never trips the binder's duplicate check — which reproduces all 83 of
  `privateNameDuplicateField`'s rows and both halves of `duplicateClassElements`.
  **Measured: `privateNameDuplicateField` 3 ours-only rows -> 0; the 630-fixture pristine
  sweep 403 -> 397 ours-only rows with ZERO fixtures regressed; the 8-profile grid
  added=0 removed=0; suite 15,168 -> 15,193 with no baseline moved.**

- [x] **(CHK.8) — THE 630-FIXTURE PRISTINE SWEEP, TRIAGED AND ITS INSTRUMENT REPAIRED;
  TWO FALSE-POSITIVE FAMILIES CLOSED (round 941).** `scripts/pristine_sweep.py` supersedes
  round 940's sweep and **121 of that round's 397 OURS-ONLY rows (30.5%) were the
  instrument's own configuration**: the case-file fallback carried the `// @target:`
  directives tsc STRIPS (a whole-file line shift, 27 fixtures); directives were read from
  the EXTRACTED text, which the `.js` baseline echoes WITHOUT them; and a missing case file
  left no target where the baseline's `(target=…)` suffix still records it. An ALIGNMENT
  ORACLE (each reconstructed input compared line-for-line against pristine's `==== file ====`
  annotation) now makes the first defect impossible to reintroduce silently. **The triage of
  the remaining 334 rows is `docs/pristine-divergences.md` and its cause-class rules are
  `scripts/pristine_triage.py`** — genuine FP 182 (48.8%) / cascade 90 / harness 59 /
  deliberate convention 42. Closed this round: TS2376 (a `super` call need not be FIRST —
  tsc walks the statement list to the first IMMEDIATE `this`/`super` reference, stopping at
  arrows, function declarations/expressions, property declarations and method-like BODIES
  but NOT at their computed NAMES) and TS18028 (the private-identifier gate reads the target
  the user ASKED FOR, not the raw `ES3` default). Sweep **373 -> 334**, zero fixtures
  regressed, pristine-only 777 -> 776 (a true positive GAINED); 8-profile grid added=0
  removed=0 on all eight; suite 15,193 -> 15,214 with no baseline moved.

- [x] **(CHK.9) INDEX-SIGNATURE PARAMETER TYPES — 12 OURS-ONLY TS1268 ROWS -> 0, AND TWO
  TRUE POSITIVES GAINED (`indexSignatures1`, round 945).** tsc's rule, read off the pinned
  sources (`checkGrammarIndexSignatureParameters` + `isValidIndexKeyType`), has three parts we
  had two of. **The intersection arm was missing entirely**, so every BRANDED string
  (`type Id = string & { __tag: 'id' }` — the shape the rule exists for) was TS1268, and an
  `IntersectionType` NODE was not even offered to the type engine, so a syntactic
  `` `${string}xxx${string}` & `${string}yyy${string}` `` never got a verdict either. **And the
  generic test read only a bare `TypeReference`**, which is why `[key: T | number]` and
  `[key: T & string]` were TS1268 where pristine says TS1337 — the cause being that an alias's
  own `T` resolves to `anyType` at that grammar check, so the question has to be asked of the
  AST. Note `someType`/`everyType` distribute over UNIONS only: an intersection is valid when
  SOME constituent is (`string & 'a'` is a legal key), and reading that as `every` is the
  round's B4 arm. Measured: sweep **310 -> 298** ours-only with 0 added, pristine-only
  **775 -> 773**, zero fixtures regressed; 8-profile grid `added=0 removed=0`.

- [ ] **(CHK.10) DEFINITE ASSIGNMENT THROUGH A LATE-BOUND ELEMENT ACCESS — 4 OURS-ONLY
  TS2564 ROWS (`strictPropertyInitialization`, ALIGNED, round 941).** `class C12 { [a]: number;
  [b]: number; ['c']: number; constructor() { this[a] = 1; this[b] = 1; this['c'] = 1 } }`
  with `const a = 'a'; const b = Symbol()`: pristine sees the definite assignment through the
  ELEMENT ACCESS and is silent, we report `Property '…' has no initializer`. Same fixture
  reports `[E.A]` (an enum member key). Small, and squarely in the computed-key arc's own
  family — note that the triage classifier exempts this fixture by name from the
  strict-by-default bucket for exactly this reason. **CONFIRMED GENUINE, round 943**: that
  fixture's case file is not in this clone, so the sweep recovers no directives for it — but
  its own baseline carries **20 TS2564**, i.e. pristine had `strictPropertyInitialization`
  ON, so these four rows are not the convention. (The `--tsc-strict-default` arm deleted them
  until it was guarded on case-file presence; see `docs/pristine-divergences.md` § 0b.)

- [x] **(CHK.11) ELEMENT-ACCESS DISCRIMINANT NARROWING — 11 OURS-ONLY ROWS -> 0
  (`typeGuardNarrowsIndexedAccessOfKnownProperty1`, round 942).** The cause is one sentence:
  **tsc's `isMatchingReference` compares references by SYMBOL and ours compares the path
  STRINGS `getReferencePath` builds**, and every discriminant reader was written against the
  DOTTED spelling alone. FOUR mechanisms, all measured: `singleLevelDiscriminantSegment` (the
  switch reader accepts `name[seg]`); `getTypeOfElementAccess` flow-narrows its UNION
  RECEIVER (B1.1's gate, which its dotted twin has always had); `getReferencePath`
  NORMALISES an identifier-spellable string index onto the dotted segment, because the
  fixture mixes both spellings inside one expression (`s[0]["sub"].under["shape"]`); and
  `requiredEnumSwitchKeys` + `paramMemberChainType` accept an element-access discriminant and
  a multi-segment receiver, which is the two TS2366. **A FIFTH — the 17.34d half, narrowing
  the access's own union RESULT — was written, measured INERT (its ablation arm reddened NONE
  of the 21 pins and no probe could be built where it fires) and REMOVED.** **Measured: 11 -> 0, sweep 334 -> 318 with zero fixtures regressed, 8-profile grid
  added=0 removed=0.** `docs/pristine-divergences.md` § 3.4.

- [x] **(CHK.12) `[Symbol.hasInstance]` NARROWING — 5 OURS-ONLY ROWS -> 0, AND THE ENTRY WAS
  WRONG ABOUT ITS OWN SECOND FIXTURE (round 942).** `instanceof` now asks the RHS type for a
  `[Symbol.hasInstance]` method whose return is a non-`asserts` TYPE PREDICATE over parameter
  0 and uses its target — round 838's `instanceTypeOfConstructorValue` named that leg as its
  one deliberate omission — which answers the three shapes `prototype` and the construct
  signatures cannot: a GENERIC construct signature, SEVERAL construct signatures, and one
  returning `any`. **Two rules read off PRISTINE's baseline and re-read off tsgo 7.0.2: a
  usable predicate DECIDES (a `value is any` target narrows NOTHING and must not fall through
  — pristine's own lines 142/143), and an `instanceof` stays `checkDerived = true` even when
  the candidate came from a predicate, so a UNION candidate is DISTRIBUTED and its
  narrow-down direction is the NOMINAL base-chain test (`C1 | A` narrowed by `C1 | C2` is
  `C1`), scoped to a union candidate so round 425's single-candidate arm is byte-identical.**
  Measured: 5 -> 0 with pristine-only 8 -> 7, i.e. a true positive GAINED.
  **The entry's other fixture is MIS-BUCKETED**: `controlFlowInstanceofWithSymbolHasInstance`
  is 7 rows of which **6 are a PARSER GAP** (`abstract new (...) => infer U`), queued as
  (CHK.14), and 1 is the `instanceof` intersection tail, queued as (CHK.15). Out of scope by
  construction: a `static [Symbol.hasInstance]` on a CLASS declaration, which
  `resolveInstanceOfRhsType` answers from the declared type before the leg is reached.
  `docs/pristine-divergences.md` § 3.5.

- [x] **(CHK.14) `abstract new (…) => T` AND THE CONSTRUCTOR-TYPE `infer` — CLOSED round 947,
  15 ours-only rows (297 -> 282), PRISTINE-ONLY FLAT at 769, zero fixtures regressed.**
  `docs/pristine-divergences.md` § 3f. **This entry's own second half was diagnosed
  backwards and the correction is the round's product**: the defect is NOT "an `infer`
  inside a PARENTHESIZED extends clause does not publish its name" — parentheses are
  irrelevant (`collectInferTypeNames` recurses through `ParenthesizedType` and always has),
  the missing arm was **`ConstructorType`**, and the UNPARENTHESIZED spelling
  `T extends new () => infer U ? U : never` failed identically while the parenthesized
  FUNCTION-type spelling always worked. It is also not a parser item: it is a one-arm gap in
  the INV.4(c)(iii) scope walker, whose sibling `collectInferDecls` carries the arm with a
  comment about keeping parity with it. Landed alongside it: `parsePrimaryType`'s
  `abstract`-then-`new` lookahead (tsc's `isStartOfFunctionTypeOrConstructorType` +
  `parseModifiersForConstructorType`), whose SPAN bound is pinned in `-project` because no
  core diagnostic reads a `ConstructorType`'s `pos`. Held as false NEGATIVES on purpose: the
  `infer` still does not RESOLVE through a constructor type (`D<new () => K>` answers `any`),
  and the recorded `modifiers` set is read by nothing — TS2511 is its named future consumer.

- [x] **(CHK.25) `using` / `await using` DECLARATIONS DID NOT PARSE — 33 OURS-ONLY ROWS OVER
  FOUR FIXTURES, THE LARGEST SINGLE CASCADE IN THE WHOLE PRISTINE POPULATION. LANDED round
  948: ours-only **282 -> 251** over 74 -> 71 fixtures, pristine-only **769 -> 767** (two
  TS2353 GAINED), zero fixtures regressed, zero corpus baselines moved.** `using x = expr;`
  reported TS1434 at the `using` and then TS2304 for every name the failed statement never
  bound. **The representation is tsc's own and needed no new node**: a
  `VariableDeclarationList`'s `flags` field already IS the head token, so `using` is
  `SyntaxKind.UsingKeyword` — no `forEachChild` arm, no `NodeKind`, no binder arm, because the
  binder's `isVar` test already reads any non-`var` head as block-scoped. `await using` is two
  tokens collapsed onto a synthetic `SyntaxKind.AwaitUsingKeyword` the scanner never produces.
  **The whole risk was the CONTEXTUAL KEYWORD and it did NOT materialise anywhere**: the eight
  profiles carry 336 occurrences of `using` as an identifier / property name and zero
  declarations, and the binary grid is byte-identical on all eight. Landed with the grammar
  rules (TS1155 / TS1492 / TS1493 / TS1494 / TS1491 / TS1495), the disposability rule
  (TS2850 / TS2851, positive-evidence-only and switched off unless the lib declares
  `Disposable`), and a VERBATIM emit of the head. `docs/pristine-divergences.md` § 3g.

- [ ] **(CHK.26) `infer U extends T` FOLLOWED BY A CONDITIONAL `?` IS PARSED AS A CONSTRAINED
  INFER WHERE tsc PARSES A CONDITIONAL — 8 OURS-ONLY ROWS, `inferTypesWithExtends1` lines 95 /
  103 / 105 (sub-triaged round 947, § 2.3 P2).** **`infer X extends` itself ALREADY PARSES**
  and has for as long as `parseTypeParameter` has handled a constraint — round 941's label for
  this bucket named the wrong thing. What fails is the DISAMBIGUATION: tsc's
  `tryParseConstraintOfInferType` parses `extends <type>` with conditional types DISALLOWED
  and rolls the whole `extends` back when the next token is `?`, **unless it is already in a
  disallow-conditional context** — so `T extends (infer U extends number ? 1 : 0) ? 1 : 0` is
  a conditional inside the parens (pristine's own comment on the line says *"ok, parsed as
  conditional"*) while `T extends infer U extends string ? U : never` keeps its constraint.
  We take the constraint unconditionally and cascade TS1005 / TS1109 / TS1128. **The rollback
  alone is NOT the fix and would break the second shape**: it needs the
  `disallowConditionalTypes` CONTEXT threaded through `parseType`'s conditional production
  (`extendsType` and a mapped type's `nameType` set it; a parenthesized type clears it) — an
  edit to the production the frozen-subsystem warning is about, which is why round 947 scoped
  it out rather than attempting it beside a landing change. `scanner.tryScan` is already the
  rollback primitive (`tryParseTypeParameters` is the reference shape). Pinned SILENT-side by
  `AbstractConstructorTypeTest.scoped out - an infer constraint is not re-read as the
  enclosing conditional`, which asserts today's TS1005 so the fix has to move it.

- [ ] **(CHK.27) THE `using` FALSE NEGATIVES ROUND 948 LEFT BEHIND — ALL FOUR ARE FEATURES
  THIS COMPILER SIMPLY DOES NOT HAVE, AND NONE COSTS AN OURS-ONLY ROW.** (i) **The DOWNLEVEL
  EMIT.** The head is emitted VERBATIM, which is tsc's own output only at a target with
  explicit resource management (>= ESNext); below it tsc rewrites the block through
  `__addDisposableResource` / `__disposeResources`, and the ~439 `usingDeclarations*` baselines
  upstream are mostly `(module=…,target=…)` variations of exactly that. Verbatim is the SAFE
  half of the choice — rewriting the head to `var` would silently delete the disposal — but a
  low target now emits a `using` a downlevel runtime cannot execute. **This clone carries no
  `using` case file, so the generated corpus still gates none of it**; an emit landing needs
  its own gate (`--outDir` + `diff -r`, since `--noEmit` makes every instrument here blind to
  transform/emit). (ii) **`declare using` — TS1545 `'using' declarations are not allowed in
  ambient contexts.`** (and TS1546); it needs an arm in `parseDeclareDeclaration`, which
  round 948 did not touch, so `declare using x: T;` still cascades. (iii) **The `case` /
  `default`-clause rule, TS1547 / TS1548**, which tsc decides from `declarationList.parent
  .parent` being a clause. (iv) **The `await using` CONTEXT rules — TS2852 / TS2853 / TS2854 and
  TS18054**; a top-level `await using` in a non-module file, or one inside a class static
  block, is silent today. Also unreproduced: TS2850's nested
  `Property '[Symbol.dispose]' is missing …` elaboration and its TS2728 related info.

- [ ] **(CHK.28) A DECORATED CLASS *EXPRESSION* IN AN INITIALIZER IS REFUSED — TS1206
  `Decorators are not valid here.`, 2 OURS-ONLY ROWS
  (`usingDeclarationsNamedEvaluationDecoratorsAndClassFields` lines 14 / 18, round 948).**
  `const C = @dec class { }` and `using C = @dec class { }` both take it; pristine accepts
  both (decorators on class expressions have been legal since TS 5.0). **It is NOT a `using`
  defect** — the `using` parse cascade had merely been masking it, which is why closing
  (CHK.25) took the fixture 10 -> 2 rather than 10 -> 0. Reproduce with
  `const C3 = @dec class { static x = 1; };` at any target; the emitter half (tsc's
  `__esDecorate` for a class expression) is a separate question from the checker's refusal.

- [ ] **(CHK.15) THE `instanceof` POSITIVE BRANCH HAS NO INTERSECTION TAIL — 1 OURS-ONLY ROW,
  BUT A GENERAL RULE (`controlFlowInstanceofWithSymbolHasInstance` line 26, round 942).**
  `s = new Set<number>(); if (s instanceof Promise) {} s.add(42)` reports
  `Property 'add' does not exist on type 'Promise<any> | Set<number>'` where pristine is
  silent: tsc's `getNarrowedType` ends in `maybeTypeOfKind(t, Instantiable) … ?
  getIntersectionType([t, c])`, so the then-branch is `Set<number> & Promise<any>` and the
  JOIN back is `Set<number>`; ours answers the CANDIDATE alone (`narrowByInstanceOf`'s
  `isMatch -> classType`), so the join is a union. `narrowByCallPredicateWorker` already
  carries the equivalent round-425 "positive-empty INTERSECTION fallback" for a PREDICATE
  target — this is the same rule at the `instanceof` site, and its blast radius is every
  `instanceof` in the program, so it needs the 8-profile grid and the 630-fixture sweep, not
  a pin alone.

- [x] **(CHK.16) A DECLARATION'S OWN TYPE PARAMETERS WERE NOT IN SCOPE FOR THE TS2344
  CONSTRAINT WALKER — LANDED, round 943, and it FIXES A FALSE NEGATIVE IN THE SAME MOVE.**
  `checkConstraintsInStatements` pushed them for a `FunctionDeclaration` (round 82, whose
  comment names this exact defect), for a type ALIAS only when the body was an `ImportType`
  (B98a's narrow gate) and for a class or interface never — so a parameter SHADOWED by a
  same-named file-level type was resolved to that type and judged against the callee's
  constraint. `withDeclTypeParamScope` is now the one site, used by the alias, class and
  interface branches, heritage clauses included. Pristine `conditionalTypes1` is two
  ours-only TS2344 from `interface A` (line 309) against `type And<A extends boolean, B
  extends boolean> = If<A, B, false>` (line 171) — **138 lines apart, which is why every
  hand-written reduction was silent and the bisection had to delete the file's TAIL**. The
  other direction was equally wrong, so the fix ADDS diagnostics: `type Loose<Q> = Box<Q>`
  with `interface Box<S extends string>` was silent and now reports TS2344 as pristine does,
  and over 611 fixtures that gained NO ours-only row. **The first cut fixed only the alias
  branch and a "regression guard" pin went RED — that is how the class/interface half was
  found.** Sweep **318 -> 316**, pristine-only 775 -> 775, zero fixtures regressed, 8-profile
  grid added=0 removed=0, suite 15,235 -> 15,248 with no baseline moved.
  `docs/pristine-divergences.md` § 3c.

- [x] **(CHK.17) LIB AVAILABILITY WAS DECIDED FROM THE *RAW* `ES3` TARGET DEFAULT WHERE tsc
  DEFAULTS AN UNSET TARGET TO THE LATEST — LANDED, round 944.** `CompilerOptions.libTarget`
  (unset -> ES2024, explicit -> itself, `es5` included) is now the one input to
  `libFeatureAvailable`, `libProvidesGlobalAt` and the lib-SET resolution in `bindRealLibs` /
  `RealLibSnapshots.prewarmParsedLibFiles`; NOT `effectiveTarget`, which maps an explicit
  `es5` UP to ES2015 and would delete that program's genuine TS2550/TS2583 (round 941's
  TS18028 fork). Sweep **316 -> 313**, pristine-only 775 -> 775, zero fixtures regressed,
  8-profile grid `added=0 removed=0` on all eight (every profile sets BOTH `target: es2020`
  and `lib: ["es2020"]`, so it is a pure control), suite **15,248 -> 15,262 / 0** with NO
  corpus baseline moved. The CLAUDE.md entry that recorded the raw reading as deliberate is
  corrected: it was INVISIBLE, not tested — 0 of 55 case files touching a `LIB_MIN_TARGET`
  member name, 0 of the ~30 referencing a `LIB_GLOBAL_INTRODUCING` global and 0 of the 26
  carrying an `and N more` count omit `@target`/`@lib`.

- [x] **(CHK.21) THE 23 `options.target < ES2015` DOWNLEVEL GATE LINES NOW READ
  `CompilerOptions.defaultedTarget` — AND THE ENTRY'S OWN EVIDENCE WAS MISATTRIBUTED, SO THE
  FAMILY'S SIGN IS THE OPPOSITE OF WHAT IT SAID (round 945).** Round 944 filed this as a
  FALSE-NEGATIVE item on four pristine-only TS2488 rows the gates were assumed to suppress.
  Run at an EXPLICIT `es2015` and `esnext`, where those gates are wide open, we are **still
  silent for all three shapes** — so no gate suppresses them and they are an unimplemented
  iterability check, re-filed as **(CHK.22)**. The real family is a FALSE-POSITIVE one that
  neither instrument could see: the raw target's `ES3` zero value made a tsconfig naming no
  `target` collect **six** diagnostics pristine does not emit (TS1250, TS1501, TS1503,
  TS2659, TS2737, TS18045 — measured on one 14-line file, before vs after, with the explicit
  `es5` and `es2017` columns byte-identical). Oracle: **every** TS1250/TS1501/TS1503/TS2396/
  TS2659/TS2737/TS18045/TS2802 baseline in the pristine corpus comes from a fixture with an
  explicit `@target`. Three raw-target sites are KEPT with reasons in the KDoc (the two
  `target >= ES2015 || …` strict-mode determinations, which a flip makes unconditionally
  strict, and one per-fixture baseline pin). `docs/pristine-divergences.md` § 3d.1.

- [x] **(CHK.22) THE for-of / SPREAD OPERAND'S `[Symbol.iterator]()` RETURN IS NOW CHECKED —
  LANDED, round 946: 4 PRISTINE-ONLY TS2488 ROWS -> 0 WITH OURS-ONLY FLAT, THE FIRST ENTRY IN
  THIS ARC THAT MOVES ONLY THE FALSE-NEGATIVE COLUMN.** `spineCheckIterableOperand` /
  `iterableOperandFailure` reproduce tsc's `getIterationTypesOfIterableSlow` ->
  `getIterationTypesOfMethod("next")` chain for `for...of` and ARRAY-LITERAL spread: an
  OPTIONAL `[Symbol.iterator]?()` is TS2488 (tsc's `method && !(method.flags & Optional)`),
  and a zero-argument `[Symbol.iterator]()` whose RETURN type has no `next` is TS2488 + the
  related **TS2489 `An iterator must have a 'next()' method.`**. **THE CHECK IS
  POSITIVE-EVIDENCE-ONLY AND THAT IS THE WHOLE FP FIREWALL**: it fires only where the member
  is FOUND and provably broken and bails on everything else, so every bail is a false
  negative and no bail is a false positive — which is why a new diagnostic on the commonest
  construct in the language moved **zero** of ~13k corpus baselines. **`this` READS AS `any`
  HERE** (no polymorphic `this` type), so `[Symbol.iterator]() { return this }` — three of
  the four rows — needed `iteratorMethodThisReturn`, a bounded declaration read that answers
  the CARRIER, which is tsc's own answer rather than a widening. Sweep **297 -> 297
  ours-only, pristine-only 773 -> 769**, zero fixtures regressed; 8-profile grid `added=0
  removed=0`; suite **15,294 -> 15,324 / 0 / 3** with no baseline moved; `cost_gate.py`
  `typeOfExpr.calls +0.22%` (the per-operand type read — a reached-ness proof), rebaselined
  in the same commit. 11-arm ablation, every arm at `ran 63`.
  `docs/pristine-divergences.md` § 3e.

- [ ] **(CHK.23) THE MISSING HALF OF THE ITERABILITY CHECK — A TYPE WITH NO
  `[Symbol.iterator]` AT ALL IS STILL ACCEPTED, AND SO ARE FOUR OTHER CONSTRUCTS (round 946,
  scoped out with tsc's answer known for every row).** § 3e.3 of `docs/pristine-divergences.md`
  is the table. The big one is the MISSING-member case, which is where tsc's rule needs a
  complete model of what is iterable — arrays, strings, tuples, `Iterable<T>`, a constrained
  type parameter, every union of them and the built-in iterator families — and one gap in
  such a model is a false positive on `for...of`; note that under the EMBEDDED lib only
  `IterableIterator<T>` declares `[Symbol.iterator]` at all, so the model cannot be built
  from member lookup alone there. The rest, each already pinned SILENT in
  `IterableOperandProtocolTest`: an OPTIONAL `next` (tsc reports it; refused because no
  pristine baseline here measures it), an iterator type with an empty member table or a
  string index signature, `[Symbol.iterator]` requiring an argument on a CLASS (B438e owns
  only the object-literal spelling and its hard-coded TS2322 chain), and the four other
  constructs — CALL-argument spread, array DESTRUCTURING, `yield*` and `for await…of`, whose
  `IterationUse` flags carry different diagnostic families (TS2504 / TS2569 / TS2461).

- [ ] **(CHK.24) THERE IS NO POLYMORPHIC `this` TYPE — `return this` AND `(): this` BOTH
  RESOLVE TO `anyType` (round 946, measured).** `class C { m() { return this } n(): this
  { return this } }` makes `c.m()` and `c.n()` answer `any`, so every `this`-returning
  builder chain in a checked program is untyped and every rule that reads such a return
  bails. Round 946 needed exactly one question answered — "does the carrier have `next`" —
  and got it from `iteratorMethodThisReturn`, a bounded read of the member's DECLARATION;
  that helper is a stopgap and says so. The general fix is tsc's `getThisType` plus the
  `ThisType` type-node arm, and its blast radius is every method-chain return in the
  program, so it needs the 8-profile grid and the 630-fixture sweep.

- [ ] **(CHK.18) `t[k] = v` THROUGH A GENERIC INDEXED ACCESS IS TS2862 WHERE PRISTINE SAYS
  TS2322 — 3 ROWS, A CODE DIVERGENCE RATHER THAN A FALSE POSITIVE
  (`keyofAndIndexedAccessErrors` lines 140-142, round 943).**
  `function test1<T extends Record<string, any>, K extends keyof T>(t: T, k: K) { t[k] = 42 }`:
  we refuse the WRITE (`Type 'T' is generic and can only be indexed for reading`), pristine
  permits it and rejects the VALUE (`Type 'number' is not assignable to type 'T[K]'`). tsc's
  rule reads the receiver's CONSTRAINT for a writable index signature before refusing; ours
  does not. Both compilers error at the same position, so this is FORM under
  `docs/logical-parity.md` § 2 — but the form is a different diagnostic identity, and the
  underlying gate is a real modelling gap that would show as a false POSITIVE the moment a
  program writes through a constrained generic index legally.

- [x] **(CHK.19) A FUNCTION-BODY TYPE ALIAS IS NOT BOUND, SO THE LIB'S `Omit` WON — 1 OURS-ONLY
  TS2314 -> 0 (`conditionalTypes1` line 297, round 945).** `getTypeParamInfo` is a whole-program,
  NAME-keyed scan with no node context, so a block-scoped `type Omit<T>` (CLAUDE.md's B83.5: the
  binder never binds a declaration nested in a function body) was invisible and the LIB's
  two-parameter `Omit` answered the arity question. Closed with round 748's
  `lexicalTypeSymbolForNode` shape one declaration kind over — a name gate computed in the SAME
  sweep that already censuses block-scoped enums, then an ancestor walk over the INV.2(c)
  `lexicalScopes` reading `scope.symbols` ONLY. **It does not re-open the INV.3 minefield the
  B83.5 entry warns about, and the reason is structural**: `declareLexical` skips any name the
  main binder already bound in that container, so a scope-space hit can only be a declaration the
  conventional tables do not have. Measured: sweep **298 -> 297**, 0 added, pristine-only FLAT,
  zero fixtures regressed; 8-profile grid `added=0 removed=0`; `cost_gate.py` moved **−24
  `globals.lookups` (−0.003%)** — tsc's own sources carry block-scoped generic aliases
  (`PropOfRaw<T>` in commandLineParser.ts among them) that now answer locally instead of running
  the global scan, and the grid proves no verdict changed. **STILL OPEN, and named here rather
  than left implicit**: `outerTypeParamNames` is supplied by the TypeAliasDeclaration caller only,
  so a CLASS's or INTERFACE's own type parameters are still `emptySet()` and
  `interface I<T> { [k: T]: string }`-style shapes keep the older answer.

- [ ] **(CHK.20) VARIADIC TUPLE TYPES ARE UNMODELLED — 30 OURS-ONLY ROWS, THE SINGLE
  LARGEST FAMILY LEFT, AND IT IS A FEATURE RATHER THAN A DEFECT (`variadicTuples1`, round
  943).** `getTupleType` maps a `RestType` element through `is RestType ->
  getTypeFromTypeNode(elem.type)` — the arm a PLAIN element gets — so **`[...T]` is built as
  the one-element tuple `[T]`**. Three lines reproduce it:
  `function f<T extends unknown[]>(t: T, m: [...T]) { t = m }` reports `Type '[T]' is not
  assignable to type 'T'`. What is missing is TypeScript 4.0's variadic tuples in full: a
  tuple type with a variadic/rest element, its normalisation, the three relation rules the
  fixture's own section header states ("for a generic type `T`, `[...T]` is assignable to
  `T`, `T` is assignable to `readonly [...T]`, and `T` is assignable to `[...T]` when `T` is
  constrained to a mutable array or tuple type"), `keyof` over one, spread-argument arity,
  and inference into a leading/trailing rest (the fixture's whole `curry` section). M3-scale;
  do NOT attempt it as a bounded rule.

- [ ] **(CHK.13) THE STRICT-BY-DEFAULT CONVENTION IS THE LARGEST *SYSTEMATIC* DIVERGENCE
  LEFT — 46 OURS-ONLY ROWS (42 by code, plus the four round 943 found wearing TS2683 /
  TS7019 / a `strictNullChecks` TS2322), AND IT IS AN OWNER DECISION, NOT A FIX (round
  941, re-sized round 943).** TS2564 / TS2454 / TS7010 fire in this compiler unless `@strict: false` is
  EXPLICITLY set (`Checker.kt`'s dispatch reads `!options.strictExplicitlyFalse`), where tsc
  requires `strict` (or the individual flag) to be ON. A real project with no `strict` in
  its tsconfig therefore gets `Property 'x' has no initializer and is not definitely
  assigned in the constructor` from us and nothing from tsc — `keyofAndIndexedAccess` alone
  is 17 rows for four plain `name: string;` class fields. Invisible to the corpus, whose
  fixtures set the directive. **Do not "fix" it without the owner**: the convention is
  load-bearing for the generated suite's expectations.

- [ ] **(CHK.7)(ii) A COMPUTED KEY'S *EXPRESSION* IS NEVER CHECKED, SO AN UNRESOLVABLE
  `[Symbol.x]` BECOMES A REQUIRED MEMBER — RE-MEASURED round 940 AND IT IS A MODELLING
  CHANGE, NOT A NAMING ONE.** `symbolProperty52`: pristine reports **TS2339 `Property
  'nonsense' does not exist on type 'SymbolConstructor'` TWICE** — once at the KEY inside
  `var obj = { [Symbol.nonsense]: 0 }` and once at the later `obj[Symbol.nonsense]` — and
  gives the literal NO such member, so `obj = {}` is silent. We emit **neither** the key's
  TS2339 (we get only the element-access one) **and** a TS2741
  `Property '[Symbol.nonsense]' is missing in type '{}'`. So the FP and the FN have ONE
  cause: `computedSymbolKey` invents `"[<dotted>]"` as a STRUCTURAL placeholder (round 723,
  and it is what makes tsc's own `Set<TElement>` literal's `[Symbol.iterator]` match) with
  nothing checking that the key expression resolves at all.
  **TWO SHAPES, and the cheap one is refused with a reason.** (a) The cause-level fix is
  tsc's `checkComputedPropertyName`: check the key EXPRESSION, emit TS2339/TS2464, and
  declare no member when it errors. That also closes pristine's TS2464 across the whole
  `computedPropertyNames*_ES6` set, which the round-939 sweep records as one of the largest
  ours-*missing* families. (b) Narrowing `computedSymbolKey` to keys whose `Symbol.<name>`
  is a REAL `SymbolConstructor` member is cheaper and is REFUSED as written: a hardcoded
  well-known list drifts from the lib and would DELETE a member for any symbol the list
  lacks — a TS2741 false positive in the other direction — while asking the type system
  means a member-resolution call from inside `getTypeOfObjectLiteral`, i.e. exactly the
  round-935 ambient-input hazard one layer down. **The whole population is 1 FP row in an
  ungated fixture on a program pristine already rejects twice; the prize is the FN.**

- [ ] **(CHK.7)(iv) STRING/NUMERIC MEMBER-NAME EQUIVALENCE IS MISSING IN THE *TYPE-LITERAL*
  SCAN ONLY, AND IT IS A FALSE **NEGATIVE** — round 939's entry has both the direction and
  the scope wrong.** Re-measured on `numericStringNamedPropertyEquivalence`: pristine emits
  7 rows, we emit 4, **ours-only is ZERO**. The CLASS scan already normalizes
  (`memberKey`'s `normalizeNumericKey`, so line 6 matches) and the INTERFACE scan matches
  lines 10/12 by accident — `1`'s text is already canonical. What is missing is
  `var a: { "1": number; 1.0: string }`: `checkDuplicateInterfaceMembers` names a numeric
  member through `getMemberNameText`, which returns the RAW text, so `"1"` and `1.0` do not
  collide and pristine's **TS2300 x2 (16,5 / 17,5) + TS2717 (17,5)** are all lost.
  **THE FIX IS ONE LINE PLUS A DISPLAY SPLIT, AND THE SPLIT IS THE REAL WORK**: group by
  `normalizeNumericKey`, but pristine prints **two different names for the same member** —
  TS2300 says `'1'` (tsc's binder message uses the SYMBOL name) and TS2717 says `'1.0'`
  (the checker's `declarationNameToString` of the later declaration, and its related TS6203
  says `'1.0'` too, at the position of the `"1"` member). `PropInfo` carries one `display`
  today, so it needs a second field. Low blast radius (a numeric member name whose text is
  not already canonical, in an interface or type literal) and it can only ADD diagnostics
  pristine already has — but it is an FN, so it does not move the v1 zero-FP metric.

- [x] **(CHK.4) THE QUALIFIED, TYPE-ANNOTATION AND WELL-KNOWN-SYMBOL ROUTES — LANDED,
  round 936, both directions, and the residue is re-scoped as (CHK.5) above.** Three
  capabilities, each a false POSITIVE in the supply direction and a false NEGATIVE in the
  excess one at the same time. (i) QUALIFIED keys — `NS.K`, `NS.Inner.IK`, a dotted
  `namespace A.B`'s const, a MERGED namespace's second block, and a const-or-plain ENUM
  member declared inside a namespace: all bind in tsc, all were TS2741 here and silent
  there. Resolved by descending `ModuleBlock` statements SYNTACTICALLY, because
  `currentFileLocals` is ambient and round 935 measured what that costs a member name; the
  one symbol-table consult left is the enum leaf, whose VALUES are in the binder's frozen
  tables and nowhere in the AST. (ii) The TYPE-ANNOTATION spellings — a no-substitution
  template-literal TYPE and a TYPE ALIAS to a literal, including a chain. **`TemplateLiteralType`
  is not a structured node in this parser** (B65.1: empty spans, the whole raw slice in
  `head.rawText`), so `templateSpans.isEmpty()` is true for a SUBSTITUTING one too and
  `head.text` answers `""` — a name matching no member, which reached the excess check as a
  real member on the first build. The raw text is the only discriminator that exists.
  (iii) WELL-KNOWN SYMBOLS in the excess check, which required one embedded-lib line:
  `IterableIterator<T>` did not declare the `[Symbol.iterator]()` member the real lib
  declares, so a literal supplying it against an `IterableIterator`-extending interface
  read as excess (the round-456 pin, and the ONLY red the suite produced). Refused, with
  tsc agreeing on every row: a widened namespace `let`, a substituting template type, an
  alias to a union, and — measured over seven of them — every computed key tsc cannot
  late-bind, which is why the well-known route demands the receiver be `Symbol` with no
  local binding of that name rather than re-admitting `computedSymbolKey` generally.
  28 pins, 13-arm ablation. The `NS.K` FP is gone; the SYMBOL axis verdict is that the
  well-known half was SMALL and the `unique symbol` half is MODELLING — see (CHK.5)(d).

- [x] **(CHK.3) LATE-BOUND COMPUTED KEYS — LANDED, round 935, BOTH DIRECTIONS IN ONE
  COMMIT. One missing capability was a false POSITIVE on one side and a false NEGATIVE on
  the other, and the round's product is that **tsc's own rule is NOT PORTABLE AS WRITTEN**.**
  Supply: `const K = "p"` / `const enum E { P = "p" }` + `{ [K]: 1 }` / `{ [E.P]: 1 }`
  satisfy a required `p` in tsc and were TS2741 here. Excess: the same keys spelling a name
  the target LACKS are TS2353 in tsc, named as WRITTEN, and were silent here. Both are now
  parity, plus every row the table was extended with before designing: a const ALIAS chain,
  a `let` with a literal ANNOTATION (const-ness is not the criterion), a `declare const`, a
  const whose literal INITIALIZER beats a union annotation, a plain (non-`const`) string
  enum, a NUMERIC enum member and a numeric const (named by the VALUE's canonical string,
  so `1e3` is "1000"), a body-local const and an inner const SHADOWING an outer one.
  Refused, with tsc agreeing on every one: a widened `let`, a genuine literal UNION, a plain
  `symbol`, a bare type parameter, a substituting template, and an AMBIENT non-`const` enum
  member with no initializer (round 746's opaque rule turns out to be tsc's own answer).
  **THE FIRST DRAFT PORTED `isTypeUsableAsPropertyName` LITERALLY — the key expression's
  TYPE — AND IT MEASURED AS A NAME THAT IS NOT A FUNCTION OF THE PROGRAM**: a FILE-LEVEL
  un-annotated `const K = "p"` answers the literal in the assignability pass and the widened
  `string` in the pass behind TS2339, so `const obj = { [K]: 1 }; obj.p` emitted the correct
  TS2322 **and** `Property 'p' does not exist on type '{}'` in ONE compile — round 933's
  two-extraction-sites signature reached through ambient state (round 911) instead of through
  a second `when`. The landed resolution is SYNTACTIC (an enum member's VALUE via
  `enumMemberEntries`; otherwise the declaration a name resolves to, by an innermost-first
  walk of the enclosing statement lists — `lookupPerFileForNode` cannot see a body local at
  all, B83.5, and a scope-chain consult would be ambient again), and the pin that fails if
  the type route returns asserts the two passes AGREE, because each pass alone is green.
  `lateBoundComputedKeyName` is asked BEFORE `computedSymbolKey` at all three naming sites,
  which is also what retires round 934's arm-A4 false positive at its source rather than by
  exclusion. 25 pins, 8-arm ablation (every arm with a uniquely-its-own failure). What is left is (CHK.4) above.

- [x] **(CHK.2) A COMPUTED OBJECT-LITERAL KEY NEVER REACHED THE EXCESS-PROPERTY CHECK —
  LANDED, round 934. A false NEGATIVE in every position, from ONE name-extraction `when`,
  and the diagnostic was being computed in full before it was dropped.** Round 933 measured
  the row and left it: ``{ p: 1, [`zz`]: 2 }`` and `{ p: 1, ["zz"]: 2 }` against
  `interface Opt { p?: number }` are TS2353 in tsc 7.0.2 and were silent here. Extended
  before designing, it is larger: a BARE numeric key `{ 7: 2 }` escapes too (so the omission
  is not about computed keys at all), and every position escapes together — `satisfies`, an
  ARGUMENT, a `return`, a NESTED literal under a computed key, a computed METHOD name.
  **The cause is the exact mirror of (CHK.1)'s**: `getTypeOfObjectLiteral` had named all of
  those keys for years, so the source TYPE carried the member and `checkExcessProperties`
  judged it excess correctly — and then looked for the AST node that declared it with a
  `when` knowing only `Identifier` and `StringLiteralNode`, found nothing, and emitted
  nothing. The lookup is now ONE shared predicate (`objLitElementMemberName`), so the type
  builder and the excess check cannot disagree about what an element names.
  **THE ROUND'S REAL PRODUCT IS THE TWO NEAR MISSES, EACH OF WHICH TURNED THE FN INTO AN
  FP ON A ROW ROUND 933's TABLE DOES NOT CONTAIN.** (i) Admitting a numeric key exposed a
  TARGET-side gap that could not matter before — `collectTargetPropertyNames` bails on a
  STRING index signature and knows nothing of a NUMERIC one — so `{ [7]: 2 }` against
  `{ [k: number]: T }` was reported where tsc is silent. (ii) Naming the key with
  `computedLiteralKey ?: computedSymbolKey` (the obvious delegation) reported `'[E.P]'` for
  `const enum E { P = "p" }` + `{ [E.P]: 1 }`, which tsc late-binds to the existing `p` and
  accepts — **`computedSymbolKey` INVENTS `"[<dotted>]"` so a well-known-symbol member can
  match structurally (round 723); it is not a claim about what the key spells and cannot
  tell `Symbol.iterator` from `E.P`.** Both are guards with a discriminating negative
  control apiece. **So the line is round 933's line in the other direction: the excess check
  acts on a computed key exactly when the key is a LITERAL spelling one fixed name**; every
  key needing the key's TYPE stays out in BOTH directions and is (CHK.3). **The message FORM
  is matched rather than recorded** — tsc keeps the delimiters (`'["zz"]'`, `''zz''`) and
  squiggles the whole written key, the span is in hand, and no ACTIVE corpus test has a
  delimited excess key (ten of the eleven such baselines are not generated; the eleventh
  belongs to another emitter). 20 pins + one round-933 pin rewritten to tsc's own answer
  (it asserted a TS2741 that tsc does not emit); six-arm ablation, all reached, four with a
  uniquely-their-own failure, four pins recorded as undiscriminated rather than claimed.
  **Every profile instrument is a CONTROL and it was measured**: across all eight profiles'
  1,249 `.ts` files an object-literal computed key matches 8 times — all eight the same
  destructuring pattern — so `+0.00%` and `added=0 removed=0` are the expected answers.

- [x] **(CHK.1) A BACKTICK-QUOTED COMPUTED MEMBER KEY NAMES A MEMBER — LANDED, round 933.
  Three FALSE POSITIVES tsc does not have, from ONE missing `when` arm, in a spelling the
  whole tsc corpus never uses.** Round 932 recorded, in passing, that `` { [`p`]: v } ``
  did not supply a required `p`. Measured against `tsc 7.0.2` this round it is three, not
  one: the object-literal supply (TS2741), an INTERFACE's own `` [`ip`] `` member (TS2339)
  and a CLASS's own `` [`cp`] `` member — the last of which resolved for the assignability
  check and simultaneously FP'd TS2339 **in one compile**, because the type-building site
  and the class-AST walker are two independent name extractions and only one of them had
  been widened. **The fix is `computedLiteralKey` growing a `NoSubstitutionTemplateLiteralNode`
  arm, plus `classMemberNameText` DELEGATING to it instead of re-spelling its `when`** — the
  archive's B451 entry says outright that this family has >= 5 independent extraction sites
  and that widening one silently leaves the others FP'ing, and the class row is what that
  looks like from the outside. **What stays refused, measured and pinned in the positive:**
  a SUBSTITUTING template (`` [`p${x}`] ``) names no fixed member and is TS2741 in tsc too.
  **What stays OPEN and is NOT pinned** (round 765's law — a known-open gap is a countdown,
  not a guard), both with tsc's answer measured: `{ [K]: v }` / `{ [E.P]: v }` supply nothing
  here and do in tsc — that needs the key's TYPE, i.e. late binding, not a spelling; and the
  EXCESS-PROPERTY direction never sees a computed key at all, so `` { [`zz`] } `` AND
  `{ ["zz"] }` both escape TS2353 where tsc emits it (a false NEGATIVE, symmetric across the
  spellings, untouched by this round). tsc additionally renders such a key's name WITH its
  delimiters in the TS2353 text (`'"zz"'`, `` '[`zz`]' ``) where we print the bare name — a
  form divergence, noted not acted on. 11 pins (`TemplateComputedMemberKeyTest`, every
  backtick row beside its quote-spelled B451 control); three-arm ablation, all reached.
  **Every profile-based instrument is STRUCTURALLY BLIND here and that is measured, not
  assumed**: the eight tsc profiles contain ZERO backtick-quoted computed member keys (the
  only `` [`…`] `` matches are array literals), which is why `cost_gate.py` reads +0.00%
  on all 20 counters and the 8-profile grid reads `added=0 removed=0` — both are CONTROLS
  here, and the corpus plus the new pins are the gate.

- [x] **(API.17) A COMPUTED OBJECT-LITERAL KEY `{ ["p"]: v }` — LANDED, round 932; § 14's gap 2,
  and the LAST silent shape anywhere in this API.** Round 930 measured a computed key as
  "usually reported" — `WOULD_NOT_COMPILE` where the contextual member is REQUIRED,
  `OCCURRENCES_INCOMPLETE` where the literal has no contextual type — and SILENT in exactly
  one shape: an OPTIONAL member, where stranding the key costs no diagnostic, so the applied
  rename compiled clean with the old name still spelled in the literal and no gate in this
  repository could see it. tsc 7.0.2 counts the key as a reference, hovers it as the member,
  navigates to the member's declaration and renames it (measured, six spans on a fixture
  carrying one). **The landing is a POPULATION change and one predicate**: `occurrenceNodes`
  now sweeps every literal for which `isMemberPosition && isMemberNameLiteral` holds, which
  subsumes (API.9)'s element accesses, (API.16)'s templates, `{ "p": v }`, `{ ["p"]: v }`,
  ``{ [`p`]: v }`` and a class's or an interface's `["p"]` — so the set a caret may land in,
  the set a sweep reports and the set a rename must edit are ONE set by construction rather
  than three definitions kept in step. **A literal the API cannot RESOLVE still belongs in it**:
  seen-and-unplaced is a stated `OCCURRENCES_INCOMPLETE` conflict, unseen is a silent miss.
  **`{ [K]: v }` is deliberately out** — it spells no fixed name and tsc reads it as a
  reference to the binding `K` alone (measured); the asymmetry with the element-access arm is
  stated in `SyntaxRoles.isMemberPosition`, because calling it a member position flips the
  completeness net's polarity for every ordinary `const` rename. **THE ROUND'S SECOND HALF WAS
  AN AUDIT FINDING**: `typeCaptureReportedType` recorded an object-literal key's TYPE as
  deliberately not closed *because the contextual type is walk-scoped state a capture cannot
  read* — and (API.10) built `typeCaptureContextualType`, a purely syntactic walk, one round
  later. Nobody came back. Measured before this round, EVERY key — computed or bare —
  answered `any`, or the COLLIDER's type where a same-spelled binding existed. Closed by
  `typeCaptureObjectLiteralKeyType`, the contextual member's type with the key's own value as
  the fallback, which is what tsc reports in both shapes. +18 pins, four inverted; ten-arm
  ablation. `docs/language-service.md` §§ 8, 9, 10b, 10d, 14.

- [x] **(API.16) A MEMBER NAMED BY A TEMPLATE ELEMENT ACCESS — LANDED, round 931; § 14's
  gap 6, the ONE genuinely silent gap in this API, is closed.** ``o[`p`]`` was outside
  (API.9)'s occurrence population, so `referencesAt` / `documentHighlightsAt` / `renameAt`
  missed it AND SAID NOTHING: round 930 proved it end to end — the rename applies, the
  template keeps spelling the old name, and the applied program has ZERO diagnostics, so
  no gate this API has can see it. tsc 7.0.2 counts it as a reference, renames it, hovers
  it as `(property) I.p: number` and completes inside it (all measured). It is now an
  ordinary occurrence in every one of those queries, with the edit covering the TEXT and
  **not the backticks** — round 926's rule one delimiter over, and the same measured span
  tsc writes. **Round 929's completion refusal is CASHED rather than overruled**: it
  refused for exactly one reason, that the sweep could not find such a member, and the
  sweep now can — the two still share ONE enumeration, so they cannot drift apart about
  what a member name is. **REFUSED, and it is a NODE-CLASS boundary rather than a
  judgement**: a template carrying a SUBSTITUTION (``o[`p${x}`]``) spells no fixed name,
  so it is neither an occurrence nor an obstacle and its caret renames nothing — which is
  tsc's answer there too (zero references, `prepareRename` refuses). **The one place a
  second mechanism was needed is HOVER**: this compiler's element-access typing keys a
  named member off a STRING literal, so routing the template through the access would
  have answered `any` — the (API.15) violation one round later — and the member is
  resolved through the receiver instead. +8 pins, two inverted; seven-arm ablation, five
  distinct red sets plus one MEASURED-REDUNDANT guard with its reach proved by a
  narrowing twin. `docs/language-service.md` §§ 8, 9, 10a, 10b, 10d, 14.

- [x] **(API.15) AN ENUM MEMBER'S DECLARATION NAME REPORTS `any` — LANDED, round 931; the one live violation
  of *prove to offer* in this API.** Measured round 930 on four shapes (plain, valued,
  `const enum`, string enum): `quickInfoAt` on the `Alpha` of `enum Plain { Alpha }`
  answers `QuickInfo(displayString = "any")`, where tsc 7.0.2 answers
  `(enum member) Plain.Alpha = 0` and where our own USE site already answers
  `Plain.Alpha`. Not an absent answer — a plausible wrong one, which is the failure mode
  (BUG.4) and (API.11) each closed one position over. **The mechanism is known and the fix
  is one leg**: `Checker.typeCaptureMemberDeclarationType` resolves a declaration name
  through its OWNER and then asks `typeCaptureCollectMembers` for the member — and an
  enum's own type is a member-LESS `Type.Object` (CLAUDE.md), so the collection finds
  nothing, the leg returns null and the fallback types the identifier as a free name.
  What it needs instead is `getDeclaredTypeOfEnumMember`, which is what the use site
  already reaches. Pinned as a DEFECT by `LanguageServiceStateTest`'s `an enum member's
  declaration name reports the WRONG type and its use reports the right one`, so closing
  it must edit that test, § 8 and § 14's gap 7 together. Definitions and references for
  the same position are already complete; only the TYPE is wrong.
  **LANDED**: `typeCaptureEnumMemberType`, eight lines, minting through
  `getDeclaredTypeOfEnumMember` — and the measured product is that the obvious
  alternative does NOT work (`getTypeOfSymbol` on an enum member symbol answers `any`,
  arm A2). Five shapes report the member's type, the same instance the use site
  reports; tsc's extra decoration is the member's VALUE, which this API deliberately
  does not render (§ 8). The defect pin is inverted in place.

- [x] **(API.12) COMPLETION INSIDE `o["` — LANDED, round 929; the last query that did not
  answer an element access.** A caret in the string of `o["…"]` is a MEMBER caret whose
  receiver is the expression before the `[`, decided by ONE classifier
  (`SourceIndex.stringMemberAnchorAt`) over (API.9)'s OWN enumeration, so "a string literal
  is a member name only in an element-access position" is one predicate shared by the
  occurrence sweep and the anchor. **Zero core changes**: the member enumeration is round
  917's, so the union rule, the accessibility filter and the `this`/export-table legs came
  for free. **The span is the literal's TEXT, quotes excluded** — tsc's own measured edit
  range and the same span a member rename writes into — and a member whose spelling is not
  an identifier (`"has space"`, `"1abc"`) is offered, which is the reason element access
  exists. **THE ROUND'S PRODUCT is that `StringLiteralNode.isUnterminated` is FALSE for a
  lone `"`** (the parser compares the raw text's last character to its first), so `bag["` at
  end of file — the state a completion request is normally made in — parsed as a terminated
  empty string and used to answer FREE_NAME with the whole lexical scope offered INSIDE the
  string; the anchor checks the arithmetic as well as the flag. **Deliberately refused**, each
  measured against tsc: a TEMPLATE `` o[`p`] `` (which tsc completes — refused because
  (API.9)'s population is string literals only, so a member written that way is one a rename
  cannot find), a caret AT the opening quote, an indexed-access TYPE, and a string completed
  from its CONTEXTUAL type. **That last measurement found a SILENT GAP one layer down: tsc
  counts `` o[`p`] `` as a reference**, so this API's references and rename miss it and do not
  say so — now § 14's gap 6. +26 pins, nine-arm ablation (five distinct non-empty sets, three
  MEASURED-REDUNDANT guards and a two-mistake REACH CONTROL), all gates green.
  `docs/language-service.md` §§ 10a, 14.

- [x] **(API.11) A MEMBER DECLARATION NAME RESOLVES TO ITS OWN SYMBOL — LANDED, round 928;
  the single largest thing refusing a member rename is gone.** A member's own declaration
  name — an interface's, a class field's, a method's, an accessor's, a static's, a
  `#private`'s, a type-literal member's, an enum member's — is bound by no scope and has no
  receiver, so it resolved to nothing: `definitionsAt` answered empty, `quickInfoAt` answered
  `any` (or the COLLIDER's type, (BUG.4) one position over), `referencesAt` answered empty for
  a member never used, and `renameAt` refused whenever another interface declared the same
  member NAME. It now resolves through its **OWNER**, the receiver's exact dual — the fourth
  resolution mechanism (`Checker.typeCaptureMemberDeclarations`). **THE HAZARD THE ITEM NAMED
  IS BIGGER THAN "resolve it to itself"**: round 884's `mergeSingleSymbol` ADOPTS, so a member
  declared in two merged `interface` blocks is one symbol carrying only the SECOND block's
  declaration — measured — and the whole list has to be reconstructed from the OWNER symbol's
  own declarations, each a container. A merged declaration, an OVERLOAD set and an ACCESSOR
  PAIR are therefore one group from any of their declaration names, in every query. Deliberate
  exclusion, in the conservative direction: an object literal's own METHOD, which is outside
  (API.10)'s key leg and stays a loud refusal. +16 pins, two changed meaning in place, nine-arm
  ablation (seven distinct sets; two arms measured REDUNDANT with their reach proved by other
  arms), `cost_gate.py` +0.00%. `docs/language-service.md` §§ 8, 9, 10b, 10d, 13, and the new
  **§ 14, State of the API**.

- [x] **(API.10) ONE SPAN, TWO SYMBOLS — LANDED, round 927; the LAST of round 922's five
  refusals.** A contextually typed object-literal KEY (`{ p: v }`) and both SHORTHANDS
  (`{ p }`, `const { p } = o`) are occurrences of the member the literal's CONTEXTUAL
  type supplies. **The capture still files ONE answer per span** — round 926 read that
  as the structural obstacle and it is not: tsc's relation between a shorthand's two
  symbols is ASYMMETRIC (the member's group CONTAINS the token; a caret ON the token
  answers the LOCAL's group alone), so what was missing was a ROLE.
  `CapturedDefinition` now carries three declaration sets differing in which of
  NAVIGATION / SEED / MEMBERSHIP they hold: `locations` all three, `related` seed +
  membership (the heritage edge, and now an object-literal key's OWN property),
  `shorthand` navigation + membership and deliberately NOT seed. The contextual type is
  computed by a SYNTACTIC walk OUT of the literal (`Checker.typeCaptureContextualType`,
  the dual of round 926's `typeCaptureDestructured`) covering eleven positions read out
  of tsc 7.0.2, because the checker's own contextual type is walk-scoped and `cpaCtxAt`
  stops at every statement edge. `renameAt` expands a shorthand in whichever direction
  it was reached from — `{ renamed: p }` vs `{ p: renamed }`, the round's discriminator,
  since both compile and both are one edit. **Still refused**: a second declaration of
  the same member name (pre-existing, and the named successor), a shorthand whose member
  cannot be placed, and a computed key. +19 pins, ten-arm ablation (nine distinct sets;
  A3/A8 share one because the round-925 verification refuses exactly what a wrong
  expansion would write), `cost_gate.py` +0.00%. `docs/language-service.md` §§ 8, 9,
  10b, 10d, 13.

- [x] **(API.9) THE MEMBER OCCURRENCE SET — LANDED, round 926; TWO OF THE THREE KINDS CLOSED
  OUTRIGHT, THE THIRD CLOSED FOR A DECLARED HERITAGE EDGE AND STILL REFUSED FOR A CONTEXTUAL
  ONE.** Round 925 measured a member's occurrence set at 2 spans against tsc's 5 and named the
  three missing kinds. Closed: **(1) a binding element's `propertyName`** (`const { p: local }`
  — a receiver question; the pattern's source is the annotation or initializer one to three
  levels up, `Checker.typeCaptureDestructured`), **(2) an element access `o["p"]`** (a
  POPULATION question; `SourceIndex.occurrenceNodes()` is `identifiers()` plus the string
  literals that name a member, and the edit span is the text BETWEEN the quotes), and **(3) an
  IMPLEMENTOR's member** via `CapturedDefinition.related` — a DECLARED heritage edge, computed
  per OCCURRENCE, which is what makes a `this.p` inside an implementor part of the interface's
  group. **Still refused: a contextually supplied key, and the binding SHORTHAND `const { p }`,
  for the same structural reason** — one span carrying two symbols, which a capture filing one
  answer per span cannot express. `referencesAt`, `documentHighlightsAt` and `renameAt` improve
  together because the set is wired once; `definitionsAt` deliberately does NOT follow the
  heritage edge, because tsc's own go-to-definition on an implementor's member answers that
  member. +20 pins, ten-arm ablation, `cost_gate.py` +0.00%, population 381,670 -> 381,672 on
  tsc's own sources. `docs/language-service.md` §§ 9, 10b, 10d.

- [x] **(API.8) RENAME — LANDED, round 925.** `RenamePlan(oldName, newName, files, refusal,
  conflicts)` / `FileRename(fileName, edits)` / `RenameEdit(start, end, newText)` /
  `RenameConflict(kind, fileName, start, end, detail)` + `RenameRefusal` (11) and
  `RenameConflictKind` (5); **`Project.renameAt(fileName, offset, newName)`**. **ZERO core
  changes** — the whole feature sits above the compiler on (API.5)'s sweep and (API.7)'s parent
  ascent. **STEP 1 WAS tsc ITSELF, and it decided three designs**: `scripts/lsp_rename.py` drives
  `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`'s `textDocument/prepareRename` + `rename` over a
  22-caret fixture and prints the resulting TEXT, so `{ p }` -> `{ p: newName }`, `const { z }`
  -> `{ z: newName }` (local) vs `{ newName: z }` (property), and the lib refusal's exact wording
  were READ rather than reasoned. It also showed **two places to do BETTER than tsc**: tsc
  validates neither the new name (`const class = 1`, `const 1bad = 1`) nor collisions (it writes
  a second `const useZ` beside the first). **THE OCCURRENCE SET WAS MEASURED BEFORE ANY CODE and
  it is NOT complete for members** — on the same fixture tsc's member rename edits 5 spans and
  ours resolves 2, missing a binding element's `propertyName`, an `o["p"]` (a string literal, so
  outside the identifier population by construction) and an IMPLEMENTOR's member (a different
  symbol here). So members are not planned around, they are **refused with the evidence**:
  a spelling scan is used as a SAFETY NET — never as the answer — and an identifier spelling the
  old name that is neither in the group nor resolved elsewhere is a conflict. **The position
  split inside that net is load-bearing**: a member declaration name resolves to nothing, so
  without it an `interface I { p }` anywhere would refuse renaming an unrelated local `p`.
  **THEN THE PLAN IS VERIFIED BY APPLYING IT AND COMPILING AGAIN** (a scratch `OverlayVfs` around
  the project's own, so nothing is observable): it must re-read, it must add no diagnostic
  (**the COLLISION check**), and every renamed occurrence plus every identifier that ALREADY
  spelled the new name must resolve to exactly what it resolved to before (**the CAPTURE check** —
  renaming a file-level `a` to `b` where a body holds its own `b` compiles, produces no
  diagnostic anywhere, and means something else; arm A4 is the only thing that sees it).
  **ONE MEASURED DESIGN CORRECTION**: the expectation for a renamed occurrence is its OWN prior
  answer, not the seed — demanding the seed reports this API's own blind spot (a member's
  declaration name resolves to nothing) as a change of meaning, and refused three correct member
  renames before it was fixed (arm A10). **DIVERGENCE FROM tsc, stated**: a bare `export { p }` /
  `import { p }` is replaced PLAINLY where tsc expands to `newName as p` — our identity crosses
  the alias hop, so the local and the export are one symbol and the whole group renames together;
  expanding would make `export { p }` behave differently from `export const p`. **REFUSED, each
  with a reason**: a declaration in a library, an ALIASED import (`import { a as b }` — one new
  name cannot spell two things, and tsc picks by caret because it has two symbols), an unresolved
  import, a caret on either half of an `as`, a reserved or malformed new name (**no build**), and
  a member whose set cannot be shown complete. **PINS +35** (`-project` 390 -> 425; core UNCHANGED
  at 14,341) — 14 parse-only shape pins written FIRST. THE DISCRIMINATOR is the shorthand, asserted
  as the exact resulting TEXT of both lines, because a plain rewrite passes every count-based
  assertion and renames the object's key. **APPLY-AND-RECHECK** pins apply the plan through
  `updateFile` and assert the diagnostics are byte-identical — an independent oracle of the
  verification `renameAt` runs internally. **TWELVE-ARM ABLATION**, one mistake at a time, anchored
  replacements with an asserted occurrence count, restored from a sha256-verified snapshot.
  **GATES**: suite 14,865 -> **14,900 / 0 failures / 0 errors / 3 skipped = exactly the +35**;
  `cost_gate.py` **+0.00% on all 20 counters** (a control: no core change);
  `huge_methods.py --fail-over 0` clean on core and on `-project` explicitly. **MEASURED ON tsc's
  OWN SOURCES**: renaming `SyntaxKind` in `types.ts` produces **9,827 edits across 49 files** in
  23.9-24.5 s warm (against `referencesAt`'s 10.6-16.0 s); `createTypeChecker` is 3 edits in
  13.3-14.3 s. `docs/language-service.md` § 10d; harness `RenameCostMain`.

- [x] **(BUG.4) Quick info on a MEMBER NAME reports the wrong type, for every receiver — FIXED,
  round 924.** The item said it reports `any`; **measured against tsc 7.0.2's own LSP it reports
  the type of whatever unrelated binding shares the member's spelling**, and `any` only where
  nothing does — 16 of 23 wrong member positions read a collider, 6 read `any`, one was right by
  coincidence. **The fix is tsc's own rule**: `getTypeOfSymbolAtLocation` moves off the right-hand
  side of a property access ONTO THE ACCESS, so the type of the `p` in `o.p` is the type of `o.p`
  — and a probe of exactly that, measured before any design was committed, was already correct for
  the generic instantiation, the inherited member, the union receiver, the type-parameter receiver,
  the static side, the enum and namespace members and the flow-NARROWED member, because
  `computeRawTypeOfPropertyAccess` implements all of them. So the landed fix contains **no member
  walk**: the brief's carrier route was the right instinct at the wrong altitude, and a member-table
  read is exactly what arm A2 shows failing (the two generic pins plus narrowing). The ONE receiver
  needing (API.3d)'s carrier is `this`/`super`, which are plain identifiers in this parser and type
  as `any`; the leg is ADDITIVE, so where it cannot decide the access answers `any` rather than a
  wrong name. **NEIGHBOURS CASHED**: an element access `o["p"]` (the caret is on the literal, whose
  own `string` made the old answer right only by coincidence) and a qualified TYPE name `N.T`
  (through the export table). **STILL REFUSED**: an object literal's own key, on round 922's
  unchanged contextual-type ground. **THREE tsc DIVERGENCES named rather than asserted away**:
  `this` in a static member (`typeof C` is unmodelled), an object-literal member's literal widening,
  and a type rendered under a synonymous alias.

- [x] **(BUG.3) A caret on `this.` inside a NESTED ARROW answers NO members — FIXED, round 923.**
  **THE LAYER QUESTION WAS THE ITEM, AND THE ANSWER IS CAPTURE-ONLY.** Settled by MEASUREMENT before
  any code: a 24-line fixture covering `this` in a method, an arrow, an arrow inside an arrow, a
  `function` expression and declaration, an object-literal method, a getter, a setter, a constructor,
  a property initializer, a static member and a class expression, compiled through the ORDINARY
  diagnostic path, gives **17 diagnostics byte-identical to tsc 7.0.2** — so the CHECKER binds `this`
  in a nested arrow exactly right and the compiler-correctness worry this item raised is answered NO.
  The defect was `typeCaptureVisit` installing `currentClassForThis = frame.classForThis`: a cta
  frame is a TYPE-checking context and does not thread `this`, so the frame an arrow BODY pushes
  carries null. Fixed by **`typeCaptureThisClass`**, a pull-based ascent transparent to arrows and
  opaque to every other `this`-binder — deliberately NOT round 922's `typeCaptureEnclosingClass` (the
  accessibility question, which would answer inside a `function`) and deliberately NOT the checker's
  own `spineCaClassCtx` (right shape, bug-compatibly transparent to a nested `FunctionDeclaration`,
  the one arm where reusing it verbatim fails). Bias PROVE TO OFFER. **Side findings, stated not
  fixed**: an EXPRESSION-bodied arrow already worked (a cta frame is pushed at a `Block` enter, so
  such an arrow pushes none), and **quick info on a member NAME is a separate RECEIVER-INDEPENDENT
  gap** — `o.p`, `this.p` in a method and `this.p` in an arrow all report `any` — so the brief's
  "they share the path" is false; promoted to the successor ranking instead. **+20 pins**,
  **seven-arm ablation** (five distinct sets, one measured-redundant guard, one redundancy
  demonstration), suite 14,818 -> 14,838, `cost_gate.py` +0.00%, **8-profile grid `added=0 removed=0`
  against a rebuilt HEAD binary**. `docs/language-service.md` § 9.

- [x] **(API.6) SIGNATURE HELP — LANDED, round 921.** `SignatureHelp(signatures, activeSignature,
  activeArgument)` / `SignatureInfo(label, parameters, returnTypeText, activeParameter)` /
  `ParameterInfo(name, typeText, optional, isRest, labelStart, labelEnd)`; **`Project.signatureHelpAt(
  fileName, offset)`**, null when the caret is in no argument list and an EMPTY signature list when it
  is in one whose callee has none. A FOURTH capture list — `TypeCaptureRequest.signatureSpans:
  List<SignatureCaptureSpan>`, the only one carrying a payload beyond the span, because the ACTIVE
  ARGUMENT is a property of the COMMAS and `f(a, |)` parses to a call with one argument.
  **THE PREMISE — "three-quarters built" — HELD FOR THE CALLEE AND WAS WRONG ABOUT THE ANCHOR.**
  `getCalleeType` + `getCallSignaturesOfType` answered a method through a receiver, an import, a
  callee that is itself a call and a decorator factory with no rule of their own, exactly as ranked;
  what the completion anchor did NOT already answer is which call and which argument, because
  **signature help is the first query in this arc whose subject is a REGION the parse carries no node
  for**. Three shapes defeat containment: `f(a, b|)` is at the real END of `b` (half-open, so outside
  it) and yet is argument 1; `f(a, |)`'s second argument does not exist in the tree; and for `f(` at
  EOF or `f(a,` before a `}` the call node's own real end lies BEFORE the caret, so no descent reaches
  it. **THE PARSER RECOVERY WAS READ OUT OF `Parser.kt` BEFORE ANY CODE, as round 917 did**:
  `parseArgumentListWorker` breaks on end-of-file and on a `}` and then runs `parseExpected(CloseParen)`,
  so the `CallExpression` EXISTS in every one of those shapes — which is what makes a token-level
  anchor possible at all. So the region is **bracket-matched over the token stream** (stopping early at
  a closer that does not match the top of the stack — an unmatched `}` means the enclosing block is
  closing) and the index is **a count of this list's own commas**, where "its own" is decided by
  testing the ARGUMENTS' spans: a comma inside a nested call, an object literal or a
  `Map<string, number>` type argument is excluded by ONE test, with no per-construct rule and no need
  to lex `<`/`>` (arm A8, 4 red). **THE ACTIVE-SIGNATURE RULE, stated so it can be argued with**: the
  FIRST signature that could still become this call — room for the caret's argument (its index is
  within the parameter list, or the signature ends in a rest, or it takes none and none were passed)
  AND `signatureAcceptsArgs` over the arguments already FINISHED, which is the same verdict
  `resolveCallOverload` selects with, so a host's highlighted overload and the compiler's chosen one
  cannot drift. The argument the caret is IN is deliberately not judged — half-typed by construction,
  so judging it would flip the highlight under the user's hands. Nothing qualifying answers 0,
  reported not hidden. Arms A6 (always 0) and A7 (arity only) redden different sets, so both halves of
  the rule are load-bearing. **ONE COMPILER-SIDE SURPRISE, FIXED**: a parameter declared with a
  BINDING PATTERN is dropped from `Signature.parameters` by `getParameterSymbols` and the survivors
  keep a POSITIONAL zip of the declaration's annotations, so rendering from the symbols alone prints
  `destructured(tail: { a: number; b: number })` — one parameter short AND wearing its neighbour's
  type, i.e. a plausible-looking lie. The DECLARATION is rendered instead whenever its parameter list
  is longer (arm A10, 1 red uniquely its own). **RENDERING reuses `typeToString`** — hover's renderer —
  and deliberately NOT `signatureToString`, whose `p?: string | undefined` is a TS2345 message
  convention; parameter ranges are recorded AS THE LABEL IS BUILT (arm A11), because searching for
  `name: type` finds the wrong occurrence as soon as one parameter's type mentions another's spelling.
  A GENERIC callee renders UNINSTANTIATED (`pickFrom<T>(xs: T[], index: number): T`) — inferring `T`
  means inferring from arguments that are not finished. **REFUSED with reasons**: tagged templates (no
  parenthesized list), type arguments, `super(...)` (an ordinary identifier here, bound to nothing —
  empty list, pinned), and a spread's arity. **NOT refused, and pinned**: decorator factories and a
  call-callee. **PINS +56** (`-project` 242 -> 298; core UNCHANGED at 14,341) — 30 parse-only anchor
  pins written FIRST, 26 end-to-end. THE DISCRIMINATOR is an OVERLOADED callee asserted as an EXACT
  list of three labels: every shortcut (render the callee's type, take the overload resolution picks,
  match by name) answers ONE and passes every other pin. **ELEVEN-ARM ABLATION, one mistake at a time,
  each dry-run for a real diff and restored from a sha256-verified snapshot; all eleven compiled and
  ALL ELEVEN reddened a DISTINCT set** — A1 outermost call 1, A2 first overload only 1 (the
  discriminator), A3 no rest clamp 1, A4 no receiver path 2, A5 no export-table leg 1, A6
  activeSignature always 0 -> 2, A7 arity-only 1 (a strict subset of A6, distinguished by the pin it
  leaves GREEN), A8 all commas 4, A9 region = the call's real end 6, A10 no declaration render 1, A11
  label ranges not followed 1. `scripts/round921-ablate.sh`. **GATES: suite 14,717 -> 14,773 / 0
  failures / 0 errors / 3 skipped = EXACTLY the +56**; `cost_gate.py` **+0.00% on all 20 counters** — a
  real gate, since `Checker.kt` grew ~370 lines reachable from the hook on the hot walk;
  `huge_methods.py --fail-over 0` clean on core (750 classes, 15,976 methods) and on `-project`
  explicitly (28 classes, 280 methods); `spine_closure_audit.py` 46 handlers all supersets;
  `scripts/round920-token-gate.sh` 1,327 files / 101,287,620 chars / ZERO violations. No wall A/B:
  production executes not one new instruction — every addition sits behind a hook that returns on a
  null per-file key set. `docs/language-service.md` § 10c.

DENOMINATORS, so every % below converts. Last MEASURED warm rebuild **5,242.6 ms** (round 899, per-arm
sd 2.51%); JFR profile denominator **5,429 ms**; **1% = 54.3 ms**. Cross-round: 5,859 (pre-887) ->
5,424 (pre-895) -> 5,243 (HEAD) = **-10.5% over rounds 887-898**. **There has been no wall A/B for
twelve rounds**, and round 899 could resolve 1.88% in SIGN alone — so every item below is a fifth to
a half of what this box can judge and must be defended on counters plus a decomposition, never on a
median. `cost_gate.py` reads +0.00% by construction for all of them.

REFUSAL FLOOR: ~**0.31%** (~17 ms) for a LOW-risk change — round 897 refused there, 898 refused
MEDIUM at 0.13-0.20%, 900 refused at 0.07-0.14% and BUILT at 0.39%, 903 refused at 0.085%.

- [x] **(WARM.31) Residual boxed primitive map/set keys — REFUSED, round 904.** 14 sites,
  **2,698,745 ops/rebuild**, premium **6.58 ns**, so **17.7 ms = 0.334% for ALL of them together** and
  **0.064% for the largest single one**. `docs/perf/boxed-primitive-key-price.md`. **Do not re-open
  from a leaf profile**: the 29.4 ms that ranked it is one draw of a number that reads 72.9 and 19.0 ms
  across round 899's own two dumps of the same binary. A next agent can refuse a NEW boxed-key site
  for free — **population x 6.58 ns**, and a site needs ~1.7 M ops to clear the floor while the whole
  spine visits 856,962 nodes.

- [x] **(WARM.32) The iterator-allocation family — REFUSED, round 905.** 215 sites are **495,305
  calls over 925,502 elements** (mean list length **1.99** / **1.72**; 52.4% of `forEachChild`'s list
  positions are SINGLETON, and `anyIdentical` hits 94.4% so a hit stops the scan). Premiums **11.95 ns**
  and **2.75 ns** per call = **3.90 ms = 0.074%**, refused by 4.4x, and that is an UPPER bound (both
  arms fold into a trivial sink). `docs/perf/iterator-allocation-price.md`. **The census refuses it
  without the amplifier**: 17 ms over 495,305 calls needs 34.3 ns/call, where a WHOLE boxed
  `HashMap<Long, .>` probe is 8.53 ns (round 904). **The sibling project's -3.1% is not contradicted —
  the mechanism transfers and the PRICE does not**, because its population is per-token `withIndex()`
  chains and ours is 2-element lists. LANDED ANYWAY: the 215 sites now route through `walkList` /
  `anyIdentical` in `NodeWalk.kt` (one home, so it cannot be re-opened blind), which shrank
  `forEachChild`'s three (JIT.1) partitions **9,256 -> 5,929 bytecodes (-36%)**.

- [x] **(WARM.33) reach-machinery (b), transpose the 43 per-file memos — REFUSED, round 906, AND THE
  CANDIDATE IS A REGRESSION AT EVERY GEOMETRY.** `docs/perf/reach-memo-transposition-price.md`.
  **The whole memo-LAYOUT direction is closed**: the ceiling for ANY layout is **2.65-15.99 ms**,
  below the floor at every cache geometry, and shrinking the cache makes the candidate worse rather
  than better. **Round 875 had the SIGN wrong** — it read the ascent's scatter onto the probe's
  sequential sweep; measured, **42.2% of ascent steps go to `nodeId - 1`, 89.8% stay within 64 ids**,
  the spine walks in PREORDER so each 1-byte array is swept sequentially, and **layout A already
  answers 97.0% of accesses out of L1** (a line serves ~14.2 consultations against a transposed row's
  ~3.8). **Round 875's queued instrument could never have decided it**: an amplifier repeats one probe,
  so from the second repetition the line is L1-hot — *a locality change cannot be amplified*, and the
  round that priced it contains no clock at all, only a census plus a set-associative LRU model.
  Also corrected: this entry's own "deletes 36.9 MB/rebuild" deletes **55 KB of array headers** —
  43 arrays of n bytes and one of 43n are the same bytes. Adjacent direction closed with it: lazily
  allocating the 17 classifiers consulted <1,000x/rebuild is worth ~2-3 ms.

- [x] **(WARM.34) `lexLevelHasName`, the COUNT question — REFUSED by its own census, round 907, AND
  THE WHOLE FAMILY IS NOW CLOSED.** `docs/perf/lex-ascent-count-price.md`. **The queue's premise was
  wrong**: "an O(depth) ascent revisiting the big outer levels" describes the CHAIN (3.69 steps),
  not the PROBES (**1.544** per ascent), because 58% of level visits are refused by the untrusted /
  non-head-fn rules or are hash-free EMPTY maps — *a chain-step population is not a probe
  population*, round 902's law one step along its own family. **563,466 ascents / 870,231 real probes
  = 31.85 ms = 0.602% is the ceiling on EVERYTHING here.** The 80.7% redundancy is real and does not
  help: a repeat ascent performs **1.32** probes and a memo probe replaces them with **1**, so the
  queued ascent memo is **2.42 ms net, 9.92 ms even if free, and −10.7 ms at the measured probe
  cost — a regression**. A per-level memo is refused BY CONSTRUCTION (*a cache keyed by the same name
  at the same granularity as the map it fronts IS that map*), and a per-file absence filter is
  <= 7.30 ms. **Closure is now GENERAL, not per-lever: any one-operation oracle costing one probe
  recovers at most 0.21%.** Container closed by 901 (+0.26%) and 902 (−0.19%).

- [x] **(SPINE.1) The six spine handlers' frame bookkeeping — REFUSED AND CLOSED, round 908.**
  Denominator re-taken: **5,050 ms** (8 probe-free warm process medians), so 1% = 50.5 ms. The six
  are still 62.6% of the probed spine and **40.1% of the rebuild**, but round 733's deflation,
  MEASURED rather than applied (and with `SpineSections` run WARM for the first time), says the
  passes' own checking work is **91.4%** and every frame pop and restore is at or below one probe
  boundary — five of eleven sections read NEGATIVE once their boundary is subtracted. **Nothing
  clears the floor**: the three ancestor climbs are 19.6 ms (0.39%, refused again), the cta
  frame+ambient install 16.0 ms and load-bearing, the cta eligibility gate 14.4 ms with round 888's
  mask having already taken **87% of its population**. **The one row above 1% — 79.8 ms of
  frame-ambient install — has a ~8 ms deletable population** (the rebuild walks 2.91 frames, produces
  nothing on 91.4% of installs, and the save copies ZERO entries on 100% of 147,572) **and fails its
  own division by ~20x, because a timestamp is an OPTIMIZER BARRIER.** Round 847's per-handler ms are
  superseded — they were against 8,095 ms — and the order swapped again (`ccetSpineLeave` #1 -> #3,
  −51% in ms, while `cpaSpineLeave` fell 5% in ms and ROSE 7.62% -> 11.56% in share: round 830 live).
  **Caveat for any successor: the `dispatch` tier bypasses `spineEnterMask`, so that table prices the
  pre-888 regime and is blind to the lever the region already banked.**

- [x] **(WARM.35) The four round-903 hot-path candidates — ALL REFUSED, round 912, AND THE QUEUE'S OWN
  POPULATION FOR THE LARGEST OF THEM WAS A TRANSCRIBED SOURCE COMMENT.**
  `docs/perf/round912-candidate-census.md`. Priced by census plus round 896's divide-and-refuse —
  **no fix built, no amplifier needed**; both census processes agree to the last digit on all 22
  counters and `mappedNodeTypeKey calls = 110,780` reproduces `cost-counters.txt`'s
  `typeNode.bypassed` exactly, which is a second independent control. Against the stated 5,242.6 ms
  denominator (1% = 52.4 ms, the ~17 ms floor = 0.324%):
  **`mappedNodeTypeKey` key build — 25,987 keys of 110,780 calls = 9.36 ms = 0.179%, refused by
  1.8x**; **`narrowTypeFromFlow`'s default-arg `NarrowFlowMemo` — 31,768 = 4.77 ms = 0.091%, by
  3.6x**; **`collectTypeofGuardNames` &c `LinkedHashSet` — 22,798 = 1.48 ms = 0.028%, by 11.5x**;
  **`spineOsWithAmbient` / `spineTcDispatchWithAmbient` — 2,841 = 0.28 ms = 0.005%, KILLED BY READING,
  by 60x**. **ALL FOUR TOGETHER are 15.9 ms = 0.303%, still under the floor for ONE low-risk change.**
  To reach 17 ms they would need **654 / 535 / 746 / 5,983 ns per operation**, against a measured
  **15.09 ns** for a whole `HashMap` get that recursively hashes AND `equals` a 2.76-node AST subtree
  (round 903). **DO NOT RE-RAISE ANY OF THE FOUR.** Three mechanism findings outlive the prices:
  **(a)** the "~88 k/rebuild" this queue attached to `mappedNodeTypeKey` **was never a measurement** —
  it is a transcribed KDoc that is itself 26% stale (real call count **110,780**) applied to the wrong
  quantity (only **25,987**, 3.4x fewer, build a key; 76.5% exit at the foreign-file gate first), so
  the entry was wrong in both directions at once; **(b)** candidate 3's `inline` **is not expressible
  in Kotlin** — both wrappers hand `block` to a RECURSIVE non-inline callee, so `inline` forces
  `noinline`, which re-materialises the lambda, i.e. a candidate can be dead on grounds of the
  LANGUAGE before any population is counted, and reading the CALLEE rather than the wrapper is what
  shows it; **(c)** candidate 4's obvious shared-memo fix is a **SOUNDNESS bug, not merely a small
  prize** — `narrowTypeFromFlowCore` handles re-entrant walks at `narrowLiveDepth == 0` by design, so
  a shared instance would be cleared under a live outer walk and a wrong serve there is a WRONG
  NARROWED TYPE; and **34.2%** of memos outgrow 32 slots, so `clear()` is not obviously cheaper than
  the allocation (round 899: price a container swap NET). **NEW REUSABLE CONSTANT, the allocation twin
  of round 904's ~1.7 M map-ops bar: a pure-allocation candidate needs > 113,000 allocations/rebuild
  at a generous 150 ns, or > 340,000 at a realistic 50 ns, to clear the ~17 ms floor** — which refuses
  most per-node allocation candidates by arithmetic, the whole spine visiting 856,962 nodes.
  **AND THE ONE THING THE AUDIT NEVER NOTICED, still under the floor:** `mappedNodeTypeKey` spends
  **110,780 parent-chain climbs plus 110,780 `String`-keyed map probes (~5.5 ms)** so that 76.5% of
  calls can answer "foreign file" — comparable to the named mechanism, and structurally required by
  the gate; the WHOLE function at these generous rates is ~15 ms, still under the floor.

**SUCCESSOR, PER THE WORK ORDER NOTE ABOVE — a refusing round must name one.** With round 908 closing
the spine side and round 912 pricing the audit residue, **the checker-side pool is empty in the
literal sense: nothing checker-side is left unpriced.** **The successor is the (API.\*) arc, whose
next unchecked item is (API.3b) go-to-definition, with (API.3c) — batching a whole file's spans into
ONE build — as the item that makes the API practical for an editor.** The remaining PERF levers are
ARTIFACT-level and **both are gated, which a next agent must not rediscover**: (ART.1) is gated on the
owner's RELEASE decision and not on engineering (`native.yml` already builds Oracle + PGO and verifies
byte-identity), and (ART.2) is gated on a **CRaC JDK that is NO LONGER INSTALLED on this box**
(`/usr/lib/jvm` holds Zulu 26 and OpenJDK 25; `~/jdks` holds 17 and 21 — none of them a CRaC build), so
neither its `afterRestore` cwd fix nor a re-measurement can be compiled or verified locally.

**THE SEARCH STATE, AFTER SIX CONSECUTIVE REFUSALS (rounds 903-908), AMENDED ROUND 912 — READ THIS
BEFORE PICKING THE NEXT CANDIDATE. THE CHECKER-SIDE POOL IS NOW EMPTY, AND SINCE ROUND 912 IT IS EMPTY
OF UNPRICED CANDIDATES TOO.** 903 refused at 0.085%, 904 at 0.334% (14 sites TOGETHER), 905 at 0.074%, 906
measured a REGRESSION and closed a whole direction, 907 refused by census and closed a family. **Every
candidate ranked off the JFR profile in this arc has come in 2-21x over when measured — nine of ten
in the recorded scoreboard, six of six this session.** Meanwhile 61% of the warm rebuild is
unclassified residue, **no single JFR row is above 1.81%**, and the box cannot resolve below ~1.5%.
**That is what an exhausted search looks like.** It is not a failure — the compiler is -10.5% over
rounds 887-898 and warm xtsc is 2.05x tsc check-only — but a sixth single-row candidate should be
justified against this record rather than picked off a profile.

**THE MEASURED LEVERS THAT ARE *NOT* EXHAUSTED ARE AT THE ARTIFACT LEVEL, AND THEY ARE AN ORDER OF
MAGNITUDE LARGER THAN ANYTHING LEFT HERE.** Both are already measured, not speculative:

- [ ] **(ART.1) Ship the PGO'd native image. -21.2% check-only / -19.1% emit**, 5/5 paired in both
  modes, 46 diagnostics and all 78 emitted `.js` byte-identical (`docs/perf/aot-native-image.md`
  § 10). Needs Oracle GraalVM (`-graal` in SDKMAN; CE's `native-image --help` does not mention the
  word) and an `.iprof` trained on BOTH modes — a check-only-only profile leaves the
  Transformer/Emitter on static heuristics. This is the biggest single lever ever measured in this arc.
  **CORRECTED round 909 — the entry's premise ("CI currently ships the Community Edition arm, which
  has no PGO at all") IS STALE AND MUST NOT BE RE-INHERITED:** `native.yml:60-72` already builds
  **Oracle + PGO** via `scripts/build-native-pgo.sh`, verifies byte-identity against the JVM and
  uploads `xtsc-linux-x64`; `bench.yml` builds the Oracle **BASE** image per push deliberately (the
  PGO cycle is too slow to pay per push for a column that is not the headline). **So the engineering
  exists and what remains is the SHIPPING decision — attaching the binary to releases, already tracked
  as (AOT.1) and explicitly the owner's** (`native.yml:8`). Also **not measurable on the dev box: no
  GraalVM is installed there** (Zulu 26 / OpenJDK 25 only), so any re-measurement is a CI job or an
  install first.

- [ ] **(ART.2) CRaC — ~30 ms restore at FULL WARM SPEED** (6.8-7.3 s against 24-25 s cold, 3.4x,
  output byte-identical bar the `time:` line; `docs/perf/crac-checkpoint.md`). **Blocked on one known
  defect, not on the mechanism**: the restored process keeps the CHECKPOINT's working directory —
  round 873's bug one layer down — so a CRaC CLI must re-install the real cwd through
  `SystemVfs.workingDirectory` in an `afterRestore` hook, exactly as `CompileServer` already does per
  request. Unmeasured risk: the 340 MB image was page-cache-hot in every restore taken so far.
  **CORRECTED round 912 — AND THIS IS ALSO A LOCAL-TOOLING BLOCK, NOT ONLY A CODE ONE: the CRaC JDK
  IS NO LONGER INSTALLED ON THIS BOX.** `/usr/lib/jvm` holds Zulu 26 and OpenJDK 25 and `~/jdks` holds
  17 and 21 — none of them a CRaC build — so neither the `afterRestore` fix nor a re-measurement can
  be compiled or verified locally; it needs a Zulu CRaC install (or CI) first. Do not rediscover this
  by writing the hook and finding nothing to run it on.

**THE ROUND-903 HOT-PATH AUDIT'S FOUR UNPRICED CANDIDATES ARE NOW PRICED AND ALL FOUR ARE REFUSED —
see (WARM.35) above, and do not re-raise them from this block's former wording** (both copies of it
are collapsed into that entry; the record it stood on, "~88 k/rebuild", was a transcribed source
comment rather than a measurement).

**CLOSED IN ROUND 903, DO NOT RE-RAISE** (round 903, `docs/perf/type-node-key-price.md`): the
`nodeTypes` deep AST-value key, **refused at 0.085%** — its premium over a `(file, nodeId)`
`LongKeyMap` is 12.98 ns over 354,131 ops = 4.60 ms, and `A - B` is an UPPER bound. Round 896's
`nodeTypeResolutionInProgress` sentinel falls with it at 1.54 ms. The JFR row's other owner is
`isPerFileDependentRefNode` at 3.70 ms; family 9.04 ms against a 57.1 ms row.
