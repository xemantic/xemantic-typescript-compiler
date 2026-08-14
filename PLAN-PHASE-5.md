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

**Round 904 (2026-08-14) — (WARM.31): THE WHOLE BOXED-PRIMITIVE-KEY FAMILY IS **REFUSED** — 14 SITES,
**2,698,745 OPERATIONS PER REBUILD, A 6.58 ns PREMIUM, 17.7 ms = 0.334% FOR ALL OF THEM TOGETHER** AND
**0.064% FOR THE LARGEST SINGLE ONE**. AND THE 29.4 ms THAT RANKED IT WAS ONE DRAW OF A **4x-UNSTABLE**
NUMBER: THE SAME LEAF FAMILY READS **72.9 ms AND 19.0 ms IN ROUND 899's OWN TWO DUMPS, SAME BINARY.**

Priced BEFORE a line of fix; no production behaviour change. `docs/perf/boxed-primitive-key-price.md`.

- **(A) THE THRESHOLD WAS COMPUTED BEFORE THE CENSUS RAN, AND IT IS WHAT CLOSED THE FAMILY.** At a
  generous 10 ns premium against the ~17 ms floor, a single site needs **~1.7 M operations per
  rebuild**. **The whole check spine visits 856,962 nodes** — so *every per-node memo in the compiler
  is refused by arithmetic before any build*, and the question reduced to whether any site is driven
  by something other than node count. None is: the largest of the fourteen is **519,478 ops = 30% of
  the single-site threshold**.

- **(B) THE CENSUS, WITH BOTH CONTROLS HITTING EXACTLY.** `--boxedKeyCensus`, deterministic, two
  processes agreeing to the last digit. Top sites per rebuild: `importedSymbolGeneralCache` 519,478,
  `Relation.cache` 456,660, `relationComparisonStack` 444,446 (**max live 27**), the enum caches
  427,024, the ten spine `nodeId` memos 319,558; total **2,698,745** over 14 sites. **Site 6 is
  exactly 2x round 900's `risgCalls` (259,739) and site 11 is >= 2x round 896's `symAdds` (24,232)** —
  two independently-recorded populations reached by a different instrument, which is what says the
  hooks are on the right operations.

- **(C) THE `-128..127` DEFLATION DOES NOT APPLY, WHICH HAD TO BE CHECKED RATHER THAN ASSUMED.**
  `Integer.valueOf` caches small ints, so a small-key site boxes nothing new — but ids here run to
  millions and `nodeId`s to 275,470, and **only 0.41% of all keys fall in the cache**. The filter that
  could have refused half the family for free refuses none of it.

- **(D) TWO SITES ARE NOT REFUSED BY PRICE BUT BY *SHAPE*, WHICH IS THE PART A FUTURE "JUST MIGRATE
  THEM ALL" PROPOSAL NEEDS.** `Binder.lexicalScopes` is **ITERATED** (`for ((_, scope) in
  result.lexicalScopes)`), and `IntKeyMap`/`LongKeyMap` have no iterator *by design* — which is
  exactly what makes the rounds-754/776/778 order hazard a compile error rather than a review item,
  and is the same exemption CLAUDE.md already grants `Binder.nodeToSymbol`. And `relationComparisonStack`
  / `relation{Source,Target}Targets` / the two in-progress sentinels are **transient add/remove stacks
  with max live 27 / 18 / 5** — their successor is a linear array, not a map at all.

- **(E) THE PREMIUM, AND THE INSTRUMENT CORRECTION IT FORCED.** Two arms, two `r`, mirrored rotation,
  8 draws per arm: a boxed `HashMap<Long, ·>` probe is **8.53 ns**, a `LongKeyMap` probe **1.96 ns**,
  premium **6.58 ns [4.88-8.27]**. **A SINGLE-`r` `A − B` OVER-READS THIS BY UP TO 23%** — the standing
  advice that the timestamp boundary "cancels between the arms at equal `r`" is **false when the arms'
  boundaries differ**, and here they do (**88.0 vs 76.1 ns**), so `A − B` reads 8.07 at `r = 8` and
  7.07 at `r = 24` against a true 6.58. Fit `p(r) = cost + boundary/r` per ARM and difference the
  COSTS; the two implied boundaries are then a free check on the fit, and they reproduce both measured
  values to 0.01 ns. **Round 903's arms were already SLOPES at two `r`, so its 12.98 ns premium and its
  refusal are unaffected** — but the two rounds together are why this is now a law rather than a note.

- **(F) THE FINDING THAT REACHES BACK INTO THE RANKING ITSELF: LEAF INSTABILITY IS PER-*MECHANISM*,
  NOT PER-ROW.** Round 868 established that LEAF attribution is unstable across processes and it is
  always quoted about one frame (`HashMap.getNode` 9.66% vs 3.70%). Run over **round 899's own two
  dumps — same binary, same round** — the boxed-primitive leaf FAMILY reads **72.9 ms and 19.0 ms**,
  a **4x** disagreement, because C2 inlined `Integer.equals` into its callers in the second process.
  **So the 29.4 ms that put this on the candidate list was one draw of a number with a 4x spread**, and
  an aggregation that SUMS inlinable stdlib leaves inherits the instability at family scale. Minimum
  two processes for any family share; `scripts/boxed_key_leaves.py` carries the warning in its own
  docstring, and its stated purpose is to LOCATE an owner, never to price one.

- **(G) A THIRD REUSABLE CONSTANT, AND A BAND THAT MUST NOT BE INHERITED.** `docs/perf/warm-leaf-profile.md`
  § 33.8's "an `Integer`-keyed probe is ~15-30 ns" is **over by 1.8-3.5x**. This *strengthens* round
  900's refusal of candidate (1) — its 84.3 ns/probe was over by **10x**, not the 3-6x recorded — and
  it means a `LongKeyMap`/`IntKeyMap` probe is ~2 ns, now confirmed twice (rounds 903 and 904). A next
  agent can refuse a NEW boxed-key site for free: **population x 6.58 ns**.

- **(H) GATES.** Suite **14,409 -> 14,416 / 0 failures / 0 errors / 3 skipped** = exactly the 7 new
  pins. `cost_gate.py` **+0.00% on all 20 counters** — the expected control. `huge_methods.py
  --fail-over 0`: 0 over the limit. Build warning-clean. **The pins DISCRIMINATE**: ablating the
  `bkPush` on the relation stack reddens exactly one pin, the one that names it, on a binary that
  otherwise builds and passes — round 902's law applied prospectively, an arm shown REACHED rather
  than merely applied. **No wall A/B, for the thirteenth round running, and nothing to A/B.**

**Round 903 (2026-08-14) — (WARM.30): THE CANDIDATE THIS FILE AND CLAUDE.md BOTH CERTIFIED AS "THE ONE
JFR ROW WORTH BELIEVING" IS **REFUSED AT 0.085%, AND ITS ROW IS OVER BY 6.3x** — BECAUSE THE
PLAUSIBILITY ARGUMENT THAT ADMITTED IT APPEALED TO A MAGNITUDE NOBODY HAD MEASURED. THE RECURSION IS
REAL AND IT IS **TWO NODES DEEP**.**

Priced BEFORE a line of fix, one instrument, no production behaviour change.
`docs/perf/type-node-key-price.md`.

- **(A) THE CANDIDATE, AND THE STATUS IT HELD.** `state.nodeTypes` (`Checker.kt:166`) is a
  `HashMap<TypeNode, Type>` keyed by the AST **value**; every concrete `TypeNode` is a `data class`
  (139 of them in `Ast.kt`, **zero** `override fun equals`), so each probe is a recursive structural
  hash — round 471's hazard. JFR: **57.1 ms**, the largest single map owner. Rounds 894-899 refuted
  eight of nine JFR-ranked candidates by dividing the row by its own population; this one **passed**
  that division at 161 ns/op, and CLAUDE.md recorded it as *"what makes that row worth believing"*.
  **That sentence is the round's subject.** A recursive `hashCode` licenses any rate you like — the
  check appealed to the depth of the recursion, and the depth had never been measured.

- **(B) THE CENSUS DECIDED IT BEFORE THE AMPLIFIER RAN.** `--typeNodeKeyCensus`:
  `calls 287,062 / hits 116,999 / misses 59,283 / bypassed 110,780`, **unindexed keys 0**.
  **Probe-weighted mean key subtree: 2.7567 nodes (max 337); 73.6% of probes present a key of at most
  TWO nodes.** At the measured 5.47 ns/node, 161 ns/op needs a **29.4**-node mean — exactly the 25-40
  the design predicted the row would require, and **10.7x** what exists. *The row was refuted by its
  own arithmetic the moment its population was known.*

- **(C) THE AMPLIFIER, THREE ARMS, AND THE PRIZE MEASURED DIRECTLY RATHER THAN BY PROXY.** `r = 8`
  and `r = 24`, 16 draws per `r`, two mirrored batches (round 891 — one rotation is a batch, not a
  result). Every sink an exact multiple of `r`; no arm flat.

  | arm | what it probes | ns/op |
  |---|---|---:|
  | A | `nodeTypes[node]` — deep hash + bucket + deep equals | **15.09** |
  | B | the same probe against a `(file, nodeId)` `LongKeyMap` | **2.11** |
  | C | `isPerFileDependentRefNode` — the second owner | **12.88** |

  `A − B` is the deep key's premium, i.e. **the prize of the proposed fix**, not a proxy for it.
  The map GET is amplified rather than `node.hashCode()` because a data-class hash is a pure function
  of an immutable object and C2 may hoist it — **and the exact-multiple sink falsifier would still
  pass**, which is the one failure mode that falsifier cannot see.

- **(D) THE DECISION: REFUSED BY 3.7x.** `(A − B) x 354,131 = 4.60 ms = 0.085%` of the 5,429 ms
  denominator, against this arc's ~0.31% (~17 ms) floor; **2.5x under it even at the most generous
  single-draw bound** (6.77 ms = 0.125%). `A − B` is an *upper* bound — arm B's key is computed
  outside every timestamp pair — so the refusal is certain rather than marginal. **Round 896's
  sentinel-set candidate falls with it**: 118,566 of those ops at this premium is 1.54 ms, against
  the 3-5 ms it was refused at on a soundness ground that no longer has to be argued.

- **(E) THE CORRECTION TO THE JFR ATTRIBUTION, WHICH STANDS WHATEVER THE VERDICT — AND WHICH THE
  ROUND FOUND BEFORE MEASURING ANYTHING.** The row has **TWO owners**, and a leaf-frame profile
  cannot separate them: `cacheable` (`Checker.kt:104175`) calls `isPerFileDependentRefNode`
  (`Checker.kt:99546`), *itself a recursive subtree walk over the same subtree the hash walks*, on
  **every** call — cacheable or not. 12.88 ns x 287,062 = **3.70 ms (0.068%)**. Family total
  **5.34 + 3.70 = 9.04 ms against a 57.1 ms row = over by 6.3x**, the ninth consecutive JFR
  over-read and at the top of the recorded 2.1-21x band. *An instrument that had bracketed only the
  map probe would have charged the walk to the hash and reported roughly double the prize.*

- **(F) ROUND 902's LAW DOES **NOT** BITE HERE, AND THAT IS INFORMATION RATHER THAN AN EXCEPTION.**
  Probe-weighted and object-weighted key sizes differ by **6.6%** (2.7567 vs 2.5856), not by round
  902's 193x. The law says which weighting to *check*; it does not say which one always wins. A round
  that assumed the probe weighting must dominate would have been right about the method and wrong
  about this population.

- **(G) WHAT THE DEEP KEY ACTUALLY BUYS, MEASURED — AND WHY IT IS NOT A LICENCE TO RE-KEY.** The
  structural key's entire semantic effect is **130 shared probes of 176,282 (0.074%)**, read off the
  two arms' sink difference and exactly `130 x r` at both `r` in both batches. The unit fixture had
  *passed* an `A == B` sink equality that the real population refutes; it is now a `<=` bound with
  the difference reported as a number. **`PerFileTypeNameCacheCollisionTest` pins the case where that
  sharing is WRONG**, so 0.074% is the size of the benefit, not of the risk.

- **(H) ARM C NEARLY SHIPPED AS A ROUND-902 DEAD ARM.** `isPerFileDependentRefNode` opens with
  `if (multiFileModuleTypeNames.isEmpty() …) return false` — on a program without such names it
  prices a field read while reading, in every driver output, exactly like a subtree walk. A REACHED
  control was added *before* the number was quoted and reports **5**, so the arm is live. Round 902's
  lesson applied one round later, prospectively rather than in the post-mortem.

- **(I) GATES.** Suite **14,409 / 0 failures / 0 errors / 3 skipped** (+13 = exactly the new pins;
  baseline 14,396). `cost_gate.py` **+0.00% on all 18 counters** — the expected control for an
  instrument-only round (round 876). `huge_methods.py --fail-over 0`: **0 over the limit**
  (`getTypeFromTypeNodeCore` grew and stays under; `Checker.<init>` 5,753/8,000). Build
  warning-clean. **No wall A/B, for the twelfth round running, and nothing to A/B** — the round lands
  an instrument and a refusal.

**Round 902 (2026-08-12) — (WARM.29): ROUND 901's SUCCESSOR — THE PARALLEL-ARRAY CONTAINER IT
PRICED AT 0.41-0.47% — IS **REFUSED, AND IT IS A REGRESSION**: MEASURED, IT COSTS **+13.75 ns PER
PROBE = −10.1 ms = −0.19%**. THE ESTIMATE WAS NOT OPTIMISTIC, IT WAS COMPUTED OVER THE WRONG
POPULATION: **A LEVEL IS SCANNED ONCE PER *PROBE*, NOT ONCE PER *EXISTENCE*, AND THE TWO MEANS
DIFFER BY 193x** (1.51 own symbols against **290.94**).**

No production behaviour changed; the round lands an instrument and a refusal. `docs/perf/lex-level-scan-price.md`.

- **(A) THE ROUND DID WHAT ROUND 901 SAID TO DO, AND THAT IS WHAT KILLED THE CANDIDATE.** Round 901
  refused a filter at 0.26% and named a successor — "replace the per-scope `HashMap` with a
  parallel-array linear scan (map fallback above ~8), 794,251 probes at ~3-6 ns instead of 33-37,
  ~22-25 ms = 0.41-0.47%" — then explicitly did **not** build it, because that rate was ESTIMATED
  and *the next instrument is a third `--lexLevelAmp` arm, not a fix*. Two arms were added (an
  unconditional scan and the HYBRID actually proposed). **Had the round trusted the estimate it would
  have shipped a 0.19% regression behind a container change touching a binder-OUTPUT type.**

- **(B) A SCOPE POPULATION IS NOT A PROBE POPULATION — ROUND 890's LAW, ONE FAMILY OVER.** Round 901
  priced the scan off `lexBoundHistogram`, which counts each `LexicalScope` **once**: `15270 8381
  3748 …`, 46.7% empty, mean **1.51**. Counting the same scopes once **per real probe** gives `0
  166388 101041 62112 44319 35255 28750 22145 15900 261681`, mean **290.94**, **212.12 scan steps per
  probe**. The unresolved-names ascent walks outwards, so it reaches the big outer levels on every
  walk: **35.5% of probes land on levels averaging 815 symbols**, and those alone are 213.2 M of the
  214.6 M symbols a scan would traverse per rebuild. *The cost of a scan is weighted by the probes;
  the cost of an allocation is weighted by the scopes. Round 901 measured the second and priced the
  first.*

- **(C) THE MEASUREMENT, WITH ITS FALSIFIER AND ITS CROSS-ROUND CONTROL.** Four arms under one
  timestamp pair each, cyclically rotated; `r = 4` and `r = 16`, two runs each, ABBA at the run level.
  Warm slopes **MAP 6.00 / FILTER 0.96 / SCAN 709.2 / HYBRID 7.42 ns per rep**; at `r = 1`, where the
  boundary cancels between arms, **the unconditional scan is +1,046 ns against the map and the hybrid
  is +13.75 ns**. Sink an exact 4x between the two `r` (nothing elided), scan and hybrid sinks EQUAL
  to the map's at both (the arms answer the same question), hybrid branch split 475,910/261,681
  matching the histogram to the unit. Round 901's two arms reproduce — MAP warm slope 6.00 vs 6.4,
  FILTER 0.96 vs 1.17, map-minus-filter first probe 28.9 ns vs 33-37 and the JFR row's 36.6.

- **(D) AND NO THRESHOLD RESCUES IT, WHICH IS THE STRONGER HALF.** The hybrid's scanned levels
  average **2.86** entries and it still loses, for two reasons that are not tuning parameters: the
  35.5% that fall back pay the array load, the null test and the length test **and then the whole map
  probe**; and 2.86 elements is 2.86 `String` dereferences to scattered objects against one cached
  hash and one `Node`. **Even if the replacement were FREE, the whole <=8 population is 13.8-17.6 ms =
  0.25-0.32%** — straddling round 897's 0.31% refusal floor. The arithmetic is closed before an
  implementation is chosen. `lexLevelHasName` is now CLOSED as a *container* question: both its levers
  are measured and both refused, the filter at +0.26% and the container at **−0.19%**.

- **(E) THE ABLATION — 6 ARMS, ALL DISCRIMINATE, AND TWO WERE **DEAD**, NOT BLIND.** B1 (scan arm
  dropped) 2 pins, B2 (hybrid dropped) 2, B3 (scan stops one short) 1 — *a strict subset of B1,
  recorded not dressed up*, B4 (array built from `symbols` **plus** `existing`) 3 **unique**, B5
  (histogram de-duplicated per scope — round 901's population injected deliberately) 2 **unique**, B6
  (size recorded as 1) 2 **unique**. **B4 and B5 read ALL PINS GREEN on the first pass and neither was
  a blind pin: both edits changed nothing.** B5 guarded on `lexScopes.contains(l)`, always true
  because `lexLevelHasName` calls `lexScope(l)` two lines above `lexAmp`; B4 polluted the array with
  `existing` keys, but the SourceFile root is the only level carrying an `existing` table past the
  untrusted-owner rule and the fixture's root bound nothing, so no amplified scope had one.
  ***Round 855 needs the sharper form: `git diff --shortstat` proves the EDIT landed, never that it
  DOES anything — and in a driver's output a dead arm and a blind pin are the same line.***

- **(F) THREE PIN SPLITS IN ONE ROUND, ALL THE SAME LESSON.** The re-weighting pin first compared
  against `lexScopeBoundKeys` and failed in the full suite at 80 against 586 — the bound count is
  lib-dominated on any small fixture (round 898's A3 / round 901's A2 for the third time in four
  rounds). The size census then began as ONE method, so B5 and B6 failed the same lone assertion; and
  its consistency assertion, first written against the CALL count, fired under both defects and
  separated neither. **An assertion that fires for two causes separates neither.**

- **(G) GATES.** Suite **14,396 / 0 / 3** (+6 = exactly the new pins; baseline 14,390).
  `cost_gate.py` **+0.00% on all 20 counters** — the expected control for a change that adds census
  hooks and one always-null field. `huge_methods.py --fail-over 0`: **0 over the limit**, 714 classes.
  **8-PROFILE `--listAll` GRID, ALL EIGHT `added=0 removed=0`** (46 each, harness 94), cross-round
  against round 901's captures, identical recipe — a control, run anyway because the hooks sit on the
  path that decides TS2304. **No wall A/B for the eleventh round running**, and nothing to A/B.

**Round 901 (2026-08-12) — (WARM.28): ROUND 899's LAST UNREFUTED CANDIDATE, `lexLevelHasName`, IS
**REAL — TWO INDEPENDENT INSTRUMENTS AGREE ON ITS RATE TO 0.5%** — AND IS **REFUSED AT ~14 ms
(0.26%)**. THE CENSUS THAT PRICED IT FOUND THE ROW'S ACTUAL CAUSE: **32,693 `HashMap`s HOLDING 47,490
KEYS BETWEEN THEM, 46.7% OF THEM EMPTY** — WORTH ~0.45%, WHICH THE FILTER WOULD HAVE FORECLOSED.**

Priced BEFORE a line of fix, one instrument, no production behaviour change.
`docs/perf/lex-level-probe-price.md`.

- **(A) THE POPULATION IS EXACTLY WHAT ROUND 899 DERIVED, AND THE DERIVATION STILL COULD NOT HAVE
  DECIDED IT.** **1,024,959 calls** against the predicted ~1.0 M. But **271,684 of the 1,009,275
  probe-path calls cost NOTHING**: `HashMap.getNode` reads `table` BEFORE it hashes the key, and a
  `mutableMapOf()` that was never written keeps `table == null`, so an empty level answers with a
  null check and a return. *Round 898's law one level down — an operation that short-circuits before
  it does anything is not one of the operations you divide by.* A census counting probes alone would
  have manufactured 7-13 ms of prize out of free work.

- **(B) THE ROW SURVIVES ITS ARITHMETIC, AND THEN SURVIVES A SECOND INSTRUMENT.** 29.8 ms over
  **813,571 REAL probes = 36.6 ns**, inside the 20-50 ns band — the second row in this arc (after
  round 900's candidate (5)) to pass, against 8 of round 894's 9 and 1 of round 899's 6 that did not.
  But that band was measured on `perFileScope`, whose keys are file PATHS in a populated table, and
  the mean queried level holds **1.5** entries, so the prior does not transfer (round 789).
  `--lexLevelAmp` measures it: **MAP warm slope 6.4 ns, FILTER warm slope 1.17 ns**, and the two-arm
  delta extrapolates to **33-37 ns for the FIRST probe** — which is the one production performs.
  **Two independent instruments, agreeing to 0.5%.** Sink is an exact 4x between r=4 and r=16, so
  neither loop was elided. *The amplifier amplifies BOTH arms in one call because at equal `r` the
  ~90 ns boundary cancels BETWEEN them, which is the only way a first-probe rate is readable at all.*

- **(C) REFUSED AT ~12.6-15.8 ms (0.23-0.29%), FOR THREE REASONS IN ASCENDING WEIGHT.** 474,954
  refusable probes, ~2.3% false positives, minus a filter test on all 737,591 real-probe calls and a
  0.5 ms eager build. **(a)** below this arc's floor — round 897 refused a LOW-risk change at 0.31%
  gross, 898 refused MEDIUM at 0.13-0.20%, 900 refused at 0.07-0.14% and built at 0.39%. **(b)**
  `cost_gate.py` reads **+0.00% by construction** (it removes probes, not resolutions), so its only
  defence would be a wall A/B at **a seventh of what this box settles** — round 899 resolved 1.88% in
  SIGN alone. **(c) THE REAL ONE: a filter in front of a container is a commitment to the
  container.** Refusing 58% of those probes banks the smaller half and removes the justification for
  replacing the container, which is worth nearly twice as much.

- **(D) WHAT THE ROUND DID *NOT* HAVE TO WORRY ABOUT, RECORDED BECAUSE IT WAS THE FLAGGED RISK.** The
  filter would sit BELOW INV.4(c)(ii)'s three load-bearing rules and guard ONE map probe whose answer
  it proves — not the function's verdict — so the untrusted-level, non-head-fn and root-exclusion
  rules are untouched by construction; and `LexicalScope.symbols` has **exactly one writer in the
  repo** (`Binder.kt` `declareLexical`), so a mask built at the end of `bindLexicalScopes` cannot go
  stale. **The soundness argument was fine. The number was not.**

- **(E) THE SUCCESSOR, PRICED FROM THIS ROUND'S OWN CENSUS.** Bound scopes by own-symbol count:
  `15270 8381 3748 1907 1171 768 456 394 174 424` — **46.7% hold ZERO** (an allocated `LinkedHashMap`
  that never receives a key), 93.2% hold <=4, **98.7% hold <=8**, tail 424 scopes. Replacing the
  per-scope `HashMap` with a parallel-array linear scan (map fallback above ~8) serves **794,251 real
  probes** across the three families at ~3-6 ns instead of 33-37: **~22-25 ms = 0.41-0.47%**, which
  clears every floor this arc has used. One writer, five readers (one audit-only). **NOT built: the
  array scan's own rate is ESTIMATED, and the next instrument is a third `--lexLevelAmp` arm, not a
  fix** (CLAUDE.md's first law). The 32,693 deleted allocations are recorded UNPRICED — an allocation
  count is not a cost (round 801).

- **(F) THE ABLATION — 6 ARMS, ALL DISCRIMINATE, FOUR UNIQUELY, AND TWO WERE BLIND FIRST TIME.** A1
  (EMPTY/REAL collapsed) 1 pin, A2 (dedup dropped) 1 **unique**, A3 (`real` frozen false) 3
  **unique**, A4 (map arm dropped) 1, A5 (hook outside its guard) 1 **unique**, A6 (mask one bit off)
  2 **unique**. A1 and A4 are caught but NOT separated — strict subsets — stated, not dressed up
  (round 807). **A2 was blind because the FIXTURE could not express the invariant** (round 898's A3):
  `queried <= bound` is vacuous on a small file where the lib binding dominates the bound count, and
  the discriminating form is the STRICT inequality against the probes. **A4 was blind because ONE
  SHARED SINK CANNOT TELL A DROPPED ARM FROM A RUNNING ONE**; splitting it per arm catches it and
  also buys the assertion that matters most — the filter is a SUPERSET of the map, so it can never
  sink less, which IS the proof-of-absence property and is what A6 exists to test.

- **(G) AND THE DRIVER CALLED A BLIND ARM A COMPILE ERROR.** Gradle prints `N tests completed, M
  failed` **only when something failed**, so its ABSENCE is a green run — the first pass reported
  that as `compile error`, a phrase that reads like infrastructure and would have buried both blind
  arms. *A driver's verdict vocabulary needs a word for "the mistake landed and nothing noticed".*

- **(H) GATES.** Suite **14,390 / 0 / 3** (+11 = exactly the new pins; baseline 14,379).
  `cost_gate.py` **+0.00% on all 20 counters** — the expected control. `huge_methods.py --fail-over
  0`: **0 over the limit**, 714 classes. **8-PROFILE `--listAll` GRID, ALL EIGHT `added=0 removed=0`**
  (46 each, harness 94), cross-round against round 900's captures, identical recipe — a CONTROL this
  round, run anyway because the hooks sit on the path that decides TS2304. No wall A/B for the tenth
  round running, and nothing to A/B.

**Round 900 (2026-08-12) — (WARM.27): ROUND 899's CANDIDATE (5) IS THE **FIRST JFR ROW IN THIS
ARC WHOSE ARITHMETIC CONFIRMS IT** — 767,521 inserts at **28.1 ns** each — AND THE COUNTER THAT
CONFIRMED IT ALSO FOUND WHY THEY EXIST: **A PROBE ARGUMENT HAD BEEN MATERIALISING ROUND 801's LAZY
VIEWS ON EVERY PRODUCTION COMPILE FOR NINETY-NINE ROUNDS.** CANDIDATE (1) **REFUSED** at 84.3 ns per
`Integer`-keyed probe.**

Both populations measured before a line of fix (CLAUDE.md's first law + round 898's admission test).
`docs/perf/suffix-name-index.md`.

- **(A) CANDIDATE (5), AND THE BINARY WAS POSED OVER THE WRONG QUANTITY.** Round 899 said ~0.5-1.0 M
  `HashSet.add`s "is implausibly large for a set built once per file and entirely plausible for one
  rebuilt per QUERY", so one counter decides it. The counter says **767,521 names inserted across
  1,143 sets**, i.e. **21.6 ms / 767,521 = 28.1 ns per add** — exactly a `HashSet.add` with a cached
  `String` hash. **The row survives its own plausibility test, the only one of round 899's six and
  round 894's nine that has.** But the sets ARE built once each (`built` memoises); they are simply
  HUGE, mean **671**. *A count of builds does not bound the work a build does — for a 100%-insert
  row the deciding quantity is INSERTS, and the binary named neither outcome.*

- **(B) THE LEVER IS THE THIRD COUNTER: THE SUFFIXES OF ONE SCAN ARE NESTED.** 1,143 suffixes are cut
  from **1,220 cached scans holding 15,331 names in total** — a 50x gap, because each suffix
  re-inserts the same tail of a shared array. Membership is then a comparison against the scan's LAST
  occurrence (`e in suffix(lo) <=> max{k : names[k]==e} >= lo`), so ONE lazily-built index per scan
  answers all of them. LAST and not first is load-bearing: a name reassigned both before and after a
  closure is the shape the structure exists for, and first-wins inverts it.

- **(C) AND THE FIRST BUILD STILL READ `materialized 1143`, WHICH IS THE ROUND'S REAL FINDING.**
  `FrontEnd.addClosureCensus(reassigned.size.toLong())` — the guard `if (mode != ON) return` is
  INSIDE the function and **Kotlin evaluates arguments strictly**, so it never got the chance to run,
  and asking a lazy view its size materialises it. The (FRONT.2) probe was building all 1,143 hash
  sets on **every production compile with the probe OFF**. Round 801 created `SuffixNameSet` to stop
  exactly that and read its own census (`created 1143, materialized 1143`) as "every set is
  eventually asked", concluding the work MOVED. **The asker was the instrument.** Post-fix the same
  census reads **`created 1143, materialized 0, inserted 0`** — nothing in production ever asks one
  its size — with **192 of 1,220** scans ever questioned, so 84% now build nothing. *A probe that
  must be free when off is not free when off if its ARGUMENT does the work.*

- **(D) THE PRICE, WITH ITS DEFLATIONS STATED.** 767,521 `HashSet.add` -> **0**, replaced by 11,619
  `HashMap.put`: **755,902 inserts removed = ~21.2 ms = ~0.39%** of a 5,429 ms rebuild at the rate
  the row and the population agree on. That rate is DERIVED from the JFR row, so a residual
  attribution bias deflates it proportionally; and no wall A/B is attempted at 0.39%, which is a
  fifth of what round 899's 12/12 sign test could resolve. **The claim is the deterministic
  population** (identical across runs) **and the arithmetic on it.**

- **(E) CANDIDATE (1) REFUSED, ON ITS OWN ARITHMETIC.** `resolveImportedSymbolGeneral` is a genuine
  double probe, and the census says **259,739 calls, all top-level, 251,380 hits (96.8%), 511,119 map
  probes** — not the **0.7-1.5 M** the 21.9 ms `containsKey` row needs. That is **84.3 ns per
  `Integer`-keyed probe** against this arc's 15-30 ns reference: **over-read ~3x, round 898's law for
  the ninth time.** The removable half is **3.8-7.5 ms = 0.07-0.14%**, below round 897's 0.31%
  refusal and at/below round 898's 0.13-0.20%. And it is not the five lines it looks: the value is
  `Symbol?` and `containsKey` is precisely what separates "absent" from "cached null", so one probe
  needs a SENTINEL — a correctness-carrying construct for 0.07-0.14%. Recorded without a price: the
  default argument `visited = mutableSetOf()` allocates on all 259,739 calls and 251,380 never touch
  it, but *an allocation count is not a cost* (round 801).

- **(F) CANDIDATE (2) NOT STARTED** — `lexLevelHasName` was ranked below both and the budget went to
  them. It remains the top open item, unrefuted, MEDIUM risk, grid required.

- **(G) THE ABLATION — 5 ARMS, ALL DISCRIMINATE, ONE WAS BLIND AND IT IS ROUND 897's A1 VERBATIM.**
  A1 (first-occurrence index) 3 pins, A2 (`> lo`) 8 pins incl. 5 pre-existing semantic ones, A3
  (index not shared) 1 **after repair**, A4 (the eager `.size` restored) 1, A5 (`contains`
  re-materialises) 4. Four have a uniquely-their-own pin; **A1 is caught but NOT separated from A2**
  — its failures are a strict subset — stated rather than dressed up (round 807). **A3 read a clean
  sweep on the first pass because sharing changes no ANSWER, only how many times the work is done**,
  so every membership pin stayed green, correctly; the repair is a COUNTER pin
  (`indexesBuilt <= scansBuilt`, `indexEntries <= scanNames`, both true by construction).

- **(H) GATES.** Suite **14,379 / 0 / 3** (+7 = exactly the new pins; baseline 14,372).
  `cost_gate.py` **+0.00% on all 20 counters** — the expected control, and here the statement that a
  change deleting hash-set inserts touches no resolution. `huge_methods.py --fail-over 0`: **0 over
  the limit**. **8-PROFILE `--listAll` GRID, ALL EIGHT `added=0 removed=0`** (46 each, harness 94),
  cross-round against round 898's captures, identical recipe — a real gate this time, not a control.
  `--verifyFlowScan`: 1,220 scans compared, **0 diverged**.

**Round 899 (2026-08-12) — (WARM.26): THE CUMULATIVE WARM A/B OF ROUNDS 895-898 — **B FASTER IN
12/12 PAIRS, BOTH BATCHES 6/6 ON OPPOSITE ROTATIONS, SIGN-TEST p = 0.0005 — BUT THE EFFECT IS
SMALLER THAN ONE ARM'S sd, SO THE *DIRECTION* IS ESTABLISHED AND THE *MAGNITUDE* IS NOT.** AND THE
SIXTH LEAF PROFILE RECONCILES WITH IT TO WITHIN ITS OWN RESOLUTION.**

**No production code changed.** Round 893 is the template; this block is ~4x smaller than that one
and the round was designed for that up front. `docs/perf/warm-leaf-profile.md` § 33.

- **(A) THE PRIOR, STATED BEFORE THE RUN.** Rounds 895-898 bank ~82-115 ms by counters (895's scan
  gating -64.3 ms, 896's `nodeToFlow` -17.9, 896's `perFileScope` 6.4-33; 897/898 are refusals plus
  flag-gated-OFF instruments, verified from the diff) = **1.5-2.1% of a ~5.4 s rebuild**, against
  per-arm sds round 893 measured at 2.21%/3.44%. So "not resolvable, the counters remain the claim"
  was a pre-declared acceptable outcome, and this note would have said so.

- **(B) THE RESULT.** Arms `63819970` (pre-895) and HEAD `7a859f00`, one JVM per SAMPLE (round 867),
  `WARMUP=6`/`ITERS=8` (the 2026-08-10 calibration), two batches of six pairs with OPPOSITE leading
  arms, box quiesced and left alone for the whole 40 minutes (round 774).

  | | A = `63819970` | B = HEAD |
  |---|---:|---:|
  | n (process medians) | 12 | 12 |
  | median | 5,418.4 ms | **5,242.6 ms** |
  | sd | 137.9 ms (**2.55%**) | 132.1 ms (**2.51%**) |

  **12/12 to B.** Pooled median paired **-1.88%**, mean -2.48%, per-pair range **[-6.51%, -0.70%]**,
  never crossing zero, exact two-sided sign test **p = 0.0005**. Batch 1 **6/6** (median -1.61%),
  batch 2 **6/6** (median -1.88%) — round 840(c)'s replication met on reversed rotations.

- **(C) AND THE MAGNITUDE IS WHERE THIS SAYS NO.** The three central estimators disagree by 1.7x
  (median of paired deltas -1.88%, mean -2.48%, median-of-medians -3.25%) and the paired range spans
  a factor of nine. Both arm sds are again over `ab-warm.sh`'s ~1% threshold, and **unlike round 893
  the effect is SMALLER than one arm sd**, so CLAUDE.md's "many times the sd" override cannot be
  invoked at all. What carries the direction is pairing plus replication; nothing carries a figure.
  **The defensible claim is "roughly 1.5-3%, direction certain, point estimate not resolvable on this
  box".**

- **(D) A CROSS-ROUND ANCHOR THAT CAME OUT UNUSUALLY WELL — AND IS LUCK, NOT A NEW LAW.** Arm A here
  differs from round 893's arm B by docs and two script lines; 893 measured that code at **5,424 ms**
  and this round at **5,418 ms — 0.11% apart across sessions**, where CLAUDE.md prices the anchor at
  up to 12.8% of drift. It does let the two rounds be laid side by side: **5,859 (pre-887) ->
  5,424/5,418 (pre-895) -> 5,243 (HEAD) = -10.5% over rounds 887-898.**

- **(E) SAME ANSWERS, DIFFERENT CODE.** All 192 measured rebuilds report 78 files / 46 errors;
  both arms' `--listAll` capture is 46 diagnostics, digest `59d930db849399aea5e03e25fedb8e4e` (the
  round-841 cross-round recipe, the same digest round 893 recorded), zero-line diff, no
  `... and N more error(s)` truncation (round 811). 694 vs 711 classes with `SourceScanFilter` (895)
  and `MapCensus` (896) in B only, asserted by the driver BEFORE it ran a sample (round 853).

- **(F) THE SIXTH LEAF PROFILE, AND THE FIRST TIME THIS ARC HAS RECONCILED THREE INSTRUMENTS.**
  Recipe identical to 888/893; 8,127+7,742 samples, max depth 182/170 vs the 512 cap, `checkSpine`
  inclusive 74.09%/74.00%, denominator **5,429 ms** (round 870). The three landed changes can be
  added up PER OWNER: `nodeToFlow` -42.6 gross but **+19.1 back as `LongKeyMap` frames = -23.5 net**;
  `perFileScope` (`lookupPerFile` -29.7, `globalsForFile` -7.8, memo +1.2) **= -36.3**; the scan
  gating (String family -74.7, `SourceScanFilter` +22.5) **= ~-52**. **Total ~-112 ms = 2.1%**,
  against the A/B's -1.88% and the counters' 1.5-2.1%. Round 893 closed with "the excess is largely
  attributed, not yet explained"; here the three instruments agree to within their own resolution.
  **A container swap must be priced NET — 45% of `nodeToFlow`'s gross came straight back.**

- **(G) BY FAMILY: the map family 1,300.7 -> 1,136.5 ms and String/StringBuilder 222.5 -> 147.8** —
  exactly the two families the rounds targeted; everything else rose and the residue is flat, which
  under a shrinking denominator is what an unchanged cost looks like (round 870) plus drift, so only
  the falls are read. `HashMap$TreeNode` is **0 samples in 15,869** for the second round running. By
  ROW the top owner is `cpaSpineLeave` at **1.81%** and nothing else clears 1.3% — round 874's law
  holding for the SIXTH take. **No C2 key split** behind any large row delta; and `ctaSpineEnter`
  reads 0.87% vs 0.61% WITHIN this round, so a cross-round delta of that size is uninterpretable.

- **(H) THE RANKED LIST — SIX, AND EVERY ONE CARRIES ROUND 898's ARITHMETIC FILTER** (ms ÷ population
  -> is the implied per-op cost physically possible?). § 33.8. **(1) `resolveImportedSymbolGeneral`**
  24.3 ms — a PROVEN `containsKey`-then-`get` double probe (round 896's `globalsForFile` shape) on an
  **Int**-keyed, boxed cache; real only at ~0.7-1.5 M probes/rebuild, **population unknown, first
  instrument is a counter**; ~8-15 ms, LOW risk, same-answers. **(2) `lexLevelHasName`** 30.1 ms — NOT
  a double probe (two different maps per level, O(depth) ascent); ~1.0 M probes at ~30 ns is
  plausible against `globals.lookups` 748,522, so **not refuted**; the lever is a proof-of-absence
  filter (round 895's shape) over a scope name set frozen after bind; ~8-12 ms, MEDIUM, grid.
  **(3) `getTypeFromTypeNodeCore`** 57.1 ms, the largest map owner — 354 k ops -> **161 ns/op**, which
  is plausible ONLY because the key is a data class with a recursive `hashCode` (round 471); the
  deletable part is the deep hash, ~10-20 ms, **HIGH** risk (cache SHARING, `typeNode.cacheHits`,
  program order, round 787). **(4) REFUSED HERE ON ARITHMETIC: the two UNCENSUSED whole-map copies**
  `spineArithFnFrame` + `spineCaCopyTop` = 42.0 ms JFR, which at round 898's measured 2.6-3.4x
  over-read is ~12-16 ms — below the 0.31% at which round 897 refused a LOW-risk change. **(5)
  `SuffixNameSet.materialize`** 21.6 ms insert-100%, where the arithmetic makes the answer BINARY:
  ~0.5-1.0 M adds is implausible for a set built once per file and plausible for one rebuilt per
  query — one counter decides it. **(6)** `Integer.equals` at **29.4 ms** of key-side leaf work is a
  LOCATION for the residual boxed-Int maps, with no owner named yet.

- **(I) WHAT IS *NOT* ON THE LIST, AND THE RULE IT ESTABLISHES.** `lookupPerFileForNode` is the
  second largest map owner (40.7 ms) and its arithmetic is textbook — round 897 counted 1,063,149
  probes, so 31.1 ms of `HashSet.contains` is **29 ns/probe**. It is absent because the LEVER is
  absent (a bitset refuses only the 440,003 misses = 2-9 ms; interning is 17.2 ms against 11.1 ms per
  parse and round 825's concurrency blocker). **A real cost with no known lever is CLOSED, not open.**

- **(J) tsgo — AND HALF OF IT WAS ALREADY CLOSED BY ROUND 889, WHICH THIS ROUND ALMOST RE-RAISED.**
  `LinkStore[K, V] { entries map[K]*V; arena }` has two axes and they must not be conflated. The
  GROUPING axis (~25 stores of co-accessed fields) **round 889 censused and refused** — our top such
  cluster is 0.49% and the bottom is one sample. The KEY axis is open and unmeasured (889's key-shape
  census left 40.8% "unclassified"): tsgo keys by `*ast.Node`, an 8-byte ADDRESS, where we key
  `nodeTypes` by the AST VALUE. That is candidate (3) and nothing more. **Unpriced; recorded only to
  fix which half is closed.**

- **(K) GATES — STATED IN FULL BECAUSE MOST WERE NOT RUN.** No production source changed (`git diff`
  touches only `scripts/` and `docs/`), so **no suite run, no `cost_gate.py`, no `huge_methods.py`,
  no 8-profile grid** were required or run — the same posture as round 893 (K). What WAS run, and is
  the round's own gate: the two arms' `--listAll` same-answers control above, the driver's
  class-dir positive controls, BenchMain's per-rebuild files/errors abort on all 192 rebuilds, and
  the profile's depth/thread/`checkSpine`-inclusive validity checks. The repo's `build/classes` was
  restored from the HEAD snapshot after arm A's build and verified at 711 classes. Source edits are
  the three new scripts plus one ROUNDS line in each of `round888_families.py`,
  `round888_compare.py`, `round894_hash_owners.py`.

**Round 898 (2026-08-12) — (WARM.25): ROUND 894'S LAST TWO OPEN CANDIDATES, **BOTH REFUSED**, AND
WITH THEM THE WHOLE RANKED LIST: **EIGHT OF ITS NINE CEILINGS ARE NOW MEASURED AND EVERY ONE IS OVER
BY 2.1-21x.** THE ROUND-891-vs-ROUND-894 CONTRADICTION RESOLVES **IN ROUND 891'S FAVOUR** —
`EpochMap` copies are **11-15 ms**, not 38.1.**

Priced BEFORE a line of fix, one instrument, no production behaviour change. `docs/perf/copy-family-price.md`.

- **(A) CANDIDATE (8), THE CONTRADICTION, SETTLED — AND HALF OF IT WAS NEVER A CONTRADICTION.** Round
  891 DERIVED 14-24 ms for `EpochMap(localTypes)`; round 894's JFR census MEASURED
  `Checker$EpochMap.<init>` at 38.1. But round 892 removed **124,709 entries** from that family (the
  narrowing frames, moved onto `MapScopeStack`), so 891's derivation **re-stated at the population
  894 was looking at** — 347,017 entries at 30-51 ns — reads **10.4-17.7 ms**. The amplifier measures
  **11.2-14.8 ms**, inside it. *A derivation is a claim about a population as much as about a rate;
  re-state it before calling it wrong.*

- **(B) AND THE CENSUS FIGURE IS REFUTABLE WITH NO BUILD AT ALL.** 38.1 ms over that family's own
  population is **1,394 ns to copy a 12.6-entry map = 110 ns PER ENTRY**, where a `HashMap` insert
  with an already-cached `String` hash is tens of ns. Round 896 stated this rule after applying it to
  candidate (4) and it was not applied here. The mechanism is CLAUDE.md's round-623 entry — a JFR
  leaf share is not a wall-clock price, and `HashMap.putMapEntries` is exactly the tight allocating
  loop that attracts samples; the EXTENDED measure amplifies it by construction.

- **(C) THE OBVIOUS RESCUE IS REFUTED BY ITS OWN ARM.** "A copy costs `K + c*n` and the per-entry
  derivation dropped `K`" is a good hypothesis — `EpochMap`'s mean copy is 12.6 entries against the
  37.6/114.3 the rate came from. It is false: **`EpochSet(paramBindings)` makes 35,015 copies of mean
  1.1** — the most call-dominated container of the six — and its slope is **unresolvable (0.5-2.8
  ms)**. If a fixed per-call cost were worth anything, that family would show it.

- **(D) CANDIDATE (6) REFUSED, AND THE COST THAT DECIDED IT IS A POPULATION THE CENSUS COULD NOT
  SEE.** `spineArgListOverlay` is **393 copies/rebuild** (365 nested-fn overlays of mean 633 entries
  + 28 shadow-minus of mean 753) carrying 8,152 writes; measured **12.7 ms = 0.23%** against a 41.2
  ceiling. The chained-scope-map replacement trades an O(1) probe for an O(depth) walk — so this
  round counted the probes: **56,096 `SpineArgCtx` lookups/rebuild, 29,703 (53%) MISSES**, and a miss
  must walk the chain to the END. That is 1-6 ms back, leaving **~7-11 ms (0.13-0.20%)** — below the
  **0.31%** at which round 897 refused a change it rated LOW risk, where this is MEDIUM (TS2554/2555,
  shadowing rules bug-compatible with a deleted legacy walker), and **no counter in
  `cost-counters.txt` moves**, so its only defence would be a wall A/B at a fifth of what this box
  settles. The sharpest single line in the census: **the shadow-minus copies 21,086 entries to remove
  29 names — 727 entries per name.**

- **(E) A NEW COUNTER, BECAUSE `writes/entries` ANSWERS ONLY THE UNDO-LOG QUESTION.** It is a
  whole-FAMILY write count and cannot tell one copy written a hundred times from a hundred copies
  written once — different shapes, different levers. Charging a copy ONCE, on its first write:
  **6,598 of 27,337 `EpochMap` copies (24.1%) are never written and they hold 188,774 entries =
  54.4% of the volume**, and the never-written ones are the BIG ones (mean 28.6 vs 7.6). So a
  copy-on-write `EpochMap` — which needs **no LIFO discipline at all**, the one structural argument
  this family has never had — is worth ~6-8 ms (0.11-0.15%). Recorded, refused: establishing it needs
  exactly the >=12-site pointer-swap audit round 891 declined, for 0.13%.

- **(F) THE INSTRUMENT'S OWN LESSON — A BALANCED PALINDROME IS NOT ENOUGH.** Batch 1 ran three arms
  (r=0/16/32) x 4 draws in two MIRRORED rotations, both palindromes, so a LINEAR drift cancels in
  each by construction — and they disagreed **2x on `em`, 4x on `al`, and by SIGN on `es`**. The raw
  draws say why: one rotation reads 5,869/5,848/5,483/5,544/5,571/5,100, monotone downward, i.e. the
  warm-up is still running at rebuild 14 and what a palindrome cannot cancel is its NON-linear
  remainder. **The fix is fewer arms and a bigger `r`, not more rotations**: batch 2 (TWO arms,
  r=64, 8 draws, palindromic, two processes) gave 13.99/15.52 for `em` and 11.46/13.93 for `al`, with
  **every one of the 16 `r=64` draws above every one of the 16 `r=0` draws**.

- **(G) WHAT THIS DOES TO ROUND 894 § 9 — IT IS A LOCATION LIST, NOT A PRICE LIST.** (1) 3.9x, (2a)
  1.1-5.4x, (2b) 4.8-21x, (3) 2.6x, (4) 5.6-14x, (5) 2.8-6.7x, (6) 3.2x, (8) 2.6-3.4x. The ratios
  cluster at ~3x wherever the owner really IS one map operation and run higher where the owner row
  was mostly something else — a systematic attribution bias plus a per-candidate misidentification,
  not eight independent over-estimates. Two build-free steps now stand between a row there and a
  decision: divide the row by its own population, and name the ONE map operation it is supposed to
  be.

- **(H) THE ABLATION — 8 ARMS, TWO BLIND, TWO DIFFERENT REPAIRS.** All eight discriminate after
  repair; 4 have a uniquely-their-own pin (A5-A8), A1/A2 and A3/A4 are discriminated as PAIRS and not
  from each other (stated, not dressed up). **A8 (the shadow-minus hook deleted) was blind because
  the two copy sites in one function SHARED one census family** — the survivor kept the counter
  non-zero, so no pin could see the other fail; the repair is the split, which is also what produced
  (D)'s 727-entries-per-name row. *A census family that cannot be zero is a census family that cannot
  be wrong.* **A3 (the first-write record never cleared) was blind because the FIXTURE could not
  express the invariant** — rounds 891/892 moved the fn-body locals onto `MapScopeStack`, so a
  single-file compile copies near-EMPTY maps (5 copies, 4 entries, none written twice) and "counted
  at most once" is vacuous; a class with methods, `this` and two callback shapes reaches 21 copies /
  34 writes and the pin now ASSERTS that multiplicity.

- **(I) GATES.** Suite **14,372 / 0 failures / 3 skipped** (+14, exactly the `CopyCensusTest` pins).
  `cost_gate.py` **+0.00% on all 20 counters** — the expected CONTROL, and here also the statement
  that hooks on `EpochMap.put` and on the arity read of every call expression are inert.
  `huge_methods.py --fail-over 0`: **0 over the limit**. **8-PROFILE `--listAll` GRID, ALL EIGHT
  `added=0 removed=0`** (46 each, harness 94), cross-round against round 897's committed captures,
  identical recipe. `scripts/round898-grid.sh`.

- **(J) NO WALL A/B, FOR THE NINTH ROUND RUNNING — and this time there is nothing to A/B.** The round
  lands an instrument and two refusals; what is claimed is deterministic populations (27,337 copies /
  347,017 entries / 42,378 writes / 56,096 lookups) and a slope with an arithmetic falsifier that
  held on all 60 instrumented rebuilds.

**Round 897 (2026-08-12) — (WARM.24): ROUND 894'S **TOP-RANKED** CANDIDATE, SCANNER IDENTIFIER
INTERNING, IS **REFUSED** — MEASURED PRIZE **17.2 ms (0.31%)** AGAINST A **67.7 ms** CEILING, MEASURED
COST **11.1 ms PER PARSE**, AND A BLOCKER THE CENSUS'S "RISK: LOW, A HANDFUL OF LINES" DID NOT SEE:
THE SCANNER RUNS ON THE CRAWL'S **N CONCURRENT WORKERS**.**

Everything below was priced BEFORE a line of fix (CLAUDE.md's first law), with one instrument —
`NameCensus.kt`, a `namecensus<N>` `BenchMain` tier, `scripts/round897-census.sh`, four JVMs in two
batches. `docs/perf/name-intern-price.md`.

- **(A) THE REGIME FACT, WHICH DECIDES HOW EVERY COST HERE IS READ.** `CrawlParseCache` serves the
  program's parse from the previous request, so **in the warm regime this arc measures the Scanner
  does not run at all**. An intern probe is paid once per file VERSION; the map probes it makes
  cheaper are paid every rebuild. That also **corrects the census**: its 24.8 ms/rebuild of
  `String.hashCode` is read there as "one hash per fresh identifier instance", but with a cached
  parse those instances persist and `String.hashCode` caches per instance — an identifier is hashed
  ONCE PER PROCESS. Whatever those 24.8 ms hash, interning at the Scanner cannot touch it, so the
  candidate's ceiling was **42.9, not 67.7 ms**, before any other deflation.

- **(B) THE POPULATIONS.** ~527 k identifier-shaped tokens per program parse collapse onto **~22.4 k
  distinct names — a 94.4% intern hit rate**, mean length 10.4 chars. On the resolution path,
  **1,063,149 `moduleOnlyGlobalNames` probes of which 623,146 HIT (58.6%)**, plus 440,003 onward
  `globals[name]` reads. The hit RATE is the quantity that matters and nobody would have guessed it:
  `HashMap` calls `String.equals` only for an entry whose 32-bit hash matches, so a MISS never walks
  characters and **only a hit can pay what interning removes**.

- **(C) THE PRIZE, BY REPLAY (round 896(B)'s shape) — 17.2 ms/rebuild (0.31%)**, four processes
  14.2 / 15.3 / 20.3 / 19.1. The captured probe sequence is replayed against the real member
  population twice, once with the production instances and once with every string collapsed onto a
  canonical one, ABBA per rep, falsified by ARITHMETIC (every arm's hit count an exact multiple of
  the reps, and the two arms agreeing on all of them — interning's equivalence claim in miniature).

- **(D) AND THE DECOMPOSITION IS THE ROUND'S BEST FINDING: INTERNING IS ONLY HALF `String.equals`.**
  The MAP arm is the control that makes the set arm readable — `globals` holds 185 entries and the
  replay hits it 10,383 times per rep, so an equals-driven delta there is at most 0.15 ms, yet the
  measured delta is **4.9-6.7 ms**. **97% of it is not `equals` at all: it is the KEY-OBJECT WORKING
  SET collapsing from ~400 k `String` instances to ~22.4 k.** Splitting the set arm the same way
  gives **`equals` 9.1 ms + locality 8.1 ms**, and **14.6 ns per equal-but-distinct comparison** —
  § 5a's mechanism measured directly for the first time. **The locality half is invisible to a
  leaf-frame profile**, because it is not time in `String.equals`, it is time in whatever frame
  dereferences the object; any census that ranks a candidate off leaf frames under-reads it.

- **(E) THE COST, AND A DESIGN THE CENSUS DID NOT CONSIDER.** `scanIdentifier` ALREADY probes a
  `String`-keyed map for every identifier-shaped token (`KEYWORDS[word]`), so one table holding the
  ~160 reserved words *and* every interned name answers both questions in one lookup. Measured:
  **52.4 ns/token as a separate table (27.6 ms/parse), 21.2 ns/token folded (11.1 ms/parse)**. The
  fold halves it and cannot go much lower — 434 k intern hits per parse at 14.6 ns is 6.3 ms on its
  own. **11.1 ms is not "clearly below" a 17.2 ms saving**, and cold — the CLI, the shipped GraalVM
  image CI benches per push, an edited file in a daemon — the census's own preferred design is a
  net LOSS.

- **(F) THE BLOCKER, WHICH IS THE STRONGER GROUND, AND THIS ROUND'S OWN INSTRUMENT PROVED IT.** The
  prize needs a PROGRAM-WIDE table (it comes from probe and stored key being one object;
  `moduleOnlyGlobalNames`' 4,088 members are minted in whichever file declared them and probed from
  all 78, so a per-FILE table captures ~none of it) — and `parseForCrawl` runs inside
  `withContext(Dispatchers.Default)`. A program-wide `HashMap.getOrPut` from `scanIdentifier` is
  **round 825's hazard verbatim**. The proof is the census's own numbers: its SCANNER counters
  disagree by up to 4.7% across four processes on one binary while its CHECKER counters are
  identical to the last digit in all four. The thread-safe designs are an `expect`/`actual`
  concurrent map on the hottest loop in the front end, or canonicalising after the parse — which
  means writing `Identifier.text` and breaking INV.2(a), the property `CrawlParseCache` and
  `RealLibSnapshots` both rest on.

- **(G) TWO MORE CORRECTIONS TO ROUND 894'S LIST, FROM THE SAME RUN.** **(2b) is refused**: at a
  58.6% hit rate a "definitely absent" bitset pre-filter is worthless for a hit, its reachable
  population is the 440,003 misses, and its prize is **~2-9 ms (0.04-0.16%)** against a ≤42.9 ms
  ceiling. **(7) is CLOSED, not deferred**: round 896 refused it because its prerequisite was
  unbuilt; that prerequisite now costs 11.1-27.6 ms per parse and is blocked, while (7)'s own
  deletable part is 6.5 ms — it can never pay for what it needs. **Three of round 894's ceilings are
  now measured at 4-10x their answer**, which is round 896's finding for the third time.

- **(H) THE ABLATION TOOK THREE PASSES AND THE TWO IT FAILED ARE THE LESSON.** Six single mistakes,
  one at a time, dry-run for a real diff, on a committed tree. Final: **5 of 6 discriminate, one pin
  each**; **A2 (the `canon` insertion order) is a REDUNDANT guard** — canonicalisation is applied to
  both sides, so whichever occurrence wins the slot the two sides agree — recorded rather than
  claimed (round 809). Pass 1 read 4 of 6 green. **A1 (the interned arm probing the RAW container)
  was a pin that did not exist**: swapping the container does not change the ANSWER, so every
  hit-count pin stays green, correctly — only the container's IDENTITY can see it. **A1's first
  repair was then blind because of the arms' own ABBA rotation** — a fault in the even branch is
  overwritten by the odd one and the end state reads healthy, so the observable had to become
  STICKY. *A rotation that protects a measurement can hide a fault in it.* **A5/A6 were blind
  because the FIXTURE masked them**: both publish pins seeded the snapshots in between, and `seed`
  installs them directly, so a last-wins or premature capture was overwritten before it could be
  observed.

- **(I) GATES.** Suite **14,358 / 0 failures / 3 skipped** (+8 from 14,350, exactly the eight
  `NameCensusTest` pins). `cost_gate.py` **+0.00% on all 20 counters** — the expected CONTROL for a
  round that lands no behaviour change (round 876), and here it also says the inert hooks are inert.
  `huge_methods.py --fail-over 0`: **0 over the limit**. **8-PROFILE `--listAll` GRID, ALL EIGHT
  `added=0 removed=0`** (46 diagnostics each, harness 94), cross-round against round 896's committed
  captures with the identical recipe — run because the round adds a hook to a line every identifier
  of every file crosses, and a gate whose expected answer is "nothing moved" is a control.
  `scripts/round897-grid.sh`.

- **(J) NO WALL A/B, AND THIS TIME BECAUSE THERE IS NOTHING TO A/B.** The round lands an instrument
  and a refusal. What is claimed is deterministic populations (1,063,149 probes, 623,146 hits,
  ~22.4 k distinct names) and paired nanos over identical populations inside one process.

**Round 896 (2026-08-12) — (WARM.23): TWO OF ROUND 894'S FIVE MAP-KEY CANDIDATES TAKEN, THREE REFUSED
WITH A COST — AND THE REFUSALS ARE THE FINDING: **TWO OF THE FIVE CEILINGS ARE AN ORDER OF MAGNITUDE
ABOVE THE ANSWER**, WHICH THE POPULATION COUNT SETTLES IN ONE DIVISION.**

**QUEUE-TAG NOTE, FIRST, BECAUSE THE LEDGER IS ALREADY DIRTY: rounds 894 and 895 were BOTH committed
as `(WARM.19)`, which is round 871's tag.** So `(WARM.19)` now names three unrelated pieces of work
(the `--serve` request ladder, the hash-owner census, the whole-source scan filter). This round is
`(WARM.23)`; `(WARM.20)`/`(WARM.21)`/`(WARM.22)` are taken (`--reachCensus` carries WARM.22), and a
next round should read the tag off the usage text rather than off the previous session note.

Round 894 ranked five candidates in `docs/perf/warm-hash-owner-census.md` § 9, each with an UPPER
BOUND ("if this owner's map work went to zero"). All five were priced BEFORE a line of fix, per
CLAUDE.md's first law, with one instrument each — `MapCensus.kt`, `scripts/round896-census.sh`.
The scoreboard is § 11 of that doc.

- **(A) THE PRICING INSTRUMENTS, AND WHY EACH HAD TO BE ITS OWN SHAPE.** A lookup can be amplified
  (round 759) and an INSERT cannot — re-putting one key into one container measures an OVERWRITE,
  not the distinct-key insert production performs. So `--flowMapReplay N` replays each file's REAL
  key sequence into a **fresh** `mutableMapOf` and a **fresh** `LongKeyMap` per rep, ABBA within the
  file; `--perFileScopeAmp N` is the ordinary amplifier; and the two sentinel SETS need no timing at
  all, because a population of 24,232 with a max live size of 3 refuses itself.

- **(B) TAKEN — (3) `nodeToFlow` -> `LongKeyMap`: 32.3 ms -> 14.4 ms, i.e. 17.9 ms/rebuild (0.33%),
  replicated at 17.6 ms in a second run.** 262,404 keys/rebuild across 83 files — round 864's own
  entry count, reached independently by a different instrument. The ceiling was 46.6 ms; a
  replacement probe is not free, which is the whole 2.6x. **The put/get SPLIT does not replicate
  (28.3/4.0 vs 22.1/10.8) while the TOTAL does (32.3 vs 32.9)** — quote the total.

- **(C) THE SWAP'S ONE NEW FAILURE MODE IS THE KEY, NOT THE CONTAINER, AND IT IS A CRASH.**
  `LongKeyMap` reserves `0L` as its empty-slot sentinel and `require`s against it at `put` — and
  `nodeKey(0, 0)` **IS** `0L`, which an error-recovery zero-width node at offset 0 really produces.
  `flowKey` shifts both coordinates by one: still a bijection (the pack is injective, `NODE_KEY_MIX`
  is odd), so round 889's spread is untouched and only WHICH pair lands on the sentinel moves — to
  `(-1, -1)`, where `recordFlow` never writes and a synthetic node's READ correctly answers null.

- **(D) AND THE ITERATION-ORDER AUDIT IS NOW STRUCTURAL RATHER THAN A CLAIM.** Round 894 audited
  `nodeToFlow` as get/put/size only. `LongKeyMap` has no iterator, no `keys`, no `entries` — so the
  rounds-754/776/778 hazard (an iteration-order change invisible in every output diff) is a COMPILE
  ERROR here, not a review item. This is the argument for preferring it to a plain `HashMap`
  wherever an audit says "never iterated". `Binder.nodeToSymbol` IS iterated and must not follow.

- **(E) TAKEN — (2a) `perFileScope`: 650,394 map probes/rebuild -> 6,426 (-99.01%).** Two halves.
  The double probe (`globalsForFile`'s `containsKey` + `lookupPerFile`'s `get`, the file PATH hashed
  twice per name) is now one, which alone halves READS to 325,236; a one-entry reference-compared
  memo then answers **318,810 of those 325,236 (98.0%)** without touching the map. The memo is
  round 895(D)'s shape — **a miss is never wrong, only slower** — and identity is a property of the
  TRAVERSAL (`perFileScope`'s keys ARE `sourceFile.fileName`, which every hot caller arrives
  holding), never a bet on string equality: keying the MAP by identity is the unsound version, and a
  pin drives the miss path with a `StringBuilder`-built path to say so.

- **(F) ITS PRICE IS A RANGE AND THE WIDTH IS THE INSTRUMENT'S, NOT THE CHANGE'S.** Pre-change
  amplification over all 650,394 probes: p(4) = 83 ns, p(16) = 163 ns, so a repeat probe is 6.7 ns;
  the in-situ empty bracket reads 53 ns, which rounds 734/735 say BOUNDS the pair rather than
  measuring it. One production probe is therefore **10-51 ns** and removing 643,968 of them is
  **6.4-33 ms**. Round 894's JFR attribution — 34.7 ms over those same probes = 53 ns each — sits at
  the top of the range. **The `String.hashCode` cache is why the bottom of the range is so low**: a
  60-100 character path is hashed ONCE ever, and the probe then takes `String.equals`' identity fast
  path, so "hashing a long path per lookup" was never what this cost.

- **(G) REFUSED — (4) `symbolTypeResolutionInProgress`, and the refusal corrects the census.**
  **24,232 adds/rebuild, MAX LIVE 3.** The census's 28.2 ms ceiling would be **1,164 ns per add** —
  ~20x a boxed `HashSet` probe on a table that never leaves its initial 16 slots. It cannot be that
  set; § 2 of the census flags that very row as inlining-migration-suspect and the population
  settles it. The whole set is worth **~2-5 ms** and a replacement recovers a fraction. Same for
  `memberResolutionInProgress` (13,019 adds, max live 6).

- **(H) REFUSED — (5) `nodeTypeResolutionInProgress`, for a cost AND a soundness reason.**
  **59,283 adds** — which is exactly `typeNode.cacheable - typeNode.cacheHits` (176,282 - 116,999)
  in `cost-counters.txt`, an independent confirmation the hook is on the right population — at 2
  deep data-class hashes each. The `nodeTypes` path pays 354,131 deep hashes in total (one per
  cacheable call, plus add/put/remove per miss), so the sentinel is **33.5%** of it, ~3-5 ms against
  the census's TypeNode-shaped key rows. Buying that needs IDENTITY keying, which **weakens the only
  cycle guard on the cacheable path**: two structurally-equal nodes in different files are one key
  today and would become two, so a cycle that terminates at `errorType` would recurse instead — in a
  compiler whose doctrine forbids catching `StackOverflowError`. Not worth 3-5 ms.

- **(I) REFUSED — (7) `AliasedCondKey`, because its prerequisite is unbuilt.** Packing
  `(FlowNode, String, String)` into a primitive needs interned NAME ids, i.e. round 894's candidate
  (1). Without it the pack must probe a `String`-keyed intern table per key construction — the very
  `String` hash-and-equals being removed (round 788: skipping cached work MOVES it). The part a pack
  would actually delete is the measured `AliasedCondKey.equals` row, **6.5 ms** of the owner's 22.4;
  the rest is the map probe any keyed container still pays.

- **(J) THE ABLATIONS — AND ONE PIN THAT DOES NOT DISCRIMINATE, RECORDED RATHER THAN CLAIMED.** Six
  single mistakes, one at a time (round 807), each dry-run for a real diff first (rounds 855/856),
  on a COMMITTED tree (rounds 789/851). `scripts/round896-ablate.sh`: **A1** shift removed -> 3 pins
  red; **A2** injectivity broken -> the 2 injectivity pins red; **A3** only one coordinate shifted ->
  exactly 1 pin red (the synthetic-position one, which is why it exists).
  `scripts/round896-ablate-2a.sh`: **B1** memo ignores its key -> 4 red; **B2** memo becomes the
  ORACLE -> 7 red. **B3 — `globalsForFile` falling through to merged `globals` — leaves everything
  GREEN, and it is a REDUNDANT GUARD rather than a blind pin: INV.3(d) already retired module-only
  names out of `globals`, so the fall-through reaches a null anyway.** The pin was RENAMED to what
  it does test (round 809). **A2 also names what the equivalence pins CANNOT see**: the INV.2(b)
  side table is filled FROM the map, so a key collision makes both paths agree on the same wrong
  answer — only an injectivity pin sees it.

- **(K) GATES.** Suite **14,350 / 0 failures / 3 skipped** (+11 from 14,339: 7 `FlowMapKeyTest` +
  4 `PerFileScopeMemoTest`, verified from the diff — no existing file gains a `@Test`).
  `cost_gate.py` **+0.00% on all 20 counters** both times, and for (2a) that is a real control, not
  a formality: `globals.lookups` / `globals.conflated` / `globals.misses` measure that very
  resolution path. `huge_methods.py --fail-over 0` 0 over the limit. **8-PROFILE `--listAll` GRID,
  ALL EIGHT `added=0 removed=0`** — and note the shape, since neither change has an in-binary arm:
  the before-side is round 895's committed captures produced by the parent commit with the
  IDENTICAL recipe (round 841: a capture is a property of OUTPUT x RECIPE). 46 diagnostics per
  profile, harness 94. `scripts/round896-grid.sh`.

- **(L) NO WALL A/B IS CLAIMED, FOR THE EIGHTH ROUND RUNNING.** ~0.33% + ~0.1-0.6% is inside what
  this box settles (~1%). What is claimed is deterministic counters (650,394 -> 6,426 probes;
  262,404 keys) and the mechanism's own paired nanos over an identical population in one process.
  Round 893 vindicated the practice collectively at -8.18%.

**Round 895 (2026-08-12) — (WARM.19): THE WHOLE-SOURCE `indexOf` FAMILY IS GATED — **488,469,784
CHARACTERS SCANNED PER REBUILD -> 22,894,093**, AND THE MECHANISM'S OWN WARM NANOS GO **86.2 ms ->
21.9 ms (-64.3 ms = -1.24%)** OVER AN IDENTICAL POPULATION WITH THE SAME 14 HITS. AND A COLD CENSUS
WOULD HAVE BEEN WRONG IN BOTH DIRECTIONS.**

Round 894 § 10 found, while separating key-side leaves out of the HashMap census, 116 ms/rebuild of
whole-source `String.indexOf` in ~50 `Checker.check*` pin walkers — no owner above 0.16%, invisible
to `cost_gate.py`, never counted. `docs/perf/whole-source-scan-census.md`.

- **(A) THE POPULATION FIRST, BEFORE A LINE OF FIX (CLAUDE.md's first law).** The instrument is a
  helper (`srcHas`/`srcIndexOf`/`srcLastIndexOf`) that every whole-source scan routes through, plus
  counters and — affordable uniquely here, because a whole-source scan is tens of microseconds
  against a ~90 ns pair — a TIMESTAMP PAIR per scan and per build. **3,827 calls over 488,469,784
  characters = 49 whole-program passes, to find 14 needles (0.37%).** They are corpus-unique pins
  (`"import { 0n as foo }"`, `"Shebang is only allowed on the first line"`), so on tsc's own sources
  essentially none can ever match.

- **(B) THE SITE COUNT IS NOT THE POPULATION, AND THE MAJORITY OF THE SITES ARE LEFT ALONE.** Of 218
  scan sites, **69 are CHAR searches bounded to a node position** (`source.indexOf(';', exprEnd)`) —
  the largest single form, and almost none of the cost. They are not rewritten: one character is
  below the filter's window width, so gating them would be pure overhead. Rewriting all 218 would
  have looked more thorough and measured worse.

- **(C) THE MECHANISM, AND WHY A FALSE NEGATIVE IS IMPOSSIBLE RATHER THAN UNLIKELY.**
  `SourceScanFilter` records a hash of every 4-character window of a file's text in a bitset. A
  needle can occur only if EVERY one of its own windows occurs, so one clear bit is a PROOF of
  absence. If `needle` occurs at `p`, then window `j` of the needle **is** window `p+j` of the text,
  which the build visited and whose bit it set. Hash collisions and the 7-bit character fold can
  only make windows look PRESENT, i.e. produce false POSITIVES — and a false positive costs one
  scan, because **the real `indexOf` remains the oracle**: the filter is consulted only to SKIP a
  call, never to answer one. Measured selectivity: **3,723 of 3,827 refused (97.3%), 95.3% of the
  characters, 90 false positives in 3,813 = 2.4%.**

- **(D) THE CACHE IS LENGTH-KEYED AND IDENTITY-PROBED, ON PURPOSE.** A `HashMap<String, Filter>`
  would hash ~10 M characters of file text once per file for nothing (round 894's own candidate (1)
  is about exactly that cost). `SrcScanCache` is a 1,024-slot open-addressed table keyed on
  `text.length` and matched with `===`; **a miss is never wrong, only slower** — it rebuilds. One
  instance per `Checker`, so a `--workers` run shares no mutable state.

- **(E) WARM, BOTH ARMS ALTERNATING IN ONE PROCESS — AND COLD WOULD HAVE BEEN WRONG IN BOTH
  DIRECTIONS.**

  | mechanism | cold | warm | warm-up |
  |---|---:|---:|---:|
  | `String.indexOf` over 488 M chars | 436.3 ms | **86.2 ms** | **5.07x** |
  | the filter build over 10 M chars | 65.9 ms | **18.9 ms** | **3.49x** |
  | **net removed** | 345.8 ms (1.49% cold) | **64.3 ms (1.24% warm)** | |

  The build is a hand-written scanner and warms 3.5x, almost exactly CLAUDE.md's round-859 figure;
  the JDK intrinsic warms **5.07x**, MORE than a whole rebuild's ~3.4x. **So a cold census
  OVERSTATES what gating a scan is worth — the exact opposite of round 859, where a cold table
  UNDERSTATED an ungated `java.util.regex` because that does not warm at all.** Text scanning is a
  different regime cold and warm in BOTH directions; take the warm one.

- **(F) ROUND 894's 116 ms WAS RIGHT AND ~26% OF IT WAS NEVER ADDRESSABLE.** The string-needle part
  measures 86.2 ms warm; the remainder is predominantly the 69 char searches, which are also
  `java.lang.String.indexOf` frames in a JFR dump and are gateable by nothing. Reading a leaf-frame
  FAMILY as one prize is round 758's population-vs-frequency law one instrument over.

- **(G) NO WALL A/B IS CLAIMED.** 1.24% is inside what this box settles; the four instrumented
  rebuilds read `overheadMs` -70 / -116 / -317 / +261, i.e. noise. What is claimed is the
  mechanism's own nanos, PAIRED, on ONE binary, with the population and the 14 hits identical in
  both arms — a same-answers control taken in the same run as the price. This is the seventh round
  running to decline a wall number, which is the house style since round 893 vindicated the practice
  collectively at -8.18%.

- **(H) THE REWRITE WAS DECIDED BY A PARSER AND VERIFIED BY INVERSION, NOT BY EYE (round 819).**
  `scripts/round895_srcscan_apply.py` rewrote 149 sites; `scripts/round895_srcscan_verify.py`
  INVERTS the rewrite and demands the original file back byte for byte — **134 lines differ, 0
  inversion failures, and the multiset of all 13,725 string literals is identical** (round 684: a
  scripted sweep may re-flow arguments but never their string literals). Two mistakes the compiler
  caught that no review would have: 8 sites pass the index as a NAMED argument (`startIndex = n`),
  and one passes a `Char`. Type safety is the whole safety net here — the helpers take `String`.

- **(I) WHAT DID NOT WORK, AND WHAT THE PINS CAUGHT.** The first rewrite pass renamed the helpers'
  own bodies into calls to themselves (infinite recursion) because their parameter was also called
  `source` — fixed by naming it `text`, which is a naming rule, not a code rule. **And the partition
  pin failed on HEAD and was RIGHT: `tooShort` calls also increment `scanned`, because a needle
  below the window width falls through and IS scanned** — the three counters are not disjoint, the
  partition is `refused + scanned == calls` and `tooShort` is a SUBSET. A census that reports a
  bucket it never had is exactly what a partition assertion exists to catch, and it caught it on its
  first run.

- **(J) THE ABLATION, AND ITS POSITIVE CONTROL.** `--srcScanBogus` corrupts the build so it records
  only every second window, which makes the filter refuse needles that ARE present.
  `SrcScanTest` asserts the gated diagnostic (TS18026 via `checkShebangError`, a walker whose whole
  body is behind one whole-source gate) **DISAPPEARS under the bogus filter and is present on the
  same binary with it intact** — so the pin discriminates, and the gate is load-bearing. Under
  `--verifySrcScan` the verifier reads `verified > 0, divergences == 0` sound and
  `divergences > 0` bogus: a verifier that read 0 in both cases would be worthless (round 790).

- **(K) THE COMMITTED GRID HARNESS HAS BEEN A ONE-PROFILE GRID ALL ALONG.** `bench-compile-tsc.sh`
  names the compiler profile `tsc-project-<commit8>` and the other seven `tsc-<name>-<commit8>`;
  every grid script in `scripts/` globs `build/bench/tsc-project-*`, which matches **the compiler
  profile and nothing else** — round 888's output directory holds exactly one profile's captures.
  `scripts/round895-grid.sh` enumerates profile dirs by the presence of a `tsconfig.json` and
  REFUSES to run with fewer than 8.

- **(L) GATES.** Suite **14,339 / 0 failures / 3 skipped**; the delta is exactly the 12 new
  `SrcScanTest` pins, verified from the diff (no existing test file gains a `@Test`), which makes
  the pre-round total on this box 14,327 — three above the 14,324 round 892 recorded, a discrepancy
  inherited and not chased. **8-PROFILE `--listAll` GRID, both arms of one binary, ALL EIGHT
  `added=0 removed=0`** (deprecatedCompat/jsTyping/compiler/server/services/tsc/typingsInstallerCore
  46 diagnostics, harness 94); the differ refuses a truncated capture (round 811) and an empty one
  (round 804), and the harness asserts `SourceScanFilter.class` is in the class dir before running
  (round 853). `cost_gate.py` **+0.00% on all 20 counters** — the EXPECTED control here (round 876),
  since these walkers touch no checker counter and emit nothing on the profiles, which is precisely
  why the grid and the corpus are the real gates. `huge_methods.py --fail-over 0` 0 over the limit.

---

### QUEUE — work top-to-bottom; promote unblockers per protocol

**WORK ORDER NOTE (restored 2026-08-14, round 903).** This section had been ARCHIVED out of the file
during a trim, and nothing noticed for ~15 rounds because rounds 886-902 were self-directing: each
session note named its own successor. **Round 902 ended with a CLOSURE and named none, so round 903
opened with no pool at all** and had to rebuild one by surveying `docs/perf/`. That is the failure
this section exists to prevent. **A round that refuses a candidate must leave at least one named
successor here, with its price and its next instrument** — a refusal is a successful round only if
the arc can continue from it.

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

- [ ] **(WARM.32) The iterator-allocation family — NEW, from the ../xemantic-rust-compiler transfer
  audit (round 903), never swept here.** Kotlin's `Iterable.any`/`all`/`forEach` are `inline` but
  their bodies are `for (e in this)` on an `Iterable` receiver, so each asks for a **heap iterator**
  and pays `hasNext`/`next` virtual dispatch per element. Two populations: **`NodeWalk.forEachChild`
  has 70 `list.forEach(action)` calls and runs ONCE PER NODE** (~857 k nodes x three sweeps —
  `spineWalkFile`, `Binder.pushChildren`, `FlowGraph`'s side-table fill), and it is **#5 in the warm
  leaf table at 1.40%**; and **140 `.any { it === child }` across ~40 INV.4 edge classifiers**,
  running per (parent, child) edge over round 875's 3.32 M edge evaluations. The sibling project
  measured **-3.1%** converting exactly this shape (its PH3) and recorded WHY the sampler did not
  over-promise there: **an object handed to an iterator escapes by construction**, so escape analysis
  was never going to fold it. **THE CAVEAT THAT DECIDES THE INSTRUMENT: the value here is NOT the
  bytes.** Round 801 removed 367,189 `String` allocations for exactly **0 ms** and round 893 measured
  warm GC at ~1.7% of wall — so this must be priced in TIME (a two-arm amplifier, iterator vs indexed
  loop, over the real node population), never in MB. Bounded above by round 875's 44 ms for the whole
  edge-predicate population. LOW risk: mechanical, and `ForEachChildOracleTest` already pins the
  enumeration reflectively.

- [ ] **(WARM.33) reach-machinery (b) — transpose the 43 per-file memos. <= ~0.8% (<= 43 ms),
  ESTIMATED.** 43 separate `ByteArray`s per file, each probed at a scattered `nodeId` at 2.23
  classifiers/node; one array of 43 statuses PER NODE puts a node's whole row in 1-2 cache lines.
  Also deletes 36.9 MB/rebuild of allocated+zeroed `ByteArray`. Queued by round 875 § 9 **with its
  number attached so it is not re-estimated upward**. Next instrument: a transposed-layout amplifier
  arm on the memo probe ALONE, before touching 43 classifiers — the third-arm discipline that saved
  round 902 from shipping a regression it had estimated at +0.41%.

- [ ] **(WARM.34) `lexLevelHasName` — the COUNT question. Ceiling 21.4-27.3 ms (0.39-0.50%);
  realistic value UNPRICED.** Round 902 closed this family as a CONTAINER question (filter +0.26%,
  parallel array **-0.19%, a regression**) and explicitly left this one open, unopened: the 737,591
  real probes arise from an O(depth) ascent that **revisits the same big outer levels on every walk**
  (35.5% of probes land on levels averaging 815 symbols). The lever is memoizing the ASCENT, not the
  probe. **Cheap first step, no build**: census how many of the 737,591 are RE-probes of a level
  already probed for the same name within one resolution. Two instruments already agree the rate is
  36.6 ns, so that census alone decides it. Risk: INV.4(c)(ii)'s three load-bearing rules sit
  directly above it.

- [ ] **(SPINE.1) The six spine handlers' frame bookkeeping — STALE DENOMINATOR, re-take before
  costing.** `ccetSpineLeave` / `spineCtaM3StatementAnchor` / `cpaSpineLeave` / `ctaSpineEnter` /
  `spineIanyEnterNode` / `spineArithEnterNode` are ~61-63% of the spine. Round 847's per-handler ms
  are against an **8,095 ms** rebuild and must not be quoted; today's JFR top row is `cpaSpineLeave`
  at **1.81%** and nothing else clears 1.3%. **Apply round 733's deflation BEFORE costing anything**:
  88.4% of the `…SpineLeave` time is the migrated passes' own checking work, not scaffolding.
  `reach-machinery.md` § 9 names this as the remaining place with more than 1% in it.

**CLOSED THIS ROUND, DO NOT RE-RAISE** (round 903, `docs/perf/type-node-key-price.md`): the
`nodeTypes` deep AST-value key, **refused at 0.085%** — its premium over a `(file, nodeId)`
`LongKeyMap` is 12.98 ns over 354,131 ops = 4.60 ms, and `A - B` is an UPPER bound. Round 896's
`nodeTypeResolutionInProgress` sentinel falls with it at 1.54 ms. The JFR row's other owner is
`isPerFileDependentRefNode` at 3.70 ms; family 9.04 ms against a 57.1 ms row.

**ALSO RECORDED, UNPRICED, from the round-903 hot-path audit** (candidates with a named mechanism but
no measurement — each needs the build-free population step first): `mappedNodeTypeKey`
(`Checker.kt:104288`) builds a `StringBuilder` + two `sortedBy` copies + a `String` + a key object per
bypassed resolution (~88 k/rebuild) purely to be a map key; `collectTypeofGuardNames` &c allocate a
`LinkedHashSet` unconditionally where the caller only asks `isNotEmpty()`, then `Set.plus` copies both
operands again (`Checker.kt:54541`-`54572`); `spineOsWithAmbient` (`:18209`) and
`spineTcDispatchWithAmbient` (`:66469`) are closure-taking and NOT `inline` on a measured-hot path;
`narrowTypeFromFlow`'s `memo: NarrowFlowMemo = NarrowFlowMemo()` default allocates through the
`$default` bridge at every call site that omits it.
