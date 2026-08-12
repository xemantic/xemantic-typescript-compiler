# (WARM.27) round 900 — the two candidates round 899 left with unknown populations

Round 899 § 33.8 ranked six candidates off the sixth leaf profile and attached
round 898's admission test to every one of them: **divide the JFR ms by the
operation count the owner actually performs and ask whether the implied
per-operation cost is physically possible.** Two of the six were ranked with
their populations explicitly UNKNOWN, and both are answered here by a counter
before any code moved.

They came out opposite ways, which is the useful part.

| candidate | JFR | population | implied per-op | verdict |
|---|---:|---:|---:|---|
| (5) `SuffixNameSet.materialize` | 21.6 ms | **767,521** inserts | **28.1 ns** | **BUILT** — a `HashSet.add` costs exactly that |
| (1) `resolveImportedSymbolGeneral` | 21.9 ms `containsKey` | **259,739** probes | **84.3 ns** | **REFUSED** — an `Integer`-keyed probe is 15-30 ns |

---

## 1. Candidate (5) — the row is real, and it is the first one in this arc that is

Round 899 stated the arithmetic as **binary**:

> a `HashSet.add` with a cached `String` hash is ~20-40 ns, so 21.6 ms implies
> ~0.5-1.0 M adds per rebuild for a module-resolution helper over 78 files. That
> is implausibly large for a set built once per file and entirely plausible for a
> set rebuilt per QUERY. So the counter decides it and the two outcomes are far
> apart: rebuilt-per-query is a memo with a real prize; built-once means the row
> is over-attributed and there is nothing here.

`FlowScan.setEntries`, one accumulate per materialisation, says:

```
suffix sets created 1143, materialized 1143, names inserted 767521 (mean 671 per set)
```

**767,521 adds, and 21.6 ms / 767,521 = 28.1 ns each** — squarely inside the
20-40 ns band the test was set at. The row survives its own plausibility check.
Of round 899's six candidates and round 894's nine, **this is the only profile
figure so far that has not deflated ~3x under measurement**.

### 1.1 …but it is NEITHER of the two predicted shapes

`SuffixNameSet.materialize` memoises into a `built` field, so no set is ever
rebuilt: `setsCreated == setsMaterialized == 1143` is one build each. The
prediction's "built-once means the row is over-attributed" does not follow,
because it silently assumed a set built once is a SMALL set. These are not: the
mean is **671 names**.

*A count of builds does not bound the work a build does. The binary was posed
over the wrong quantity — builds — when the quantity that decides a 100%-insert
row is INSERTS.*

### 1.2 The lever the third counter found

The suffixes are not independent. Each is cut from one cached `ReassignScan`,
and one scan backs many closures:

```
reassign scans built 1220 holding 15331 names
```

**1,220 scans hold 15,331 names in total** while their 1,143 suffixes insert
767,521 — a 50x gap, because the suffixes of one scan are NESTED and every one
of them re-inserts the same tail. Membership over a suffix is therefore a
comparison against the scan's LAST occurrence of the name:

```
e in suffix(lo)   <=>   (exists k >= lo) names[k] == e
                  <=>   max { k : names[k] == e } >= lo
```

`SuffixNameIndex` is that map, built lazily, once per scan, and shared by every
suffix cut from it. The LAST occurrence and not the first is load-bearing:
duplicates are the whole point of the structure (a name reassigned both before
and after a closure), and first-wins answers `false` where the truth is `true`.

### 1.3 The defect underneath, which the counters found and no gate here could

The first build after the swap still read `materialized 1143`. The reason is one
argument:

```kotlin
FrontEnd.addClosureCensus(reassigned.size.toLong())   // Flow.kt, before
```

`addClosureCensus` opens with `if (mode != ON) return`. **Kotlin evaluates
arguments strictly, so that guard never got the chance to run** — and asking a
lazy view its size materialises it. The (FRONT.2) probe was therefore building
all 1,143 hash sets on **every production compile**, with `FrontEnd.mode` OFF.

Round 801 introduced `SuffixNameSet` precisely so those sets would not be built,
and read its own census — `created 1143, materialized 1143` — as evidence that
"every set is eventually asked", concluding the deferral MOVED work rather than
deleting it (CLAUDE.md's round-801 entry says so). **The asker was the
instrument.** With the argument passed as the SET and `.size` read after the
guard, the same census reads:

```
suffix sets created 1143, materialized 0, names inserted 0
suffix name indexes built 192 inserting 11619 names
```

Nothing in production ever asks one of these views its size or iterates it. Only
**192 of 1,220** scans are ever questioned at all, so 84% of them now build
nothing.

*A probe that must be free when off is not free when off if its ARGUMENT does
the work. The mode check inside the function is the wrong side of the call.*

### 1.4 The price

| | before | after |
|---|---:|---:|
| `HashSet.add` (suffix sets) | 767,521 | **0** |
| `HashMap.put` (shared indexes) | 0 | **11,619** |
| net inserts removed | | **755,902** |

At the row's OWN implied rate of 28.1 ns — the honest rate to use, since it is
the one the JFR row and the population agree on — that is **~21.2 ms**, against
a **5,429 ms** warm rebuild (round 899's denominator) = **~0.39%**. The new
11,619 puts are ~0.3-0.5 ms.

Two deflations are stated rather than hidden. The 28.1 ns is derived FROM the
JFR row, so if that row carries the usual attribution bias the true saving is
proportionally smaller; and this arc does not attempt a wall A/B at 0.39%,
because that is well below what this box settles (round 899's 12/12 sign test
could not resolve a magnitude at 1.9%). **The claim is the population, which is
deterministic and reproduced exactly across runs, and the arithmetic on it.**

### 1.5 What did NOT move

`cost_gate.py`: **+0.00% on all 20 counters** — the expected control (round 876).
The change deletes hash-set inserts and cannot move a checker counter; that it
moves none is the statement that the resolution path is untouched.

---

## 2. Candidate (1) — REFUSED, and the number that decided it

`resolveImportedSymbolGeneral` is a proven `containsKey`-then-`get` double probe
(round 896's `globalsForFile` shape) on an `Int`-keyed, therefore boxed, cache.
Round 899 priced it at 24.3 ms with 21.9 ms in `containsKey`, said the row is
physically real only at ~0.7-1.5 M probes per rebuild, and said the first
instrument is a counter.

```
resolveImportedSymbolGeneral: calls=259739 top-level=259739 hits=251380
map probes=511119 (containsKey 259739 + get 251380)
```

- **259,739 `containsKey` calls, not 0.7-1.5 M.** 21.9 ms over them is
  **84.3 ns per `Integer`-keyed probe**, where this arc's own reference is
  15-30 ns. **The row is over-read ~3x** — round 898's law for the ninth time,
  and its clustering at ~3x for the owners that really are one map operation.
- Every call is top level (the recursion always allocates its own `visited`), and
  **96.8% of them hit**.
- The removable half is the second probe: **251,380 gets = 3.8-7.5 ms =
  0.07-0.14%** of a warm rebuild.

That is **below the 0.31% at which round 897 refused a change it rated LOW risk**
and at or below round 898's 0.13-0.20% refusal of candidate (6).

And the change is not quite the five lines it looks like. The cache is
`HashMap<Int, Symbol?>` — the value is NULLABLE, and `containsKey` is exactly
what separates "not cached" from "cached as null" for 96.8% of the traffic.
Collapsing to one probe needs a SENTINEL, i.e. a correctness-carrying construct
whose whole justification would be 0.07-0.14%. **Refused.**

One location recorded without a price, because round 801's law forbids inferring
one: the parameter default `visited: MutableSet<Int> = mutableSetOf()` allocates
a `LinkedHashSet` on all 259,739 calls, of which the 251,380 cache hits never
touch it. *An allocation count is not a cost* (round 801 measured 367,189 removed
`String` allocations at 0 ms), so this is a note, not a candidate.

---

## 3. Candidate (2) — not started

`lexLevelHasName` (30.1 ms, ~1.0 M probes at ~30 ns, **not refuted** by round
899's arithmetic) needs a proof-of-absence filter over a bind-frozen scope name
set, is MEDIUM risk against INV.4(c)(ii)'s three load-bearing rules, and needs
the 8-profile grid. It was ranked below (1) and (5) for this round and the budget
went to them. It remains the top open item.

---

## 4. The ablation

Five arms, one mistake at a time, dry-run for a real diff, on a committed tree
(rounds 789/807/851/855).

| arm | the mistake | pins red | uniquely its own |
|---|---|---:|---|
| A1 | index records the FIRST occurrence | 3 | no — a strict SUBSET of A2 |
| A2 | `> lo` instead of `>= lo` at the cut | 8 | yes (5 `FlowScanEquivalenceTest` semantic pins) |
| A3 | index not shared — one per suffix | 1 | yes, **after repair** |
| A4 | the eager `.size` probe argument restored | 1 | yes |
| A5 | `contains` re-materialises the set | 4 | yes |

**All five discriminate; four have a uniquely-their-own pin.** A1 is caught but
is not separated from A2 — every pin A1 reddens, A2 reddens too — and that is
recorded rather than dressed up (round 807).

**A3 was BLIND on the first pass, and it is round 897's A1 verbatim.** Making
every suffix build its own index changes no ANSWER; it only does the work N
times. So every membership pin stayed green — correctly — and the arm read as a
clean sweep, which under round 807's protocol would have credited the pins with
discrimination they did not have. The repair is a COUNTER pin: an index belongs
to a scan, so `indexesBuilt <= scansBuilt` and `indexEntries <= scanNames` hold
by construction and are breached the moment a scan's suffixes start minting one
each. *Only the container's identity can see a container swap.*

---

## 5. Gates

| gate | result |
|---|---|
| `jvmTest`, 4 modules, real XML parser | **14,379 / 0 failures / 3 skipped** (+7 = exactly the new pins; baseline 14,372) |
| `cost_gate.py` | **+0.00% on all 20 counters** — the expected control |
| `huge_methods.py --fail-over 0` | **0 over the limit** |
| 8-profile `--listAll` grid | **all eight `added=0 removed=0`** (46 each, harness 94) |
| `--verifyFlowScan` | 1,220 scans compared, **0 diverged** |

### 5.1 The grid

Cross-round against round 898's committed captures, identical recipe
(`scripts/round900-grid.sh`). Unlike rounds 897/898 this is a real gate and not a
control: production behaviour changes on a path the checker reads during
narrowing.
