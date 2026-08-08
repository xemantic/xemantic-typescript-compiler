# (WARM.3) — the price of re-deriving LIB TYPES on every daemon request

*Round 849, 2026-08-08. Fourteenth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → … → `warm-jvm-attribution.md` (843–846) →
`warm-spine-attribution.md` (847) → here. This is a PRIZE MEASUREMENT, taken
before any design, exactly as the queue item demanded.*

> **HEADLINE — (WARM.3) IS CLOSED AS A MEASURED NEGATIVE, AND IT CLOSES MORE
> THAN ITSELF.**
>
> **(1) The prize is 1–6 ms of a 7,139–7,316 ms warm rebuild = 0.01–0.08%.**
> A process-global cache of derived lib types could delete **71 outermost mint
> spans** costing between 1 and 6 ms depending on the draw. Round 844 measured
> the sibling candidate (sharing the bound lib *state*) at 8.65 ms = 0.13%;
> this one is *smaller still*.
>
> **(2) The population is tiny, not cheap.** A lib mint is **20× more expensive
> per span** than a non-lib one (93,464 ns vs 4,606 ns warm) — resolving
> `interface Array<T>` really is heavy work. There are simply **119 of them**
> against 14,826 total mints. This is round 716's law in its purest measured
> form: what looks like a big shared surface is 0.8% of the mints.
>
> **(3) The ratio is 133.9 : 1 — and that does NOT rescue it.** Every derived
> lib type is read back **133.9 times on average** (15,932 consumptions of 119
> productions), so the cache would be superbly *effective*; it would just be
> effective at deleting almost nothing. A produced-vs-consumed ratio proves a
> cache would be READ, never that it would be WORTH READING.
>
> **(4) The stronger negative, free from the same run: the WHOLE mint boundary —
> lib and user code together — is 38–66 ms warm = 0.5–0.9% of the artifact.**
> So no cache placed at `getDeclaredTypeOfSymbol` / `resolveStructuredTypeMembers`
> can clear the ±1.0% warm A/B band, whatever it is keyed on. That retires the
> whole "share derived types across requests" direction, not just the lib slice.
>
> **Nothing was optimized this round.** This is an instrument and a verdict.

---

## 1. How this was measured

One binary (`9b83b8f1`), one profile (`build/bench/tsc-project-637d5746` — the
compiler profile: **78 files, 46 errors**), `--noEmit` throughout, this box (8
cores, 15.6 GB, zero swap). Gradle and Kotlin daemons were stopped **inside the
measuring script, between the build and the first sample** (round 800's trap).
The `cost_gate.py` and `huge_methods.py` gates ran before the daemon stop and
long before any timing sample — `cost_gate.py` runs a compile of its own and must
never overlap a measurement. The box was not touched while the script ran.

| arm | harness | n |
|---|---|---:|
| warm | `BenchMain <proj> 3 8 libtypes,libtypes`, **2 processes** | 4 draws |
| cold | `MainKt --noEmit --libTypeCensus` | 1 |

All five instrumented rebuilds answered **78 files / 46 errors**, and `BenchMain`
aborts non-zero if an instrumented rebuild disagrees with its own measured loop —
none did. Warm probe-free medians: **7,138.8** and **7,315.7 ms**. Per CLAUDE.md's
cross-round rule those absolutes are THIS-ROUND figures; only the shares travel.

**Every counter below is bit-identical across all four warm draws AND the cold
run.** That is the census's own falsification: a deterministic counter that moved
between regimes would mean the probe, not the compiler, was deciding what got
minted.

---

## 2. § The instrument, and the one thing it gets right by construction

Two hooks, both placed where the checker's own code **already asks "have I got
this yet?"** — which is what makes the round-801 produced-vs-consumed ratio come
free rather than as a second instrument:

| hook | consumed side | produced side |
|---|---|---|
| `getDeclaredTypeOfSymbol` | `declaredTypes[symbol.id]` HIT | the miss → `…Worker` |
| `resolveStructuredTypeMembers` | `properties != null` early return | the fall-through mint |

Only the **outermost** mint is timed, through a depth counter **SHARED by both
hooks** — they recurse into each other freely (a member table resolves member
annotations, which resolve declared types, which resolve member tables…), so
per-hook depth counters would double-count the same nanos. A timed span is
therefore INCLUSIVE of everything it caused, which is the right shape for a
prize: it is what a served entry would delete. The evidence that the sharing
works: **119 lib mints produce only 71 lib spans**, i.e. 48 lib mints occur
nested inside another mint and are correctly not timed twice.

The lib/non-lib classification is a `builtinLibDecls` membership test — an AST
node hash, so subject to CLAUDE.md's node-keyed-map rule — memoized per
`Symbol.id` and run **only with the clock stopped**.

### 2.1 The instrument's own defect, which its own pin caught

The consumed-side hook first went into `resolveStructuredTypeMembersCore`. That
function's `properties != null` guard is **duplicated one frame up** in
`resolveStructuredTypeMembers`, so the wrapper absorbs every hit and the hooked
branch is dead. The census reported `memHitLib = 0` — a perfectly legitimate-
looking zero that would have been written up as "nothing ever reads a derived lib
type back, so the cache is pointless", which is the *opposite* of the truth
(133.9 : 1). `LibTypeCensusTest`'s consumed-side pin is what failed, and it failed
because it had been written to fail if the probe were inert.

**The reusable trap: a produced-vs-consumed census keyed on a boundary the
CALLER already short-circuits measures nothing at all, and reports it as a
zero.** This is the same shape as round 786's "a partition of a function whose
eligibility test lives inside it must open on the WRAPPER", now in its
counter-rather-than-timer form.

---

## 3. § The table

Counters identical in every arm; only the nanos columns are per-draw.

| | lib | other | total |
|---|---:|---:|---:|
| `declaredTypes` mints | **63** | 1,804 | 1,867 |
| `declaredTypes` hits | **3,890** | 41,581 | 45,471 |
| member-table mints | **56** | 12,962 | 13,018 |
| member-table hits | **12,042** | 703,801 | 715,843 |
| **mints total** | **119** (0.80%) | 14,766 | 14,885 |
| **hits total** | **15,932** | 745,382 | 761,314 |
| **outermost timed spans** | **71** | 13,917 | 13,988 |

| draw | LIB raw | LIB net | LIB ns/span | OTHER raw | OTHER net | OTHER ns/span |
|---|---:|---:|---:|---:|---:|---:|
| warm p1 d1 | 6 ms | 6 ms | 93,464 | 64 ms | 61 ms | 4,606 |
| warm p1 d2 | 3 ms | 3 ms | 51,645 | 38 ms | 36 ms | 2,738 |
| warm p2 d1 | 1 ms | 1 ms | 27,259 | 66 ms | 63 ms | 4,779 |
| warm p2 d2 | 1 ms | 1 ms | 26,800 | 41 ms | 40 ms | 2,992 |
| **cold** | **7 ms** | 7 ms | 105,885 | **150 ms** | 143 ms | 10,803 |

`net` subtracts the in-situ empty-pair calibration (96–209 ns warm, 501 ns cold),
which per rounds 734/735 OVER-reads by 3.5–4.4×, so the net columns are a
conservative floor. The cold in-situ pair reading **2.4–5.2× the warm one**
independently reproduces round 847's "a probe boundary is ~1.85× more expensive
cold" in the same direction.

**The prize is the LIB row: 1–6 ms warm.** Against a 7,139–7,316 ms rebuild that
is **0.01–0.08%**, i.e. between one and two orders of magnitude below the ±1.0%
warm A/B band. The four draws disagree by 6× among themselves precisely because
the quantity is at the resolution floor of the instrument — which is itself the
finding.

---

## 4. § Why this closes more than the lib slice

The `other` column is not decoration. Non-lib mints are **13,917 spans costing
38–66 ms warm = 0.5–0.9% of the artifact**, and they are the *entire* remaining
population at these two boundaries. So:

* A cache keyed on lib-ness buys ≤0.08%.
* A cache that somehow served **every** declared type and **every** member table
  across requests — an impossibility, since user code changes between requests —
  would buy ≤0.9%, still inside the warm noise band.

Type derivation is simply not where a warm rebuild's time is. Round 847 already
located it: `checkSpine` is **66.0%** of the warm artifact, and its top four
handlers are 33.4%. The mint boundaries measured here are ~0.9%.

---

## 5. § The hazards that were NOT paid for

Because the prize is under 0.1%, none of the following had to be resolved — but
they are recorded so a future round does not re-derive them as if the direction
were open:

* **`getTypeOfSymbol` persists only when the caller's instantiation context is
  empty** (round 778). A shared entry can freeze a member as `T` or as `any`
  depending on first-touch ORDER, and CLAUDE.md records that such a failure is
  **byte-identical in every output** — round 607 measured 51 corpus failures
  whose `--listAll` was unchanged.
* **`Type` ids are thread-local sequences handed off by `runWithDeepStack`**
  (INV.6(6c0)). A type minted in one request and served into another crosses that
  boundary, and the id-keyed relation caches cannot tell it from a request-local
  type — the same mechanism as the round-825 `--workers` race.
* Round 844 already measured **175 merge collisions per compile, 20 of them
  mutating a lib symbol**, for the *state*-sharing sibling.

The correct trade is therefore not close: three known-silent correctness hazards
for ≤0.08%.

---

## 6. § What this does NOT show

* **One profile** (the 78-file compiler profile), `--noEmit`, one box.
* The LIB figure is a **LOWER bound**: signature derivations and type-node
  resolutions reached only from OUTSIDE these two mint boundaries are not
  counted. The bound that matters is § 4's — the whole boundary is ≤0.9% — and
  that one is not a lower bound on a *lever*, because no cache can serve the
  non-lib part.
* The cold arm is **n=1**, so every cold/warm ratio here rests on a single draw.
* **No A/B, nothing optimized, no lever landed.**

---

## 7. Reproducing

```bash
bash scripts/round849-warm-sections.sh 1     # builds, gates, stops daemons, measures
```

or, for a single reading:

```bash
java -cp <cp> com.xemantic.typescript.compiler.MainKt --noEmit --libTypeCensus <proj>
java -cp <testcp> com.xemantic.typescript.compiler.bench.BenchMainKt <proj> 3 8 libtypes,libtypes
```
