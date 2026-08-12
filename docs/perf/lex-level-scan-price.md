# (WARM.29) round 902 — the parallel-array successor, measured and REFUSED, and the population round 901 priced it from

Round 901 refused a proof-of-absence filter for `lexLevelHasName` at ~14 ms
(0.26%) and, in the same census, named a better successor and priced it:

> Replacing the per-scope `HashMap` with a parallel-array linear scan (map
> fallback above ~8) serves **794,251 real probes** at ~3-6 ns instead of 33-37:
> **~22-25 ms = 0.41-0.47%**, which clears every floor this arc has used.
> **NOT built: the array scan's own rate is ESTIMATED, and the next instrument is
> a third `--lexLevelAmp` arm, not a fix.**

This is that arm. **The successor is REFUSED, and it is not close: measured, the
container it proposes is a REGRESSION of ~13.8 ns per probe = −10.1 ms = −0.19%.**

The estimate was not merely optimistic. It was computed over the wrong
population, and correcting the population is what the round is actually worth.

| | |
|---|---:|
| real probes amplified per rebuild | **737,591** |
| level size a probe lands on — **round 901, scope-weighted** | **1.51** |
| level size a probe lands on — **probe-weighted** | **290.94** |
| scan steps a linear scan would pay per probe | **212.12** |
| unconditional scan, against the map, first probe | **−1,046 ns** |
| hybrid (array ≤ 8, map above), against the map, first probe | **−13.75 ns** |
| **net** | **−10.1 ms = −0.19% of a 5,429 ms rebuild** |

---

## 1. A scope population is not a probe population

Round 901 priced the successor from `lexBoundHistogram`, which counts each
`LexicalScope` **once**:

```
bound scopes by own-symbol count, 0..8 then 9+:
15270  8381  3748  1907  1171  768  456  394  174  424      mean 1.51
```

46.7% hold zero, 98.7% hold ≤ 8, and a linear scan over 1.5 entries is obviously
cheaper than chasing `HashMap` → `table` → `Node`. The inference is sound and the
population is wrong. **A level is scanned once per PROBE, not once per
existence**, and the unresolved-names ascent walks from the query outwards, so it
reaches the outer levels on *every* walk while the 15,270 empty leaf scopes are
each touched a handful of times.

`lexProbeSizeHistogram` counts the same scopes once **per real probe**.
Reproduced identically on four runs and across two builds:

```
probe-weighted level size, 0..8 then 9+:
    0  166388  101041  62112  44319  35255  28750  22145  15900  261681
mean 290.94 own symbols        scan steps/probe 212.12
```

* bucket 0 is empty by construction — the amplifier is armed only where the map
  is non-empty, which is round 901's EMPTY/REAL split doing its job
* **35.5% of probes (261,681) land on a level holding ≥ 9 symbols**, and those
  levels average **815** symbols each
* they alone account for **213.2 M of the 214.6 M symbols** a scan would traverse
  per rebuild — 156.5 M actual comparisons after early exit on a hit

The two means differ by **193×**. That is round 890's law one family over — *a
key population is not a map population; census the live size in the same run* —
restated for a container: **the cost of a scan is weighted by the probes, and the
cost of an allocation is weighted by the scopes.** Round 901 measured the second
and priced the first.

## 2. The four arms

`--lexLevelAmp r` now runs four arms under one timestamp pair each, cyclically
rotated so no arm owns a position. Two values of `r` cancel the ~90 ns boundary
algebraically within each arm; at equal `r` it cancels **between** them, which is
the only way a FIRST-probe rate — the one production performs — is readable.

Compiler profile, `r = 4` and `r = 16`, two runs of each, ABBA at the run level.

| `r` | MAP | FILTER | SCAN | HYBRID |
|---:|---:|---:|---:|---:|
| 4 | 96 / 91 ns | 50 / 49 | 3330 / 3168 | 114 / 109 |
| 16 | 166 / 165 ns | 63 / 59 | 12023 / 11495 | 202 / 199 |
| **warm slope** | **6.00 ns/rep** | **0.96** | **709.2** | **7.42** |
| **p(1) = boundary + first** | **75.5 ns** | **46.6** | **1121.5** | **89.2** |

**The arithmetic falsifier holds exactly** (round 759). The sink is 1,050,548 at
`r = 4` and 4,202,192 at `r = 16` — an exact 4× — so no loop was elided; and
where round 901's filter arm could only assert a SUPERSET inequality, the scan
and hybrid arms assert **equality** with the map, which both satisfy at both `r`.
The hybrid's own branch split is 475,910 scanned / 261,681 fell back, matching
the histogram to the unit.

**Cross-round control.** The two arms round 901 measured reproduce: MAP warm
slope **6.00** against its 6.4, FILTER **0.96** against its 1.17, and the
map-minus-filter first-probe delta **28.9 ns** against its 33-37 and the JFR
row's implied 36.6. This batch ran ~9% faster overall (MAP `p(4)` 93.5 against
103), which is the whole disagreement; the decision below does not depend on it,
because the deciding delta is negative in **4 of 4 runs at both values of `r`**.

## 3. The verdict

Subtracting the arms at `r = 1`, where the boundary cancels:

| | first probe, against the map |
|---|---:|
| the 64-bit filter (round 901's refused lever) | −28.9 ns *(a saving)* |
| **the unconditional array scan** | **+1,046 ns** *(a cost)* |
| **the hybrid: array while ≤ 8, map above** | **+13.75 ns** *(a cost)* |

Over 737,591 probes the hybrid is **+10.1 ms = +0.19% SLOWER**. Round 901's
"~3-6 ns instead of 33-37" is refuted in sign, not merely in magnitude.

**Why the hybrid loses even though its scanned levels average 2.86 entries.**
Two compounding reasons, and neither is a tuning parameter:

1. the 35.5% of probes that fall back pay the array load, the null test and the
   length test **and then the whole map probe** — the branch cannot be cheaper
   than the thing it defers to;
2. a 2.86-element scan is 2.86 `String` dereferences to objects scattered across
   the heap, against one cached hash, one table index and typically one `Node` —
   the map was never doing much more work than the scan, only different work.

**And no threshold rescues it, which is the stronger half of the refusal.** The
ceiling, if the replacement were entirely FREE:

| population made free | ms | % of a 5,429 ms rebuild |
|---|---:|---:|
| buckets 1..8 — 475,910 probes at 29-37 ns | 13.8-17.6 | **0.25-0.32%** |
| ALL 737,591 real probes (impossible: the big levels need a map) | 21.4-27.3 | 0.39-0.50% |

So the entire small-level population, replaced by something that costs *nothing*,
straddles round 897's 0.31% refusal floor. There is no threshold, and no cleverer
small-map layout, that turns this into a lever — the arithmetic is closed before
the implementation is chosen.

## 4. What survives

* **`LexicalScope.symbols` is a well-chosen container.** 35.5% of its probes land
  on levels averaging 815 entries; a hash map is what those want, and the small
  levels are not where the probes are.
* **The row is still real** — round 901's 29.8 ms at 36.6 ns/probe, confirmed by
  two independent instruments — but both of its levers are now measured and both
  are refused: the filter at +0.26% (below floor, and it forecloses the
  container) and the container at −0.19% (a regression).
* **`lexLevelHasName` is CLOSED as a container question.** What is left is a
  *count* question — 737,591 real probes over an ascent that revisits the same
  outer levels — and that is a different lever with a different risk profile
  (INV.4(c)(ii)'s three load-bearing rules sit directly above it). It is not
  opened here.

## 5. Pins and ablation

Six arms, one mistake at a time, on a committed tree. Every arm shares the
property that makes the ablation necessary: **none of these mistakes changes an
answer**, so no output assertion anywhere can see one.

| arm | the mistake | pins red | uniquely its own |
|---|---|---:|---|
| B1 | the scan arm never runs | 2 | set unique; separated from B3 by the liveness pin |
| B2 | the hybrid arm never runs | 2 | set unique |
| B3 | the scan stops one element short | 1 | **no — a strict subset of B1** |
| B4 | the array built from `symbols` **plus** `existing` (round 748) | 3 | **yes** — *the scanned array holds the level's own keys* |
| B5 | the histogram de-duplicated per scope — *round 901's population, injected deliberately* | 2 | **yes** — *every real probe is bucketed exactly once* |
| B6 | the probe size recorded as 1 instead of the level's length | 2 | **yes** — *the summed sizes agree with the histogram* |

**All six discriminate. Five have a failure set contained in no other arm's;
three have a pin that only they break.** B3 is caught but not separated from B1 —
a scan that stops one element short and a scan that never runs both break the
map-equality assertion, and nothing here distinguishes *wrong* from *absent*
beyond the liveness pin B1 additionally trips. Recorded, not dressed up (round
807).

### Two arms were DEAD, not blind — and the driver could not tell

The first pass reported B4 and B5 as BLIND: all pins green against an injected
mistake. Both had in fact changed **nothing**.

* **B5** guarded the histogram on `lexScopes.contains(l)`, which is *always true*
  at that point — `lexLevelHasName` calls `MapCensus.lexScope(l)` two lines
  before it calls `lexAmp`. The de-duplication it meant to inject had already
  been performed on its own predicate.
* **B4** polluted the scanned array with `existing` keys, but the SourceFile root
  is the only level carrying an `existing` table past the untrusted-owner rule
  (§ 1 of round 901), and the fixture's root bound nothing — empty `symbols`,
  never a REAL probe, no array, nothing to corrupt.

> **Round 855's law needs a sharper form: `git diff --shortstat` proves the EDIT
> landed, never that it DOES anything — and in a driver's output a dead arm and a
> blind pin are the same line.** The repairs were a genuine first-sight
> de-duplication and a file-level bare block whose `var` is B83.5-hoisted into
> the root's own `symbols`, which is what makes the root a real probe at all.

And one pin had to be split twice for the same reason in one round: the size
census began as a single method, so B5 and B6 failed the same lone assertion; and
the consistency assertion, first written against the CALL count, fired under both
defects and separated neither.

## 6. Gates

| gate | result |
|---|---|
| `jvmTest`, 4 modules, real XML parser | **14,396 / 0 / 3** (+6 = exactly the new pins; baseline 14,390) |
| `cost_gate.py` | **+0.00% on all 20 counters** — the expected control |
| `huge_methods.py --fail-over 0` | **0 over the limit**, 714 classes |
| 8-profile `--listAll` grid | **all eight `added=0 removed=0`** (46 each, harness 94) |

The grid is a control — the only production edits are census hooks inside
`if (MapCensus.on)` guards plus one always-null field — and it is run anyway
because those hooks sit in `lexLevelHasName`, on the path that decides TS2304.

**No wall A/B, for the eleventh round running**, and there is nothing to A/B: the
round lands an instrument and a refusal. What is claimed is a deterministic
population, reproduced identically across four runs and two builds, and a
sign-consistent delta at two values of `r`.
