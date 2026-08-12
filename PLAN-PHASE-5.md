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

**Round 893 (2026-08-12) — THE CUMULATIVE WARM A/B OF ROUNDS 887-892: **-8.18% MEDIAN PAIRED, B FASTER
IN 12/12 PAIRS, BOTH BATCHES 6/6 ON OPPOSITE ROTATIONS** — THE ARC'S FIRST DEMONSTRATED WARM SPEED-UP,
AND IT EXCEEDS THE SUM OF THE SIX ROUNDS' OWN PRICES. THE GC EXPLANATION IS REFUTED; THE LEAF PROFILE
SHOWS RED-BLACK BUCKET PROBING AT **EXACTLY ZERO**, DOWN FROM 5.91% OF WARM WALL.**

Six consecutive rounds landed counted removed work and every one of them declined to claim a wall
number — correctly, each being ~0.5-1% against a box that settles at ~1% (rounds 840(c)/858/886 each
recorded a disagreeing second batch at that size). The arc's actual wall effect was therefore
UNMEASURED. This round measured it. **No production code changed.** `docs/perf/warm-leaf-profile.md`
§ 32.

- **(A) THE PROTOCOL, WHICH IS THE ROUND.** Two BUILDS — `6a4e3612` (parent of 887) and `abf184ee`
  (HEAD) — snapshotted to two class dirs, **one JVM per SAMPLE** (round 867: two arms that share a
  compiled method are not independent arms), `WARMUP=6` / `ITERS=8` (the 2026-08-10 calibration: two
  identical arms sit 3.3% apart at warm-up 2 and 0.8% at 6), sample = that process's median.
  **Two batches of six pairs with OPPOSITE leading arms**, so each arm leads exactly 6 of the 12
  pairs — round 891's 4x disagreement came from a rotation that left the leading draw's ~15% on one
  arm. Box quiesced (`--stop` + bracket-pattern kotlin-daemon kill BEFORE measuring, round 851;
  14.4 GB free), and **the box was left alone for the whole 50 minutes** (round 774: watching a
  benchmark is part of the benchmark).

- **(B) THE RESULT.**

  | | A = `6a4e3612` | B = `abf184ee` |
  |---|---:|---:|
  | n (process medians) | 12 | 12 |
  | median | 5,859 ms | **5,424 ms** |
  | mean | 5,882 ms | 5,418 ms |
  | sd | 130 ms (**2.21%**) | 186 ms (**3.44%**) |

  **B wins 12/12. Median paired delta -8.18% (-479 ms); mean -7.87%; per-pair range
  [-12.03%, -2.81%], never crossing zero. Batch 1 6/6, median -7.32%; batch 2 6/6, median -9.51%.**
  Median-of-medians -7.42%.

- **(C) THE SD IS ABOVE THE QUIET-BOX THRESHOLD AND THIS SAYS SO EXPLICITLY.** `ab-warm.sh`'s rule is
  that a warm run whose per-arm sd exceeds ~1% was not measured on a quiet box and its verdict should
  be discarded. Both arms exceed it (2.21% / 3.44%). CLAUDE.md permits an explicit override when the
  effect is many times the sd; **here it is ~2.4x the larger sd, which is NOT the "tens of times"
  case, so the override is not what carries this.** What carries it is the SIGN: 12/12 is 1-in-4,096
  under the null, the per-pair range lies wholly below zero, and the two batches are independently
  6/6 with the rotation reversed — round 840(c)'s replication requirement, met. **-8.18% is not a
  point estimate to quote as a precise figure**; the defensible claim is "a real speed-up of roughly
  5-10%".

- **(D) THE CONTROLS THAT MAKE THIS A COMPILER MEASUREMENT.** Both arms emit **46 errors** over 78
  files on every one of the 192 measured rebuilds (BenchMain aborts the run on any files/errors
  drift), the compiler-profile `--listAll` digest is `59d930db849399aea5e03e25fedb8e4e` for BOTH —
  the cross-round recipe, `grep 'error TS' | sort` — with a **zero-line diff** and no
  `... and N more error(s)` truncation in either capture (round 811). The binaries are structurally
  distinct: **694 vs 688 classes**, with `MapScopeStack`/`SetScopeStack` (892) and `SpineMask` (888)
  present only in B, asserted as positive controls by the driver before it ran a sample (round 853 —
  a gate reading a class dir needs proof the code under test is in it). **Same answers, different
  code.**

- **(E) THE EXCESS: -8.18% AGAINST ~3-5% OF SUMMED PER-ROUND PRICES.** Three candidate explanations
  were on the table; the round tested one, the leaf profile answered a second, and the third is open.

- **(F) GC IS REFUTED, AND ON BUDGET RATHER THAN SIGN.** One process per arm with `-Xlog:gc`, same
  6+8 shape: **A 68 pauses / 1,375 ms total / 65.4 ms max; B 86 pauses / 1,287 ms / 43.4 ms.**
  B does MORE GC cycles for slightly less pause, and the whole budget is **~92-98 ms per rebuild =
  ~1.7%** in both arms — an order of magnitude below the effect, so **eliminating GC entirely could
  not produce 8%**, whichever way the arms fell. The 6.3 ms/rebuild that separates them is 0.1 of the
  8 percentage points. Stated as a MEASURED NEGATIVE. (n=1 per arm with logging on, so the -4.4%
  those two processes show is not a verdict; the budget is what n=1 establishes, and it is enough.)

- **(G) THE LEAF PROFILE, RE-TAKEN A FIFTH TIME ON ROUND 888's EXACT RECIPE, FINDS THE MECHANISM.**
  Two processes, `stackdepth=1024`, `delay=60s,duration=90s`, `jfr print --stack-depth 512`, filtered
  to `xtsc-deep-stack`, stdlib charged to the nearest non-stdlib OWNER; 7,942 + 8,113 samples, max
  depth 212/174. Denominators are each round's own `medianMs` (888: **5,905 ms**; 893: **5,461 ms**),
  because a JFR share is a share of WALL TIME and the rebuild just got 7.5% shorter (round 870).

  | leaf-class family | ms888 | ms893 | Δ |
  |---|---:|---:|---:|
  | own code | 3,650.0 | 3,487.7 | -162.3 |
  | **HashMap / HashSet** | **1,560.7** | **1,300.5** | **-260.2** |
  | String / StringBuilder | 259.8 | 222.5 | -37.3 |
  | ArrayList / ArrayDeque | 262.6 | 273.8 | +11.2 |

  and split out of that family: **`HashMap$TreeNode` as a leaf was 5.91% = 348.8 ms/rebuild at round
  888 and is 0.00% — ZERO samples in 16,055 — at round 893.** Rounds 889/890 did not make map lookups
  a bit cheaper; **they removed an entire mechanism.** Reading the two rows together: of the 348.8 ms
  of red-black probing, ~89 ms returned as ordinary linear bucket probing and **~260 ms is gone**.
  **Round 890 priced its own change at ~0.5%** — under-read ~9x, because an amplifier counts map
  OPERATIONS while the cost of a treeified bucket is superlinear in its depth (890 measured max
  bucket 1,140 -> 6 for `Relation.packKey`).

- **(H) OWNER FAMILIES (round 874's unit), ms/rebuild.** `cta*` handlers **216.0 -> 84.3 (-131.7)** =
  rounds 891/892 landing exactly where they were built (they priced themselves at ~83 ms, so ~1.6x
  under-read); `flow-graph build` **207.2 -> 134.0 (-73.2)** and `module/import resolution`
  **192.3 -> 133.9 (-58.4)** = round 889's `nodeKey` finalizer (`nodeToFlow` is a `FlowGraphBuilder`
  table); `spine walk core` -15.9; `name resolution` -11.9; residue -131.5. **At the OWNER level the
  biggest movers are `ctaSpineEnter` -54.6, `FlowGraphBuilder.recordFlow` -51.3, `cpaSpineLeave`
  -10.8, `spineEnterNode` -9.0** — no row is more than ~1% of the rebuild, which is round 874's law
  holding for the fifth take running: **the ROW is the wrong unit and the FAMILY is the right one.**

- **(I) TWO CAVEATS ON THE TABLE THAT MUST TRAVEL WITH IT.** The profile spans **889-892** (round
  888's dump already contains 887+888) while the A/B spans **887-892**; and it is a CROSS-ROUND
  ABSOLUTE comparison, which CLAUDE.md prices at up to 12.8% of drift on identical code. So the table
  RANKS mechanisms and only the paired A/B MEASURES the arc. Also unchanged: **a JFR leaf share is
  not a wall-clock price** (round 623 eliminated a 5.3% leaf for -0.3%) — what lifts this one above
  "candidate" is that an independent paired A/B on the same code in the same session measured -8.18%.

- **(J) WHAT IS STILL UNEXPLAINED.** GC refuted; superlinear red-black probing directly supported and
  probably most of it; **non-additivity under a shifting denominator (round 870) remains open and is
  not separately testable from here.** The two under-readings the profile exposes (hash ~9x, `cta*`
  ~1.6x) roughly close the gap arithmetically, but that is a coincidence unless each is priced
  independently. **The honest state: the excess is largely ATTRIBUTED, not yet EXPLAINED.**

- **(K) GATES.** No production code changed — no suite run, no `cost_gate.py`, no `huge_methods.py`
  were required or run. The only source edits are two ROUNDS-dict lines in
  `scripts/round888_compare.py` / `scripts/round888_families.py` (adding the 893 dumps) plus docs.
  The repo's `build/classes` was restored from the HEAD snapshot after arm A's build, verified at 694
  classes.

**Round 892 (2026-08-11) — (WARM.18b): THE FAMILY ROUND 891 REFUSED IS **CONVERTED** — `CtaFrame`'s
localTypes+declNodes+shadowed AND THE NARROWING FRAME'S `EpochMap`, **1,214,236 ENTRIES COPIED PER
REBUILD -> 28,695 UNDO RECORDS OVER AN IDENTICAL POPULATION** — AND ALL THREE REFUSAL REASONS
DISSOLVED UNDER THE INSTRUMENT ROUND 891 QUEUED BUT DID NOT BUILD.**

Round 891 refused this family for three named reasons and queued **(WARM.18b)** with its instrument
named: *"a counting facade over `CtaFrame.localTypes`, run for one census, BEFORE any code."* This
round built exactly that, first, and read it before writing a line of the fix.
`docs/perf/cta-frame-copy-families.md` §§ 6-10.

- **(A) THE INSTRUMENT, AND WHY IT HAD TO BE A FACADE.** A THROWAWAY counting facade over every write
  path of all three components (round 890's shape: census, read, **revert**). It is the only way in —
  the maps are reached by REFERENCE through the ambient fields (`currentLocalTypes` &c), so a hook
  anywhere else is round 891's own 2-of-3-paths hook again, which read 6.3x optimistic with nothing
  saying so.

  | write path | count |
  |---|---:|
  | `localTypes.put` @ fn-frame | 24,572 |
  | `localTypes.remove` @ fn-frame | 81 |
  | `localTypes.put` @ narrowing frame | 1,942 |
  | `declNodes.put` / `.remove` @ fn-frame | 1,582 / 352 |
  | `shadowed.add` @ fn-frame | 166 |
  | **writes needing an undo record** | **28,695** |
  | writes at the file root (no scope open — free) | 567 |
  | `putAll`/`addAll` anywhere but the seed, and `clear()` | **0 / 0** |

  **28,695 against 1,089,527 entries copied = 2.6%**, the same order as `varTypes`' 1.4%. Adding the
  narrowing frames' 124,709 entries the population at stake is **1,214,236**.

- **(B) THE THREE REFUSALS, ANSWERED — AND TWO OF THEM WERE ARTEFACTS OF NOT HAVING MEASURED.**
  (i) the ratio is 2.6%, not an un-instrumented zero. (ii) **`ambiguousNames` RESETS rather than
  shadows — true, and irrelevant: it is not in the family.** A fn frame gives it a FRESH EMPTY set,
  which is O(1); it was never COPIED, so there is nothing to replace and no mechanism to find. The
  refusal treated a sibling FIELD as a sibling COST. (iii) the narrowing frame's
  `EpochMap(top.localTypes)` — **the one real reason, and its answer is to convert it too, onto the
  same stack.** Not optional: a function declared inside a then-branch takes its base from
  `ctaFrames.last()`, so a copy there and a live map at the fn frame is exactly the
  two-disciplines-over-one-ambient-field hazard round 891 named — a nested body would inherit the
  PRE-narrow map. It is also worth another 124,709 entries.

- **(C) THE CONDITION TABLE, RE-AUDITED OVER ALL 423 REFERENCES** (374 `currentLocalTypes`, 12
  `currentLocalDeclTypeNodes`, 37 `currentShadowedNames`). Strictly LIFO on `ctaFrames` with a
  per-file `reset`; **no `.keys`/`.values`/`.entries`/`.forEach`/`.iterator`/`.sorted`/
  `.toMutableMap` reader and no `for (x in map)` anywhere** (the one whole-collection read is
  `name in currentShadowedNames`, i.e. `contains`); **0 `clear()` calls, measured**; 433 removals,
  all recordable. **The retention question is the one round 891 could not settle and it resolves
  cleanly:** the 56 `= currentLocalTypes` sites are 37 local `val saved…` POINTER SWAPS (the object
  identity is stable, so the restore puts the same object back) plus exactly two FIELD retentions —
  `spineCaRestingLocalTypes` and `spineArithBase` — both taken at SPINE ENTRY, i.e. the PRE-SPINE
  resting map, which is never a cta frame's. And the ≥12 ad-hoc `EpochMap(currentLocalTypes)`
  installs that round 891 read as disqualifying are harmless *because* each is a genuine DETACHED
  snapshot that is written into and then discarded by a pointer-swap restore — a copy that never
  merges back cannot observe the difference between a copy chain and a live map.

- **(D) WHAT LANDED.** `MapScopeStack<V>` + `SetScopeStack` (`ScopeStack.kt`). **Round 891's
  `VarScopeStack` is RETIRED ONTO the generic class rather than copied beside it** — the
  reverse-replay mechanism now exists once, not twice. The set twin needs two things the map does
  not: one BIT per touched element (`had` = present-before) instead of a value, so the restore is a
  different statement; and `addAll` recording per ELEMENT. **Two flags on `CtaFrame`, because the two
  scope-opening shapes are not the same shape:** `localScoped` (fn-body AND narrowing frames -> pop
  the localTypes stack) and `ctaFnScoped` (fn-body only -> also pop declNodes and shadowedNames,
  which the narrowing frame SHARES with its parent). The three `putAll(base.*)` seeds are simply
  GONE: `base` is `ctaFrames.last()` at all eight call sites and every frame now holds the live view,
  so the scope just opened already holds what the copy would have been seeded with.

- **(E) ONE DELIBERATE, STATED DIVERGENCE.** The `onMutate` hook reproduces the expression-memo epoch
  bump the replaced `EpochMap` performed, and it is a SUPERSET — the fn-body maps were plain
  `HashMap`s and did not bump, so ~25,000 writes now bump that did not. That is the SAFE direction
  for a probe-only fence (round 660): an extra bump can only make the shadow memo MISS, where a
  missing one could make it serve a stale entry.

- **(F) THE CONTROLLED ROW (round 793 — the change moves no boundary and no population).**

  | | before | after |
  |---|---:|---:|
  | `CtaFrame` local pushes | 9,525 | **11,016** (+1,491 = the narrowing frames, MOVED here) |
  | entries copied by it | **1,089,527** | **0** |
  | undo records | 0 | **28,695** |
  | `EpochMap` pushes / entries / writes | 28,828 / 471,726 / 44,320 | 27,337 / 347,017 / 42,378 |
  | entries copied, ALL SIX families | 1,600,775 | **386,539** |

  **42.3x less work over an identical population.** The three `EpochMap` deltas are exact
  (−1,491 / −124,709 / −1,942 = the narrowing frames, their entries and their writes), which is the
  falsifier that they MOVED rather than vanished. **And the headline is an INDEPENDENT cross-check:
  the undo log records 28,695 — the throwaway census's write count to the unit — measured by a
  different instrument, on a different binary.**

- **(G) PINS AND ABLATION.** `ScopeStackTest` (round 891's 13, re-pointed at the generic class, plus
  12 new: the set twin's shadow/restore/`addAll`/root/reset/no-op/`clear`, the map at a NON-`String`
  value type, the `onMutate` contract and the map's `clear`) and a new `CtaLocalScopePinTest` whose 4
  pins go THROUGH A COMPILE and assert BOTH directions of one shadowing — the inner binding wins
  inside the body (a TS2322 that must be PRESENT, which is what stops the others passing vacuously)
  and the outer binding is back after it (a TS2322 that must be ABSENT, reachable only if the pop
  restored). **THE ABLATION — 12 arms, one mistake each, on a committed tree, two source
  files (mechanism in `ScopeStack.kt`, wiring in `Checker.kt`):**

  | arm | the mistake | red |
  |---|---|---:|
  | A1 | map `pop` replays FORWARD | 2 |
  | A2 | map `pop` restores nothing | 13 |
  | A3 | a write records ABSENT | 9 |
  | A4 | set `pop` restores nothing | 4 |
  | A5 | the set records "was PRESENT" unconditionally | 2 |
  | A6 | `push` records mark 0 | 8 |
  | A7 | a file-root write IS recorded | 2 |
  | A8 | `reset` keeps the entries | 2 |
  | A9 | the NARROWING frame shares instead of scoping | **1** (was **0**) |
  | A10 | the fn pop drops the declNodes/shadowed pops | **0** |
  | A11 | `reset` skips the localTypes stack | **0** |
  | A12 | the fn-body frame opens NO localTypes scope | 3 |

  **Every non-zero red set is DISTINCT** (A3/A6 and A7/A8 each differ by exactly one pin); **five arms
  have a uniquely-their-own failing pin** (A2, A4, A7, A8, A9) and the other five are discriminated
  by their SET only, which is stated rather than dressed up. **THE ABLATION'S REAL PRODUCT IS THAT IT
  FOUND A MISSING PIN, WHICH IS THE STRONGEST EVIDENCE IT WAS WORTH RUNNING: A9 — the arm for the
  invariant that DECIDED THE ROUND — reddened NOTHING**, because no fixture contained an `if`; round
  813's law is that such a green is as often BLIND as it is redundant, and here it was blind. **The
  first replacement pin then FAILED ON HEAD** — this compiler emits nothing at all for
  `const s: string = <string | undefined>`, so a nullish pin would have been vacuous in BOTH
  directions. The shape that works (`typeof x === "string"` over `string | number`) was verified
  against an un-narrowed control in the scratch-project CLI loop BEFORE either assertion was written,
  and that control shipped as a pin. **A10 and A11 still redden nothing and the reason is structural,
  not a redundant guard**: A10's consumers are AST-shape/shadow-detection rules no fixture drives
  across a fn boundary, and A11 is a CROSS-FILE leak, which `diagnose()` — single-file — cannot
  express at all. **12 of the 32 pins are reddened by no arm** and are recorded as INVARIANT GUARDS
  with no coverage claim (the READ-path pins, the guarded no-ops, both `clear()` refusals, the
  `toMutableMap` detachment, the `onMutate` contract, and the four SET-side root/reset/no-op/clear
  pins, for which no arm was cut because A7/A8 both aimed at the map stack).

- **(H) GATES.** Suite **14,308 -> 14,324 / 0 failures / 3 skipped** = exactly the 16 new pins.
  Compiler-profile `--listAll` digest **`59d930db849399aea5e03e25fedb8e4e`** over 46 errors — the
  cross-round recipe CLAUDE.md records, i.e. equivalence against a capture predating this arc.
  `cost_gate.py` **+0.00% on all 20 counters** (the EXPECTED control, round 876 — this moves no
  decision). `huge_methods.py --fail-over 0` **0 over the limit, 694 classes**. **NO WALL-TIME A/B IS
  CLAIMED** — round 891's amplifier priced this family at 43-55 ms from a single unreplicated batch
  and the narrowing entries add ~4-6 ms at the measured 30-51 ns/entry, so ~0.8-1.1%, inside what
  this box settles (rounds 840(c)/858/886). The claim is the controlled row plus the cross-checked
  census.

**Round 891 (2026-08-11) — (WARM.18): THE `CtaFrame.varTypes` PER-SCOPE COPY BECOMES AN UNDO LOG —
**1,145,523 ENTRIES COPIED PER REBUILD -> 16,182 UNDO RECORDS OVER AN IDENTICAL POPULATION** — AND THE
OTHER THREE FAMILIES ARE **REFUSED WITH A PRICED CONDITION TABLE**, INCLUDING THE ONE THAT IS THE
BIGGER PRIZE.**

Round 869 replaced two of the six per-scope whole-map copy families and said in as many words why it
stopped: the rest are where a wrong scope does not crash — it silently resolves a name to an OUTER
binding (the `applyBodyLocalShadowing` FP class). This round worked the item in the order CLAUDE.md
requires — **conditions first, price second, code third** — and took exactly one of the four.
`docs/perf/cta-frame-copy-families.md`.

- **(A) THE CONDITION TABLE, WHICH IS WHAT DECIDED IT.** Round 869's law: replaceable EXACTLY when the
  stack is strictly LIFO, no key is removed, and no reader mutates or retains the map.

  | family | one LIFO stack? | removal? | retains / iterates? | writes/entries | price | verdict |
  |---|---|---|---|---:|---:|---|
  | `CtaFrame.varTypes` | **YES** — `ctaFrames`, one frame per owner node, `reset` per file | **none** in 217 refs (3 write paths + one `putAll`) | ~15 legacy call sites, all synchronous in one dispatch; **no** `.keys`/`.values`/`.entries`/`.forEach`/`.iterator`/`.sorted` reader | 0.22% (recorded) | **33.9 ms = 0.59%** | **CONVERTED** |
  | `CtaFrame` localTypes+declNodes+shadowed | copies at ONE site, but the maps are installed into AMBIENT fields >=12 other sites re-install with different objects | — | — | **UN-INSTRUMENTED** | **43-55 ms = 0.8-1.0%** | **REFUSED** |
  | `EpochMap(localTypes)` | **NO** — THREE spine stacks (ccet/cpa/cta-narrowing) plus >=12 ad-hoc `currentLocalTypes = EpochMap(currentLocalTypes)` sites whose restore is a **POINTER SWAP** | — | — | 9.4% | ~14-24 ms = 0.25-0.42% (derived) | **REFUSED** |
  | `EpochSet(paramBindings)` | same | — | — | 20% | ~1-2 ms (derived) | **REFUSED** |

- **(B) THE PRICE, AND THE MEASUREMENT LESSON THAT COST THE ROUND AN EXTRA BATCH.** Three new
  per-family amplifier arms (`copyampcv<r>` / `copyampcl<r>` / `copyampcta<r>`, the shape round 869's
  `copyampos` established) price ONE family on the binary that still has it. **Two batches of the same
  family on the same binary, with OPPOSITE rotations, read 53.6 and 14.1 ms/rep — a 4x
  disagreement.** The cause is round 869's own first-draw law taken one step further: rotation INSIDE
  a process is not enough at two draws per arm, because the leading draw's ~15% lands wholly on
  whichever arm ran first (`r=16` in batch 1, `r=0` in batch 2) and a 6-point fit cannot absorb it.
  Pooling the 12 draws gives **33.9 ms**; discarding each batch's leading draw gives **32.6** with the
  two sub-intervals agreeing to 2.5%. **Quoted: ~33 ms = 0.59% [0.47-0.64%].** Batch 1 alone would
  have been written up as 0.94%. Arithmetic falsifier held on every rebuild
  (`ampSink == r x entries`, e.g. `18,328,368 = 16 x 1,145,523`).

- **(C) THE ONE MECHANISM `AnnScopeStack` DOES NOT HAVE, AND IT IS NOT COSMETIC.** This family's map
  is handed OUT by reference to helpers that WRITE into it (`checkVarDeclAssignability`,
  `ctaTypeParamsIntoLocals`, the `extraVarTypes` `putAll`), so the scope cannot be a private map with
  a `put` method — it is a `MutableMap` **FACADE** (`VarScopeStack.view`) whose `put`/`putAll`/
  `remove` route through the log. `clear()` THROWS: it cannot be recorded in O(1), and a silent one
  would drop the enclosing scopes' entries with no way to restore them at the pop. **And the facade
  is the only place this family's writes can be counted, which is how a five-round-old census number
  turned out to be wrong: the old hook was attached to 2 of the 3 write paths, so it read 2,564 where
  the truth is 16,182** — 6.3x optimistic, with nothing anywhere saying so.

- **(D) SHARING IS "OPEN NO SCOPE".** A BARE (non-`Block`) then-statement narrowing frame deliberately
  shares its parent's map, because the legacy dispatch it reproduces passed the map straight through.
  That is the frame's `varScoped` flag, which is also what `ctaSpineLeave` pops on — so push and pop
  are mirrored by construction rather than by a separate rule. Every other frame kind (8 fn-body
  shapes, statement blocks, `ModuleBlock`, switch clauses) opens a scope; the file-root frame does
  not, and is dropped by `reset`.

- **(E) THE CONTROLLED ROW (round 793 — the change moves no boundary and no population).**

  | | before | after |
  |---|---:|---:|
  | `CtaFrame.varTypes` pushes | 30,433 | 30,433 |
  | entries copied by it | **1,145,523** | **0** |
  | undo records | 0 | **16,182** |
  | entries copied, all six families | 2,746,298 | 1,600,775 |

  **70.8x less work over an identical population.** And the amplifier is the change's own falsifier:
  `copyampcv16/8/0` re-run on the new binary reads **`ampSink 0`** — it finds nothing left to
  amplify, so the copies are GONE rather than merely uncounted.

- **(F) WHY THE BIGGER PRIZE WAS REFUSED — this is not a ranking by size.** The cta LOCAL family is
  0.8-1.0% against `varTypes`' 0.59%. Three facts stopped it: (i) its produced-versus-consumed ratio
  **does not exist yet** — the census reads `writes 0` and that is round 849's un-instrumented zero,
  not a measurement, and round 801's law is that the ratio comes FIRST; (ii) its sibling
  `ambiguousNames` is a **RESET, not a shadow** (a fn frame gives it a fresh EMPTY set), which an
  undo log expresses in O(size) — the very cost being removed — so the family is not one mechanism;
  (iii) the narrowing frame copies `localTypes` into the `EpochMap` family, which is refused for not
  being a stack at all, so converting the cta half leaves two disciplines over one ambient field.
  Queued as **(WARM.18b)** with its instrument named: a counting facade over `CtaFrame.localTypes`,
  run for one census, BEFORE any code.

- **(G) PINS AND ABLATION.** `VarScopeStackTest`, 13 pins, all over strings and ints. The shadowing
  shape is pinned directly (inner scope sees outer entries; an inner write is gone after the pop; a
  shadowed key comes back with the OUTER value, not absent; repeated writes to one key still restore
  the INHERITED value — the pin the reverse replay exists for), plus the `putAll` seed path, the
  removal path, the SHARED-frame case, the file-root/`reset` boundary, and `toMutableMap()` still
  being a detached snapshot (the legacy nested walk's one genuine copy).
  **Eight single-mistake ablations** (`scripts/round891-ablate.sh`, one arm per invocation, each
  reverted before the next, on a committed tree — round 789/851), red set predicted per arm:

  | arm | the mistake | pins red |
  |---|---|---:|
  | A1 | `pop` replays its slice FORWARD | **2** |
  | A2 | `pop` restores nothing, only truncates | 9 |
  | A3 | a write records ABSENT instead of the pre-write value | 7 |
  | A4 | `putAll` bypasses the log | **1** |
  | A5 | `push` records mark 0 | 6 |
  | A6 | a file-root write (no scope open) IS recorded | **1** |
  | A7 | `remove` is not recorded | **1** |
  | A8 | `reset` keeps the entries — a cross-FILE leak | 2 |

  **All eight red sets are DISTINCT** (A3 and A5 differ by exactly one pin), and A4/A6/A7 each have a
  uniquely-their-own failure. **A1 — the mistake the whole scheme turns on — is seen by exactly two
  pins**, `repeated writes to one key in one scope still restore the inherited value` (forward replay
  leaves the LAST pre-write value, i.e. the inner one) and `a write made with no scope open persists`.
  **REPORTED HONESTLY (round 868): 3 of the 13 pins are reddened by NO arm** — `an inner scope sees
  the outer scope's entries`, `toMutableMap on the view is a detached snapshot` and `popping with no
  scope open is a no-op`. Every arm here breaks the POP or the RECORD, and those three assert the READ
  path and the guarded no-op; they are recorded as INVARIANT GUARDS with no discrimination claim
  attached, not counted as coverage.

- **(H) GATES.** Suite **14,295 -> 14,308 / 0 failures / 3 skipped** = exactly the 13 new pins.
  Compiler-profile `--listAll` digest **`59d930db849399aea5e03e25fedb8e4e`** over 46 errors — the
  cross-round recipe CLAUDE.md records, i.e. equivalence against a capture taken before this arc.
  `cost_gate.py` **+0.00% on all 20 counters** (the EXPECTED control, round 876 — this change moves no
  decision). `huge_methods.py --fail-over 0` **0 over the limit, 692 classes**. **NO WALL-TIME A/B IS
  CLAIMED** — 0.59% is inside what this box settles (rounds 840(c)/858/886); the claim is the
  controlled row plus the amplifier price, both deterministic.

**Round 890 (2026-08-11) — (HASH.1)(b): THE PACKED-KEY SWEEP, RUN ON THE **REAL** KEY POPULATIONS
RATHER THAN A MODEL. THIRTEEN PACKED-LONG KEY SITES CENSUSED; **TWO ARE DEGENERATE AND FIXED, FOUR
ARE MEASURED ALREADY-FINE, TWO ARE REFUSED** — AND THE MECHANISM IS SHARPER THAN ROUND 889 COULD
STATE IT: `Relation.cache`'s 43,080 REAL KEYS COLLAPSE ONTO **18,201 HASHES, 1,140 OF THEM IN ONE
BUCKET**, BECAUSE TYPE IDS ARE MINTED SEQUENTIALLY AND A RELATION ASKS ABOUT **NEIGHBOURS**.**

Round 889 fixed `nodeKey` and queued the family. This round did not model anything it could measure:
a THROWAWAY census (an `add(name, key)` at every packed-Long write site, dumped under `--passTiming`,
reverted before a line of the fix was written) captured the ACTUAL key population of every such
container on the compiler profile, and `scripts/round890_bucket_model.py` ran `java.util.HashMap`'s
bucket arithmetic over each — as packed, and after the golden-ratio finalizer.
`docs/perf/hash-key-spread.md` § 5.

- **(A) THE TABLE.** Primed columns are the same population multiplied by `0x9E3779B97F4A7C15`.

  | packer | halves | correlated? | container | keys | cap | used | max | tree% | used' | max' | tree%' | verdict |
  |---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
  | `Relation.packKey` | (srcTypeId, tgtTypeId) | **YES** | `Relation.cache` | 43,080 | 65,536 | 17,486 | **1,140** | **27.3%** | 31,532 | 6 | 0% | **FIXED** |
  | `packRelationKey` | (typeId, symId) | **YES** | `resolvedPropertyTypes` | 10,482 | 16,384 | 5,698 | 10 | 2.1% | 7,742 | 6 | 0% | **FIXED** |
  | `packRelationKey` | (typeId, typeId) | YES | `relationComparisonStack` | 51,447 seen, **27 LIVE** | 64 | — | — | — | — | — | — | free ride |
  | `packRelationKey` | ″ | ″ | `elaborationStack` | 1 seen, 1 live | 16 | — | — | — | — | — | — | free ride |
  | `packRelationKey` | ″ | ″ | `functionElaborationStack` | **0** on this profile | — | — | — | — | — | — | — | free ride |
  | inline pair | (targetId, targetId) | YES | `ts2403IdentityStack` | 6,214 seen, **2 LIVE** | 16 | — | — | — | — | — | — | uniformity |
  | inline pair | (enumSymId, enumSymId) | YES | `enumTypesRelationCache` | **0** on this profile | — | — | — | — | — | — | — | uniformity |
  | `internKey` | (internSalt, pos) | **no** | `typeParamInternCache` | 1,186 | 2,048 | 919 | 4 | 0% | 902 | 4 | 0% | **already fine** |
  | `walkMemoKey` | (nodeId, fileHash) + `* 31` folds | mixed by the folds | `walkMemo` | 31,875 | 65,536 | 25,257 | 6 | 0% | 25,325 | 6 | 0% | **already fine** |
  | 3 × M0.3(iii) intern keys | (id, id) | YES | `referenceCacheLong` &c | — | — | — | — | — | — | — | — | **already fine** — `LongKeyMap.bucket` applies the SAME finalizer inside the map |
  | `SpineDispatch.nodeKey` | (fileHash, nodeId) | no | `distinctPa`/`distinctP` | — | — | — | — | — | — | — | — | **refused** — `[CENSUS]`-only, never written in production |
  | `PassTiming` pairs ×5 | — | — | probe maps | — | — | — | — | — | — | — | — | **REFUSED — `redundantPairNanos` is UNPACKED** (`k and 0xFFFF_FFFFL`) |
  | `nodeKey` | (pos, end) | YES | `nodeToFlow` &c | — | — | — | — | — | — | — | — | fixed in (a) |

- **(B) THE MECHANISM, NAMED EXACTLY.** For `nodeKey` the collapse was "the hash's range is the set
  of node lengths". For an id pair the census names it outright: `hash == 1 -> 1,140 keys`,
  `hash == 2 -> 420`, `hash == 6 -> 471`, `hash == 7 -> 440`. **Type ids are minted SEQUENTIALLY and
  the pairs a relation actually asks about are overwhelmingly NEIGHBOURS** — an instantiation against
  its target, a union against a member it was built from — so `a xor b` for `(2k, 2k+1)` is `1` for
  every k and 1,140 unrelated type pairs share one bucket; **21% of all queried pairs have
  `|src - tgt| <= 64`**, i.e. a hash under 128. **The diagonal is the degenerate limit**: `a xor a`
  is 0, so an un-mixed identity relation would put every `(T, T)` query in bucket 0.

- **(C) A DISTINCTION THE MODEL-ONLY APPROACH WOULD HAVE GOT WRONG IN BOTH DIRECTIONS.** Ranked by
  total distinct keys, `relationComparisonStack` (51,447) looks like the family's worst offender and
  `resolvedPropertyTypes` (10,482) like a rounding error. **The census's `maxLive` column inverts
  that**: the stack is add/remove and never holds more than **27 entries at once**, so its table
  never grows past 64 and it cannot treeify however bad its hash is — while `resolvedPropertyTypes`
  is a grow-only cache and does. **A key population is not a map population**; a sweep that ranks by
  the first mis-prices every transient container it touches.

- **(D) FOUR SITES ARE ALREADY FINE AND SAYING SO IS PART OF CLOSING THE FAMILY.** `internKey` packs
  a per-file SALT against an AST position — genuinely uncorrelated halves, max bucket 4.
  `walkMemoKey`'s `* 31` folds of the walk kind and input digest already spread it, max bucket 6. The
  three M0.3(iii) intern caches are `LongKeyMap`s, which apply this very finalizer INSIDE `bucket()`
  — the fix has been sitting in the repo, correct, for the one map family that did not need help.
  Both live sites now carry a one-line KDoc stating the measurement, so the next agent does not
  re-derive it.

- **(E) TWO SITES ARE REFUSED, ONE OF THEM ON A SOUNDNESS OBLIGATION.** `SpineDispatch.nodeKey` feeds
  `[CENSUS]`-only sets that a production run never writes. And **`PassTiming.redundantPairNanos` is
  the repo's one key that is UNPACKED** (`(k and 0xFFFF_FFFFL).toInt()`, PassTiming.kt:1005) — a
  finalizer there would silently mis-attribute the probe's own table. That is round 889 § (F)'s first
  obligation actually biting something, which is why it is checked per site rather than assumed.

- **(F) THE SECOND OBLIGATION, RE-CHECKED.** All seven ROUTED containers are membership-or-lookup
  only (`in`, `[]`, `getOrPut`, `add`/`remove`) and none has a `.keys`/`.values`/`.entries`/`forEach`
  reader. **Four of them are plain `HashMap`/`HashSet`, not `mutableMapOf`** — so unlike round 889's
  three LinkedHashMaps, had any been iterated this WOULD have been a rounds-754/776/778 program-order
  change that no output diff can see. The check was the deciding one here, not a formality.

- **(G) THE PINS, AND THE ONE THAT FAILED FIRST TIME FOR A GOOD REASON.** `IdPairKeyHashSpreadTest`,
  four pins, every one comparing against the un-mixed packing written LONGHAND inside the pin (round
  889's lesson). The neighbouring-ids pin failed on its first run because I wrote the adjacent pair as
  `(2k+1, 2k+2)`, which does NOT XOR to 1 — `(2k, 2k+1)` does; the pin was asserting the pathology it
  claimed to reproduce and the pathology was not there, which is exactly the failure mode a
  longhand-comparison pin is supposed to expose, and it exposed it on the author.

- **(G2) THE ABLATION, ONE MISTAKE, RED SET PREDICTED IN ADVANCE.** `packIdPair` loses its
  `* NODE_KEY_MIX` and nothing else changes: **3 of the 4 new pins RED** (`neighbouring ids …`,
  `the compiler profile's relation key shape …`, `the diagonal does not collapse …`) and **the 5
  `nodeKey` pins GREEN**, which is the control that the mistake landed where it was aimed. The
  fourth pin (`the packing stays injective …`) is green and was written saying it could not
  discriminate — both packings are bijections — so it is an INVARIANT GUARD with no claim attached
  (round 807), defending a future cheaper mix that loses injectivity. Restored tree: 9/9 green.

- **(H) NO WALL-TIME NUMBER IS CLAIMED.** Round 889's JFR prices the `Relation.cache` group at 0.97%
  of compile-thread samples with 56% inside `HashMap$TreeNode` frames, i.e. a recoverable ~0.5% ≈
  ~30 ms of a ~5.9 s warm rebuild — well inside what this box settles (rounds 840(c)/858/886). The
  claim is the bucket arithmetic on the REAL populations, which is deterministic and reproducible
  offline. `cost_gate.py` +0.00% is the EXPECTED control (round 876), not a win.

- **(I) WHAT THE PINS DO NOT GUARD, STATED PLAINLY.** They pin `packIdPair` itself. **A future site
  that hand-rolls `(a shl 32) or b` again is invisible to every pin in this repo** — the only defence
  is that `packIdPair` is now the sole id-pair packer in `Checker.kt` and its KDoc says so.
