# (WARM.28) round 901 — `lexLevelHasName`, measured and REFUSED, and the successor its census uncovered

Round 899 § 33.8 ranked six candidates off the sixth leaf profile. Candidate (2),
`lexLevelHasName` at **30.1 ms**, was the one it explicitly did **not** refute:
it is not a double probe (round 896's shape does not transfer), and ~1.0 M
probes at ~30 ns is plausible beside `globals.lookups` 748,522. Rounds 900 took
(5) and refused (1); this round takes (2).

**Verdict: the row is REAL — confirmed by a second, independent instrument to
within 0.5% — and the lever is REFUSED at ~14 ms (0.26%).** The census that
priced it also found something worth more, and the last section prices that.

| | |
|---|---:|
| `lexLevelHasName` calls per rebuild | **1,024,959** |
| REAL map probes (a map with a non-null table) | **813,571** |
| implied rate from the JFR row (29.8 ms) | **36.6 ns** |
| rate measured independently by amplification | **33-37 ns** |
| probes a proof-of-absence filter could refuse | **481,170** |
| net prize | **~14 ms = 0.26%** |
| mean own symbols per queried lexical level | **1.51** |

---

## 1. The population, and the split that decides it

`--mapCensus`, compiler profile, reproduced identically on three runs.

```
lexLevelHasName: calls=1024959 untrusted=15469 fnSkipped=215 rootExcluded=0
  symbols: EMPTY(no table, hash-free)=271684  REAL probes=737591 hits=262637
  existing: probes=75980 hits=69764   MISSED=6216 (after a real symbols probe=0)
  symbols missed with no existing table=670658 (real=474954)
  => REAL probes a filter could refuse = 481170 (of 813571; filter TESTED on 737591)
lexLevelHasType: calls=64975 EMPTY=36160 REAL=28182
isTypeParam/constraintOf symbols[]: EMPTY=47014 REAL=28478
scopes queried=26313 holding 39809 own keys => mean 1.51
scopes BOUND=32693 holding 47490 own keys
```

**1,024,959 calls — exactly the ~1.0 M round 899 derived.** The derivation was
right, and so was refusing to act on it without the counter, because the count
of *calls* is not the count of *work*:

> `HashMap.getNode` reads `table` **before** it hashes the key, and a
> `mutableMapOf()` that was never written keeps `table == null`. So **271,684 of
> the 1,009,275 probe-path calls are a null check and a return** — no hash, no
> bucket, no `equals`. A census that counted probes alone would have priced them
> at the arc's 20-50 ns reference and manufactured 7-13 ms of prize out of
> operations that cost nothing.

That is the same error one level down from round 898's: *divide the ms by the
operations the owner performs* — and an operation that short-circuits before it
does anything is not one of them.

One structural fact falls out of `lexAbsentReal = 0`: **the `existing` probe only
ever happens at the `SourceFile` root**, because `Binder` gives an `existing`
table to exactly three owner kinds (root, module, enum) and the latter two are
refused by the untrusted-owner rule above it. Its hit rate is **92%** — that
probe is mostly useful and there is nothing to filter there.

## 2. The arithmetic check (round 898's admission test)

29.8 ms of `containsKey` over **813,571 real probes = 36.6 ns**, inside the
20-50 ns band. **Not refuted.** This is the second row in this arc, after round
900's candidate (5), to survive its own plausibility test — against eight of
round 894's nine and one of round 899's six that did not.

But 36.6 ns is at the *top* of a band measured on `perFileScope`, whose keys are
file PATHS in a populated table, and the census says the mean queried level holds
**1.5** entries. A cost prior from one family of sites does not transfer to
another (round 789), so the rate had to be measured.

## 3. `--lexLevelAmp` — and why it amplifies BOTH arms

Round 759's amplification: `r` operations under ONE timestamp pair, two values of
`r` to cancel the ~90 ns boundary algebraically. The addition here is that **both
arms are amplified in the same call** — the real map probe and the 64-bit
proof-of-absence filter that would replace it, ABBA per call. At equal `r` the
boundary cancels **between** the arms, which is what makes a *first-probe* rate
readable at all; and what a decision needs is what a swap RECOVERS, not what the
old container costs (round 896's `nodeToFlow` lesson, one candidate over).

| `r` | MAP `p(r)` | FILTER `p(r)` | delta |
|---:|---:|---:|---:|
| 4 | 105 / 101 ns | 53 / 51 ns | **52 / 49 ns** |
| 16 | 180 / 180 ns | 67 / 65 ns | **113 / 114 ns** |

Two runs of each, interleaved. The arithmetic falsifier holds exactly: the sink
is 2,591,316 at `r = 4` and 10,365,264 at `r = 16` — **exactly 4x**, so neither
loop was elided.

* **warm map probe = 6.4 ns**; **warm filter test = 1.17 ns** (identical in both runs)
* delta slope **5.08 / 5.42 ns**, intercept at `r = 1` = **36.8 / 32.7 ns**

> **The first probe of a level costs 33-37 ns more than the first filter test,
> and production performs exactly one.** That agrees with the JFR row's 36.6 ns
> to within its own spread — two independent instruments on the same quantity.

The 6.4 ns warm re-probe against a 33-37 ns first probe says what the cost *is*:
not hashing (the `String` hash is cached after the first level of the ascent) but
**three dependent pointer loads** — the `HashMap`, its table array, its `Node` —
none of which are in cache for a map holding one entry.

## 4. The prize, and the refusal

| | |
|---|---:|
| refusable `symbols` probes | 474,954 |
| …refused after false positives (1.51 keys in 64 bits ~ 2.3%) | ~463,900 |
| gross, at 33-37 ns | **15.3-17.2 ms** |
| filter tested on all 737,591 real-probe calls, 1.2-3 ns | −0.9 to −2.2 ms |
| eager build: 47,490 key hashes + 32,693 masks | −0.5 ms |
| **net** | **~12.6-15.8 ms = 0.23-0.29%** |

The 6,216 refusable `existing` probes are excluded: filtering the root's file
locals needs a far wider bitset (173 keys per file) and would refuse 8% of that
map's 75,980 probes.

**REFUSED, for three reasons in ascending order of weight.**

**(a) It is below this arc's floor.** Round 897 refused a change it rated LOW
risk whose gross was 0.31%; round 898 refused MEDIUM at 0.13-0.20%; round 900
refused at 0.07-0.14% and built at 0.39%. 0.26% at MEDIUM sits under the LOW-risk
refusal.

**(b) Nothing could defend it.** `cost_gate.py` reads **+0.00% by construction** —
the change removes map probes, not resolutions — so its only defence would be a
wall A/B at **a seventh of what this box can settle** (round 899 resolved 1.88%
in SIGN alone, with the magnitude unresolvable).

**(c) And the real reason: a filter in front of a container is a commitment to
the container.** The census's sharpest line is not the 30.1 ms, it is that
**32,693 `HashMap`s hold 47,490 keys between them** and the first probe of one
costs 36 ns because of pointer chasing. Refusing 58% of those probes banks the
smaller half **and removes the justification for replacing the container** — the
surviving 42% could no longer pay for it. The filter would foreclose the better
lever, which is § 5.

*What this round did NOT have to worry about, recorded because the brief flagged
it:* the filter would sit **below** INV.4(c)(ii)'s three load-bearing rules and
guard **one map probe** whose answer it proves, not the function's verdict — so
the untrusted-level, non-head-function and root-exclusion rules are untouched by
construction. And `LexicalScope.symbols` has exactly **one writer in the whole
repo** (`Binder.kt` `declareLexical`), so a mask built at the end of
`bindLexicalScopes` cannot go stale. The soundness argument was fine. The number
was not.

## 5. The successor — `LexicalScope.symbols` is a `HashMap` holding 1.45 entries

Bound scopes by own-symbol count (`0..8`, then `9+`):

```
15270  8381  3748  1907  1171  768  456  394  174  424
```

* **46.7% of the 32,693 bound scopes hold ZERO own symbols** — an allocated
  `LinkedHashMap` that never receives a key
* 93.2% hold **<= 4**; **98.7% hold <= 8**; the tail above 8 is **424 scopes (1.3%)**

So the row's cause is not the probe, it is the container. A parallel-array
representation (a names array + a symbols array, linear scan, falling back to a
map above ~8) would:

| | |
|---|---:|
| probes it serves — all three families | **794,251** real (737,591 + 28,182 + 28,478) |
| …plus the currently-free empty probes | 354,858 |
| rate today (measured, § 3) | 33-37 ns first, 6.4 ns warm |
| rate for a <=8 array scan with a length check | ~3-6 ns |
| **recoverable** | **~22-25 ms = 0.41-0.47%** |

That clears every floor this arc has used, including the 0.39% at which round 900
built. It additionally deletes **15,270 wholly-empty `HashMap` allocations** and
17,423 small ones from `bindLexicalScopes` — *unpriced*, deliberately, because an
allocation count is not a cost (round 801 measured 367,189 removed `String`
allocations at 0 ms).

**What is not yet known, and must be before it is built** (CLAUDE.md's first
law): the array scan's own rate is *estimated*, not measured — the
`--lexLevelAmp` harness already has the shape to measure it as a third arm, and
that is the next instrument, not the next fix. Readers are five sites, all
`[name]` gets (`Checker.kt` 24323 — audit-only — 33910, 33955, 34068, and
`Binder.kt` 995); the writer is one. The risk is that it changes a
binder-OUTPUT type and would touch `Inv2LexicalScopeTest`.

## 6. Pins and ablation

Six arms, one mistake at a time, dry-run for a real diff, on a committed tree.

| arm | the mistake | pins red | uniquely its own |
|---|---|---:|---|
| A1 | EMPTY/REAL split collapsed | 1 | no — a strict subset of A3 |
| A2 | queried-scope set stops de-duplicating | 1 | **yes** |
| A3 | the `real` flag frozen false | 3 | **yes** (`sink exact multiple of r`) |
| A4 | the amplifier's map arm dropped | 1 | no — subset of A3 and A6 |
| A5 | a hook hoisted OUT of its `MapCensus.on` guard | 1 | **yes** |
| A6 | the mask built one bit off its own probe | 2 | **yes** (`the filter never refuses a name the map holds`) |

**All six discriminate; four have a uniquely-their-own pin.** A1 and A4 are
caught but not separated — their failures are strict subsets — and that is
recorded rather than dressed up (round 807).

**Two arms were BLIND on the first pass, for the two reasons this arc keeps
meeting.** A2 was round 898's A3: `lexScopesQueried <= lexScopesBound` is
satisfied *vacuously* on a small fixture, because the lib binding dominates the
bound count — the discriminating form is the STRICT inequality against the
probes, since fewer scopes than probes is what de-duplication *means*. A4 was
blind because **one shared sink cannot tell a dropped arm from a running one**;
splitting the sink per arm catches it, and the split also bought the assertion
that matters most here — the filter is a **superset** of the map, so it can never
sink less. That inequality *is* the proof-of-absence property, and A6 exists to
show it asserts something.

## 7. Gates

| gate | result |
|---|---|
| `jvmTest`, 4 modules, real XML parser | **14,390 / 0 failures / 3 skipped** (+11 = exactly the new pins; baseline 14,379) |
| `cost_gate.py` | **+0.00% on all 20 counters** — the expected control |
| `huge_methods.py --fail-over 0` | **0 over the limit**, 714 classes |
| 8-profile `--listAll` grid | **all eight `added=0 removed=0`** (46 each, harness 94) |

The grid is a CONTROL this round — the only production edits are census hooks
inside `if (MapCensus.on)` guards — and it was run anyway because those hooks sit
in `lexLevelHasName`, i.e. on the path that decides TS2304. Cross-round against
round 900's committed captures, identical recipe (`scripts/round901-grid.sh`).

**No wall A/B, for the tenth round running**, and there is nothing to A/B: the
round lands an instrument and a refusal. What is claimed is a deterministic
population, reproduced identically on three runs, and two independent
measurements of one rate that agree to 0.5%.
