# Architecture rethink — the M5 inversion arc (INV)

> **ROUND-732 CORRECTION TO § 0 — READ FIRST.** § 0 below concludes "the measured
> lever is consultation, not computation" and sizes (DISPATCH.1) at 1.0–2.5 s.
> **That is wrong, and it was measured wrong.** Round 732 derived the per-kind
> handler table by instrumentation and verified it over the whole corpus: the
> table removes **64% of the handler consultations and 4.8% of the time** —
> 883 ms of an 18.5 s spine as an UPPER bound, ~100–300 ms realistically.
> The error was inferring consultation overhead from "IDENTIFIER costs 2,746 ns
> and almost no handler wants it": in fact **22 of the 59 handlers genuinely act
> at an identifier**, because handlers keyed on PARENT edges, FRAME-owner
> identity and nodeId REGISTRIES cannot be closed by the node's own kind — and
> those are the expensive ones. The "skip `spineEnterNode` for bare Identifiers
> → byte-identical" probe skipped real work the compiler profile happens not to
> need. **The real shape: six handlers hold 71% of the spine** —
> `cpaSpineLeave` 4,366 ms, `ccetSpineLeave` 3,046 ms,
> `spineCtaM3StatementAnchor` 2,900 ms, `spineIanyEnterNode` 1,025 ms,
> `ccetSpineEnter` 920 ms, `ctaSpineEnter` 586 ms; the other 53 together are
> ~4.8 s. **ROUND-733 CORRECTION TO THAT LAST SENTENCE: it is NOT "frame
> bookkeeping".** Attributing INSIDE the top two (`--spineSections`) shows
> **88.4% of their 8.2 s is the cpa and ccet passes' OWN checking work**
> (`checkPropertyAccessInExpr`, `checkSingleCallExpressionTypes`); the ambient
> install+restore is 360 ms and the ancestor climbs — (SPINE.1)'s named target,
> predicted at 1–3 s — are **176 ms**. A handler's per-handler nanos are its
> WORK, not its scaffolding; "handler X is N ms" never licenses "X's
> bookkeeping costs N ms". What it really points at is
> `checkSingleCallExpressionTypes`: a 920-line function run in full for every
> one of 52,413 call expressions at **53.6 µs each = 2.9 s**, the largest
> per-node cost measured anywhere here. Full derivation:
> `docs/perf/spine-leave-attribution.md`; follow-on queue item: **(CALL.1)**.
> Stage 1 of § 0.1's staged plan is therefore NOT worth 11–19%, and stage 2's
> "DISPATCH.1 retroactively unlocks M0.4" does not follow — a migrated pass
> costs its own work either way.
> **ROUND-734 RESOLUTION OF THAT POINTER: `checkSingleCallExpressionTypes` is
> TYPE-SYSTEM WORK, not machinery — 78% of it (2,007 of 2,564 ms) is
> `checkArgumentsAgainstSignature` (1,357), `getCalleeType` (474), the TS2793
> impl probe (101), `checkArgumentsAgainstOverloads` (53) and
> `getCallSignaturesOfType` (19).** Everything else in the function — 18
> emission sites, their gates, spans and displays — is 557 ms including ~70 ms
> of probe, so the whole non-type-system prize is ≤490 ms, inside the ±2%
> drift band. **This third attribution therefore CORRECTS the table below in
> the other direction: the residual "dispatch + handler machinery ~7,600 ms
> (42%)" is not machinery — it is the migrated passes' own checking, and the
> type system is bigger than the 5,056 ms row says** (that row counts only the
> instrumented primitives, not the argument-check and callee-resolution code
> that calls them). The next lever is inside the relation/inference path
> (M3.1), reached via **(CALL.2)**: `checkArgumentsAgainstSignature`, 61 µs per
> call over 22,145 calls in a 1,534-line function. Full derivation:
> `docs/perf/call-expression-attribution.md`.
> **ROUND-735 RESOLUTION, AND IT REDIRECTS THE STAGED PLAN IN § 0.1: inside
> `checkArgumentsAgainstSignature` the argument TYPE computation is 924 ms of
> 1,624 ms while the whole `checkTypeRelatedTo`+TS2345 section is 19 ms — so
> the lever is NOT the relation engine either.** Of that 924 ms, **flow
> narrowing is 600 ms (37% of the function) and `getTypeOfExpression` only
> 196 ms (12%)**. Two consequences for the plan below. **Stage 3 ("cut the
> `getTypeOfExpression` ×2.7 recompute", up to ~9%) is NOT reachable through
> the call path**: this function types each argument exactly once and makes
> 5.3% of the compile's calls at the compile-mean 5.6 µs, so it is not a
> recompute site — finding the recompute needs the 701,736 calls attributed
> **by CALLER**, which no round has done. **Stage 4 (flow narrowing) is where
> the call path actually lands, and it now has a shape instead of a total:
> 394 of 70,037 walks (0.56%) cost 1,485 ms — 47% of all narrowing and 4.9% of
> a 30.5 s compile, the first single target measured above the ±2% band since
> round 731.** They do not trip, do not exhaust the 1,000,000-visit budget
> (1,601 arrivals each, max 19,515) and are 99.9% cold, so no cache reaches
> them; they run at **2,354 ns per flow-node arrival against a 372 ns
> all-walk mean**. Follow-on: **(CALL.3)**. Full derivation:
> `docs/perf/argument-check-attribution.md`.
> **ROUND-736 RESOLUTION — AND THE ARC'S FIRST LANDED WIN, `-4.53%` median with
> a 6/6 win rate.** The 394 monsters were neither a bigger graph nor expensive
> traversal: **they arrive at 1,900 flow nodes but only 214 DISTINCT ones —
> revisit factor 8.85 against 1.48 for a typical walk — and 51% of the whole
> narrowing population is `applyConditionNarrowing`** (1,858 ns × 759,784
> calls), which the tail hits disproportionately (`FlowCondition` is 41% of
> tail arrivals against 18% overall). The cause was one condition: the
> intra-walk memo `NarrowFlowMemo.served(id, depth)` answered only when
> `depth <= storedDepth`, so a node reached again by a LONGER path recomputed
> its whole antecedent subtree — **631,585 arrivals compile-wide, 426,753 of
> them at `FlowCondition` nodes, against 290,011 serves.** That condition
> guards exactly one thing (a deeper entry has less budget before
> `NARROW_MAX_DEPTH`), and that is decidable rather than approximable: an entry
> now carries the maximum depth its own subtree reached, and a deeper probe is
> served iff `depth + height < maxDepth`. Invocations −55%, arrivals −26% with
> DISTINCT UNCHANGED, `applyConditionNarrowing` calls −56%, the `>= 1 ms` tail
> 429 → 230 walks with −96% of its arrivals; `--listAll` byte-identical and
> four cost counters FELL. **Two lessons for this section. (1) "The cache
> cannot reach them" (round 735, correct about the INTER-walk memo) hid a memo
> failure one level down — when a cache measures as not helping, check whether
> a different cache at a different SCOPE is the one failing.** (2) The obvious
> follow-on — a pre-test skipping the 95.6% of `applyConditionNarrowing` calls
> that return their input unchanged — is priced at ~410 ms, INSIDE the band,
> because those identity calls cost 949 ns against 21,708 ns for the ones that
> narrow: **§ 0's law holds in a shape that is not a cache at all.** Follow-on:
> **(CALL.4)**. Full derivation: `docs/perf/narrow-walk-attribution.md`.
> **ROUND-737 RESOLUTION — § 0.1 STAGE 3 IS STRUCK, AND THE ESTIMATE'S OWN
> EVIDENCE WAS A DOUBLE COUNT.** The 701,463 `getTypeOfExpression` calls were
> attributed BY CALLER (`--typeOfExprCallers`; only the OUTERMOST call walks the
> stack, so a subtree is attributed to the handler that asked for it).
> **Stage 3's mechanism — "several handlers independently type the same node" —
> is CONFIRMED and pervasive**: 177 initiating sites, **45.2% of the 254,069
> typed nodes carry more than one origin** (modal count: three), and the ×2.76
> factor decomposes as **2.05× cross-handler × 1.34× recursion**, with
> per-caller factors of 1.00–1.11 (no handler re-types anything alone).
> **Its size is wrong by 3.2×**: a PERFECT per-node cache saves **823 ms
> (2.9%)**, single-visit discipline **670 ms (2.3%)**, the largest handler-pair
> merge **166 ms (0.58%)**, and the SOUND memo **46 ms** — against a ±2% band of
> ~590 ms. The estimate also leaned on "3,911 ms", which is `typeOfExprNanos`
> charging a subtree once per nesting level; **the true total cost of all
> expression typing is 2,439 ms = 8.5% of checker-init**, so 8.5% was always the
> ceiling. § 0's law appears here in a shape with no cache in it: the four
> biggest co-occurrence pairs by COUNT are 141,388 repeat typings worth 71 ms
> (0.5 µs each) while the biggest by TIME is 2,603 typings worth 166 ms (64 µs
> each) — **what is redundantly recomputed is what was already cheap.** No
> optimisation was landed. Follow-on: **(TYPE.2)** — the by-caller table's own
> pointer, `checkVarDeclAssignability` under `spineCtaM3StatementAnchor`
> (431 ms of typing over 11,933 initializers inside a 2,900 ms handler that no
> round has opened). Full derivation:
> `docs/perf/type-of-expression-attribution.md`.
> **ROUND-738 RESOLUTION — BOTH PRIORS FALSE, AND THE GATE COST IS NOW MEASURED
> ON A REAL HANDLER.** Inside `checkVarDeclAssignability`, **flow narrowing is
> 1 ms of 872 ms (0.11%)** and the **assignability relation 13 ms (1.5%)** —
> round 735 found the same relation prior wrong by 48× one function over, and it
> is now falsified in BOTH of the compiler's largest assignability sites. What is
> there instead: **12,960 of 15,116 invocations (86%) never reach an
> assignability check** — they are UNANNOTATED declarations whose whole job is
> `getTypeOfExpression(init)` plus a map write, **405 ms = 46% of the function** —
> so the function is two populations sharing a name, 12,960 at **34 µs** and
> 1,881 at **227 µs** (round 737's 36 µs was their mean). **The handler is
> 2,363 ms and 85% of it is four callees' own checking work**
> (`checkVarDeclAssignability` 891, `checkReturnAssignability` 615,
> `checkAssignmentExpression` 318, `walkFunctionBodiesInExpr` 181). Level A was
> opened on the HANDLER, so the eligibility decision and its parent-chain climbs
> are a ROW: **194 ms over all 856,976 nodes = 212 ns each**, plus 158 ms of
> ambient scaffolding — **together 1.2% of the compile**, the per-handler shape
> of round 732's own correction to (DISPATCH.1). Nothing landed: the one
> candidate (hoist the unannotated branch above the ~18-walker prologue) is worth
> **≈0** because every prologue walker already bails on `decl.type ?: return
> false`. What the prologue DOES show is a price for the "endgame" paragraph
> below: **265 ms of FP-firewall walkers against the 19 ms relation they exist to
> correct, 14×.** Full derivation: `docs/perf/var-decl-attribution.md`.
> **ROUND-738 PART 2 — § 0.1 STAGE 5 IS MEASURED, AND HALF OF ITS "20%" WAS NOT
> FRONT END AT ALL.** The front end is **11.0%**: crawl WALL 1,683 ms (5.4%,
> already containing the read, decode and PARSE of all 9,977,097 characters,
> 16 in flight), bind 1,622 ms (5.2%), config 102 ms, `extractRelativeImports`
> 17 ms, and the core's own parse loop **0 ms — 78 of 78 pre-parses reused**.
> **There is no lever in it.** The other 9.2% of the never-measured region was
> `Transformer.transform` + `Emitter.emit` producing JavaScript a `--noEmit`
> build threw away (`noEmit` was consulted only where outputs are WRITTEN):
> **2,623 ms of 31,235**. Gating it (a new `skipEmitOutputs`, set only by
> `ProjectCompiler` — never the `@noEmit` corpus directive that 440 tests use)
> measured **−11.42%, B wins 6/6** — the arc's largest landed win, and a SCOPE
> correction rather than a speed-up: real `tsc --noEmit` does not emit either.
> ~~so every published xtsc-vs-tsc `--no-emit` ratio compared our check+emit
> against tsc's check-only; the honest gap is ~2.15×, not 2.4×.~~ **RETRACTED,
> round 739 — see § 0.2. There was no published `--no-emit` ratio: the CI 3-way
> ran EVERY compiler with emit, so the 2.4× was already like-for-like and this
> change does not move it.** § 0.1's
> staged plan as a whole is now: stage 1 ≈0.3–1% against 11–19%, stage 2's
> premise VOID (it was priced as "stage 1 unlocks it"), stage 3 STRUCK, stage 4
> the only one that paid (−4.53%, ~2% left), stage 5 measured and empty — so
> **"~1.4–1.7× of today" is not supported; the plan's remaining honest value is
> single digits.** Full derivation and the three remaining routes:
> `docs/perf/front-end-attribution.md` § 5.
> **ROUND-755 RESOLUTION — STAGE 4 IS NOW EMPTY TOO, AND THE ITEM'S OWN DEFINING
> NUMBER HAD HALVED WHILE IT SAT IN THE QUEUE.** Round 736 left
> `applyConditionNarrowing`'s "33,307 genuinely-narrowing calls at 21,708 ns =
> 723 ms" as the largest unattributed number in the arc. Re-measured: **21,970
> calls at 20,085 ns = 441 ms** — the per-call cost is stable (−7.5%), the COUNT
> fell **34%** while the total call count ROSE 2.5%, downstream of (REL.1) and
> round 754 changing what the declared types are. *An item defined by a measured
> number must re-measure it before a round is spent inside it.* **The split:
> `narrowByCallPredicate` is 351 ms over 23,138 calls at 15,181 ns = 80% of a
> narrowing call, and the dispatcher's own residue is 9 ms = 2%** — so
> `applyConditionNarrowing` is not a function with an expensive body, it is a
> `when` that reaches one expensive callee, and that callee does type-predicate
> RESOLUTION (M3.1 work, not machinery). **Nothing landed, correctly**: 441 ms is
> **1.6%** against a band re-derived this session at **±2.0%** (5 null pairs:
> median −0.05%, range [−526, +569] on a 26,778 ms compile) — the whole
> population is smaller than one A/B pair's noise, and a perfect memo over the
> entire leaf is capped at 1.75%, in-band before it costs anything. Two
> corrections to round 736: its rejected "does this condition mention the name"
> pre-test is not merely in-band but **UNSOUND** (99,002 identity calls are the
> aliased-condition path, whose whole point is a condition that does *not*
> mention the reference; 1,873 of them narrow), and its "identity calls are the
> cheap tail" hid that **73% of the identity time is inside three leaves that
> RESOLVE something**. Full derivation:
> `docs/perf/condition-narrowing-attribution.md`.
> **ROUND-756 RESOLUTION — THE LAST UNOPENED REGION OF THE ARC, AND A CENSUS
> FINDING THE MILLISECONDS COULD NOT GIVE.** `walkFunctionBodiesInExpr` (round
> 738's 181 ms row, never opened) reproduces **exactly** — 28,940 openings,
> 6,280 ns each, all three digits — because its population is AST SHAPE, not
> declared types. Partitioned by a new **level D** in `CtaSections`, the arc's
> first RECURSIVE partition (levels A–C's `depth != 1` shape would charge the
> whole descent to the dispatch row; `beginD` hands the caller's running row
> back and `endD` reopens it, so every row is SELF time). **Net of a
> 168 ns/boundary charge: the walk itself 65 ms (36%), `calleeDeclaredCtxParams`
> 49 ms (27%), `checkFunctionBody` for arrows 49 ms (27%) and for function
> expressions 16 ms (9%)** — sum 179 against the 181 measured independently, and
> level D's outermost invocations are 28,940 = level A's row openings exactly.
> **So the work the function is NAMED for is 36% of it.** The walk visits
> **199,131 nodes to reach 1,510 function-like ones (0.76%)**, only **636** with
> a block body, and those bodies cost **61–131 µs each** — so round 738's aside
> ("the bodies it walks are mostly already walked") is wrong in mechanism: **the
> walk spends more finding them than checking them.** **THE ARM CENSUS FOUND A
> FALSE NEGATIVE**: 874 of the 1,510 (58%) are EXPRESSION-BODIED ARROWS whose
> arm walks nothing, and "the spine anchors the inner statement anyway" was
> tested and is FALSE — `() => (function () { const s: string = 5; … })` is
> silent where tsc reports TS2322 (three controls fire). Queued as **(FN.1)**.
> Two zero-population findings in the same census: the whole B150/B585
> object-literal-method machinery is JS-gated and **never runs** on tsc's `.ts`
> source despite 755 objlit arm entries, and `invocationsDOutside == 0` — the
> walker has exactly ONE live entry point. **NOTHING LANDED: 181 ms = 0.68%,
> largest row 0.24%, band ±2.0% — deleting the whole walker is a third of one
> noise band.** Method note for the next round: **the ON-vs-COARSE differential
> failed here** (Δ29 ms against a 26 ms within-mode spread, bounding the
> boundary only at 31–168 ns) — *a differential is only as sharp as the smaller
> of its two spreads*, and the usable calibration came from comparing BOTH modes
> against the probe-free binary. Full derivation:
> `docs/perf/walk-function-bodies-attribution.md`.
> **ROUND-757 CORRECTION TO THAT CENSUS, AND IT IS THE LENS FOR EVERYTHING
> BELOW.** (FN.1) landed the descent into an expression-bodied arrow's body —
> and the 874 arrows the census counted contain **SIX** block bodies between
> them, a **146×** over-estimate. Its own diagnosis: *a census that counts
> REACHED nodes answers "how often is this arm taken", not "how much is behind
> it"* — and the round had priced the fix by the first number while claiming the
> second. `--listAll` byte-identical, all 8,837 baselines unchanged.
> **ROUND-758 RESOLUTION — THE ARC AUDITED ITS OWN NUMBERS, AND THIS SECTION IS
> WHERE TWO OF THE FOUR FALSIFIED CLAIMS LIVE.** 57 load-bearing quantitative
> claims across § 0/§ 0.1 and the ten `docs/perf/` artifacts were classified
> POPULATION / FREQUENCY / TIME / RESIDUAL: **40 stand, 11 are stale or weak, 4
> are falsified, 2 are unverified. Three of the four falsified are a FREQUENCY
> spent as a POPULATION, and all three shrink — none grows.** **(1)
> `IDENTIFIER` is 44.5% of the NODES and 8.4% of the spine's TIME** (1,853 ms of
> 22,104), **ratio 5.3×** — so the "the measured lever is consultation" sentence
> below loses its premise as well as its inference, and the per-kind counter it
> came from **printed "enter+leave" while summing ENTER ONLY** (fixed; the
> corrected counter reproduces round 732's independent `--dispatchProbe`
> per-kind numbers to 0.2–5%). **(2) `getCalleeType`'s "half its results are
> thrown away" is 50.6% of the CALLS and 8–10% of the TIME** — 1,452 ns per
> discarded resolution against 16,491 ns per kept one, so the implied ~237 ms is
> **38 ms**, out by **6.2×**; (CALL.1) § 6's last forward-pointer closes as a
> measurement. **(3) The "dispatch + handler machinery (residual) ~7,600 ms
> (42%)" row is a SUBTRACTION that was given a NAME, and rounds 732/733/734 each
> measured a piece of it and found no dispatch — so § 0.1's parity row "remove
> ALL dispatch overhead → 66 units, 1.5×" should read `100 → ~99, 2.4×`, out by
> ~34×, and it is the FIRST step of the parity argument.** **"Single digits
> remain" SURVIVES and is better supported; NO parked item is revived.** The map
> was also re-measured for the first time since round 716 (§ 0's table, below).
> Full derivation: `docs/perf/claim-audit-round758.md`.
> **ROUND-759 RESOLUTION OF THE TWO ❓ ROWS — AND § 0's LAW GETS ITS FIRST
> COUNTER-EXAMPLE.** (AUDIT.2): `argument-check-attribution.md` § 3's "only 27%
> reach the relation, yet all 37,379 pay for the full `argType` computation" is
> **TRUE AND UNDERSTATED — the 72% that never reach the relation carry 89% of
> the argument-typing time, at 22,604 ns each against 7,134 ns for the 28% that
> do, 3.2× the WRONG way.** Round 758 predicted "< 40%" and round 759 predicted
> 35%; both were out by ~2.5× in the same direction — and the row's own total
> had meanwhile fallen **924 → 689 ms (−25%)** with the counts unchanged to the
> unit, so round 755's re-measure-first rule applies for the third time. Both
> predictions failed because both applied "the
> population you could skip cheaply is the population that was already cheap"
> where it does not hold. **The law is CONDITIONAL: it holds when the exit
> predicate and the cost share a CAUSE** (a resolution that fails fast, a
> narrowing that bails early). Here the predicate is a property of the PARAMETER
> (`isSimpleCheckableType`, foreign TPs) and the cost a property of the
> ARGUMENT, and complex parameters attract complex arguments —
> `getTypeOfExpression` is **6.1× dearer** for a non-relating argument (8,252 vs
> 1,344 ns) and **82% of the narrowing walks** are non-relating, both measured
> rather than subtracted. **Before invoking the law again, ask what SELECTS the
> population and what DRIVES its cost, and whether they are the same thing.**
> **No lever, for a reason unrelated to the size:** *paying for `argType` is not
> wasting it* — eleven blocks below consume the value, and the assignability
> relation is the CHEAPEST consumer in the function at 19 ms, which is the third
> independent finding (after 735's 48× and 738's 65×) that the relation engine
> is not where the call path's time is. The only arguably-skippable subset is
> 275 ms of narrowing = 1.0%, half a band. **(AUDIT.3): the globals-lookup
> population is measured — 36–71 ms = 0.13–0.26%, see the § 0 table note.**
> Predictions scored **2 of 6**, which is the healthy rate round 758 said its own
> 5-of-5 lacked. Full derivation: `docs/perf/claim-audit-round758.md` §§ 10–12.


*Written 2026-07-13 (round 490). Owner directive: "follow your intuition and rescope
towards reaching the overall goal", plus the owner's measured finding that
`Flow<String>` outperforms `Sequence<String>` and that reading/decoding files on
`Dispatchers.IO` while processing on `Dispatchers.Default` yields further gains —
adopt pull-based design where it fits. This doc is the design record for the
re-scoped M5 arc. Read it BEFORE working any INV/M5 queue item. The queue itself
lives in PLAN-PHASE-5.md § QUEUE.*

---

## 0. ROUND-716 CORRECTION — read this before §1

*Owner directive 2026-07-26: "do anything needed … to increase the performance. We
are free to completely redesign this project." Round 716 answered the sizing
question this document has been assuming rather than measuring, and **the answer
overturns §1's diagnosis**. §1–§5 are kept for the record; where they conflict with
this section, this section is the measurement.*

**§1 says the cost is "uncached type recomputation". It is not.** Full attribution
of a compiler-profile run (`--passTiming`, new INV.4(g)/INV.5(c5) counters):

**RE-MEASURED ROUND 798** (2026-08-02, HEAD `750849af`, **median of 3 probe-free
`--passTiming` runs**; the 758 column was HEAD `7d49c910`). CLAUDE.md's own
standing warning is why: *"the absolute figures here go stale fast — re-measure a
number before spending a round inside it"*, and five landings (rounds 793–797)
had moved them. The 716/758 columns are kept because queue items quote them; the
**798 column is the live one**. **Read the two flagged rows with their caveats —
they are the audit's ❌ rows** (`docs/perf/claim-audit-round758.md` § 4).

| | 716 ms | 758 ms | **798 ms** | share of checker-init |
|---|---:|---:|---:|---:|
| whole compile (wall) | — | ~26,500 | **29,304** (28,501–30,002) | |
| checker-init | — | 26,200 | **25,557** (24,746–25,934) | |
| `checkSpine` | 14,292 | 22,104 | **21,578** | **84.4%** (73.6% of the compile) |
| — `spineEnterNode` | 7,166 | 11,155 | **11,942** | |
| — `spineLeaveNode` | 5,478 | 8,046 | **6,705** ⬇ −16.7% | |
| — unresolved-names family | 840 | 1,223 | **1,233** | |
| — `forEachChild` | 255 | 557 | **532** | |
| — scope maintenance | 25 | 53 | **53** | |
| **the INSTRUMENTED type-system rows** ⚠️ | 5,056 | 6,907 | **5,536** | **21.7%** |
| — flow-narrowing walks | 2,437 (69,917) | 2,290 (71,414) | **1,158 (17,851)** ⬇ **−49% ms, −75% walks** | |
| — `getTypeOfExpression` ⚠️ **double counts** (round 737: charges a subtree once per nesting level, ×~1.6) | 1,804 (624,810) | 3,254 (709,357) | **3,127 (649,410)** | |
| — relations (depth-0) | 468 | 685 | **661** | |
| — type-node resolution (depth-0) | 311 | 580 | **491** | |
| — member resolution | 36 | 98 | **99** | |
| **the ~400 tail passes** | *"14 units", § 0.1* | 3,130 | **3,140** | **12.3%** (10.7% of the compile) |
| `outside-pass` (init work in no `pass()`) — **round 802: 636 ms of it is ONE function** | — | — | **975 → 144** | 3.4% → 0.6% |
| **front end** (wall − checker-init) | — | — | **3,755** | (12.8% of the compile) |
| ~~**dispatch + handler machinery (residual)**~~ ❌ **A RESIDUAL THAT WAS GIVEN A NAME** — rounds 732/733/734 each measured a piece of it and found NO dispatch; real dispatch is 100–300 ms. It is the migrated passes' OWN CHECKING WORK | ~7,600 | ~12,300 | **~13,100** | **51%** |

**What actually moved since 758, and what did not.** The one structural change is
**flow narrowing**: 71,414 walks → **17,851** (−75%) for 2,290 → **1,158 ms**
(−49%) — rounds 736, 755, 790 and above all 796's already-relates argument gate.
`getTypeOfExpression` calls fell 709,357 → 649,410 (−8.5%) and `spineLeaveNode`
6,705 (−16.7%), while `spineEnterNode`, the tail passes, the node count and the
per-kind concentration are FLAT. **The wall did not move with them** — 29.3 s
here against ~26.5 s recorded at 758 — which is a statement about the box and the
`--passTiming` load, not about the compiler; the counters are the comparable
quantity (COST.1), and this is exactly why. **The per-kind shape is unchanged**
and still a LOCATION, not a lever: CALL_EXPRESSION 3,680 ms = **17.1%** of the
spine over 6.1% of the nodes, the five statement-anchor kinds 8,206 ms = **38%**
over 10.0%, IDENTIFIER 2,006 ms = **9.3%** over 44.5%.

**The per-handler table (round-798 `--dispatchProbe`, net of 37 ns/call), against
round 732's:** `cpaSpineLeave` 4,366 → **3,005**, `spineCtaM3StatementAnchor`
2,900 → **2,918**, `ccetSpineLeave` 3,046 → **2,732**, `spineIanyEnterNode`
1,025 → **1,031**, `ccetSpineEnter` 920 → **866**, `ctaSpineEnter` 586 → **706**,
`spineArithLeaveNode` **615**. Five of those six had been opened by an attribution
round; **`spineIanyEnterNode` was the one that had not, and round 798 opened it**
(`docs/perf/implicit-any-attribution.md`) and landed a 320 ms gate in it.

**ROUND-799 ADDENDUM — the two biggest rows of that table are RE-MEASURED and the
round-733 closure HOLDS.** `--spineSections` at HEAD against round 733: the
cpa+ccet leave partition is **8,195 → 5,831 ms net (−29%)**, the passes' OWN
checking work **88.4% → 84.3%**, the ambient install+restore 360 → 341 ms, and
the three ancestor climbs — (SPINE.1)'s named target — **176 → 186 ms**. The
handlers shrank because the passes they call got faster (rounds 787–797); the
scaffolding did not move in absolute terms, and is still ~915 ms across ELEVEN
sections consulted 856,962 times each at 5–190 ns. **There is no per-node lever
in either handler**; what is left in them is (ENGINE.2)/(CALL.5) work. Round 799
therefore took the smaller but CONCENTRATED target — the (IANY.1) residue, whose
506 ms is half one arm (the CALL/NEW argument edge at 7.9 µs × 31,575) — and
landed a further 55 ms there. `docs/perf/spine-leave-attribution.md` § 7.

**ROUND-801 RE-DERIVATION — the 801 column, and the two rows that redirected
the round.** Median of 3 probe-free `--passTiming` runs at `d26c6988`, daemons
stopped *inside* the measuring script. **Nothing large was stale**: every 798
row held to within a few percent, the movement being the ~2.5% rounds 798-800
landed. Wall **28,570** (29.12/28.57/28.39 s), checker-init **24,806**,
`checkSpine` **20,610** (83.1% of init, 72.1% of the compile), enter **11,226**,
leave **6,549**, ures **1,200**, `forEachChild` **520**, narrowing **1,122 ms /
17,853 walks**, `getTypeOfExpression` **2,961** ⚠️, relations **628**, type-node
**485**, front end **3,764** (13.2%).

**The two NEW rows are what matters.** (1) **The ~400 tail passes are 2,962 ms
and FLAT** — largest **75 ms = 0.26% of the compile**, top 20 = 33%, 300 passes
= 11%, and **only 2 of 400 call `getTypeOfExpression` at all while 0 narrow**.
So the tail is ~400 pure AST traversals with syntactic predicates: a
STRUCTURAL cost whose treatments (M0.4, DISPATCH.1) are already measured and
closed, and no per-pass lever exists. (2) **`outside-pass` = 975 ms** — init
work inside no `pass()` wrapper, never named before.

**ROUND-801 — `Binder.bind` IS OPENED AND CLOSED.** The front end's bind was
1,549 ms (6.0%) and had never been partitioned. `bind()` is three statements,
so the partition is exhaustive by construction at 3 timestamp pairs per FILE —
the first in this arc needing no boundary calibration. It is `bindStatements`
**31**, `bindLexicalScopes` **~470** (876,201 node pops), `FlowGraphBuilder.build`
**~1,050** (236,587 flow nodes), residue **−13 ms**. Inside the flow build,
`collectReassignedNamesInRange` holds **275-444 ms over 2,014 closures**;
**~700 ms is the flow walk itself at 3.0 µs per flow node**.
**Two levers built, both measured ZERO.** Removing **367,189 String
allocations** from the B464 text scan: **0 ms** (an allocation count is not a
cost). Deferring the suffix set: the row fell 53.5 → 0.9 ms and then its own
census read **created 1143, materialized 1143** — every set is eventually
asked, so the work **MOVED into the checker**, round 788's law answered against
the change. **`bind` therefore joins `checkArgumentsAgainstSignature` (797) and
the spine-leave handlers (733/799) as measured, bounded and closed.**
`docs/perf/bind-attribution.md`.

**ROUND-802 — `outside-pass` IS NAMED (it is one function), AND A LARGER RESULT
CAME OUT OF THE SAME ROUND.** The 975 ms row is the ~15 setup statements at the
top of `Checker.init`; each is now `pass("init:<name>")`, so the partition is
exhaustive by construction and the residue falls **975 → 144 ms**.
**`init:buildFileLocalTypeMaps` is 636 ms = 65% of the phase and 2.2% of the
compile** — an eager `getTypeOfSymbol` over every file-level declaration —
against 89 ms for second place and under 2 ms for eleven of the sixteen rows.
No lever was landed on it: `getTypeOfSymbol` memoises, so a deferral MOVES the
work (round 788), and the census is queued as (SETUP.2) with that falsifier.

**AND THE ROW THIS TABLE CANNOT SHOW.** HotSpot's `DontCompileHugeMethods` is a
product flag defaulting to **true** with `HugeMethodLimit` = **8,000
bytecodes**; a method above it is **never compiled by C1 or C2** and runs
interpreted for the whole process. A static `javap` census
(`scripts/huge_methods.py`) finds **19 of 13,910 methods over the limit**,
including **`checkMemberAccessMissingCore` 46,567**,
`checkArgumentsAgainstSignatureCore` 23,890, `checkVarDeclAssignabilityCore`
19,296, `checkAssignmentExpressionCore` 18,100,
`checkSingleCallExpressionTypesCore` 15,567, `checkPropertyAccessInExpr` 9,062
— **and `forEachChild` 9,750**, the traversal primitive of the entire compiler.
**Rounds 787–800 opened five of those one at a time and each concluded "no
concentration — the cost is spread over the whole function"; a uniformly
interpreted function is exactly that.** `-XX:-DontCompileHugeMethods` measures
**−3.1%, B wins 4/4 pairs**, output identical at 46 errors (arm B's sd is 1.46%,
so the sign is certain and the magnitude is ±~1.5%). Queued as **(JIT.1)** — the
shippable form is a mechanical split, which costs none of the scope trade
§ 0.1's endgame paragraph warns about.
`docs/perf/setup-phase-and-huge-methods.md`.

**ROUND-800 ADDENDUM — `spineIanyEnterNode` IS CLOSED, AND THE HANDLER IS NOW
FLAT.** The CALL/NEW argument arm round 799 sized at 249 ms is gated on a bounded
reader predicate: callee resolutions **20,812 → 1,439 (−93.1%)**, handler total
**724/707 → 602/617 ms (Δ 106 ms, ~0.36%)**, `typeOfExpr.calls` −4.21% and
**`distinct` −6.18%** — distinct falling FASTER is the measured answer to round
788's law, because 14,813 expression nodes are now typed nowhere in the compile,
i.e. that arm was their only consumer. **Rounds 798+799+800 removed 481 ms of a
1,031 ms handler and there is no concentration left in it**: the residue is
`own : every other kind` (~165 ms over 803,896 nodes at ~200 ns — a per-node
floor), `BinaryExpression parent` (~55 ms over 50,454) and the already-tableswitched
NO-ARM row (~60 ms over 249,471). Round 800 also corrected round 799's own § 11:
the no-arm parent kinds are exactly the kinds the REACH classifier `spineIanyEdge`
lacks an arm for, so nothing below an `as` is walked and only the array-literal
counter-shape against `rhsCanConsumeFnCtx` is real.
`docs/perf/implicit-any-attribution.md` §§ 13–18.

⚠️ The type-system row set is **inflated and incomplete at once**: the
`getTypeOfExpression` row double counts (737), while round 734 showed the row
set MISSES the argument-check and callee-resolution code that calls those
primitives. Do not quote "the type system is 26%" as either a floor or a ceiling.

857k nodes → **14.8 µs per node** for enter+leave, of which **8.9 µs is not type-system
work**. `spineEnterNode` is a linear chain reaching ~118 handler entry points and
`spineLeaveNode` 14 sub-dispatchers — **every handler is consulted about every node**.

**Three cache hypotheses died in one session, all measured, none reasoned:**

1. **The context-bypassed resolution prize is 68 ms** (31,571 outermost calls,
   2.2 µs each) — 0.35% of the compile. INV.5(c)'s entire reason for existing is
   worth a third of one percent.
2. **Widening the INV.5(c) gate is a LOSS.** The round-548 conservative gate rejects
   73.1% of bypassed resolutions (measured 65,000 of 88,829). Removing it lifts hits
   5,575 → 32,104 (23% → 46%) **and runs 28% slower** (6 interleaved pairs) — the
   composite-key hash probe costs more than the resolution it avoids. Memoizing the
   fingerprint (builds 53,765 → 13,293) still measured **+11.9%**.
3. **Identity keying (tsc's mapper-object approach) gets 4.1% hits** — the context
   maps are re-allocated per install, not reused per region, so reference identity
   finds almost nothing.

This is the **third independent confirmation of one law** (after round 665's 30 ms
expression memo and round 659's 75%-reappears migration): *in xtsc the cacheable
population is the cheap tail. Caching in front of a resolution does not pay, because
the resolutions that are cacheable are the ones that were already fast.* **Stop
proposing caches.**

> **ROUND-759 QUALIFICATION — the CACHE form of this law stands; its GENERALISED
> form does not.** The generalisation the arc came to use ("the population you
> could skip cheaply is the population that was already cheap") held six times
> and then failed: inside `checkArgumentsAgainstSignature` the arguments that
> never reach the assignability check are the **expensive** ones, 3.2× over.
> **The law holds when the exit predicate and the cost share a CAUSE** — a
> resolution discarded because it failed fast, a narrowing call that returned
> early. It fails when the predicate reads one operand and the cost belongs to
> the other and the two correlate the wrong way. Two rounds predicted the same
> wrong answer in the same direction from this prior; **ask what SELECTS a
> population and what DRIVES its cost before invoking it.**
> (AUDIT.2), `docs/perf/claim-audit-round758.md` § 10.4. tsc is not fast because it caches; `NodeLinks.resolvedType` is a
field read on the node, not a keyed probe — there is no key to build.

~~**The measured lever is consultation, not computation.** Decisive probe: skipping
`spineEnterNode`'s entire chain for bare `Identifier` nodes (44.5% of all nodes,
2,746 ns each = **1,048 ms**) leaves the compiler-profile diagnostics **byte-identical**.
That time is provably unnecessary work. See queue item **(DISPATCH.1)**.~~

**STRUCK — the INFERENCE round 732, the PREMISE round 758.** Round 732: 22 of
the 59 handlers genuinely act at an identifier (the ones keyed on parent edges,
frame identity and nodeId registries cannot be closed and are the expensive
ones), the removable part at IDENTIFIER is 340 ms probe-inflated / ~100 ms real,
and the byte-identical probe skipped real work the compiler profile happens not
to need. **Round 758: the 44.5% was a FREQUENCY — a share of node VISITS — and
identifiers are 8.4% of the spine's TIME (1,853 ms of 22,104), a ratio of
5.3×.** The 2,746 ns also came from a counter that printed `enter+leave` and
summed ENTER ONLY; corrected, the per-kind concentration is the *opposite* shape:

| | share of nodes | share of the spine |
|---|---:|---:|
| IDENTIFIER | **44.5%** | **8.4%** |
| the five statement-anchor kinds (VARIABLE_STATEMENT, EXPRESSION_STATEMENT, RETURN_STATEMENT, IF_STATEMENT, BLOCK) | **10.0%** | **40%** |
| CALL_EXPRESSION | 6.1% | 17.0% |

**Price a per-node idea against the POPULATION behind the kind, never against
its share of the node count.** Full table: `docs/perf/claim-audit-round758.md`
§ 5.1. And note that this table is a LOCATION, not a lever — the identical
inference from the identical table is what produced (DISPATCH.1).

**Corrected targets.** The 2.4× gap to JS tsc is not a type-system gap — our type
system is 5 s of an 18 s compile and tsc does *more* semantic work than we do. It is
the accumulated per-node checking machinery. Order of remaining levers, by measured
size:

**This table was the round-716 estimate; every row has since been MEASURED and
every one came in smaller. Kept with its verdicts because queue items still
quote the left column.**

| lever | 716 estimate | **measured** | verdict |
|---|---:|---:|---|
| (DISPATCH.1) per-kind handler table | 1.0–2.5 s | **0.1–0.3 s** (round 732) | **do not pursue** |
| flow-narrowing walks | 2.4 s | **−4.53% landed** (736); residue 441 ms = 1.6% (755) | **spent** |
| ~~`getTypeOfExpression` call COUNT (2.8× recompute)~~ | 0.67–0.82 s | ceiling **823 ms** unsound / **46 ms** sound (737) | **do not pursue** |
| context-cache work of any shape | 0.07 s | 68 ms (716) | **do not pursue** |

Also measured: **961,213** globals lookups at **98.1%** miss (round 758; it was
1,341,719 / 98.9% in round 716). **The "≲0.2%" price attached to it is MEASURED
as of round 759 (AUDIT.3): the population is 36–71 ms = 0.13–0.26% of the
compile** — a first-touch probe costs ~37 ns and a warm re-read 9.1 ns,
measured by amplification (`--globalsAmp r` brackets `r` reads under one
timestamp pair, so two values of `r` cancel the pair's own cost; the sink is an
exact multiple of the hit count at every `r`, which rules out elision
arithmetically). The assertion was the right order, every reading is 7.6–15×
below the ±2.0% band, and **§ 0 now has no asserted-never-measured population
left.** Derivation: `docs/perf/claim-audit-round758.md` § 11.

### 0.1 What single-thread parity with tsc actually costs

> **ROUND-843 POINTER (2026-08-07) — THIS BUDGET IS A *COLD-JVM* BUDGET, AND THE
> WARM ONE IS SHAPED DIFFERENTLY.** `docs/perf/warm-jvm-attribution.md` is the
> first per-pass attribution taken on a fully JIT-compiled compiler. Two things
> a reader of the units below must carry: **(a)** the front end warms ~3.8× while
> the checker warms ~2.27×, so checker-init goes **86.2% → 91.8%** of the wall
> and the front-end row of this budget is ~8 units warm, not 11 or 20 — the
> compile becomes MORE checker-bound, the opposite of the natural assumption;
> **(b)** every absolute ms in this section and in the eleven `docs/perf`
> documents behind it was measured with `--passTiming`, which costs ~2,840 ms
> cold (+12.4%) and 3,450–3,945 ms warm (+50–55%) — roughly the same
> MILLISECONDS in both regimes, so a cold SHARE and a warm SHARE are not
> comparable. **No line item below changes; the weighting does.** Also note the
> warm artifact itself moved: `--serve` steady state is ~7.0 s, not the 11.6 s
> this section's "parity is artifact-scoped" argument was priced against.

The budget, from the attribution above. Take the whole compile as 100 units
(checker-init is 80 of them; the front end — read/parse/bind/crawl — is the other
20, never yet profiled because the checker always dominated):

```
checker-init                    80        →  ROUND 758: 87
  ├─ dispatch + handlers        34   ❌ NOT DISPATCH — see below
  ├─ type system                22   ⚠️ inflated AND incomplete (§ 0)
  ├─ tail passes (~403)         14        →  ROUND 758: 10.4, and NOT removable
  └─ unresolved-names, misc     10
front-end                       20        →  ROUND 738: 11 (+9 discarded emit, now gated)
```

**The 34-unit row is a RESIDUAL that was given a NAME** (`spineEnterNode +
spineLeaveNode − the instrumented type-system rows`), and a subtraction is not a
measurement of whatever you call it. Rounds 732, 733 and 734 each opened a piece
of it: the per-kind dispatch table removes 64% of the consultations for 4.8% of
the time (~100–300 ms in production); 88.4% of the two largest handlers is
`checkPropertyAccessInExpr` / `checkSingleCallExpressionTypes` doing their pass's
own checking, against 360 ms of ambient scaffolding and 176 ms of ancestor
climbs; and 78% of the largest of *those* is type-system work. **The 34 units
are the checking work itself.**

Matching JS tsc means 100 → 42 (the CI ratio is 2.4×; **that ratio is EMIT-mode
and these 100 units are a `--noEmit` compile — see § 0.2, the two are not the same
compile and the check-only ratio is still unmeasured**). Working backwards:

| if we removed… | claimed | **round-758 audit** |
|---|---:|---|
| ALL dispatch overhead | 66 → 1.5× | ❌ **100 → ~99, still 2.4×.** The 34 units are not dispatch; real dispatch is 100–300 ms = ~1 unit. **Out by ~34×, and it is the FIRST step of this argument** |
| \+ ALL 403 tail passes | 53 → 1.9× | ⚠️ they are **10.4** units, and NOT removable — round 620 found only 3 of 23 census-silent passes deletable, and round 659's migration A/B measured **+0.24%**, i.e. nothing |
| \+ HALF the type system | 42 → **parity** | ⚠️ rests on a type-system row that is both inflated (double count) and incomplete (§ 0) |

~~**So parity is not one lever — it needs all three, and the third is hard.**~~
**CORRECTED, round 758.** The first two rows do not deliver what they claim, so
this is not three levers with a hard third — **it is ONE question: does the
checking work itself get cheaper?** That is the "endgame" paragraph below, and
rounds 739/755 priced it at ~2.3% on the three largest assignability sites.
State *that* plainly to anyone who proposes a single change that "gets us to tsc".
~~What is realistically reachable from the staged plan below is ~1.4–1.7× of
today, i.e. roughly 1.5× slower than tsc rather than 2.4×.~~ **RETRACTED, round
738.** Three of the five stages are now measured and a fourth's premise is void
(§ 0.1 status in the round-738 header above): the plan's remaining honest value
is **single digits**, not 40–70%. ~~Also note the 2.4× itself was measured against
our `--noEmit` doing ~9% of work tsc's does not — the honest gap is ~2.15×.~~
**RETRACTED, round 739 (§ 0.2): the 2.4× was measured with every compiler
emitting, so it was already like-for-like; what is missing is a check-only ratio,
which has never been measured on either side.**

**The staged plan (each stage enables the next):**

1. ~~**(DISPATCH.1) per-kind handler table** — 11–19%. *Prerequisite for everything
   below.* Low risk, mechanical, decisive probe in hand.~~ **STRUCK, round 732
   (measured ≈0.3–1%) and round 758 (its evidence — "IDENTIFIER is 44.5% of the
   nodes" — was a FREQUENCY; identifiers are 8.4% of the spine's time, 5.3×).**
   Nothing below was ever gated on it.
2. **Resume the M0.4 tail migration** — worth ~14% AFTER stage 1, versus the 4%
   round 659 measured before it. Round 659's "75% reappears" was measured without
   a dispatch table, where one walk still consults every handler at every node.
   **The INV.4 inversion was not wrong, it was mis-ordered**; stage 1 retroactively
   unlocks it.
3. ~~**Cut the `getTypeOfExpression` recompute factor (2.7×)** — up to ~9%.~~
   **STRUCK, round 737, by measurement (`docs/perf/type-of-expression-attribution.md`).
   The MECHANISM stated here is exactly right and its SIZE was wrong by 3.2×.**
   Attributing all 701,463 calls by CALLER confirms the claim: 177 sites
   initiate typing, **45.2% of the 254,069 typed nodes are typed by more than
   one of them**, and the factor decomposes as **2.05× cross-handler × 1.34×
   recursion** — co-occurrence is the dominant term and no handler re-types
   anything by itself (per-caller factors are 1.00–1.11). **But a PERFECT
   per-node cache — the ceiling for this stage in any shape — saves 823 ms
   (2.9% of a 28.7 s compile); single-visit discipline saves 670 ms (2.3%); the
   largest handler-pair merge is 166 ms (0.58%) against a ±2% band of ~590 ms;
   and the SOUND memo measures 46 ms.** § 0's law again, and this time without
   a cache in sight: the four biggest co-occurrence pairs by COUNT are 141,388
   repeat typings worth 71 ms (0.5 µs each) while the biggest by TIME is 2,603
   typings worth 166 ms (64 µs each) — **the redundantly-typed nodes are the
   cheap ones.** Also corrected here: the "3,911 ms" this estimate leaned on is
   a DOUBLE COUNT (`typeOfExprNanos` charges a subtree once per nesting level);
   the true total cost of all expression typing is **2,439 ms = 8.5% of
   checker-init**, which caps this stage at 8.5% even if typing were free.
   Follow-on, and the first thing this attribution points at: **(TYPE.2)**
   `checkVarDeclAssignability` under `spineCtaM3StatementAnchor` — the largest
   single typing origin (431 ms over 11,933 top-level initializers, 36 µs each)
   inside the third-largest and still-unopened spine handler (2,900 ms).
4. **Flow narrowing** (69,917 walks, 18% of init) — round 664 banked 0.83 s; ~57%
   of misses are COLD, so the rest needs tsc's shape (a flow type computed once per
   reference and carried IN the type), not another memo.
5. ~~**Front-end (20%)** — unprofiled. Worth a look once it is a fifth of a smaller
   number.~~ **MEASURED round 738 (`docs/perf/front-end-attribution.md`): the front
   end is 11.0% and has NO lever in it** — the crawl (5.4%) already overlaps read,
   decode and parse 16-in-flight and the core re-parses nothing (78/78 pre-parses
   reused, core parse loop 0 ms); bind is 5.2% and in-band. **The other ~9% of this
   "20%" was `Transformer` + `Emitter` running under `--noEmit` and discarding the
   result**, now gated (`skipEmitOutputs`) for **−11.42%, B wins 6/6** — a scope
   correction, not a speed-up.

**Explicitly NOT on the list:** anything cache-shaped (three independent
measurements), and parallelism until after stage 2 — M2 measured 77% of the work as
non-divisible per-worker duplication, and that fraction is largely the dispatch
machinery, so shrinking it is also what makes workers pay.

**The endgame, stated honestly:** after stages 1–5 the residue is the shape of the
checks themselves — 1,005 `check*` functions where a general engine would have
rules (census, round 739: 805 `check*` + 181 `emit*` + 60 `tryEmit*` = 1,046).
That redundancy is what the byte-identical corpus gate incentivised
(narrow verifiable walkers beat broad engine rules, and every broad attempt
regressed). Removing it is a SCOPE decision, not a perf task, and it trades the
property that made the corpus reachable. **FIRST PRICE, round 739
(`docs/perf/engine-rule-price.md`) — and the "14×" this paragraph was about to be
sold with is the wrong statistic.** 265/19 compared the FP-firewall walkers against
the final relation call alone, which is 2.2% of the function it lives in.
Re-classified against the work a general rule engine would ALSO do (resolve the
target node, compute the source type, infer unannotated initializers, narrow):
**engine 483 ms (55.4%), dedicated-walker layer 326 ms (37.4%) — 0.67×, not 14×**,
and the layer is **1.21% of a check-only compile** on the largest of the three
assignability sites. Deletable is less still: **165 ms of the 326 is the weak-type
rule**, real TypeScript semantics that tsc holds inside `checkTypeRelatedTo`, so it
MOVES into any replacement engine rather than vanishing → **0.6–1.2% for this site.**
~~Two sites remain unmeasured, with scored predictions written down; do not put the
scope question to the owner until they are in.~~ **THE PRECONDITION IS DISCHARGED AND
THE ANSWER IS NO (round 830, `docs/perf/engine-rule-price.md` §§ 9–11).** Sites 2 and 3
were measured at rounds 755/786 and a FOURTH — the property-access path, the one that
holds the mass — at round 787; round 830 re-measured all four **on one binary in one
round** so the total is a within-round sum. **The dedicated-walker layer on the four
largest checking sites is 757 ms = 3.13% of a check-only compile (bracket 2.90–3.82%),
of which ~22 ms (0.09%) is plainly deletable** — the largest group at *every* site is a
rule tsc also implements and would MOVE. Per-site shares **fall as the site gets
bigger**: 37.6 / 27.2 / 23.5 / 20.1%. Against a ±2.0% drift band, and against
**(JIT.1)'s already-banked −3.93% from one mechanical method split**, this endgame is
not worth the scope trade. **(SCOPE.1) is CLOSED UNRAISED; do not put it to the owner.**
One caution this measurement itself produced: site 4's share rose 8.0% → 20.1% while its
firewall stayed at 209 ms, because the ENGINE fell 2,364 → 830 ms — **a rising layer
share means the engine got faster, not that the layer got more expensive.**

**PROCESS — the cost gate this arc lacked.** Round 713 added ~72k
`getTypeOfExpression` calls (624,961 → 696,953, +11.5%) for one conformance
diagnostic, ≈70–200 ms, and nothing noticed: the round gates are the corpus and
`--listAll`, neither of which sees cost. Over 200 rounds that is exactly how ~118
handler entry points per node accumulate. Every round that touches the checker
should now record `getTypeOfExpression` calls, `narrowWalks`, and the spine
per-kind numbers, and justify an increase.

### 0.2 What the tsc ratio actually measures — and what it never measured

*Round 739, queue item (BENCH.1). This section exists because round 738 corrected
a published ratio on a premise that was false, in the direction that flattered us.
Read it before quoting any xtsc-vs-tsc number.*

**What each side runs, verified in the scripts rather than assumed:**

| script | xtsc | tsc / tsgo | what it decides |
|---|---|---|---|
| `bench-3way.sh` (CI, `bench-history/`) | `MainKt <proj>` — **emits** | `-p tsconfig --outDir tmp` — **emits** | the published ratio |
| `ab-interleaved.sh` | `MainKt --noEmit` | — | every perf A/B in this arc |
| `cost_gate.py` | `MainKt --noEmit --passTiming` | — | the 20 cost counters |
| `bench-compile-tsc.sh` | either, per `--no-emit` | — | `bench/*.tsv` (**gitignored — local only**) |

**So the 2.4× was never a `--no-emit` comparison.** Both sides emitted; it is a
like-for-like EMIT ratio, and round 738's `skipEmitOutputs` gate — which fires only
under `--noEmit` — does not move it by construction. Round 738's "the honest gap is
~2.15×" is **retracted**: it multiplied the ratio by our own emit fraction while
implicitly taking tsc's as zero, which is the ratio's *floor*, not its value.

**The real mismatch is the other one, and it is still open.** § 0.1's budget model
("take the whole compile as 100 units") is measured on a `--noEmit` compile, and it
is compared against a ratio measured with emit on both sides. Those are two
different compiles. Measured round 739 on the compiler profile, same binary, 4
interleaved pairs: check-only **26,896 ms** vs emit **29,194 ms**, i.e. **the emit
work is 2,298 ms = 8.5% of a check-only compile, 7.9% of an emit-inclusive one**
(B slower in 4/4 pairs; per-pair +1,959…+2,422 ms).

**The check-only ratio, which is the one this arc's numbers belong to, has never
been measured** — the bench never ran tsc or tsgo with `--noEmit`. It is bounded,
not free: with `s` the emit share of each side,

```
R_check-only = R_emit × (1 − 0.079) / (1 − s_tsc)
```

so it equals `R_emit` exactly when tsc's emit share equals ours, drops to
`0.921 × R_emit` only in the impossible case that tsc's emit is free, and **exceeds
`R_emit` as soon as tsc's emit costs more than 7.9% of its run** — which is the
likely case, since our checker is the slow part and our emitter is not. Taking the
median over the last 30 CI runs (`R_emit` = 2.40×), the check-only ratio is
**≥ 2.21× and probably ≥ 2.4×**. `bench-3way.sh` now measures both modes on all
three compilers, so the next CI run replaces this bound with a number.

**Quoting rules, from here on.** (1) Name the mode with the ratio. (2) Never compare
a ratio of one mode against a ratio of the other. (3) Never read a single CI row as
the ratio: xtsc is one cold JVM run per row against tsc's median of three, and over
340 archived rows the ratio ranges **1.87×–2.72×** (median 2.28× overall, 2.40× over
the last 30) on a compiler whose real change over that span was far smaller.

---

## 1. The verdict

Micro-optimization has hit its measured ceiling. Rounds 482–489 each produced 1–3%
against a **flat JFR profile** (top self-time entry ≤ 6%). Flatness here is not
"nothing left to optimize" — it is the signature of an architecture whose cost is a
**multiplier**, not a hotspot:

> ~hundreds of sequential full-program checker passes
> × uncached type recomputation
> × per-pass scope re-derivation
> × non-canonical type identity.

The benchmark that frames the goal (compiler profile, 78 files / 195k LOC, cold):

| | time | vs xtsc |
|---|---|---|
| tsgo 7.0-dev | 2.1 s | 12× faster |
| tsc 6.0.3 (JS on Node) | 10.2 s | 2.5× faster |
| xtsc (round 489) | ~25 s | — |

The decisive observation: **tsc does strictly MORE semantic work per node than xtsc**
(full bidirectional inference, contextual typing everywhere, variance-aware
relations) and is still 2.5× faster. It wins by doing the work **once** — one tree
walk, every computed fact cached, every instantiation interned — not by being
micro-faster. The historical proof that xtsc responds to structural fixes: rounds
432–434 (scans → indexes) took the self-compile from ~593 s to ~20 s (30×), while
eight subsequent micro-rounds bought ~10% combined.

## 2. Evidence inventory (verified in-code, 2026-07-13)

1. **`Checker.init` is a ~1,700-line sequential dispatch of ~512 distinct check
   passes** (523 call sites; 1,005 `check*` functions; Checker.kt = 162,840 lines =
   64% of the codebase). There are **575 `for (result in binderResults)` loops** —
   575 places that independently iterate the whole program, dozens of them full
   recursive walks that each rebuild their own scope state (`currentLocalTypes` +
   the shadowing/ambiguity machinery, per pass).
2. **`getTypeOfExpression` has no cache** (Checker.kt:96297). All 321 call sites
   recompute recursively, in every pass that consults them. tsc computes a node's
   type once and stores it (`NodeLinks.resolvedType`).
3. **The one node-type cache (`nodeTypes`) is bypassed whenever ANY resolution
   context is active** (`cacheable = currentTypeParamScope == null &&
   inferenceNamespaceStack.isEmpty() && …`, Checker.kt:~93091). Inside generic code
   — i.e. most of checker.ts — annotations re-resolve on every touch. Root cause:
   resolution context is *ambient mutable state*, so caching is unsound; tsc makes
   context explicit (mapper objects), so its caches are always valid.
4. **Type identity is not canonical**: `getUnionType` mints a fresh `Type.id` per
   call (documented gotcha); `resolveGenericPropertyType` mints fresh
   `Type.Object`/`Signature`/`Symbol` per query and is **depth-capped at 4 because
   it OOMs otherwise**. Non-canonical ids defeat every id-keyed cache downstream
   (relation cache, display maps) and forced structural workarounds
   (`ts2403Identical`, `unionAliasStructural`).
5. **Flow narrowing is re-launched per consumer site** (TS2339 / TS2454 / arg-check /
   return-check / assignment / arithmetic — wired one by one across rounds 408–479)
   with per-walk memos, instead of one flow-typed reference cached on the node.
6. **`mergeSymbolTable(globals, every file's locals)`** (Blocker #3) spawned the
   conflation-suppression ecology (`moduleFileLocalVarNames`,
   `conflatedTypeAliasFiles`, `conflatedInterfaceFiles`, `conflatedEnumFileSubsets`,
   per-file interface views, chimera bails) — consulted on the hottest path:
   `checkMemberAccessMissing`, the top walker in the last four JFRs, runs for every
   property access in the program.
7. Single-threaded end to end; ~1.9 GB RSS on the harness profile (312 files).
8. Front-end share: read+decode+parse+bind ≈ 1.5–2 s of the 25 s (front-end
   functions never appear in JFR top entries; the checker dominates). Emit ≈ 1–2 s
   (26.9 s emit vs ~25 s noEmit).

## 3. Cross-check against tsc and tsgo

**tsc** (the architecture to converge on): a **pull-based, single-walk,
memoize-everything** checker. `checkSourceFile` visits each file once; per-node
grammar+semantic checks run in that single visit (plus a small deferral queue for
contextually-typed functions). Every computed fact is cached in side tables:
`SymbolLinks.type`, `NodeLinks.resolvedType`, resolved signatures, structured
members resolved once *onto the type*, instantiations interned on the generic
target keyed by type-argument id lists, unions/intersections interned by sorted
member-id key, relation results cached per `(sourceId,targetId)` with a maybe-stack
for cycles. Generic instantiation is a pure **TypeMapper** applied to a cached
generic type — context lives in the artifact, never in ambient state. Flow analysis
runs once per reference (`getFlowTypeOfReference`) and participates in the cached
type.

**tsgo** (TypeScript 7): same semantics, plus native code + data layout (roughly
the first ~3.5×) and **concurrency**: parse/bind/emit fully parallel per file, and
type-checking split across a fixed number of checker workers (default 4,
`--checkers`), each with "their own view of the world" — own type tables, shared
immutable ASTs/binder output, deterministic partitioning, accepting redundant type
computation per worker rather than sharing hot caches. Confirmed against the
[TypeScript 7.0 RC announcement](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0-rc/)
and [native previews post](https://devblogs.microsoft.com/typescript/announcing-typescript-native-previews/).
Our `docs/parallel-caching.md` reached the same share-nothing design independently;
it stands. Note the prerequisite: tsgo parallelizes **one** demand-driven check
pass — you cannot usefully partition 512 program-wide passes. Parallelism is gated
behind the single-pass inversion.

**Why xtsc looks the way it does (honest retrospective):** the walker-accretion
model was arguably the *right* strategy for conformance — the byte-identical corpus
gate rewarded surgical, individually-verifiable walkers; broad engine attempts
measurably regressed (round-336 variance dead-end, round-409 resolveAlias flood).
It produced 10,196 green tests and zero real FPs on all 8 profiles. But it is the
wrong *permanent* shape: it multiplies passes, prevents unified caching, and
forecloses the post-v1 horizon — a checker whose construction IS the compilation
(512 eager passes in `init`) can never do incremental checking, watch mode, or
serve an LSP. The inversion is not only a perf play; it is the prerequisite for
everything after v1.

## 4. The streams decision (owner's Flow proposal)

The owner's microbenchmarks: `Flow<String>` beats `Sequence<String>`; reading files
on `Dispatchers.IO` (UTF-8 → UTF-16 transcoding) while processing on
`Dispatchers.Default` yields further gains. Where this lands:

**Adopted — streams at the boundaries (INV.1, INV.6, INV.7):**

- **Front-end pipeline**: a cold `Flow` of file paths → `flatMapMerge(concurrency =
  N)` where each file does read+decode on `Dispatchers.IO`, then scan+parse on
  `Dispatchers.Default` → collect to the parsed-file list (the phase barrier).
  Backpressure here is real and useful: bounded in-flight files = bounded peak
  memory during the front-end, self-regulating exactly as the owner describes.
- **Back-end**: per-file emit on Default, file writes flowing to an IO sink.
- **Watch/incremental mode (post-inversion)**: file-change events as a `Flow` is
  the natural driver.
- kotlinx-coroutines-core as a commonMain dependency is **owner-approved
  2026-07-13** (kotlinx.* is within the commonMain dependency rule; JS/WASM degrade
  to single-threaded dispatchers; Native is supported).

**Redirected — no streaming through the middle, and why:**

- After parse, the unit of work stops being a `String` and becomes a **graph**.
  Checking any file needs the bound symbol tables of every file it can reach
  (imports, `export *` barrels, script-file globals, `declare global`, module
  augmentations) — the classic compiler barrier. tsgo, with every incentive to
  stream, still runs phase barriers: parallel parse → barrier → bind → barrier →
  partitioned parallel check → parallel emit.
- Backpressure regulates producer/consumer rate mismatch over an unbounded stream.
  A compiler's working set is bounded and must stay **resident** — every AST is
  potentially consulted by every later check, so nothing is ever "consumed" and
  droppable mid-pipeline. There is no queue to regulate; the memory floor is the
  program itself.
- The **pull** intuition is exactly right, but the pullable thing is *facts, not
  strings*: "the type of node N", pulled on demand, computed once, memoized —
  tsc's model. A cold Flow re-runs its upstream per collector, which is precisely
  wrong for a DAG with massive fan-in reuse; a memoized lazy graph computes each
  fact once. So: **streams at the I/O boundaries, demand-driven memoization in the
  core, fork-join structured concurrency between phases.**
- Scale honestly stated: the front-end is ~1.5–2 s of 25 s today, so INV.1 is a
  ~1–2 s-class win now — worth having, growing with the 500k-LOC "any project"
  horizon, and it derisks the coroutine foundation the big INV.6 win (2–3×) needs.
  The 15+ s prize is the checker inversion (INV.2–INV.5).
- Inside checker hot paths, neither `Flow` nor `Sequence` belongs — plain loops,
  arrays, and id-keyed tables win; the owner's Flow-vs-Sequence result applies to
  the pipeline layer, not the core.

**Determinism hazards recorded now:**

- Parallel **parse** is safe: the parser is per-file pure (internSalt =
  fileName.hashCode stamped per file); verify no hidden global counters during
  implementation.
- The **binder stays sequential in file order** in INV.1: global `nextSymbolId`
  allocation order is load-bearing (documented ~350-test reshuffle on id drift;
  first-touch semantics). Parallel binding needs per-file id spaces + deterministic
  renumbering — INV.2 territory at the earliest, likely never necessary (bind is
  cheap).
- `Type.id` allocation is checker-phase and unaffected by front-end parallelism;
  under INV.6 each worker replicates Tier-3 state per `docs/parallel-caching.md`.

## 5. The phased plan (queue items INV.0–INV.7)

> **2026-09-01, Phase 18:** this INV.0–INV.7 series is SUPERSEDED as a queue — parts
> landed (INV.0 = PassTiming; INV.2(c) tables; the INV.4 spine; INV.5 interning;
> INV.6 `--workers`; the (INC.\*) arc), and the live successor series
> ((INV.D)/(INV.0)/(INV.1) in PLAN-PHASE-5.md, DIFFERENT meanings under the same
> prefix) is defined by `docs/INVERSION-DESIGN.md`, whose § 8 is the bridge between
> the two numberings. Read this section as the 2026-07-13 architecture rationale,
> not as work items.

Not a rewrite — an **inversion of control**, migrating walkers into a single-pass
spine while the corpus suite + `--listAll` byte-diffs + bench TSV pin behavior at
every step. The verification loop is the project's superpower; it is what makes
this safe. Every phase = many small suite-gated commits.

- **INV.0 — Instrument the multiplier.** Per-pass wall-time behind an opt-in flag
  (`pass("name") { … }` wrapper around the init dispatch; accumulator fields
  DECLARED BEFORE `init` per the Kotlin init-order gotcha; mechanical wrap of the
  plain `checkFoo()` lines, manual for conditionals). Counters: getTypeOfExpression
  invocations vs distinct nodes touched; nodeTypes cacheable-vs-bypassed ratio;
  narrowing walks launched per consumer site. Deliverable: a sorted pass-time table
  in a session note = the INV.4 migration worklist + the honest baseline. Gate:
  instrumentation off by default, byte-identical, suite green.
- **INV.1 — Concurrent front-end (the owner's Flow beachhead).** (a) add
  kotlinx-coroutines-core + a behavior-identical sequential-Flow refactor of
  project file loading; (b) IO decode / Default parse via bounded `flatMapMerge`;
  binder stays sequential file-order; (c) determinism verification (corpus + 3×
  listAll byte-diff runs); (d) bench rows (compiler + harness). Expected ~1–2 s on
  big profiles + the structured-concurrency foundation.
- **INV.2 — Bind the world.** Full lexical binding (function bodies, blocks —
  dissolves B83.5), container/parent chain, per-file `nodeId` enabling
  array-indexed side tables (kills a real slice of the `HashMap.getNode` top
  entry; unlocks the file+node-identity memo keying rounds 481–482 were blocked
  on). Scope symbols allocate from a SEPARATE id space to avoid the ~350-test
  boundary reshuffle. Existing walkers keep working (additive tables); their
  scope hacks become deletable in INV.4.
- **INV.3 — Per-file scoping.** Consume the already-built `buildPerFileScopes`;
  module files resolve own-locals + imports + true globals (script files + libs);
  retire the `mergeSymbolTable` conflation for module files; delete the conflation
  ecology walker-by-walker (each deletion suite- and listAll-gated, and each
  removes hot-path checks from `checkMemberAccessMissing`). Also lays the
  cross-file value-resolution groundwork EP.1 (cross-module const-enum inlining)
  needs.
- **INV.4 — Single-pass spine.** `checkSourceFileOnce` per-node dispatch; migrate
  walker families in INV.0's cost order — every migration deletes a full-tree pass
  and its private scope machinery. Once ONE authoritative walk state exists, two
  things become safe that are unsound today: a per-node expression-type cache, and
  folding flow narrowing into reference typing once (collapsing the 70-round
  per-consumer wiring). The long middle; plan as many small items.
- **INV.5 — Canonical types + explicit instantiation.** Intern
  unions/intersections by sorted member-id key (tsc-style; preserves display
  order); replace ambient `currentTypeAliasArgs`/TP-scope with explicit mapper
  objects; cache instantiated members ON the `Type.Reference` (delete
  `resolveGenericPropertyType` fresh-minting and its depth-4 OOM cap); `nodeTypes`
  keyed (node, mapper) — always valid; open `canUseTypeEngine`'s generic gate;
  delete superseded pin walkers.
- **INV.6 — Parallelism.** Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic merge via
  the existing diagnostic sort. Structured concurrency from INV.1's foundation.
- **INV.7 — Productization.** Native re-enable (the measured big-input GC
  inversion should largely dissolve once INV.4/5 cut allocation); watch mode
  driven by a file-event Flow; `.tsbuildinfo`-style incremental reuse. (Absorbs
  old M5.5/M5.6.)

## 6. Targets and measurement protocol

*(Rewritten 2026-07-20, round 618, owner-approved. The original targets priced in
the (f1)/(f2) memo+fold wins, which measured as dead-ends at the pre-canonical-types
cost structure (rounds 596/599). The honest wall ledger for the inversion: ~25 s
pre-arc (round 489) → 35.7 s mid-arc peak → ~31 s today — wall-NEGATIVE until the
M0 debt burn-down completes, in exchange for the warm loop (watch incremental
46–157 ms, `.xtsbuildinfo` ~630 ms), the partition seam, one authoritative walk,
and the cache soundness M1 builds on. Round-618 measurements framing the arc: the
JVM flag matrix is a measured dead-end (not GC-bound at 4 g); relations are 0.79 s —
the engine is NOT the bottleneck; HashMap+String-equality ≈ 15% of wall with NO
single hot map; the 440-pass legacy tail (6.2 s) emits NOTHING on the compiler
profile — the corpus is its only pin; Identifier = 44.2% of 858k nodes and a kindId
table kills 88% of dispatch-chain cost.)*

Revised targets (compiler profile = 78 files/195k LOC, cold CLI, single-threaded
unless stated; queue items in PLAN-PHASE-5.md § QUEUE "PERF"):

| checkpoint | target |
|---|---|
| M0 — debt burn-down (tail triage/deletion, kindId dispatch, layout: atoms/links records/open-addressing, scaffolding retirement) | ≤ 24 s (recover, then beat, the round-489 baseline) |
| M1 — identity stability (epoch churn, canonical narrowing outputs, member caching on the Reference) reviving the f1/f2 memo+fold | ≤ 15–20 s |
| M2 — parallel scaling Phase 1 (shared frozen collectors) | ≤ 10–12 s at 4 workers (≈ JS-tsc parity) |

Native is explicitly NOT a perf lever (measured round 610: 196 s debug .kexe;
K/N ≤ JVM for this allocation-heavy workload) — it stays a product/portability
asset. tsgo-class times (2–4 s) are out of scope for this arc. For the edit loop
the perf problem is already largely solved by watch/incremental — these targets
are about the cold full build.

Invariants at EVERY step: corpus suite 100% green; **the COST.1 counter gate
(`scripts/cost_gate.py`) clean, or the increase justified and rebaselined in the
same commit** — added round 717, because the round-713 +11.5% went unnoticed by
every gate listed here; the 8-profile FP floors
unchanged (env-legit only); a bench TSV row per landed item;
diagnostics/emit byte-diffs (`--listAll`, `emit-diff-tsc.sh`) empty vs the
pre-change binary for behavior-preserving steps; wall-clock claims decided by
interleaved A/B medians ONLY — anything priced below the drift band folds
into a structural item instead of landing alone (the round-618 discipline).
A fresh JFR (or the INV.0 pass table) before and after each structural phase —
the profile shifts after every fix.

**Two A/B protocols, two bands — name the one you used (round 774).** The band is
a property of the PROTOCOL, not of the box, so a number is uninterpretable without it:

| protocol | driver | band | use it for |
|---|---|---|---|
| **COLD** — one fresh JVM per sample | `scripts/ab-interleaved.sh` | **±2.0% ≈ ±536 ms** | warm-up-shaped costs, claims about the shipped one-shot CLI, and anything compared against `bench-history/` (every archived row is cold) |
| **WARM** — one JVM per sample, N in-process rebuilds, sample = its median | `scripts/ab-warm.sh` | **±1.0% ≈ ±114 ms** | steady-state COMPUTE claims about compiler machinery ("does this make the checker do less work"), i.e. effects of 100–500 ms the cold band cannot see |

**The warm band holds only on a box nobody is touching**: round 774's A/A measured
±1.0% with the box idle and **−6.70%** on the same binary while an agent polled the run's
log, so start a warm run and then leave the machine alone, and discard any run whose
printed per-arm sd exceeds ~1%. The warm band was calibrated in round 771 and re-measured in round 774 —
**calibration table in `docs/perf/aot-native-image.md` § 4, not duplicated here**,
along with the list of arc items it re-opens and the two cautions attached to them
(nothing got bigger, only visible; round 736's identity pre-test was rejected as
UNSOUND, not as small). Both protocols are `--noEmit` check-only, so they compare to
each other and to `cost_gate.py`, and NOT to the emit-mode CI ratio (§ 0.2). The warm
protocol is only admissible because `BenchMain` prints `files`/`errors` per iteration
and the driver aborts on any drift — an in-process rebuild that answers a different
program is not a faster compile. Neither protocol beats an IN-PROCESS COUNTER
(`--passTiming`, `cost_gate.py`) where one exists: counters are deterministic.

## 7. Anti-goals

- **No more micro-opt rounds against the flat profile** — closed as of this
  rescope, unless INV.0 data exposes a genuine ≥5% single lever.
- ~~**No big-bang rewrite / no 1:1 tsc-checker port.**~~ **LIFTED 2026-07-26 (round
  716, owner).** The rationale was that "every broad attempt under the exact-baseline
  gate regressed" — but the owner has now made LOGICAL parity the bar, not byte
  parity, which is precisely the gate those attempts failed. A redesign is
  permitted; a broad change that alters only the FORM of a baseline is landed with a
  new test pinning the logic and the old one switched off. The walkers still encode
  489 rounds of FP knowledge, so prefer migrating to discarding — but that is now an
  engineering judgement, not a prohibition.
- **No shared concurrent maps** (per `docs/parallel-caching.md`, confirmed by
  tsgo's shipped design).
- **No streaming through the checker**; no parallel binding before the id-space
  work; scanner stays UTF-16 `String`-based (positions are baseline-pinned;
  UTF-8-native scanning is at most an INV.7/native-arc idea).
- **Don't touch** Scanner/Parser/Emitter shape (fine, tsc-shaped, invisible in
  every JFR), the flow-walk budgets, or the verification loop itself.

## 8. Relation to the EP milestone

EP.2 (printer formatting) is independent — interleave freely. EP.1 (cross-module
const-enum inlining) gets structurally easier after INV.3's cross-file resolution
work — prefer that ordering. EP.0 (emit-diff dashboard wiring) any time.
