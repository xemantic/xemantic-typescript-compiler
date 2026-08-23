# Status

**HOVERING AROUND A FILE IS NOW FREE AFTER THE FIRST HOVER — A SECOND CARET IN `checker.ts`
WENT 2,142 ms -> 73, ONE IN `binder.ts` 481 -> 2, AND `fileSemantics` AFTER A HOVER 575 -> 17
(2026-08-23, (INC.13)).** (INC.12) memoized a capture on its REQUEST, so a repeated question
was free; every caret-scoped query except `documentHighlightsAt` asked about ONE span, so the
caret NEXT DOOR still paid the whole ~345 ms floor. `Project.captureAround` now asks about the
**BUFFER** — `SourceIndex.occurrenceNodes()`, deliberately `documentHighlightsAt`'s own
population — so `quickInfoAt`, `definitionsAt`, `semanticsAt`/`fileSemantics` and highlights
are **ONE build per buffer between them**. **The oracle was built FIRST and cost no baseline**,
which is the part worth copying: at a fixed partition, a span asked ALONE and the same span
asked as part of its file are the same question, so any divergence is a defect in one arm.
`scripts/caret-vs-file-capture.sh` reads **EQUIVALENT — 904 sampled spans in 76 files, zero
divergence in either channel** — and prices the widening at **+17 ms at the median file**
(373 -> 390), because a narrowed build is mostly FLOOR and extra spans are cheap beside it.
(INC.10)'s first-touch hazard does not fire, and the reason is stated: a capture changes WHERE
a walk records, not WHEN the compiler resolves a declaration. **The trade is not hidden — the
FIRST query in a buffer gets dearer, +27% on `binder.ts` and +65% on `checker.ts`, so
break-even is the SECOND caret**; it was NOT gated on file size, because a size heuristic is a
guess where the differential is a measurement. **It does NOT widen for a caret on a node that
is no occurrence** (a call expression, a literal, a `this`): a file-wide request would not
carry it and an absent capture renders nothing with no error anywhere. **The receipt is a
COUNT — N carets in one buffer is ONE build, pinned from a FRESH state.** Three ablations
(never widen -> 4 RED; widen unconditionally -> 2 RED including an independent hover control;
the shared population drifts -> 1 RED **only after the fixture grew a member-name literal — it
read 0 RED first, and the pin was BLIND, not the invariant redundant**). Three public claims
inverted in place, including the **34x batching ratio `docs/language-service.md` advertised to
hosts, which is GONE** — batching a buffer is a convenience now, not a cost decision. Suite
**15,683 / 0 / 3**, `cost_gate.py` PASS (+1.02% `mapped.hits`, the same pre-existing drift and
the expected answer for a round that changed no compiler code), `huge_methods.py --fail-over 0`
green on core (755 classes) and `-project` (49), `partition-equivalence` **EQUIVALENT on all 78 files** (median narrowed query 385 ms, floor 365, ratio 12.60x — a redraw of the same compiler, which this round did not touch), and both capture censuses **unmoved at their baselines** (5 spans / 3 files, `narrowRendersMoreAny=0`; 286 rows / 49 files, members=285 scopes=0 signatures=1).

**A REPEATED LANGUAGE-SERVICE QUESTION NOW COSTS NOTHING, AND THE REST OF THE WARM PROGRAM IS
PRICED (2026-08-22, (INC.12)).** Measured first: **(P1) — a second query with the program
UNCHANGED — is worth the WHOLE ~345 ms floor** (config+crawl+imports ~12 ms, BIND 73-88, the
~190 program-wide `init` passes 252-254), against a queried file's own checking of **40 ms at
the median file**. **(P2) — a query after ONE buffer changed — measured IDENTICAL to (P1)**
(`diagnosticsOf` after editing the queried file 2,001 ms against 1,999 unedited; about another
file 498 against 505), because outside the content-keyed parse cache and `diagnosticsOf`'s
exact-question memo there was NO cross-query reuse at all — re-asking one hover cost a full
build. **LANDED: `Project.captures`**, a capture build memoized on its REQUEST, two entries,
access-ordered, dropped by every edit alongside the diagnostics cache. Two of the editor's
commonest sequences turn out to BE the same question asked twice, and neither is
special-cased: **hover then go-to-definition at one caret build an IDENTICAL
`TypeCaptureRequest`** (506 ms -> **0**), and **`documentHighlightsAt`'s request is derived
from the FILE's occurrence nodes and not from the caret at all**, so highlights at every later
caret in an unchanged buffer is free (592 -> **19**, the residue being the per-caret grouping,
not a build). A repeated hover is **1,933 -> 0**. Three ablations, each reddening a different
pin set; the staleness obligation is pinned in both directions, including the one where a
mis-keyed hit is a MISSING FILE — an edit that ADDS A FILE to the program. **REFUSED with the
measurement:** reusing the BIND (73-88 ms = 20% of a median query; not refused by (INC.9)'s
per-file argument, but it needs a program-SHAPE gate reusing the checker's own merge predicate
and a full-vs-reused differential — (INC.13)); and reusing the CHECKER (**252-254 ms = 63%,
the largest thing left**), which would make WHICH QUERY RAN FIRST observable for every later
query, against `symbolTypes`' first-resolution persistence that (INC.2)/(INC.5)/(INC.6) spent
three rounds on — (INC.14), and its first step is the differential, not the refactor. Suite
**15,681 / 0 / 3** (+7 pins), `partition-equivalence` **EQUIVALENT on all 78** (median query
382 ms, floor 342, ratio **13.15x**), `cost_gate.py` PASS (+1.02% `mapped.hits`, the same
pre-existing drift), `huge_methods.py --fail-over 0` green on core and `-project`, both
capture censuses unmoved (5 spans / `narrowRendersMoreAny=0`; 286 rows / members=285 scopes=0
signatures=1).

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
first-mint ordering, and 83% of that ordering (2,722 -> 462 spans) is
recovered for **6.81 ms** by keeping the TYPE ALIASES eager. The residual 462 is undiagnosed
and runs the other way round, so it may not be a display question at all. Queued as (INC.11),
against a free differential oracle: the full and narrow capture arms must agree.

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
