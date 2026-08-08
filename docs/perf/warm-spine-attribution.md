# (WARM.4) — the WARM per-kind / per-handler attribution of `checkSpine`

*Round 847, 2026-08-08. Thirteenth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → … → `warm-jvm-attribution.md` (843–846) →
here. Round 846 made this affordable: the `spine` tier keeps the SPINE sub-rows
while dropping the per-CALL bookkeeping that dominates a warm table, and this
round adds a `dispatch` tier that runs round 732's `SpineDispatch.PROBE` inside
a JIT-warm process.*

> **HEADLINE — FOUR THINGS.**
>
> **(1) The per-KIND shape is regime-INVARIANT.** The five statement-anchor
> kinds are **9.9% of the nodes and 39.9% of the spine warm**, against
> **40.0% cold on the same binary** — CLAUDE.md's cold "10% of the nodes, 40%
> of the spine" reproduces warm to within 0.1 points. Nothing about the kind
> distribution is a cold-JVM artifact.
>
> **(2) The per-HANDLER ORDER is NOT invariant — the top two swap.** Cold, the
> largest handler is `spineCtaM3StatementAnchor` (19.1%); warm it is
> **`ccetSpineLeave` (18.2%)**, because `ccetSpineLeave` warms **2.75×** against
> the spine's 3.38×. `ctaSpineEnter` rises 5.4% → **7.5%** for the same reason
> (2.28×, the worst of the big four). A handler's warm share is its cold share
> times (spine warm-up ÷ its own), and those differ by up to 1.5×.
>
> **(3) CLAUDE.md's standing six-handler list is STALE in order AND magnitude.**
> It records `cpaSpineLeave 4,366 ms` as the largest and "six handlers hold 71%".
> On today's binary `cpaSpineLeave` is **2,060 ms cold (−53%)** and third —
> rounds 788–795 landed levers in it, exactly as the round-830 entry predicts —
> and the top six hold **60.9% cold / 63.0% warm**, not 71%. `ccetSpineEnter`
> has dropped out of the six; `spineArithEnterNode` has entered it.
>
> **(4) The single largest object in the warm artifact is one handler at one
> kind: `ccetSpineLeave` at `CALL_EXPRESSION` — 839 ms net = 10.4% of an
> 8,095 ms warm rebuild**, 92.5% of that handler's whole cost.
>
> **Nothing was optimized this round.** This is an instrument and a table.

---

## 1. How this was measured

One binary (built at the top of the round, `BUILD SUCCESSFUL` in 3m11s), one
profile (`build/bench/tsc-project-637d5746` — the compiler profile: **78 files,
46 errors, 856,962 spine nodes**), `--noEmit` throughout, this box (8 cores,
15.6 GB, zero swap). Gradle and Kotlin daemons were stopped **inside the
measuring script, between the build and the first sample** (round 800's trap).
The box was not touched while the script ran (round 774: watching a benchmark is
part of the benchmark).

| arm | harness | n | tier |
|---|---|---:|---|
| warm | `BenchMain <proj> 3 8 spine,dispatch,spine,dispatch`, **2 processes** | 4 draws/tier | `spine`, `dispatch` |
| cold | `MainKt --noEmit --dispatchProbe` | 2 | `dispatch` |
| cold | `MainKt --noEmit --passTimingSpine` | 1 | `spine` |

Every one of the 11 instrumented rebuilds answered **78 files / 46 errors**, and
`BenchMain` aborts non-zero if an instrumented rebuild disagrees with its own
measured loop — none did. Warm probe-free medians: **8,176.7** (process 1) and
**8,013.7 ms** (process 2), mean **8,095.2 ms**, which is the denominator every
"% of the warm artifact" below is taken against. Per CLAUDE.md's cross-round
rule that absolute is a THIS-ROUND figure; only the within-round shares and
ratios travel.

**What is resolved at this n.** The four warm `spine` draws put `enter` at
3,153 / 2,988 / 3,326 / 2,941 ms — a ±6.2% spread. So: the sub-rows `enter`,
`leave` and `ures`, the top-12 kinds, and every handler down to ~**100 ms** are
resolved; **rows below ~50 ms are not**, and the cold column is n=1 (`spine`) /
n=2 (`dispatch`), so a cold/warm ratio carries that. `scope` (42 ms) and
`forEachChild` (86 ms) are reported because they are the two rows whose
*ratio* is interesting, not because their absolutes are sharp.

**The instrument's own price**, which is why nothing here is a production cost
model: the `spine` tier costs +52 to +1,014 ms warm (`overheadMs`, 4 draws) and
the `dispatch` tier **+4,482 to +5,670 ms** — the per-handler probe more than
doubles a warm rebuild. Its in-situ timestamp-pair calibration reads **38–40 ns
warm and 38–39 ns cold** (856,962 calls each): the timestamp pair itself does
NOT warm up, which is what makes the net columns comparable across regimes.

---

## 2. § The SPINE sub-rows, warm vs cold

`spine` tier. Warm n=4 (mean), cold n=1.

| row | **warm ms** | % spine | cold ms | % spine | **cold/warm** |
|---|---:|---:|---:|---:|---:|
| `spineEnterNode` | **3,102** | **58.5%** | 11,221 | 62.5% | **3.62×** |
| `spineLeaveNode` | **1,586** | **29.9%** | 5,199 | 29.0% | **3.28×** |
| unresolved-names | 490 | 9.2% | 1,354 | 7.5% | 2.76× |
| `forEachChild` | 86 | 1.6% | 124 | 0.7% | **1.43×** |
| scope | 42 | 0.8% | 55 | 0.3% | **1.31×** |
| **SUM** | **5,307** | 100% | **17,953** | 100% | **3.38×** |

`enter : leave` = **1.96 warm**, 2.16 cold. Round 846's independent `rows`-tier
reading was 2.01 — reproduced.

**Partition check.** The sub-rows sum to 5,307 ms against the `spine` tier's own
`checkSpine` row of 5,990 ms (mean of 4) = **88.6%**; the 683 ms gap is the
tier's own boundaries, **797 ns/node** over 856,962 nodes, against round 846's
599 ns/node at n=2. Cold: 17,953 against 19,217 = **93.4%**, i.e. 1,475 ns/node
— **a probe boundary is ~1.85× more expensive cold**, which is the mechanism
behind every "the probe compresses the ratio of the row it lands on" correction
in `warm-jvm-attribution.md` § 10.4.

**The two rows that barely warm at all are the two that are almost pure
traversal** — `forEachChild` (1.43×) and scope maintenance (1.31×) — against
3.3–3.6× for the rows that do checking work. They are together **128 ms = 1.6%
of the warm artifact**, so this is a shape observation, not a lever: there is
nothing to win there even if it were free.

---

## 3. § The per-KIND table — and it is regime-invariant

`spine` tier, enter+leave per node kind. Warm n=4 (mean), cold n=1. The `%`
column is of the 5,307 ms sub-row sum; the twelve rows below are **82.5%** of it.

| kind | nodes | **warm ms** | **%** | ns/node | cold ms | cold/warm |
|---|---:|---:|---:|---:|---:|---:|
| `CALL_EXPRESSION` | 52,509 | **1,040** | **23.8%** | 19,816 | 3,310 | 3.18× |
| `BLOCK` | 24,613 | **654** | **14.9%** | 26,551 | 1,744 | **2.67×** |
| `VARIABLE_STATEMENT` | 14,712 | **515** | **11.8%** | **35,022** | 2,072 | 4.02× |
| `IDENTIFIER` | 381,670 | 459 | 10.5% | **1,203** | 1,651 | 3.60× |
| `EXPRESSION_STATEMENT` | 17,392 | 386 | 8.8% | 22,208 | 1,357 | 3.51× |
| `RETURN_STATEMENT` | 15,662 | 366 | 8.4% | 23,353 | 1,253 | 3.43× |
| `BINARY_EXPRESSION` | 38,454 | 260 | 5.9% | 6,768 | 1,080 | **4.15×** |
| `IF_STATEMENT` | 13,679 | 199 | 4.5% | 14,530 | 749 | 3.77× |
| `FUNCTION_DECLARATION` | 8,910 | 176 | 4.0% | 19,781 | 638 | 3.62× |
| `PROPERTY_ACCESS_EXPRESSION` | 67,902 | 149 | 3.4% | 2,191 | 546 | 3.67× |
| `VARIABLE_DECLARATION` | 15,710 | 123 | 2.8% | 7,813 | 482 | 3.93× |
| `CONDITIONAL_EXPRESSION` | 4,506 | 53 | 1.2% | 11,707 | 207 | 3.92× |

**The five statement anchors** (`VARIABLE_STATEMENT`, `EXPRESSION_STATEMENT`,
`RETURN_STATEMENT`, `IF_STATEMENT`, `BLOCK`) are **85,058 nodes = 9.9% of the
program** and **2,120 ms = 39.9% of the warm spine**; cold the same five are
7,175 ms = **40.0%**. CLAUDE.md's cold record ("10% of the nodes, 40% of the
spine") is therefore a property of the compiler, not of the cold JVM — and the
warning attached to it stands unchanged: **that is a LOCATION, not a lever**
(the same inference from the same table produced the dead (DISPATCH.1)).

**Warm-up is NOT ordered by cost — confirmed on a second instrument.** Round
843 § 5(b) found this with the `full` tier; the `spine` tier says it more
sharply, because the probe no longer dominates. The most expensive kind per node
(`VARIABLE_STATEMENT`, 35.0 µs) warms **4.02×**; the second most expensive
(`BLOCK`, 26.6 µs) warms **2.67×, the worst in the table**; the cheapest
(`IDENTIFIER`, 1.2 µs) warms 3.60×; the best is `BINARY_EXPRESSION` at 4.15×.
Any model that predicts a warm cost by scaling a cold one with a single factor
is wrong by up to ±25% per kind.

---

## 4. § The per-HANDLER table — the deliverable

`dispatch` tier (`SpineDispatch.PROBE`). Warm n=4 (mean of 4 draws across 2
processes), cold n=2. `net` = raw minus the in-situ 38 ns timestamp pair ×
consults. Every handler is consulted at all 856,962 nodes.

**Partition check, and it is the strongest available here:** the handler nets
sum to **4,812 ms** against the `spine` tier's independently measured
`enter + leave` of **4,688 ms** = **102.6%**. Two probes that share no code
agree within 2.6%.

| handler | **warm net** | **%** | 2nd-draw | cold net | % | **cold/warm** |
|---|---:|---:|---:|---:|---:|---:|
| leave `ccetSpineLeave` | **876** | **18.2%** | 921 | 2,408 | 15.8% | **2.75×** |
| enter `spineCtaM3StatementAnchor` | **853** | **17.7%** | 883 | 2,911 | 19.1% | 3.41× |
| leave `cpaSpineLeave` | **617** | **12.8%** | 644 | 2,060 | 13.5% | 3.34× |
| enter `ctaSpineEnter` | **359** | **7.5%** | 374 | 818 | 5.4% | **2.28×** |
| enter `spineIanyEnterNode` | 171 | 3.6% | 172 | 624 | 4.1% | 3.65× |
| enter `spineArithEnterNode` | 153 | 3.2% | 161 | 464 | 3.0% | 3.04× |
| enter `spineOsEnterNode` | 150 | 3.1% | 158 | 309 | 2.0% | 2.05× |
| leave `spineArithLeaveNode` | 129 | 2.7% | 136 | 425 | 2.8% | 3.29× |
| enter `ccetSpineEnter` | 122 | 2.5% | 130 | 356 | 2.3% | 2.93× |
| enter `spinePdEnterNode` | 104 | 2.2% | 108 | 235 | 1.5% | 2.27× |
| enter `spineDaEnterNode` | 99 | 2.1% | 104 | 395 | 2.6% | 3.98× |
| enter `spineUbdEnterNode` | 91 | 1.9% | 94 | 349 | 2.3% | 3.85× |
| *(43 further handlers)* | *1,088* | *22.6%* | | *3,885* | *25.5%* | |
| **TOTAL** | **4,812** | 100% | | **15,239** | 100% | **3.17×** |

The `2nd-draw` column is each process's SECOND draw of the tier, given because
round 846 measured the probe's own code being cold on the first instrumented
rebuild. Here the effect is **+3 to +6% and uniform across handlers**, i.e. it
scales every row rather than reordering any — so the 4-draw mean is used and the
column is offered as the check, not as the number.

**Top four = 2,705 ms = 56.2%** of the probed spine and **33.4% of the whole
warm artifact.** Top six = 63.0%.

### 4.1 Where each big handler's cost actually sits (warm, raw nanos, mean of 4)

| handler | total | concentration |
|---|---:|---|
| `ccetSpineLeave` | 909 | **`CALL_EXPRESSION` 841 = 92.5%**; IDENTIFIER 25, VARIABLE_DECLARATION 12, NEW_EXPRESSION 10 |
| `spineCtaM3StatementAnchor` | 887 | `VARIABLE_STATEMENT` 347 (39.2%), `RETURN_STATEMENT` 263 (29.7%), `EXPRESSION_STATEMENT` 214 (24.1%) — **93.0% in three kinds** |
| `cpaSpineLeave` | 651 | spread: EXPRESSION_STATEMENT 130 (20.0%), VARIABLE_STATEMENT 110, BINARY_EXPRESSION 106, RETURN_STATEMENT 103, IDENTIFIER 65, VARIABLE_DECLARATION 45 |
| `ctaSpineEnter` | 393 | **`BLOCK` 246 = 62.7%**; IDENTIFIER 42, IF_STATEMENT 37, CASE_CLAUSE 16 |

(Raw, not net: subtract 38 ns × that kind's node count — 2.0 ms for
`CALL_EXPRESSION`, 14.5 ms for `IDENTIFIER`, under 1 ms for each statement kind.
The concentrations above are unaffected; the IDENTIFIER rows roughly halve.)

**So the single largest object in the warm artifact is `ccetSpineLeave` at
`CALL_EXPRESSION`: 839 ms net = 10.4% of an 8,095 ms rebuild**, and the second
is `spineCtaM3StatementAnchor` over three statement kinds, 821 ms = 10.1%.

**What that does NOT license.** `spine-leave-attribution.md` (round 733) already
opened both `…SpineLeave` handlers cold and found **88.4% of their time is the
cpa and ccet passes' own checking work** — `checkPropertyAccessInExpr` and
`checkSingleCallExpressionTypes` running inside the frame-ambient block — while
the ancestor climbs that looked like the target were 176 ms of 8,195. Nothing in
this round's data contradicts that, and this round did not re-open them. A warm
row of 876 ms is therefore ~776 ms of type-system work and ~100 ms of
scaffolding, and only the latter is deletable by restructuring.

---

## 5. § What the (DISPATCH.1) per-kind table is worth WARM

The probe computes it directly: the time handlers spend on kinds outside their
derived closure.

| | upper bound | per skipped consultation | skipped consultations |
|---|---:|---:|---:|
| **warm** (n=4) | **340–362 ms** (mean 352) | **10–11 ns** | 32,006,965 |
| **cold** (n=2) | 1,000 / 1,019 ms | 31 ns | 32,006,965 |

That is **6.6% of the warm spine** against 5.6% cold, and **4.35% of the warm
artifact**. A skipped consultation warms **2.95×**, essentially the spine's own
3.38× — so **the dispatch table's relative value is regime-invariant, and the
warm artifact does not make it a better idea than round 732 found it.**

Round 732's cold verdict was an 883 ms upper bound realising as ~100–300 ms
(11–34%), because the probe's `when(h)` indirection inflates. Applying its own
discount to 352 ms gives **~40–120 ms = 0.5–1.5% of the warm artifact** —
straddling the ±1.0% warm A/B band. **(DISPATCH.1) stays closed**, now with a
warm measurement rather than an extrapolation.

---

## 6. § What this does NOT show

* **(WARM.3) is NOT sized by this round.** The per-request re-derivation of lib
  TYPES lives *inside* these handlers and no row here separates it. It needs its
  own instrument — the cheapest honest one is a counter+timer around
  `resolveInterfaceMembers` keyed on whether the symbol's declaration file is a
  lib file, taken on two consecutive warm rebuilds so the *second* one shows what
  a daemon request actually re-pays. That is a `commonMain` change and therefore
  a suite+cost-gate cycle; it was not started. Round 844's sibling measured
  0.13%, and round 716's law says the servable population is the cheap tail, so
  the prior is "small" — but the prior is not a measurement.
* **One profile** (the 78-file compiler profile), `--noEmit`, one box.
* **No A/B, nothing optimized, no lever landed.**
* The cold arm is **n=1** for the `spine` tier, so every cold/warm ratio in § 2
  and § 3 rests on a single cold draw; the `dispatch` cold arm is n=2.
* The `dispatch` tier's absolutes are **probe-inflated by >50%** of a warm
  rebuild and are sound for RELATIVE attribution only — never as production
  costs. The § 4 partition check against an independent probe is what makes the
  relative attribution trustworthy.

---

## 7. Reproducing

```bash
bash scripts/round847-warm-spine.sh     # builds, stops daemons, measures
python3 scripts/round847_analyze.py     # reduces the logs to §§ 2-4
```

The harness is `BenchMain`'s 4th argument: `spine,dispatch,spine,dispatch` runs
two draws of each tier inside ONE warm process, which is what makes the
per-handler table and the sub-row partition check comparable without a second
JVM between them.
