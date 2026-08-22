# Status

**THE SECOND-LARGEST ROW IN THE INCREMENTAL FLOOR WAS EMIT-ONLY WORK AND NOW RUNS ONLY WHEN
THE EMITTER ASKS; THE LARGEST IS REFUSED WITH A THREE-POINT MEASUREMENT (2026-08-22,
(INC.10)).** `init:trackAllImportReferences` was **29.44 ms of a 305.3 ms floor pass table**,
and its whole product — `referencedAliases` — has ONE reader, `isReferencedAliasDeclaration`,
which has ONE caller: a single line of `Transformer` reached only by `import x = require(…)`
under `module: preserve`. Round 738's `skipEmitOutputs` gate means a `--noEmit` build never
constructs a transformer, so every language-service query filled a set nothing could read. It
now runs on the first ask: **pass table 305.3 -> 274.8 ms, narrowed query median 422 -> 402,
ratio at the median file 12.43x -> 12.61x**, and the walk count goes **78 -> 0** on both the
floor and a full `--noEmit` build. Deferring beat gating on `skipEmitOutputs` for a testing
reason: the corpus EMITS, so thousands of `.js` baselines now exercise the deferred path on
every suite run. **The banked ms EXCEEDS the row (30.5 vs 29.44) — the first time the (INC.7)
relocation discount has not applied, because this walk resolves nothing and there is no
memoized work to relocate.** **`init:buildFileLocalTypeMaps` (66 ms) IS REFUSED, and it was
BUILT before it was refused.** The deferral works and is cheap — 78 -> 3 maps on the floor arm,
row **66.07 -> 0.01 ms**, query median **349**, ratio **14.17x**, `partition-equivalence`
EQUIVALENT on all 78 files, cost gate and corpus unmoved — and it moves the CAPTURE channel
from **5 divergent spans to 2,722 in 46 of 76 files**. Every divergence is a DISPLAY one, in
both directions, and the mechanism is `aliasDisplayMap`: an alias name attaches to an interned
type at its FIRST mint, so resolving every file's declarations up front is what makes type
display a function of the program rather than of who walked first. Keep the `TypeAlias`
symbols eager and it is 6.81 ms / 462 spans; keep the whole DECLARATION branch eager and it is
**64.94 ms / 5 spans** — **the deferrable part is 1.13 ms of 66**. Round 829 censused this pass
as *1,499 of 4,161 entries ever read*; read-ness of the ENTRY was the wrong question, because
the pass's second product is the ORDER. Suite **15,674 / 0 / 3** (+8 pins), `cost_gate.py` PASS
(largest delta +1.02% `mapped.hits`, the same pre-existing drift), `huge_methods.py
--fail-over 0` green on core and `-project`, both capture censuses unmoved (5 spans /
`narrowRendersMoreAny=0`; 286 rows / members=285 scopes=0 signatures=1). **The tail is now
FLAT and worth saying so**: 416 rows, eleven over 5 ms, forty-six over 1 ms, and the remaining
**370 rows sum to 11.9 ms between them**. **And the 66 ms is not lost, it is BLOCKED ON ONE
THING** — the deferral is already built and its only casualty is `aliasDisplayMap`'s
first-mint ordering, so making alias display a function of the alias DECLARATION rather than
of who minted first banks the whole 66 ms (query median 402 -> 349, ratio 14.17x) against a
free differential oracle. Queued as (INC.11).

**A NARROWED ERROR QUERY IS DOWN TO 422 ms AND ITS FLOOR TO 378 — A FILE'S FLOW GRAPH IS
NOW BUILT ONLY WHEN SOMEBODY ASKS FOR IT (2026-08-22, (INC.9)).** The floor was re-decomposed
rather than scaled, and the ranking had moved: of ~523 ms, the ~190 surviving `init` passes are
**304 ms (58%)** and BIND **198 (38%)** — so bind is not the largest COMPONENT, but it holds
the largest single MECHANISM, `FlowGraphBuilder.build` at **126 ms = 24% of everything a
narrowed query costs**, against a pass table whose biggest row is 66 ms. At the floor nothing
reads those graphs at all. `BinderResult.flowGraph` now builds on FIRST ASK: **floor 514 -> 378
ms, median narrowed query over tsc's own 78 sources 542 -> 422, ratio at the median file
9.70x -> 12.43x**, `scripts/partition-equivalence.sh` EQUIVALENT on all 78 files, and no
whole-program measurement moves by construction — a full build asks for every checked file's
graph anyway, which is why no corpus baseline can move. **THIS IS THE CANDIDATE ROUND 865
PRICED AND REFUSED**, at *52 of 123 files, 0.3% of the mints*: a correct number about a FULL
build, where the same rule reaches 122 of 123 files under a partition. A cost prior does not
transfer across REGIMES any more than across families. Laziness had to be defer-and-build and
never omit (a missing flow node is a false positive, not a slow answer), and it is sound
because `FlowGraphBuilder` is a pure function of the `SourceFile`; `lazy` rather than a
nullable field is load-bearing, since `CheckerPool` and `--shareBind` hand one `BinderResult`
set to several threads. **REFUSED with the measurement in the same round: a cross-query BIND
CACHE.** All of bind is now 72 ms of a 378 ms floor, and against that ceiling every
`BinderResult` from one `Binder` shares its `(pos,end)`-keyed `nodeToSymbol` map — keys that
collide across files — while `mergeSingleSymbol` adopts binder-owned symbols and
`declarations.addAll` is not idempotent. **The receipt is a COUNT and it reaches full builds
too**: flow graphs built go **123 -> 0** on the floor arm and **123 -> 78** on a full build —
the 45 that vanish are the real-lib `.d.ts` files, bound with a flow graph on every compile
this repo has ever run and read by no consumer, ever. Suite **15,666 / 0 / 3** (+11 pins),
`cost_gate.py` PASS (largest delta +1.02% `mapped.hits`, the same pre-existing drift batch 3
recorded), `huge_methods.py --fail-over 0` green on core AND the `-project` module, and BOTH capture
censuses unmoved (5 spans / `narrowRendersMoreAny=0`; 286 rows / members=285 scopes=0) while
their own timing arms improved with everything else — the narrowed capture median is
**556 -> 420 ms** and `binder.ts` warm rotated **7.51x -> 8.31x**.

**THE INCREMENTAL FLOOR IS DOWN TO 514 ms AND A NARROWED ERROR QUERY TO 542 — 68 TAIL
WALKERS NOW RUN ONLY OVER THE FILES THEY WERE ASKED ABOUT (2026-08-22, (INC.7) batches
1-3).** 376 of the ~400 program-wide tail walkers iterated `binderResults`, so they cost the
same whether the checker checks 78 files or none — 66% of the floor. Moving a walker's loop
onto `checkedResults` is a **strict no-op for every full build and therefore for the whole
corpus** (`checkedResults` IS `binderResults` when `assignedFileNames == null`), so it can
only change what a PARTITION does; and a partition reports nothing about files it was not
asked about, so a pure EMITTER skipping them loses nothing observable. Batch 3 gated **45**
more: floor **789 -> 514 ms** (PLAIN arms 883 -> 548 and 808 -> 506), narrowed query median over tsc's own 78 sources
**818 -> 542 ms**, ratio at the median file **6.17x -> 9.70x**. Whole arc: floor
1,207 -> 514, query 1,077 -> 542. **THE DISCOUNT IS NOW MEASURED THREE TIMES AND SHRANK
EXACTLY AS PREDICTED — 79.0%, 85.5%, 92.9%** of each batch's naive row sum, because
relocation lands on ungated tail walkers and the more of them are gated, the more of the
moved work is never asked for at all.

**THE ROUND'S TWO NEGATIVE RESULTS ARE WORTH MORE THAN THE ms.** 33 of the 252 ungated
`binderResults` loops are **not passes at all** but lookup helpers that scan the program to
find a declaration (`resolveIdentifierInFile`, `findTypeParamDeclByName`, …) — all 0.00 ms,
and gating one breaks NAME RESOLUTION rather than dropping a diagnostic, which is exactly
what a mechanical `sed` over the loop header would have done. And the victim heuristic
(batch 2's relocation victim is batch 3's candidate) MISFIRED:
`checkBaseClassImprovedMismatch` is a REWRITER that can never qualify, and its 17.89 ms was
an inherited lazy `SourceScanFilter` build — it was the first walker to ASK, not the walker
that COSTS. Suite **15,655 / 0 / 3**, no corpus baseline moved,
`scripts/partition-equivalence.sh` EQUIVALENT on all 78 files after every one of the three
sub-batches, with a baseline sweep taken at HEAD first so the after-runs are attributable.

**THE LANGUAGE SERVICE ANSWERS *EVERY* EDITOR QUERY AS A PARTITION NOW — THE ERROR QUERY AT
~5.8x AND THE CARET QUERIES AT ~4.7-4.9x (2026-08-22, owner directive: make it incremental
enough to carry an IntelliJ plugin).** **(INC.2b)** wired hover, go-to-definition,
completion, signature help, the semantic sweep and document highlights to the same seam:
end to end through the API, `quickInfoAt` is **5,004 -> 1,015 ms**, `fileSemantics`
5,178 -> 1,185 and `documentHighlightsAt` 5,050 -> 1,159, while `referencesAt`,
`renameAt` and a plain rebuild do NOT move — they are the queries left whole-program,
because their claim is about every file, and they are the controls that say the deltas are
the change rather than drift. The partition is DERIVED from the capture request's own
spans, which is what makes the pins discriminate at all: narrowing's failure mode is an
ABSENT answer, not a wrong one, so a call site that never states the file set cannot
forget a file it asked about. **Nothing was ever absent, in 402,000 captured spans across
both gates.** ORIGINAL HEADLINE: `Project.diagnosticsOf(fileNames)` hands the file set to the compiler as its
CHECK PARTITION instead of filtering a whole-program build: on tsc's own 78 sources
(9,977,097 chars) a whole-program build is **4,818 ms** and a narrowed query is now
**824 ms**, with every one of those 78 files reporting exactly the rows the full build
reports for it. The seam (`recheckOnly` -> `Checker(assignedFileNames)`, the INV.6 view
`--workers` uses) already existed and this module was passing null to it, because narrowing
was understood to need `--watch`'s reverse-dependency closure. **An editor's question does
not: it asks what is wrong in ONE buffer and claims nothing about the others.**

