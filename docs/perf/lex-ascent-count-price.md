# (WARM.34) round 907 — the COUNT question, measured and REFUSED, and the family CLOSED

Rounds 901 and 902 closed `lexLevelHasName` as a **container** question — a 64-bit
proof-of-absence filter at **+0.26%** (below floor, and it forecloses the
container) and the parallel-array successor at **−0.19%, a regression** — and
left exactly one lever, unopened:

> the 737,591 real probes arise from an O(depth) ascent that **revisits the same
> big outer levels on every walk** (35.5% of probes land on levels averaging 815
> symbols). The lever is memoizing the **ASCENT**, not the probe. Ceiling
> 21.4–27.3 ms (0.39–0.50%); realistic value **UNPRICED**.

**Verdict: REFUSED, and the family is CLOSED.** The premise is wrong. An ascent
walks **3.69 NameScope steps** and performs **1.54 real probes** — so there is no
O(depth) chain of expensive probes to collapse, and no ascent-level oracle can
recover more than **0.54 probes per ascent** unless it is *cheaper than a probe*,
which only a refusing filter is, and that one is bounded at **7.96 ms**.

| | |
|---|---:|
| ascents per warm rebuild | **563,466** |
| real probes they perform | **870,231** (mean **1.544**) |
| NameScope chain steps | 2,079,962 (mean 3.69) |
| **the WHOLE probe stream, at 36.6 ns** | **31.85 ms = 0.602%** |
| ceiling on an ASCENT MEMO (repeats served, memo FREE) | **9.92 ms = 0.187%** |
| ceiling on a per-file PROOF-OF-ABSENCE FILTER | **7.96 ms = 0.151%** |
| ceiling on ANY one-operation oracle costing one probe | 11.23 ms = 0.212% |
| arc refusal floor | ~17 ms = ~0.31% |

Binary: `e8c422e3` + this round's census. Profile `build/bench/tsc-project-*`
(78 files, **46 errors** on every run). Denominator **5,290 ms** as rounds 905 and
906 stated it (1% = 52.9 ms). Rate NOT re-measured: round 901's JFR row implies
**36.6 ns** per first probe of a level and its amplifier measured **33–37 ns**,
agreeing to 0.5%; round 902 reproduced the second at 28.9 ns in a batch that ran
9% fast. Captures under `build/bench/round907/`, three processes.

---

## 1. The census (`--mapCensus`, three processes identical to the last digit)

```
(WARM.34) ASCENTS: calls=563466 (has=264767 shadow=224276 type=36493 tp=37930 tpConstraint=0)
          NameScope steps=2079962  no-probe ascents=100184
     REPEAT (scope,name,family) asked before = 204880 (has=114930 shadow=76288 type=6616 tp=7046)
     real probes: FIRST=599256 REPEAT=270975 sum=870231 (must equal all real probes 870231)
                  by family=384008,429563,28182,28478,0
     real probes per ascent 0..10 then 11+:
          100184 231365 127215 61696 25736 10728 4311 1401 526 224 48 32
     ascents whose consulted levels held the name NOWHERE: 110595 carrying 217539 real probes
     (level,name) pairs at a REAL lexLevelHasName probe: distinct=142632 repeat=594959
```

**The partition check is EXACT** — the per-ascent probe counts sum to 870,231,
which is every real probe the three families make (`lexSymProbe + lexExProbe +
lexTypeSymProbe + lexTpProbe`). That is round 796's law and it is what says the
bracketing sees every path: the five ascent functions are the only callers of the
four real probe sites, they never nest, and the close-on-next-entry bracket is
therefore a partition rather than a sample. Every pre-existing counter in the same
report is unchanged across the round's two builds, which is the cross-build
control.

### 1.1 A chain-step population is not a probe population

Round 902's own law, one step further along the same family. The ascent's shape:

| | per ascent |
|---|---:|
| NameScope chain steps | 3.69 |
| `lexLevelHasName` calls (round 901) | 1.82 |
| …refused by the untrusted-owner rule | 0.027 |
| …hash-free (an EMPTY level, null `table`) | 0.48 |
| **real probes** | **1.544** |

So of 3.69 chain steps, most contribute no probe at all: a `NameScope` whose `lex`
equals its parent's opens no segment, an EMPTY level's probe returns before it
hashes (round 901's split), and the untrusted/non-head-function rules refuse the
rest. **The "O(depth) ascent revisiting the same big outer levels" is a walk of
3.69 pointer steps carrying 1.54 map probes.** The 35.5%-of-probes-on-815-symbol-
levels figure from round 902 is not contradicted — those probes are real and they
are where the ms is — but there are only 1.54 probes per ascent to collapse, and
that, not the chain length, is what any oracle is differenced against.

### 1.2 …and the redundancy IS there, at the pair level

**80.7% of the `lexLevelHasName` real-probe stream is a re-probe of a
`(level, name)` pair already asked** — 142,632 distinct pairs carry 737,591
probes, **5.17 probes per pair**. The brief's suspicion was correct as a
statement about the *stream*. § 3 is why no cache can bank it.

## 2. The ASCENT memo — the queued lever

A `(NameScope, name, family)`-keyed answer, one probe replacing a whole walk.

| | |
|---|---:|
| ascents whose key had been asked before | **204,880 (36.4%)** |
| real probes those repeats perform | **270,975** |
| …per repeat ascent | **1.323** |
| first sightings | 358,586, performing 599,256 probes (**1.671** each) |

**A repeat ascent performs 1.32 real probes. A memo probe replaces them with 1.**
So the memo removes **66,095 probes = 2.42 ms = 0.046%** — and charges 358,586
memo MISSES plus 358,586 inserts against it, which it cannot possibly pay for.

The generous readings, all refusing:

* memo probes and inserts entirely **FREE**, all 270,975 repeat probes deleted:
  **9.92 ms = 0.187%**, refused by 1.7x;
* memo probes at round 901's *warm re-probe* rate of 6.4 ns — the most optimistic
  rate this family has ever measured, and the memo map is per-scope and
  `String`-keyed exactly like the thing it replaces: 9.92 − 3.61 = **6.31 ms**;
* memo probes at the rate actually measured for a first probe of a small
  `String`-keyed map, 36.6 ns: **9.92 − 20.62 = −10.7 ms**, a regression.

**Why it fails is the shape, not the hit rate.** 36.4% is a perfectly good hit
rate. What kills it is that the thing being memoized is 1.3 map probes long, and
a memo is 1 map probe.

*The soundness question the brief flagged never arises, and is recorded so nobody
re-opens it as an obstacle:* a `NameScope`'s `names`/`typeNames`/`typeParamNames`
sets are MUTATED as the walk declares, so a negative memo would go stale and need
invalidation on every `add`. That is a real cost and a real risk (a stale negative
is a false TS2304), and it is not what refuses the lever — the arithmetic refuses
it before the risk is priced.

## 3. The per-LEVEL memo — refused by construction, not by arithmetic

The 80.7% pair-level redundancy of § 1.2 is 594,959 probes = **21.8 ms = 0.41%**,
which is round 902's stated ceiling, reproduced. It cannot be banked:

> **A cache keyed by the same name at the same granularity as the map it fronts
> IS that map.** A per-`LexicalScope`, `String`-keyed memo of "does this level
> bind this name" is `LexicalScope.symbols`. It replaces one probe of a 1.5-entry
> map with one probe of a same-sized map, at identical cost, and it has to be
> populated first.

A program-wide `(LexicalScope, name)`-keyed map is strictly worse: a pair key
costs two hashes and two comparisons against the single `String` probe it
replaces, and the table is 142,632 entries deep rather than 1.5. There is no
version of this that is not negative.

## 4. The per-FILE proof-of-absence filter — the last shape, and the only cheap one

The one operation that *is* cheaper than a probe is a bit test (round 901
measured the 64-bit filter arm at **1.17 ns**, reproduced at 0.96 ns by round
902). A filter can only REFUSE, so its population is the ascents whose name is
bound in **no** lexical level of the file — tested once per ascent instead of
round 901's once per level.

| | |
|---|---:|
| ascents where **no consulted level held the name** | **110,595 (19.6%)** |
| real probes they perform | **217,539 (25.0% of the stream)** |
| gross at 36.6 ns | **7.96 ms = 0.151%** |
| filter tested on all 563,466 ascents at 1.17 ns | −0.66 ms |
| **net, upper bound** | **≤ 7.30 ms = 0.138%** |

**This is an UPPER bound, deliberately.** `{name absent from the whole file} ⊆
{no consulted level held the name}`, because a name absent everywhere is absent
from each level the ascent happens to visit — so the measured population can only
exceed the filter's, never fall short of it. A refusal taken against an upper
bound is a refusal with certainty (round 903). Three further deflations are not
even applied: a per-file union of every level's names is hundreds of keys, so a
*64-bit* mask is saturated and refuses nothing — a real one needs a wide bitset
with its own build and its own false positives; the filter cannot refuse the
threaded `names` sets, so the 3.69-step chain walk survives it; and it must be
built per file.

The "hit" predicate here is the level's own **presence** test, not the function's
verdict — `lexLevelHasType` can find a symbol and answer false on its flags, and
`typeParamConstraintOf` can find an unconstrained type parameter — because a name
filter over-approximates both, and presence is what bounds it.

## 5. The general theorem, which is what closes the family

Any scheme in this direction replaces an ascent's probes with ONE operation of
cost `c`:

    saving  =  870,231 x 36.6 ns  -  563,466 x c

* `c = 0` (a free oracle serving **every** ascent): **31.85 ms = 0.602%** — this
  is the whole probe stream, i.e. the cost of every probe the three families make.
  **The family is 0.60% of a warm rebuild in total.**
* `c` = one `String`-keyed map probe, which is what any map-based oracle costs:
  **11.23 ms = 0.212%**, below the floor.
* and no oracle serves every ascent: a **memo** serves only the 36.4% that repeat
  (§ 2), a **filter** only the 19.6% that are absent everywhere (§ 4).

Even granting BOTH mechanisms at once, both free, and assuming their populations
are disjoint (they are not — an ascent can be both a repeat and a no-hit), the
union bound is 270,975 + 217,539 = 488,514 probes = **17.88 ms = 0.338%**; charge
the memo its probes at the optimistic 6.4 ns and the filter its tests at 1.17 ns
and it is **13.61 ms = 0.257%**, still below the floor, for two mechanisms, an
invalidation protocol and a per-file bitset.

> **To clear a 0.31% floor here a lever must delete more than half of the entire
> probe stream at zero cost.** The best one deletes 25% and the other 31%, and
> neither is free.

## 6. What this closes, and what it corrects

* **`lexLevelHasName` is CLOSED — container and count.** Round 901 refused the
  per-level filter (+0.26%), round 902 refused the container (−0.19%, a
  regression), and this round refuses both count levers (0.19% and 0.15%
  ceilings) and bounds *every* count lever at 0.60% gross. **Do not re-open it
  from the leaf table**: the row is real, 30.1 ms, and it has now been attacked
  from three directions with four measured instruments.
* **Round 902 § 4's premise is corrected.** "An O(depth) ascent that revisits the
  same big outer levels on every walk" describes the CHAIN (3.69 steps); the
  PROBES are 1.54. This is round 902's own law — a scope population is not a
  probe population — applied to its own successor: **a chain-step population is
  not a probe population either.**
* **The queue's ceiling was scoped to one of three families.** "21.4–27.3 ms if
  all 737,591 real probes went free" covers `lexLevelHasName` alone; the complete
  family is **870,231 probes = 31.85 ms**, adding `lexLevelHasType` (28,182),
  the two `isTypeParam`/`typeParamConstraintOf` sites (28,478) and the root's
  `existing` probes (75,980).
* **Two populations worth reusing.** `typeParamConstraintOf` is called **0 times**
  per rebuild on the compiler profile — a whole ascent family that never runs.
  And **17.8% of ascents make no real probe at all**, answered entirely from the
  threaded `names` sets.

## 7. Pins and ablation

Six pins (`LexAscentCensusTest`), and the ablation is one mistake at a time on a
committed tree (round 807), each arm naming what shows the mistake was REACHED
(round 902: `git diff --shortstat` proves the edit landed, never that it does
anything).

| arm | the mistake | reached because | pins reddened |
|---|---|---|---|
| C1 | the recursion calls the public entry, so every chain step opens an ascent | the chain-steps pin asserts `steps > calls`, i.e. the fixture really recurses | 2 — *an ascent is opened once per top-level query*, *the probe histogram partitions the ascents* |
| C2 | the ascent is never closed on the next entry (probes charged to the first ascent only) | the partition pin asserts the probe total is non-zero | 1 — *the per-ascent probe counts partition the real probes* |
| C3 | the repeat set keyed on the name only, dropping the scope | the repeat pin asserts a first sighting exists at all | 1 — *the same name at two different scopes is two first sightings* |
| C4 | the `(level,name)` pair census keyed on the name only | the pair pin asserts distinct > 0 | 1 — *a pair is distinct per LEVEL* |
| C5 | the presence hit recorded from the flags VERDICT instead of the level's map | the no-hit pin asserts the no-hit population is non-empty | 1 — *a level that holds the name is a hit even when the verdict is false* |
| C6 | `lexAscentStep` hoisted out of its `MapCensus.on` guard | — (an INV.0 violation, visible only as a count with the census off) | 1 — *the census is inert when off* |

## 8. Gates

| gate | result |
|---|---|
| `jvmTest`, 4 modules, real XML parser | see the session note |
| `cost_gate.py` | **+0.00%** expected — a CONTROL, not a verdict (round 876) |
| `huge_methods.py --fail-over 0` | 0 over the limit |
| 8-profile `--listAll` grid | run, because the round DOES edit production shape |

Unlike rounds 901/902/906 this round is not purely guarded hooks: the five ascent
functions are split into a public entry and a `…From` recursion so a top-level
resolution can be told from a chain step. That is behaviour-preserving by
inspection (the recursion targets move from `has` to `hasFrom` and so on, one for
one) and it sits on the path that decides TS2304 — so the grid is a gate here,
not a control.

## 9. For the next agent

* **Reusable constants**: the unresolved-names ascent is **563,466 ascents,
  2,079,962 chain steps and 870,231 real map probes per warm rebuild** — 1.544
  probes per ascent, 36.4% of ascents repeat a `(scope, name, family)` already
  asked, 19.6% find the name in no level they consult, and 17.8% probe nothing at
  all. At 36.6 ns the whole stream is **31.85 ms = 0.60%**.
* **The general law this round adds**: *a cache keyed by the same key at the same
  granularity as the map it fronts is that map.* Before pricing a memo, divide
  the work it would replace by the operations it would perform — a memo in front
  of 1.3 map probes cannot win, whatever its hit rate.
* **And the corollary**: an ascent's DEPTH is not its COST. Count the operations
  that survive the walk's own guards (here 58% of the level visits are refused or
  hash-free) before reading "O(depth)" as a lever.
