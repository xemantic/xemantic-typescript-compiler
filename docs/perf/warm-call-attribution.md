# (WARM.5) — the WARM intra-function attribution of `checkSingleCallExpressionTypes`

*Round 851, 2026-08-08. Sixteenth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → … → `warm-spine-attribution.md` (847) →
`lib-type-rederivation.md` (849) → `warm-intra-handler.md` (850) → here. This
closes the last unprobed region of the warm top four — and, with it, the warm
arc.*

> **HEADLINE — FIVE THINGS.**
>
> **(1) THE ANSWER IS THE SAME ONE, FROM A FOURTH INDEPENDENT PROBE: 94%
> CHECKING WORK, 6% EVERYTHING ELSE.** `checkSingleCallExpressionTypes` is
> **~618 ms = 8.4% of a warm rebuild**; its type-system rows are **603 ms** and
> the entire dedicated-walker layer inside it — the seven-walker round-793
> prologue, the TS2722 optional-member walker, the TS2347 walker — is **37.7 ms
> = 0.51% of the artifact**. Round 850 measured 94%/6% for
> `spineCtaM3StatementAnchor` and 98%/2% for `checkPropertyAccessInExpr`; round
> 733 measured 88.4% cold. Four sites, two regimes, one answer.
>
> **(2) THE EXIT CENSUS'S SHARPEST COLUMN IS A ZERO, AND IT IS THE RIGHT KIND OF
> ZERO: 0 of 52,413 invocations emit a diagnostic on this profile.** The entire
> call-checking path spends 8.4% of a warm rebuild proving there is nothing to
> say — which is exactly what a clean program should cost, and which is why
> round 792's law is the whole reading: a profile zero bounds a hazard's
> frequency, never its existence, and the 13,900-baseline corpus is what makes
> this path load-bearing.
>
> **(3) A STANDING CLAIM IS REFUTED BY 12×.** Round 734 read "50.6% of
> `getCalleeType`'s invocations bail" beside "`getCalleeType` costs 474 ms" and
> inferred half of it was wasted. Warm: **48.4% of the calls bail and they are
> 4.1% of the row** — 10.8 ms of 262.4 — because a bailing resolution costs
> **425 ns against 9,004 ns** for a usable one, **21× less**. CLAUDE.md's
> population-vs-frequency law, with a fresh warm instance.
>
> **(4) THE CROSS-PROBE PARTITION CHECK IS THE STRONGEST IN THE ARC.** Round
> 850's `arg` probe measured `checkArgumentsAgainstSignature` at 309 ms = 4.37%
> of its own warm rebuild; this probe, in a different round with different code
> and a different denominator, reaches it as a NESTED row at 296 ms = **4.02%**.
> Two instruments sharing no code, **92%** agreement.
>
> **(5) NO LEVER CLEARS THE ±1.0% WARM BAND — the SIXTH consecutive priced
> negative.** The largest non-checking object in the whole function is the
> seven-walker prologue at **0.23%**.
>
> **Nothing was optimized this round.** The `src/` change is the probe's COARSE
> twin and its exit census, both behaviour-free and pinned as such.

---

## 1. How this was measured

One binary, one profile (`build/bench/tsc-project-637d5746` — the compiler
profile: 78 files, 46 errors), `--noEmit`, this box (8 cores, 15.6 GB, zero
swap). `cost_gate.py` (exit 0, **every one of 18 counters +0.00%**) and
`huge_methods.py --fail-over 0` (exit 0, **census still 0 over the limit**;
largest method 7,702 bytecodes) ran before the daemon stop. Gradle and Kotlin
daemons were stopped between the last build and the first sample (round 800's
trap), `free -m` showed 10.2 GB free, and the box was not touched while the
runs went (round 774).

| probe | tier list | processes | ON draws | COARSE draws |
|---|---|---:|---:|---:|
| `CallSections` (CALL.1) | `call,callcoarse,call,callcoarse` | 2 | 4 | 4 |

All **8** instrumented rebuilds answered **78 files / 46 errors**, and every
deterministic counter is **bit-identical across all four draws of each tier** —
that is the falsification, and it is what licenses a 4-draw mean for the nanos.

**Denominator.** Probe-free warm medians 7,402.4 / 7,335.1 ms, **mean 7,368.8
ms**. Per CLAUDE.md's cross-round rule this absolute is a THIS-ROUND figure
(round 850 read 7,076 ms, round 847 8,095 ms on the same code); only the
within-round shares travel.

```bash
bash scripts/round851-finish.sh          # builds, gates, stops daemons, measures
python3 scripts/round851_analyze.py      # reduces the two logs to §§ 2-5
```

---

## 2. § The WARM boundary, and the range this round cannot close

`CallSections.COARSE` keeps four anchors (`ENTRY` / `B216` / `CALLEE_TYPE` /
`CALL_SIGS`) and makes every other boundary a static read plus a not-taken
branch; the nested sub-measures are ON-only. That is round 734's differential,
inside one warm process.

| estimator | ns/boundary |
|---|---:|
| naive — partition-row delta ÷ partition-row extra closes | 63 |
| **ALL-boundary — partition-row delta ÷ every extra timestamp pair** | **17** |
| the probe's own in-situ steady state (8 back-to-back empty spans) | 82 |
| `BenchMain` `overheadMs`, whole-run (noisy) | 31 |

Unlike round 850's, this partition is **FLAT** — one level — so no nesting
correction applies and the two differential estimators differ only in whether
the ON-only nested pairs are counted. They should be, and 17 ns is the
estimator that counts each boundary once. It is also consistent with the in-situ
82 ns under round 734's measured 3.5–4.4× in-situ over-read (82/4.4 = 19 ns).

**What this round cannot close, stated rather than smoothed:** Δ = 14 ms against
a per-arm draw spread of ±5%, so the boundary is bounded to **17–82 ns** and no
better. That is why the function's total is quoted from the **COARSE** arm,
which crosses **184,251** boundaries against ON's **976,944** and is therefore
5.3× less sensitive to the choice:

| arm | raw ms | boundaries | net at 17 ns | net at 82 ns |
|---|---:|---:|---:|---:|
| ON | 641 | 976,944 | 624 | 561 |
| **COARSE** | **627** | **184,251** | **624** | **612** |

**So: `checkSingleCallExpressionTypes` = 612–624 ms, ~618 ms, 8.3–8.5% of a warm
rebuild.** Everything below uses 8.4%. Note the two arms agree at **624 ms
exactly** under the estimator used, which is the calibration's own consistency
check and not an input to it.

---

## 3. § The partition, and its three checks

| row | raw ms | **% warm** | closes | ns/call |
|---|---:|---:|---:|---:|
| **single signature branch** | **299.5** | **4.06%** | 23,212 | 12,905 |
| — of which `checkArgumentsAgainstSignature` | 274.5 | 3.72% | 23,212 | 11,827 |
| — of which the five single-sig dedicated walkers | 16.4 | 0.22% | 23,212 | 705 |
| **`getCalleeType`** | **262.4** | **3.55%** | 52,413 | 5,007 |
| — of which → a usable type | 243.5 | 3.30% | 27,043 | 9,004 |
| — of which → `any`/`error` (result DISCARDED) | 10.8 | 0.15% | 25,370 | **425** |
| overload branch | 21.3 | 0.29% | 3,693 | 5,779 |
| — of which `checkArgumentsAgainstOverloads` | 19.1 | 0.26% | 1,082 | 17,652 |
| B216 dependent indexed-access | 13.5 | 0.17% | 52,413 | 258 |
| TS2347 + null/undefined callee + `any` bail | 10.9 | 0.14% | 52,413 | 208 |
| TS2722 optional member | 9.6 | 0.12% | 52,413 | 184 |
| explicit type arguments branch | 9.3 | 0.12% | 27,012 | 344 |
| union callee (TS2349 a/b/c) | 4.1 | 0.05% | 27,043 | 153 |
| `getCallSignaturesOfType` | 3.7 | 0.04% | 27,012 | 135 |
| no-call-signatures branch | 2.9 | 0.03% | 27,012 | 109 |
| the other six prologue walkers, combined | 3.7 | 0.05% | 1,019 each | — |
| **the whole function (COARSE arm, net)** | **~618** | **8.4%** | 52,413 | 11,791 |

**Check 1 — against round 847's per-handler probe.** `ccetSpineLeave` measured
876 ms / 8,095 = **10.82%** of that round's warm artifact; this function is
**8.4%**, i.e. **78%** of the handler. This is a CONTAINMENT check (the handler
also carries its own dispatch and the rest of its payload), so ≤100% is
expected; round 850 got 87% (`cta`) and 68% (`cpa`) on the same comparison, and
the same caveat applies — round 847's probe costs +4,482 to +5,670 ms against
this one's **+32 to +258 ms**, so where they disagree the cheaper instrument is
the better estimate of an artifact share.

**Check 2 — against round 850's independent `arg` probe, and it is the
strongest number in the arc.** Round 850: `checkArgumentsAgainstSignature` =
**309 ms = 4.37%**. Here, reached as three nested rows (274.5 single + 2.6
typeArgs + 19.1 overloads) = **296 ms = 4.02%**. Different round, different
denominator, no shared code: **92%**.

**Check 3 — internal.** `getCalleeType` closes 52,413 = invocations exactly;
52,413 − 25,370 (`any`/error bail) = **27,043** = the reach of the next row.
The exit census sums to 52,413 EXACT (§ 4).

**Unresolved at this n:** every row whose ns/call is under the boundary is
listed as such by the reducer; at 17 ns nothing in the table is, at 82 ns the
three sub-1-ms prologue walkers are. Rows under ~1 ms have draw spreads up to
60% and are not quotable individually — they are quoted only in the 3.7 ms
combined line.

---

## 4. § THE EXIT CENSUS — what the call path pays for, and what it buys

Recorded at `end()`, a boundary the partition already crossed, from the row
already open: **zero new boundaries** (round 796), so the ON arm's boundary
count is unchanged and a before/after row comparison stays valid (round 793).

| the row an invocation RETURNED from | left | **emitted** | had paid: prologue | had paid: `getCalleeType` | of it `any`/err |
|---|---:|---:|---:|---:|---:|
| TS2347 + null/undefined callee + `any` bail | **25,370** (48.4%) | **0** | 2.5 ms | 10.8 ms | **25,370** |
| single signature branch | 23,212 (44.3%) | **0** | 3.2 ms | 218.0 ms | 0 |
| overload branch | 3,693 (7.0%) | **0** | 0.8 ms | 24.5 ms | 0 |
| explicit type arguments branch | 107 | 0 | 0.0 | 0.7 ms | 0 |
| union callee | 31 | 0 | 0.0 | 0.2 ms | 0 |
| **TOTAL** | **52,413** | **0** | **6.5 ms** | **254.3 ms** | 25,370 |

**Partition check: 52,413 of 52,413 = EXACT**, by construction — every
invocation that `begin`s closes exactly one row at `end`.

Three readings:

* **The emit column is 0 everywhere.** On tsc's own sources — a program that
  type-checks clean, whose 46 residual errors are offline-environment artifacts
  from other passes — the entire call-checking path emits nothing. All 8.4% is
  verification. **This is not a deletion argument and must not be read as one**
  (round 792 measured a whole-function pre-gate on `checkMemberAccessMissing`
  reading 0 emitting calls in a 22,187-call skip set on *this same profile* and
  killing 7 corpus baselines). What it does say is that no reordering that
  merely defers emission-side work can win here — there is no emission-side work
  to defer.
* **48.4% of invocations leave at the `any`/error bail** having paid only
  10.8 ms between them — 425 ns each. The half of `getCalleeType` that round 734
  called wasted is 0.15% of the artifact, and skipping it entirely would return
  that, minus round 788's law (a skipped resolution that is memoized MOVES).
* **The census AGREES with `returnedIn`'s differencing on every row here**
  (52,413 − 27,043 = 25,370 ✓, and so on down), so the differencing this
  function already had was sound. Its value is the three columns differencing
  cannot produce: what the exiting invocation had already PAID, its outcome, and
  whether it bought anything.

---

## 5. § THE DELIVERABLE — checking work vs everything else

Classified by CALLEE, as in round 850 § 4.

| class | rows | raw ms | share |
|---|---|---:|---:|
| **CHECKING** (the type system) | single-sig 299.5 + `getCalleeType` 262.4 + overloads 21.3 + typeArgs 9.3 + union callee 4.1 + `getCallSignaturesOfType` 3.7 + no-sigs 2.9 | **603.2** | **94%** |
| **DEDICATED-WALKER LAYER** | the 7-walker prologue 17.2 + TS2722 optional member 9.6 + TS2347/EARLY_GATES 10.9 | **37.7 = 0.51% of the artifact** | **6%** |

**94% / 6%** — `spineCtaM3StatementAnchor` read 94%/6% and
`checkPropertyAccessInExpr` 98%/2% (round 850); `ccetSpineLeave` and
`cpaSpineLeave` read 88.4% cold (round 733). Four sites now, two regimes, one
answer.

### Every candidate in this function, priced

| candidate | prize | why it is not landed |
|---|---:|---|
| the whole round-793 seven-walker prologue | 17.2 ms = **0.23%** | below band by 4×; and round 793 already priced its pre-gate |
| the five single-sig dedicated walkers | 16.4 ms = 0.22% | below band |
| `getCalleeType`'s discarded half | 10.8 ms = 0.15% | below band; round 788 says a memoized skip MOVES |
| the TS2347 walker + `any` bail row | 10.9 ms = 0.14% | below band; the bail is load-bearing |
| the TS2722 optional-member walker | 9.6 ms = 0.12% | below band |
| `checkArgumentsAgainstSignature` | 274.5 ms = 3.72% | round 850 opened it: 91% of its argument typing serves arguments that never reach the relation, and rounds 759 + 788 say the realisable part is smaller than the row |
| `getCalleeType` → a usable type | 243.5 ms = 3.30% | this IS the type system resolving a callee |

**Nothing clears ±1.0%.** The largest non-checking object is 0.23%.

---

## 6. § THE WARM ARC'S CLOSING STATEMENT

Six candidate levers have now been priced, each with the prize measured BEFORE
any design:

| round | candidate | measured |
|---|---|---:|
| 848 → (WARM.2) | sharing bound lib state across daemon requests | **0.13%** |
| 849 → (WARM.3) | per-request lib-TYPE re-derivation | **0.01–0.08%** (whole mint boundary 0.5–0.9%) |
| 850 | `spineCtaM3StatementAnchor` scaffolding | **0.62%** |
| 850 | `checkPropertyAccessInExpr` pass-through walk | **0.10%** |
| 850 | the nine pre-emission probes in `checkSinglePropertyAccess` | **0.99%** (band edge) |
| **851** | **the dedicated-walker layer in the call path** | **0.51%** |

**What the warm floor is.** A warm rebuild is 88.9% checker-init, of which
`checkSpine` is 66.0% of wall (round 846). Four handlers are 33.4% of the
artifact (round 847). Those four are now attributed intra-handler and disjoint —
`spineCtaM3StatementAnchor` 9.17%, `checkPropertyAccessInExpr` 5.21% (round
850), `checkSingleCallExpressionTypes` 8.4% (this round) = **22.8% of the
artifact directly attributed**, replacing round 850's 18.75% figure now that the
call path is measured whole rather than through its argument check alone.

**What it is made of.** In every one of those regions, 94–98% is the passes' own
checking work: resolving a callee, computing an argument's type, asking the
relation, deriving a member table. The traversal, the frame bookkeeping, the
eligibility gates and the dispatch `when`s — the whole (SPINE.1) thesis — are at
or *under* one probe boundary: cta's gate 88 ns over 915,543 consultations,
cpa's walk 93 ns over 801,892 arm closes, and here 258 ns over 52,413 for the
prologue's first walker. **Summed across all four handlers, the non-checking
layer is ~2% of the artifact.**

**What would have to change for it to move — an architectural change, not a
probe.** Three of the four directions are already closed by measurement:

* *Do the work once* — caching. Closed four times: rounds 659, 665, 716 (three
  hypotheses in one round, each after real implementation) and 849 (the entire
  type-mint boundary is 0.5–0.9%, so no cache placed there can clear the band
  whatever it is keyed on). The mechanism is stable: **in xtsc the cacheable
  population is the cheap tail.**
* *Do less checking per node* — the § 0.1 endgame of replacing ~1,046 dedicated
  walkers with engine rules. Closed at round 830: the layer is 3.13%, ~22 ms is
  deletable, and at every site the largest group is a rule tsc has too, so it
  MOVES. This round's 0.51% is the same finding at a fourth site.
* *Arrange the existing computation better* — this arc. Six priced negatives.

What is left is genuinely architectural, and only two of them have a measured
multiplier above 1.3×:

1. **Compute it on more threads.** `--workers 4` = **1.361×**, 6/6
   sign-consistent (round 826), the largest single measured multiplier this
   codebase has. Its cost is CPU and RSS, not wall (cores peak at 6.63 of 8).
2. **Do not compute it cold.** (JIT.1) is **1.505× warm** on the huge-method arc
   alone (round 845); the JDK 25 AOT cache read 1.638× against its own round's
   plain arm (round 828); GraalVM is at **1.02× tsc** in CI over 75 rows (round
   841).
3. **Change what is computed.** The only remaining single-thread direction, and
   it is an algorithm change, not a restructuring: `getTypeOfExpression`'s ×2.76
   recompute factor (round 737 — whole prize 823 ms = 2.9%, largest actionable
   merge 0.58%, because the factor is 2.05× CROSS-HANDLER and no handler
   re-types alone), or adopting tsc's field-on-node `NodeLinks.resolvedType`
   model, which is exactly the design round 716 measured as un-portable here.

**THE VERDICT: the warm arc CLOSES.** Not because there is nothing left to
measure, but because the thing it was formed to find — a warm restructuring
lever — has been shown, at four independent sites and by six priced candidates,
not to exist at this granularity. The instrument is complete and committed:
every one of the top four warm handlers now has a warm section probe with a
COARSE calibration twin and an exit census, so a future round can re-price any
of them in one script run. The next warm number will come from parallelism or
from the AOT/warm artifact path, not from a partition.

---

## 7. § What this does NOT show

* **One profile, one box, `--noEmit`, no A/B.** No arm was built; nothing was
  optimized. The 8.4% is a share of THIS round's rebuild.
* **The boundary is bounded to 17–82 ns, not measured to one value** (§ 2), and
  the quoted total is the COARSE arm's precisely because it is insensitive to
  that range.
* **The emit census's zero is a property of THIS profile** (round 792). It
  proves nothing about the corpus, which is what keeps this path load-bearing.
* **The classification in § 5 is by CALLEE**, so a "checking" row includes
  whatever scaffolding lives *inside* the pass it calls — round 733's precedent,
  and neither round can see below its own partition.
* **`ccetSpineLeave`'s remaining 22%** (876 ms − this function's 618, cross-round
  and therefore only indicative) is the handler's own dispatch plus its other
  payload, and is not partitioned.
