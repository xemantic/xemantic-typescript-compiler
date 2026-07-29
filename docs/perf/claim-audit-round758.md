# (AUDIT.1) — the attribution arc audits its own numbers

*Round 758. Tenth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → `call-expression-attribution.md` (734) →
`argument-check-attribution.md` (735) → `narrow-walk-attribution.md` (736) →
`type-of-expression-attribution.md` (737) → `var-decl-attribution.md` (738) →
`engine-rule-price.md` (739/755) → `condition-narrowing-attribution.md` (755) →
`walk-function-bodies-attribution.md` (756/757) → here.*

*This round writes no optimisation. It asks of every load-bearing number in the
arc: **is this a POPULATION (how much work is behind it) or a FREQUENCY (how
often a path is entered)?** Round 757 falsified four of its own five predictions
and found its predecessor out by **146×** on exactly that distinction. Those
numbers are now the project's map, so the map was checked.*

> **HEADLINE — 57 load-bearing claims audited. Three are frequencies standing in
> for populations; all three shrink when the population is measured, none grows.
> Two more are unverified and are flagged rather than fixed.**
> **(1) `IDENTIFIER` is 44.5% of the nodes and 8.4% of the spine's time** —
> 1,853 ms of 22,104 ms, **ratio 5.3×**. This is the claim § 0 built "the
> measured lever is consultation, not computation" on, and the claim CLAUDE.md
> still carried as standing guidance.
> **(2) `getCalleeType`'s "half its results are thrown away" is 50.6% of the
> CALLS and 8–10% of the TIME** — a discarded resolution costs **1,452 ns**
> against **16,491 ns** for a kept one, **11×**, so the implied ~237 ms prize is
> **38 ms**, out by **6.2×**. (CALL.1) § 6's last open forward-pointer closes here.
> **(3) The `--passTiming` per-kind table printed "enter+leave" and summed
> ENTER ONLY.** Every per-kind figure ever quoted from it — including § 0's
> "`IDENTIFIER` … 2,746 ns each" — was the enter chain alone. Fixed; the
> corrected counter now reproduces round 732's *independent* `--dispatchProbe`
> per-kind numbers to **0.2–5%**, which is also the instrument's falsification.
> **AND ONE STRUCTURAL ERROR THAT IS NOT A FREQUENCY: § 0.1's parity table says
> "remove ALL dispatch overhead → 66 units, 1.5×". The 34 units it removes are
> not dispatch** — rounds 733/734 measured them as the migrated passes' own
> checking work, and round 732 measured real dispatch at 100–300 ms. **The
> honest row is 100 → 99. The first step of the parity argument is out by ~34×.**
> **"Single digits remain" SURVIVES, and is now better supported**: every
> re-derived population came in *below* its citation. **No parked item is
> revived.**

---

## 1. Method

For each claim: classify, then — if it is a FREQUENCY being spent as a
POPULATION — measure what is behind it.

| class | what it is | the failure mode |
|---|---|---|
| **P** POPULATION | how much work exists behind a thing (ms over an enumerated set, outermost-call time) | — |
| **F** FREQUENCY | how often a path is entered / how many nodes carry a kind | multiplying it by a mean cost, or reading its share as a share of cost |
| **T** TIME | a measured millisecond figure | staleness; a single run quoted without a band |
| **R** RESIDUAL | a time obtained by SUBTRACTION and then *named* | the name is a hypothesis, never a measurement |
| **C** COUNT | a census of source constructs | — |

Three disciplines were applied to this round itself, from the arc's own record:

* **Predictions written down before measuring** (§ 6), scored after.
* **The instrument was falsified**, not trusted: the per-kind counter's fix is
  confirmed by an *independent* instrument (round 732's `--dispatchProbe`)
  landing on the same three numbers, and by the broken version being off by
  1.7–4.0× on those same three.
* **Every negative result was checked for whether it could have shown the
  positive** (round 750's rule). One row in `dispatch-table.md` fails that test
  and is flagged (§ 4, D8).

## 2. What was built

Additive-only, behaviour-free when off, both gated by an existing opt-in flag.

* **`PassTiming.spineKindNanos` now also accumulates `spineLeaveNode`'s time**
  (both leave sites in `spineWalkFileProfiled`), so the report line
  `per-kind enter+leave` is finally true. The per-kind COUNT still increments
  once per node, at enter. `spineWalkFileProfiled` runs only under
  `PassTiming.enabled`, so **production has no new instruction at all.**
* **`CallSections.N_CALLEE_BAIL` / `N_CALLEE_LIVE`** — two nested rows splitting
  the `getCalleeType` section by whether its result survives the
  `calleeType === anyType || calleeType === errorType` bail three sections
  later. The outcome test sits INSIDE the `mode != OFF` gate: an inline
  `close(<expr>, t)` would have evaluated `<expr>` at every one of 52,413 call
  expressions before the gate could return. Production is one static read and a
  not-taken branch.
* One new pin, `the getCalleeType outcome split partitions every resolution`
  (`CallSectionProbeTest`), asserting `bail + live == calls[CALLEE_TYPE]` — so a
  future edit cannot silently turn the measurement into a sample.

## 3. The map, re-measured — because it had never been

§ 0's table is round 716's, taken ~40 rounds and two landed wins ago
(−4.53% round 736, −11.42% round 738). Round 755's carry-forward — *an item
defined by a measured number must re-measure it before a round is spent inside
it* — had never been applied to the arc's own top-level table.

Compiler profile, HEAD `7d49c910`, `--noEmit`. Probe-free wall **26,541 ms**;
the `--passTiming` run below is **29,973 ms** (the probe inflates ~12%, so read
shares, not absolutes).

| | round 716 | **round 758** | class |
|---|---:|---:|---|
| `checkSpine` | 14,292 (83% of init) | **22,104 (84.4% of init, 74% of the compile)** | T |
| — `spineEnterNode` | 7,166 | **11,155** | T |
| — `spineLeaveNode` | 5,478 | **8,046** | T |
| — unresolved-names family | 840 | **1,223** | T |
| — `forEachChild` | 255 | **557** | T |
| — scope maintenance | 25 | **53** | T |
| the instrumented type-system rows | 5,056 (28%) | **6,907 (26.4%)** | T — **contains a double count** |
| — flow-narrowing walks | 2,437 (69,917) | **2,290 (71,414 walks)** | T |
| — `getTypeOfExpression` | 1,804 (624,810 calls) | **3,254 (709,357 calls)** | T — round 737: charges a subtree once per nesting level, ×~1.6 |
| — relations (depth 0) | 468 | **685** | T |
| — type-node resolution (depth 0) | 311 | **580** | T |
| — member resolution | 36 | **98** | T |
| **the ~400 tail passes** | *"14 units" (§ 0.1)* | **3,130 (11.9% of init, 10.4% of the compile)** | T |
| globals lookups / miss rate | 1,341,719 / 98.9% | **961,213 / 98.1%** | F |
| pre-parse reuse | — | **78 of 78** | F |

**Nothing in the shape moved; three of the absolutes did.** The tail-pass row is
10.4% against § 0.1's 14 units — and § 0.1 treats those units as removable,
which rounds 619/620 (only 3 of 23 census-silent passes were deletable) and 659
(the migration A/B measured **+0.24%**, i.e. nothing) both contradict.

## 4. The audit table

57 load-bearing claims. **Verdict key:** ✅ stands · ⚠️ stale/weak · ❌ falsified ·
❓ unverified (flagged, not measured).

### `ARCHITECTURE-RETHINK` § 0 / § 0.1

| # | claim | class | verdict |
|---|---|:--:|---|
| A1 | `checkSpine` = 83% of checker-init | T | ✅ share holds (84.4%); absolute is 1.55× stale |
| A2 | enter 7,166 / leave 5,478 ms | T | ⚠️ stale (11,155 / 8,046) |
| A3 | "the whole type system … 5,056 ms (28%)" | T | ⚠️ the `getTypeOfExpression` row inside it double counts (round 737); and round 734 showed the row set MISSES the argument-check/callee-resolution code — inflated and incomplete at once |
| A4 | "dispatch + handler machinery (residual) ~7,600 ms (42%)" | **R** | ❌ the LABEL is falsified (733: 88.4% of the two biggest handlers is their pass's own checking; 734: same for the residual). Real dispatch is 100–300 ms |
| A5 | "14.8 µs per node, of which 8.9 µs is not type-system work" | T over an F-weighted mean | ⚠️ the distribution spans **4,855 ns (IDENTIFIER) to 193,130 ns (VARIABLE_STATEMENT)**; a per-node mean invites "× 857k = the prize" |
| A6 | 59 handler consultations per node | F | ✅ — and its population WAS measured (D3/D4) |
| **A7** | **"IDENTIFIER 44.5% of all nodes, 2,746 ns each = 1,048 ms" ⇒ "the measured lever is consultation"** | **F used as P** | ❌ **the population is 8.4% of the spine — 1,853 ms of 22,104. Ratio 5.3×.** § 5.1 |
| A8 | Corrected-targets row "(DISPATCH.1) 1.0–2.5 s" | P estimate | ❌ round 732 measured ≈0.3–1%; the table was never updated |
| A9 | Corrected-targets row "flow narrowing 2.4 s" | T | ⚠️ superseded — 736 landed −4.53%, 755 closed the residue at 1.6% |
| A10 | "1,341,719 globals lookups at 98.9% miss, priced ≲0.2%" | F + **asserted** P | ⚠️ count is stale (961,213 / 98.1%); the ≲0.2% price has never been measured, only asserted |
| A11 | budget: checker-init 80 / front end 20 | T | ⚠️ corrected round 738 (front end 11.0%, the other 9.2% was discarded emit); today checker-init is **87%** of the compile |
| A12 | budget row "dispatch + handlers 34" | **R** | ❌ same mislabel as A4 |
| **A13** | **"if we removed ALL dispatch overhead → 66 units, 1.5×"** | derived from A12 | ❌ **the honest row is 100 → 99. Out by ~34×, and it is the FIRST step of the parity argument.** § 5.3 |
| A14 | "+ ALL 403 tail passes → 53, 1.9×" | T + a deletability assumption | ⚠️ 10.4% not 14%, and 619/620/659 all say the units are not removable |
| A15 | "+ HALF the type system → 42 = parity" | T | ⚠️ rests on A3 |
| A16 | endgame "~1,005 `check*` functions" | C | ✅ census 1,046 |
| A17 | "265 ms firewall vs 19 ms relation = 14×" | ratio, wrong denominator | ✅ already corrected in place (739: 0.67×; 755: 0.40×) |

### `dispatch-table.md` (round 732)

| # | claim | class | verdict |
|---|---|:--:|---|
| D1 | 46 enter + 13 leave handlers | C | ✅ |
| D2 | 35/46 enter closed, 3/13 leave closed | C | ✅ |
| D3 | 32.0 M of 50.6 M consultations removable (64%) | F | ✅ **and the round measured its population rather than multiplying it** |
| D4 | removable consultations = 883 ms probe-inflated, ~100–300 ms real | P | ✅ |
| D5 | 22 of 59 handlers genuinely act at an identifier | C | ✅ |
| D6 | at IDENTIFIER: kept 1,142 ms, removable 340 ms | P | ✅ consistent with today's 1,853 ms enter+leave total |
| D7 | `--dispatchGated` measured SLOWER (+4.9% / +7.4%) | T | ⚠️ **one run per mode, not interleaved, no band quoted** — directionally consistent with D4 but not evidence at the strength it is cited as ("independent confirmation") |
| **D8** | working-kind table: `spineOsEnterNode` **0** | **F that could not have been non-zero** | ❌ instrument artifact — the handler has no `work()` call, so its 0 is printed in the same column as measured zeros. Round 750's rule. Labelled in-line; now labelled in the table |
| D9 | six handlers = 71% of the spine | T | ✅ as a LOCATION; its cause was mislabeled and corrected in place by 733 |
| D10 | per-kind: CALL_EXPRESSION 3,636 / VARIABLE_STATEMENT 2,835 / RETURN_STATEMENT 1,839 ms | T | ✅ **independently reproduced this round at 3,752 / 2,841 / 1,752** |
| D11 | frame-pop family closable, worth ~20 ms | P | ✅ |

### `spine-leave-attribution.md` (733)

| # | claim | class | verdict |
|---|---|:--:|---|
| S1 | partition 8,195 ms net; probe inflates ~10%, measured | T | ✅ |
| S2 | 88.4% is the passes' own checking work | P | ✅ |
| S3 | ambient install+restore 360 ms; ancestor climbs 176 ms | P | ✅ (falsified the item's own 1–3 s by 6–17×) |
| S4 | "consulted 856,962" per section | F | ✅ used correctly — every section's TIME is printed beside it |
| **S5** | "`owner` has 19,551 hits **at the anchor section's ~72 µs/hit** ≈ 1,340 ms of WORK, leaving ≲30 ms of gate" | **F × a rate borrowed from a DIFFERENT section** | ❓ the `owner` section's own per-hit cost was never measured. The conclusion (a parent-kind axis ≲60 ms) may hold — EWTA+PropertyDeclaration are 28 ms measured — but this derivation is an imputation, not a measurement |
| S6 | `checkSingleCallExpressionTypes` 53.6 µs × 52,413 = 2.9 s | P | ✅ reproduced (2,551 ms raw partition today) |

### `call-expression-attribution.md` (734)

| # | claim | class | verdict |
|---|---|:--:|---|
| C1 | exit profile 26,496 / 22,145 / 3,640 / 101 / 31 / 0 | F | ✅ **exactly reproduced** (26,496 bail, 25,917 live) |
| C2 | the `signatures.isEmpty()` branch and all 7 prologue walkers fire **0** times | F, zero | ✅ falsifiable — sibling rows in the same table are non-zero |
| C3 | 78.3% type-system work; `checkArgumentsAgainstSignature` 1,357 ms | P | ✅ (today 1,231 ms raw, after the landed wins) |
| C4 | Branch A's ceiling ≤490 ms | P | ✅ |
| **C5** | **"`getCalleeType` costs 474 ms and half its results are thrown away"** | **F used as P** | ❌ **the discarded half is 8–10% of the time. 1,452 ns vs 16,491 ns, 11×. Implied 237 ms → measured 38 ms, ratio 6.2×.** § 5.2 |
| C6 | all 22 `getLineAndCharacterOfPosition` are downstream of the emit gate | static C | ✅ |

### `argument-check-attribution.md` (735)

| # | claim | class | verdict |
|---|---|:--:|---|
| G1 | 22,419 invocations / 38,247 iterations; 10,146 (27%) reach the relation | F | ✅ |
| **G2** | "**yet all 37,379 that get past the first gate pay for the full `argType` computation**" | **F used as P** | ❓ **UNVERIFIED — the 924 ms `argType` total is measured, its split across the 27%/73% exit classes is not.** Sized and predicted in § 7; NOT measured this round |
| G3 | argType 924 ms vs relation 19 ms = 48× | P vs P | ✅ |
| G4 | 86% of walks return the input unchanged, worth ≤237 ms | P (both sides measured) | ✅ the model of doing it right |
| G5 | 394 of 70,037 walks (0.56%) = 1,485 ms | P | ✅ consumed by 736 |
| G6 | trips 0 / budget 0 / memo cold — three mechanisms disproved | F, zero | ✅ and 736 found the memo failure one scope down |

### `narrow-walk-attribution.md` (736)

| # | claim | class | verdict |
|---|---|:--:|---|
| N1 | revisit factor 8.85 vs 1.48 | F | ✅ |
| N2 | 631,585 too-shallow misses | F | ✅ — the fix's effect was measured by interleaved A/B, never inferred from the count |
| N3 | `applyConditionNarrowing` = 51% of the population, 1,412 ms | P | ✅ |
| N4 | 95.6% identity calls at **949 ns** vs **21,708 ns** for the narrowing ones | P, both sides | ✅ **the arc's own textbook statement of this trap** |
| N5 | −4.53%, B wins 6/6 | T, interleaved | ✅ |
| N6 | "33,307 narrowing calls at 21,708 ns ≈ 723 ms" | P | ⚠️ superseded (755: 441 ms) — the number moved 34% while the item sat parked |

### `type-of-expression-attribution.md` (737)

| # | claim | class | verdict |
|---|---|:--:|---|
| Y1 | 701,463 calls / 254,069 distinct = ×2.76 = 2.05 × 1.34 | F/P | ✅ |
| Y2 | 45.2% of typed nodes have >1 origin | F | ✅ **explicitly NOT spent as a population** |
| Y3 | perfect cache 823 ms / single-visit 670 / largest pair 166 | P | ✅ |
| Y4 | "biggest four pairs by COUNT = 141,388 repeats worth 71 ms; biggest by TIME = 2,603 worth 166 ms" | P vs F | ✅ **the arc's first explicit statement of the law this audit generalises** |
| Y5 | "3,911 ms is a DOUBLE COUNT; the true total is 2,439 ms" | T, corrected | ✅ |

### 738 / 739+755 / 755 / 756+757

| # | claim | class | verdict |
|---|---|:--:|---|
| V1 | 12,960 of 15,116 (86%) never reach an assignability check — **405 ms, 46% of the function** | F **and** P | ✅ the % and the ms are printed together |
| V2 | eligibility + parent-chain climbs 194 ms over 856,976 nodes = 212 ns | P | ✅ |
| F1 | front end 11.0%; crawl 1,683 ms; 78/78 pre-parses reused | T/F | ✅ (78/78 reproduced today) |
| F2 | emit gate −11.42%, 6/6 | T, interleaved | ✅ |
| E1 | site 1 walker layer 326 ms (1.21%); site 2 177 ms (0.66%) | P | ✅ |
| E2 | "8,587 of 10,119 (85%) exit inside the legacy string checker" — **15 ms** | F + P | ✅ the ms sits beside the % |
| E3 | three-site layer bounded at 876 ms = 3.3% | P bound | ✅ |
| CN1 | 21,970 `applyConditionNarrowing` narrowing calls × 20,085 ns = 441 ms | P | ✅ |
| CN2 | 73% of the identity time is in three leaves that RESOLVE something | P | ✅ — corrects 736's "identity calls are the cheap tail" |
| **W1** | "874 of 1,510 (58%) are expression-bodied arrows" | **F used as P** | ❌ already falsified round 757: the population is **SIX** block bodies. **146×** |
| W2 | 199,131 nodes visited to reach 1,510 function-like (0.76%) | F | ✅ the time (65 ms) is measured beside it |
| W3 | four rows are zero — with a `.js` control that lights two of them | F, zero, **falsifiable** | ✅ the model for a zero |
| W4 | `calleeDeclaredCtxParams` at all 29,787 calls to contextualize ≤636 bodies | F + P (49 ms) | ✅ |

**Tally: 57 claims — 40 ✅, 11 ⚠️, 4 ❌ (A4/A12/A13 counted once as the residual
mislabel; A7, C5, D8, W1), 2 ❓ (G2, S5).** Of the four falsified, **three are a
frequency spent as a population** and one is a residual spent as a population.

## 5. The corrections, each with its multiple

### 5.1 IDENTIFIER: 44.5% of the nodes, 8.4% of the time — 5.3×

The `--passTiming` report line reads `per-kind enter+leave`. It accumulated
`spineEnterNode` ONLY. Fixed (§ 2), and **the fix is confirmed by an independent
instrument**: round 732's `--dispatchProbe` derived per-kind totals by a
completely different construction, and the corrected counter lands on its three
published numbers.

| kind | round 732 `--dispatchProbe` | round 758 corrected counter | broken (enter-only) counter |
|---|---:|---:|---:|
| CALL_EXPRESSION | 3,636 ms | **3,752 ms** (+3.2%) | 929 ms (**4.0× low**) |
| VARIABLE_STATEMENT | 2,835 ms | **2,841 ms** (+0.2%) | 1,624 ms (1.7× low) |
| RETURN_STATEMENT | 1,839 ms | **1,752 ms** (−4.7%) | 950 ms (1.8× low) |

The corrected per-kind table, compiler profile, 856,962 spine nodes,
`checkSpine` = 22,104 ms:

| kind | ms | nodes | ns/node | % of nodes | % of spine |
|---|---:|---:|---:|---:|---:|
| CALL_EXPRESSION | 3,752 | 52,509 | 71,458 | 6.1% | 17.0% |
| VARIABLE_STATEMENT | 2,841 | 14,712 | 193,130 | 1.7% | 12.9% |
| **IDENTIFIER** | **1,853** | **381,670** | **4,855** | **44.5%** | **8.4%** |
| BLOCK | 1,762 | 24,613 | 71,588 | 2.9% | 8.0% |
| EXPRESSION_STATEMENT | 1,753 | 17,392 | 100,820 | 2.0% | 7.9% |
| RETURN_STATEMENT | 1,752 | 15,662 | 111,873 | 1.8% | 7.9% |
| BINARY_EXPRESSION | 1,462 | 38,454 | 38,030 | 4.5% | 6.6% |
| PROPERTY_ACCESS_EXPRESSION | 699 | 67,902 | 10,303 | 7.9% | 3.2% |
| IF_STATEMENT | 656 | 13,679 | 47,990 | 1.6% | 3.0% |
| FUNCTION_DECLARATION | 623 | 8,910 | 69,942 | 1.0% | 2.8% |
| VARIABLE_DECLARATION | 555 | 15,710 | 35,373 | 1.8% | 2.5% |
| CONDITIONAL_EXPRESSION | 219 | 4,506 | 48,753 | 0.5% | 1.0% |

**The two orderings share almost nothing.** By count the top three are
IDENTIFIER (44.5%), PROPERTY_ACCESS (7.9%) and CALL_EXPRESSION (6.1%); by time
they are CALL_EXPRESSION, VARIABLE_STATEMENT and IDENTIFIER. **The five
statement-anchor kinds (VARIABLE_STATEMENT, EXPRESSION_STATEMENT,
RETURN_STATEMENT, IF_STATEMENT, BLOCK) are 10.0% of the nodes and 40% of the
spine**; IDENTIFIER is 44.5% of the nodes and 8.4% of it. *That* is the
concentration, and it is the cpa/cta statement anchors — the region rounds 733,
737 and 738 have been opening one function at a time.

**What changes.** § 0's sentence "*the measured lever is consultation, not
computation*" loses its evidence entirely. Round 732 had already falsified the
INFERENCE (22 of 59 handlers genuinely act at an identifier; the removable part
is 340 ms probe-inflated, ~100 ms real); this round removes the premise too —
identifiers were never 44.5% of the cost. **What stands**: round 716's own
"1,048 ms" was arithmetically correct *for the enter chain*, which is what its
probe skipped. The number was right; the noun attached to it was not.

### 5.2 `getCalleeType`: 50.6% of the calls, 8–10% of the time — 6.2×

(CALL.1) § 6 left this as one of the arc's last open forward-pointers:

> "`getCalleeType` costs 474 ms and **half its results are thrown away** at the
> any/error bail three sections later (26,496 of 52,413). … it is a question of
> whether the bail's verdict is knowable more cheaply than by resolving."

Two runs of the split, compiler profile:

| | calls | share of calls | ms raw | share of the section | ns each |
|---|---:|---:|---:|---:|---:|
| **result DISCARDED** (`any`/`error`) | **26,496** | **50.6%** | **37 / 46** | **8.0% / 9.6%** | **1,452** |
| result USED | 25,917 | 49.4% | 426 / 435 | 92% / 90% | 16,491 |

**A discarded resolution costs 11× less than a kept one.** The counts are
deterministic to the unit across runs and match round 734's exit profile
exactly, so only the milliseconds carry noise.

**The verdict's own answer, and it is the reverse of the question:** the bail's
verdict *already is* cheap. There is nothing to know more cheaply — an
`any`/`error` callee is precisely the one that fails resolution fast. **The
maximum prize is 38 ms = 0.14% of the compile**, against a ±2.0% band of
~530 ms. **(CALL.1) § 6's pointer closes as a measurement.**

This is § 0's law for the sixth and seventh time (with 5.1), and again with no
cache in sight: *the population you could skip cheaply is the population that
was already cheap.*

### 5.3 The residual that was named "dispatch" — ~34×

Not a frequency; the other failure mode. § 0's table ends with

> | dispatch + handler machinery (residual) | ~7,600 ms | 42% |

which is `spineEnterNode + spineLeaveNode − the instrumented type-system rows`.
A residual is a subtraction; naming it is a hypothesis. § 0.1 then spends it:

> | if we removed… | result | still |
> | ALL dispatch overhead | 66 | 1.5× |

**Rounds 732, 733 and 734 each measured a piece of that 34 units and none of it
was dispatch.** 732: the whole per-kind dispatch table removes 64% of
consultations for 4.8% of the time, ~100–300 ms in production. 733: 88.4% of the
two largest handlers is `checkPropertyAccessInExpr` and
`checkSingleCallExpressionTypes` doing their pass's own checking; the ambient
scaffolding is 360 ms and the ancestor climbs 176 ms. 734: inside the largest of
those, 78% is type-system work.

**So the row should read `100 → ~99, 2.4×`.** The 34 units are the checking
work itself, which is exactly what the "endgame" paragraph is about and exactly
what rounds 739/755 priced at ~2.3% for the three largest assignability sites.
**Parity does not need "all three levers"; it needs the checks to get cheaper,
and that is one question, not three.**

### 5.4 Two flagged, not fixed

* **G2** — "all 37,379 arguments pay for the full `argType` computation" while
  only 27% reach the relation. The 924 ms total is measured; its split by exit
  class is not. After 5.1, 5.2, N4 and Y4 the prior should be that the
  non-relating 73% are the cheap ones. Sized in § 7.
* **S5** — the `owner` section's ≈1,340 ms of "WORK" is 19,551 hits × a per-hit
  rate borrowed from a *different* section. The conclusion is probably right
  (its two sibling parent-keyed sections are 28 ms measured) but the derivation
  is an imputation.

## 6. The predictions, scored

Written down before any measurement.

| | prediction | measured | |
|---|---|---|---|
| P1 | IDENTIFIER's enter+leave share is **6–9%** against the 44.5% frequency; ratio 5–7× | **8.4%, ratio 5.3×** | **HELD** |
| P2 | adding leave re-orders the table: CALL_EXPRESSION overtakes IDENTIFIER, ≥ 2,500 ms | **3,752 ms, rank #1** | **HELD** |
| P3 | the `getCalleeType` bail half is ~50% of calls but **≤ 40%** of the time | **8–10%** | **HELD** — but far more extreme than called; the direction was right and the magnitude under-called by ~4× |
| P4 | the top three kinds by time are together **< 40%** of the spine — no kind is a lever | **38.3%** | **HELD** |
| P5 | ≥ 3 claims turn out to be frequencies spent as populations | **3 falsified + 2 unverified** | **HELD** |
| falsifier | any re-derived population **above** the ±2.0% band (~530 ms) ⇒ report a revived lever | largest correction is 38 ms | **not met** |

Five of five held — and that is the round's own warning sign, not its credential.
Rounds 732–757 scored 2-of-4, 2-of-4, 2-of-5, 4-of-5 wrong, and those are the
rounds that changed the map. **An audit whose every prediction holds has probably
tested claims it already half-knew were wrong** (A7 and W1 were both flagged by
round 757; C5 was the arc's own open question). The claims that would falsify
*this* round are the two ❓ rows, and they are unmeasured by choice of scope.

## 7. What this hands forward, sized and NOT started

**No parked item is revived.** Every re-derived population came in below its
citation, so nothing that was parked on a size becomes live. Two items are
worth writing down:

1. **(AUDIT.2) — split `argType` by exit class** (G2). One field
   (`pendingArgTypeNanos`) plus two nested `ArgSections` rows charged at the
   relation section's open and flushed at the loop's next iteration / `end()`.
   **Ceiling 924 ms = 3.5%, expected answer ≈ 200–300 ms on the non-relating
   73%**, i.e. in-band, closing the last "yet all of them pay" claim in the arc.
   Half a round. *Prediction, so it can be scored: the 73% that never reach the
   relation carry **< 40%** of the 924 ms.*
2. **(AUDIT.3) — price A10.** "1,341,719 globals lookups at 98.9% miss, priced
   ≲0.2%" is the last asserted-not-measured population in § 0. Today 961,213
   lookups; at 0.2% of a 26.5 s compile the implied cost is ~55 ms / ~57 ns per
   lookup, which is plausible for a `HashMap` miss and therefore probably right.
   One nested span at `globalsForFile` settles it. A quarter of a round, and its
   value is closing the file, not finding time.

**And the thing this audit does NOT license.** The corrected per-kind table
(§ 5.1) shows 10% of the nodes carrying 40% of the spine. That is a LOCATION,
not a lever — the identical inference from the identical table is what produced
(DISPATCH.1) and cost rounds 716–732. Those five kinds are the cpa/cta statement
anchors, and 733/737/738 have already opened three of the functions behind them
and found ordinary checking work every time.

## 8. Verification

* Compiler profile `--listAll`: **46 errors**, composition unchanged
  (TS2591×43 / TS2304×2 / TS2584×1); production vs `--callSections` sorted-line
  diff **IDENTICAL**.
* Filtered batch (`*CallSectionProbe*` `*Inv0PassTiming*` `*SpineSectionProbe*`
  `*SpineDispatchProbe*` `*CtaSectionProbe*` `*ArgSectionProbe*`
  `*NarrowMemoDepth*`): **66 tests, 0 failures** (65 + 1 new pin).
* Build warning-clean (`compileKotlinJvm compileTestKotlinJvm`, no `w:`).
* Production cost of the two additions: **zero instructions** for the per-kind
  change (`spineWalkFileProfiled` is entered only under `PassTiming.enabled`),
  one static read plus a not-taken branch for the `getCalleeType` split.

## 9. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
# the corrected per-kind enter+leave table (grep 'per-kind')
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTiming --listAll build/bench/tsc-project-*
# the getCalleeType outcome split (grep 'getCalleeType ->')
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --callSections build/bench/tsc-project-*
```
