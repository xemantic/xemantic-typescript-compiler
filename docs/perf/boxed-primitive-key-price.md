# (WARM.31) — the price of the residual BOXED-PRIMITIVE map/set keys (round 904)

**Verdict: REFUSED. Every one of the 14 sites is 5x or more below the arc's
~17 ms floor — the largest is 3.42 ms (0.064%) — and the WHOLE family together
is 17.7 ms (0.334%), reachable only by a 14-site sweep, two of whose members
are structurally un-swappable.**

Binary: `b76cc98e`. Profile: `build/bench/tsc-project-*` (78 files, 46 errors).
Warm, `BenchMain <proj> 6 3 <tiers>`; census medians 5,275 / 5,363 ms, so the
round's stated denominator is **5,320 ms**. Captures under `build/bench/round904/`.

## 0. The candidate, and why it was only ever a LOCATION

`docs/perf/warm-leaf-profile.md` § 33.8 candidate **(6)**, in its own words:

> **The residual boxed-Int map keys — a LOCATION, not yet a candidate.**
> `Integer.equals` is **29.4 ms** of key-side leaf work, i.e. cost that exists
> *only* because a primitive key is boxed … There is no single owner to name —
> the instrument is a grep of `Map<Int` / `HashMap<Int` plus a per-site
> population count, and each site is then its own small same-answers decision.

That is exactly what this round did, and the reason it is a counter rather than
a fix is round 898's admission test, transposed one family over:

> What a container swap returns is `population x per-operation premium`. The
> premium is ONE number shared by every site in the family, so the only unknown
> per site is its population — and a population is a counter, not a build.

## 1. The bar, set before any measurement

An `IntKeyMap`/`LongKeyMap` probe is ~2 ns (round 903 measured 2.11 ns). Round
899's own reference band put a boxed `Integer`-keyed probe at 15-30 ns, so the
most generous credible premium was ~10-28 ns. At 10 ns and a ~17 ms floor, **a
site needs ~1.7 M operations per rebuild to be worth a LOW-risk swap on its
own** — against a whole-spine node population of **856,962** (`cost-counters.txt`).

So the census could refuse the candidate outright, and only a site above ~1.7 M
would have needed the amplifier at all.

## 2. The census (`--boxedKeyCensus` / tier `boxedkey`)

Operations per warm rebuild. **Both processes agree to the last digit** — the
census is deterministic, which is its own falsifier.

| # | site | key | ops | maxLive | key range | small-key |
|---|---|---|---:|---:|---|---:|
| 6 | `importedSymbolGeneralCache` **[CONTROL]** | `Int` | **519,478** | — | 1,356,535..1,372,113 | 0 |
| 1 | `Relation.cache` | `Long` | **456,660** | — | full 64-bit | 0 |
| 0 | `relationComparisonStack` | `Long` | **444,446** | **27** | full 64-bit | 0 |
| 10 | `enumValues` / `canonicalEnumSymCache` | `Int` | **427,024** | — | −445,840..1,371,400 | 0 |
| 9 | the 10 spine `nodeId` memos | `Int` | **319,558** | — | 0..275,470 | 10,341 |
| 7 | `imported{Namespace,Guard,Enum}Cache` | `Int` | 165,498 | — | 1,356,537..1,372,110 | 0 |
| 5 | `resolvedPropertyTypes` | `Long` | 98,330 | — | full 64-bit | 0 |
| 2 | `relation{Source,Target}Targets` scan | `Int` | 85,865 | **18** | 761,692..798,925 | 0 |
| 11 | `symbolType`/`memberResolutionInProgress` **[CONTROL]** | `Int` | 74,500 | **5** | 761,692..1,507,076 | 0 |
| 13 | `Binder.scopes` / `lexicalScopes` | `Int` | 68,959 | — | 0..275,467 | 536 |
| 8 | `unresolvedLexScopes[nodeId]` | `Int` | 27,550 | — | 0..275,467 | 261 |
| 4 | `typeParamInternCache` | `Long` | 7,628 | — | full 64-bit | 0 |
| 12 | `FlowGraphBuilder.reassignScanCache` | `Int` | 3,234 | — | 2,527..3,145,369 | 0 |
| 3 | `elaboration`/`functionElaborationStack` | `Long` | **15** | 1 | one key | 0 |
| | **FAMILY TOTAL** | | **2,698,745** | | | 11,138 |

**Not one site reaches 1.7 M; the whole family does not reach 2.8 M.** The
largest is 30% of the single-site threshold. The candidate is refused here, and
everything below only sharpens it.

**Both controls hold exactly**, which is what makes the zeros above trustworthy
(round 902: a wrong number from a mis-wired hook reads exactly like a real
negative).

* Site 6 reads **519,478 = 2 x 259,739**, and `risgCalls` is **259,739** —
  round 900's independently measured population, reproduced to the digit. Two
  map operations per call is the double probe round 899 named.
* Site 11 reads 74,500, of which `symAdds` (**24,232**, round 896's number,
  printed live in the same run) accounts for 2 x 24,232 = 48,464; the remaining
  26,036 is `memberResolutionInProgress`' own add/remove pair.

**The small-key column is nearly empty, and that is a finding, not a formality.**
`Integer.valueOf`/`Long.valueOf` cache −128..127, so a key in that range boxes
nothing new AND short-circuits `HashMap.getNode`'s identity test (`e.key == key`)
before `equals` is ever reached. **11,138 of 2,698,745 operations = 0.41%** are
in the cache. Every id-keyed site in this compiler runs in the millions
(symbol/type ids) and every `nodeId`-keyed one runs to ~275 k, so the family
really is fully boxed — the one deflation that could have refused sites for
free does not apply.

## 3. The premium (`--boxedKeyAmp N` / tier `bkamp<N>`)

Two arms (round 898: fewer arms and a bigger `r`) under one timestamp pair each,
sited on `Relation.cache` — the largest non-already-refused member — with a
`LongKeyMap` populated in LOCKSTEP at the same call site off the same key
stream. Two `r`, ABBA inside each process, rotation mirrored across two
processes (round 891). Eight draws per arm per `r`.

    p(r) = cost + boundary / r

| arm | p(8) | p(24) | **cost** | implied boundary |
|---|---:|---:|---:|---:|
| **A** `cache[k]` — boxed `HashMap<Long, Ternary>` | 19.53 | 12.20 | **8.53 ns** | 88.0 ns |
| **B** `shadow.get(k)` — `LongKeyMap` | 11.47 | 5.13 | **1.96 ns** | 76.1 ns |

    PREMIUM  A - B = 6.58 ns per probe     [4.88 ampB .. 8.27 ampA]

Three falsifiers, all passing.

1. **Lockstep**: the two arms' sinks are EQUAL in all eight draws, so the
   shadow really mirrors the cache.
2. **Sink exactness**: every sink is an exact multiple of `r`.
3. **Hoisting**: `p(r)` is not flat for either arm (A 19.5 -> 12.2, B 11.5 ->
   5.1), so neither loop was elided — the slope is the falsifier, never the
   sink (round 903).

**The model closes on itself, which is the strongest single check here.** The
naive `A - B` at one `r` reads 8.07 (r=8) and 7.07 (r=24) rather than 6.58,
because the two arms' boundaries are not identical (88.0 vs 76.1 ns): the
residual is `(88.0 - 76.1)/r`, i.e. **+1.49 at r=8 and +0.50 at r=24**, and
6.58 + those is 8.07 and 7.08 against 8.07 and 7.07 measured. So a single-`r`
`A - B` over-reads the premium by up to 23%, and the two-`r` fit is what removes
it.

**Cross-round confirmation worth keeping**: arm B's **1.96 ns** is the same
`LongKeyMap` probe round 903 measured at **2.11 ns**, in a different round, at a
different site, on a different key stream. That constant is now confirmed twice
and can be reused.

## 4. The decision

At 6.58 ns per operation, against 5,320 ms:

| site | ms | % |
|---|---:|---:|
| `importedSymbolGeneralCache` (already refused, round 900) | 3.42 | 0.064% |
| `Relation.cache` | 3.00 | 0.056% |
| `relationComparisonStack` | 2.92 | 0.055% |
| `enumValues` / `canonicalEnumSymCache` | 2.81 | 0.053% |
| the 10 spine `nodeId` memos | 2.10 | 0.039% |
| `imported{Namespace,Guard,Enum}Cache` | 1.09 | 0.020% |
| the remaining eight sites, together | 2.40 | 0.045% |
| **FAMILY TOTAL, all 14** | **17.74** | **0.334%** |

* **Every individual site is REFUSED**, the largest by **5.0x**. The largest one
  that was not already refused is `Relation.cache` at 3.00 ms = 0.056%.
* **The family total is 17.7 ms = 0.334%**, which grazes the floor — but that is
  a **14-site sweep**, not the LOW-risk single change the floor is calibrated
  for, and at the pooled lower bound (4.88 ns) it is **13.2 ms = 0.248%**, below
  the floor outright.

And the 17.7 ms is not even attainable, for two reasons found while censusing:

* **Site 13 is structurally refused.** `BinderResult.lexicalScopes` is ITERATED
  (`for ((_, scope) in result.lexicalScopes)`), and `IntKeyMap`/`LongKeyMap`
  deliberately have no iterator — that is the same reason CLAUDE.md exempts
  `Binder.nodeToSymbol`. −0.45 ms.
* **Sites 0, 2 and 11 are not `LongKeyMap` shapes at all.** They are transient
  add/remove stacks with max live **27, 18 and 5**, so by round 890's law their
  tables never reach `MIN_TREEIFY_CAPACITY` and their natural successor is a
  linear-scan primitive array, whose cost is NOT arm B and is unmeasured. Their
  3.97 ms is priced by the wrong instrument in either direction.

## 5. The correction to the JFR row, which is the tenth in a row

Round 899 quoted `Integer.equals` at **29.4 ms**. Re-aggregating round 899's own
two dumps — same binary, same round, two processes — with an `Integer`/`Long`
leaf filter charged to the nearest non-stdlib owner reads:

    deep1.txt   113 boxed-primitive leaf samples = 1.390%  =  72.9 ms/rebuild
    deep2.txt    28 boxed-primitive leaf samples = 0.362%  =  19.0 ms/rebuild

**A 4x disagreement between two processes on one binary** — round 868's law
("LEAF attribution is NOT stable across processes: C2 inlined it in the second")
biting a whole family rather than one row. The measured answer is **17.7 ms for
the entire family**, and `Integer.equals` is only a *part* of the 6.58 ns
premium, which also contains the boxing, the pointer chase and `Long.hashCode`.
So the quoted row is over by ~1.7x and deep1's reading by ~4x.

Round 899's dumps also show the family is a long thin tail — **25+ owners, the
largest 0.44% of the profile**, and that largest one is `resolveImportedSymbolGeneral`,
already refused by round 900. There was never a single owner to name.

### 5a. A reference band in § 33.8 needs correcting

§ 33.8 states "an `Integer`-keyed probe is ~15-30 ns" and uses it to refuse
candidate (1). **Measured here, a boxed `HashMap<Long,·>` probe is 8.53 ns** —
the band is over by 1.8-3.5x. The refusal of candidate (1) is unaffected and in
fact strengthened (84.3 ns implied against 8.53 measured is a 10x refutation,
not 3-6x), but the band itself should not be inherited again.

## 6. The one direction this round did NOT measure

An amplifier prices the OPERATION and cannot see a working-set effect (round
897: interning names was ~half `String.equals` and ~half the key objects' cache
locality, and the second half is charged to whatever frame dereferences the
object). Removing ~2.7 M boxed `Long`/`Integer` allocations per rebuild might
therefore return more than 6.58 ns each.

Two measured results say it will not, and they are why this is stated rather
than pursued: **round 801** removed 367,189 `String` allocations and measured
**exactly 0 ms**, and **round 893** found warm GC is ~92-98 ms of a ~5.4 s
rebuild (~1.7%) and that the binary with 2.2 M FEWER copied map entries took
MORE pauses. At 2.7 M boxes against a 1.7% GC budget there is no room for the
missing multiple.

## 7. For the next agent

* **Do not re-open candidate (6) from a leaf profile.** It is measured: 14 sites,
  2,698,745 operations, 6.58 ns each, 17.7 ms for all of them together.
* The instrument survives: `--boxedKeyCensus`, `--boxedKeyAmp N`, the `boxedkey`
  and `bkamp<N>` tiers, and `scripts/round904-census.sh`. A NEW boxed-key site
  can be priced by adding one `MapCensus.bk` hook and reading its ops against the
  ~1.7 M threshold — no build of a fix, and no amplifier, is needed to refuse one.
* **Reusable constants, now confirmed twice**: a `LongKeyMap` probe is **~2 ns**
  (1.96 here, 2.11 round 903); a boxed `HashMap<Long,·>` probe is **8.53 ns**;
  the premium between them is **6.58 ns**; a `PassTiming.nowNanos()` pair is
  **76-88 ns**, consistent with the arc's ~90 ns figure.
* **The method that decided it is arithmetic, not a build**: population x premium
  against the floor, with the threshold population computed FIRST. The census
  cost one build and two runs; the fix would have cost fourteen swaps.
