# (WARM.24) The price of interning identifier names — round 897

Round 894's census (`warm-hash-owner-census.md` § 9) ranked **scanner identifier
interning** as candidate (1), the top of its list, at an upper bound of
**67.7 ms/rebuild (1.24%)**, called it *"a handful of lines in one function"*,
rated its risk **LOW**, and said in the same paragraph that the fix's own cost is
**unpriced**.

This round priced it. **The answer is REFUSE**, on two independent grounds, and
the round's most useful output is a mechanism the census could not see.

| | |
| --- | --- |
| **measured prize**, warm, on the population that could be priced | **17.2 ms/rebuild (0.31%)** |
| census ceiling for the same candidate | 67.7 ms (1.24%) — **3.9x the answer** |
| **measured cost**, folded into the probe the Scanner already pays | **11.1 ms per program parse** |
| measured cost, as a separate intern table (the census's own reading) | **27.6 ms per program parse** |
| structural blocker | `scanIdentifier` runs on the crawl's **N concurrent workers** |

---

## 1. The instrument

`NameCensus` (commonMain) plus a `namecensus<N>` `BenchMain` tier, run WARM in
four independent JVMs (`scripts/round897-census.sh`, two batches). It arms
counters and captures for ONE rebuild after the six-iteration warm-up, then runs
five timed arms over the captured populations **after** the compile, so nothing
it times lands inside the compile it censuses.

The tier also **disables `CrawlParseCache` for its rebuild**, and that is not
incidental — see § 2.

Every arm alternates order per rep (ABBA), and the falsifier is arithmetic and
never timing (round 759): each arm's hit count must be an exact multiple of the
reps, and the raw and interned arms must agree on all of them. They do, in all
four processes, to the last digit.

**One disclosed imprecision.** The crawl parses concurrently on
`Dispatchers.Default`, so the SCANNER counters are a data race: `idTokens` spans
513,335–537,295 across four processes on one binary (±2.3%). The **checker**
counters are exact — `lookupPerFileForNode = 737,958` and
`globalsForFile = 325,191` in all four — which is the control that confines the
race to where it is claimed to be. It is also, as § 5 explains, the round's
second refusal ground arriving as a measurement artifact.

---

## 2. The regime fact, first, because it decides how the cost is read

`CrawlParseCache` (round 871) serves the program's parse from the previous
request when the bytes match, and `RealLibSnapshots` has done the same for lib
files since M2.1(c). **So in the warm regime this whole arc measures, the Scanner
does not run at all.**

Two consequences:

1. **An intern probe costs ZERO per warm rebuild** — it is paid once per file
   VERSION, not once per rebuild. Warm, interning is pure profit.
2. **The census's `String.hashCode` 24.8 ms/rebuild is not what its § 9(1) says
   it is.** That paragraph reads it as *"one hash per fresh instance"* of a
   scanner-minted identifier — but with a cached parse those instances persist
   across rebuilds and `String.hashCode` caches per instance, so an identifier's
   hash is computed **once per process**, not once per rebuild. Whatever those
   24.8 ms hash, it is strings built fresh by the checker, which interning at
   the Scanner cannot touch. **The candidate's ceiling is therefore 42.9 ms, not
   67.7** — before any of the deflation below.

The measurement had to disable the parse cache precisely because of (1): with it
on, the token population the cost arm needs does not exist to be captured.

---

## 3. The populations

**Tokens (raced, ±2.3%; four processes):**

| | p1 | p2 | p3 | p4 |
| --- | ---: | ---: | ---: | ---: |
| identifier-shaped tokens | 527,246 | 528,487 | 513,335 | 537,295 |
| of which reserved words | 124,307 | 128,366 | 125,505 | 126,982 |
| distinct NAMES | 22,152 | 22,259 | 22,995 | 22,090 |
| intern hit rate | 94.50% | 94.43% | 94.07% | 94.61% |
| mean token length | 10.42 | 10.38 | 9.98 | 10.35 |

~527 k `text.substring` calls collapse onto **~22.4 k distinct names**, a
**94.4%** hit rate. That is the number that had to be high for the idea to be
affordable at all, and it is.

**Probes (exact, identical in all four processes):**

| | |
| --- | ---: |
| `lookupPerFileForNode` entries | 737,958 |
| `globalsForFile` entries | 325,191 |
| **`moduleOnlyGlobalNames` probes** | **1,063,149** |
| of which HIT | **623,146 (58.6%)** |
| onward `globals[name]` reads | 440,003 |
| `moduleOnlyGlobalNames` members | 4,088 |
| `globals` entries | 185 |

The hit RATE is the quantity that matters and it is not the one anybody would
have guessed. `HashMap` calls `String.equals` only for an entry whose 32-bit
hash matches, so **a miss essentially never walks characters and only a hit can
pay the cost interning removes**. 58.6% of these probes hit.

---

## 4. The prize, and a second mechanism the census cannot see

Per rebuild, over the captured probe sequence:

| arm | p1 | p2 | p3 | p4 | mean |
| --- | ---: | ---: | ---: | ---: | ---: |
| set, production instances | 20.39 | 23.02 | 25.65 | 24.64 | 23.43 ms |
| set, canonical instances | 8.23 | 9.75 | 8.11 | 8.22 | 8.58 ms |
| **set delta** | 12.15 | 13.26 | 17.53 | 16.42 | **14.84 ms** |
| map delta (scaled to the 440,003 real reads) | 2.07 | 2.03 | 2.77 | 2.64 | **2.38 ms** |
| **prize** | 14.22 | 15.29 | 20.30 | 19.07 | **17.2 ms** |

Against a warm rebuild median of 5,554 ms that is **0.31%**.

### The decomposition, which is the finding

The MAP arm is the control that makes the set arm readable. `globals` holds 185
entries and the replayed probes hit it **10,383 times per rep** — so an
equals-driven delta there could be at most `10,383 x 14.6 ns = 0.15 ms`. The
measured delta is **4.9–6.7 ms**, i.e. **97% of it is not `String.equals` at
all.**

What it is: the **working set of key objects collapses**. The raw arm
dereferences ~400 k distinct `String` objects; the interned arm dereferences
~22.4 k. That is 5.41 ns per probe of pure cache behaviour, and it applies to
every probe, hit or miss.

Subtracting it from the set arm leaves the equals mechanism proper:

* **`String.equals` on equal-but-distinct instances: 8.55 ns/probe → 9.1 ms/rebuild,
  over 623,146 hits = 14.6 ns per comparison** (mean name length 10.4 chars).
  This is § 5a of the census's mechanism, measured directly for the first time.
* **Key-object locality: 5.41 ns/probe → 8.1 ms/rebuild** over the 1,503,152 real
  container operations.

So interning's benefit is **roughly half `equals` and half locality** — and the
locality half is **invisible to a leaf-frame profile**, because it is not time
spent in `String.equals` or `String.hashCode`, it is time spent in whatever frame
dereferences the object. Any future census that ranks a candidate off leaf
frames alone will under-read a working-set collapse the same way.

### What that implies about the whole program

At 14.6 ns per comparison, the census's **42.9 ms** of in-map `String.equals` is
**~2.94 M equal-but-distinct comparisons per rebuild**. The population priced
here — 623,146 — is **21.2%** of them. A whole-program intern would plausibly
recover 40–80 ms once the locality term is included.

**That is an extrapolation, and this arc does not act on extrapolations** (a
count is not a measure; a ceiling is not an answer). It is recorded so that a
future round with a way past § 5's blocker knows the prize is bigger than what
was priced, not smaller.

---

## 5. The cost, and the blocker

### The cost

| ns per identifier token | p1 | p2 | p3 | p4 | mean | x ~527 k tokens |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| separate intern table | 63.16 | 35.42 | 59.73 | 51.45 | 52.4 | **27.6 ms / parse** |
| **folded** into the existing `KEYWORDS` probe | 20.30 | 12.85 | 26.61 | 24.95 | 21.2 | **11.1 ms / parse** |

The fold is a design the census did not consider and it halves the cost:
`scanIdentifier` **already** probes a `String`-keyed map for every
identifier-shaped token (`KEYWORDS[word]`, Scanner.kt:772), so one table holding
the ~160 reserved words *and* every interned name answers both questions in one
lookup. Interning's marginal price is then the difference between probing a
22.4 k-entry map and a 160-entry one, plus the 22.4 k inserts — not a whole
second probe.

It is still 11.1 ms. It cannot go much lower: an intern probe must `equals` its
candidate against the stored canonical, and 434 k hits per parse at ~14.6 ns is
6.3 ms of that 11.1 on its own.

**Against a measured 17.2 ms saving, 11.1 ms is not "clearly below".** Warm the
comparison does not arise (the Scanner does not run); cold — the CLI, the shipped
GraalVM image that CI benches per push, and any edited file in a daemon — the net
is **+6 ms on a multi-second compile**, and the census's own preferred design
(a separate table, 27.6 ms) is a cold **LOSS**.

### The blocker, which is the stronger ground

**The prize requires a PROGRAM-WIDE table.** It comes from a probe and a stored
key being the same object; `moduleOnlyGlobalNames`' 4,088 members are minted in
whichever file declared them and probed from all 78. A per-FILE intern table
makes two files' `kind` different instances again and captures essentially none
of the 17.2 ms.

**And `scanIdentifier` runs on the crawl's N concurrent workers.**
`ProjectCompiler.build` calls `parseForCrawl` inside
`withContext(Dispatchers.Default)`; only the *fold* after the flow is drained is
single-threaded, which is exactly why `CrawlParseCache.store` lives there and its
`lookup` is documented read-only. A program-wide `HashMap.getOrPut` from
`scanIdentifier` is therefore **round 825's hazard verbatim** — "a plain
`HashMap.getOrPut` from N worker threads is a data race with no exception to find
it by", the defect that WAS the `--workers` race.

**This round's own instrument is the proof**: its Scanner counters disagree by up
to 4.7% across four processes on one binary, while its checker counters are
identical to the last digit in all four. The census hooks are a race for exactly
the reason a production intern table would be.

The only thread-safe designs are (a) an `expect`/`actual` concurrent map — new
platform code on the hottest loop in the front end, at a per-probe price above
the 21.2 ns measured here — or (b) canonicalising after the parse, which means
rewriting `Identifier.text` and so breaking INV.2(a)'s "the AST is never written
after `indexSourceFile`", the property `CrawlParseCache` and
`RealLibSnapshots` both rest on.

So the census's **"a handful of lines in one function… Risk: LOW"** is falsified.

---

## 6. Two corrections to round 894's list, derived from the same run

### (2b) `moduleOnlyGlobalNames` → a bitset pre-filter: ceiling ~10x the answer

The census bounds it at **≤42.9 ms** and says a hash-bitset answers "definitely
absent" in one load. It does — but this round's census says **58.6% of the
probes HIT**, and a "definitely absent" filter is worthless for a hit. Its
reachable population is the **440,003 misses**, and the floor it competes against
is the interned arm's 7.6–9.8 ns/probe over the whole population. The prize is
**~2–9 ms/rebuild (0.04–0.16%)**.

### (7) `AliasedCondKey` → a packed primitive key: CLOSED, not deferred

Round 896 refused it *"because its prerequisite is unbuilt"* — interned name ids.
That prerequisite is now priced at 11.1–27.6 ms per parse and structurally
blocked by § 5. Since (7)'s own prize is **6.5 ms**, it can never pay for the
prerequisite it needs. It is closed.

---

## 7. Reproducing this

```
./gradlew :xemantic-typescript-compiler-core:compileTestKotlinJvm
./gradlew --stop && pkill -f 'KotlinCompile[D]aemon'
scripts/round897-census.sh all      # four JVMs, ~12 min
```

Each process prints the `== (WARM.24) name-intern census ==` block. The
`namecensus<N>` tier takes `N` as a rep count; `namecensus6,namecensus12` in one
process would share a compiled `probeSet` between arms (round 867), which is why
each `N` gets its own JVM.

Pins: `NameCensusTest` (8). Ablation: `scripts/round897-ablate.sh`, six single
mistakes one at a time.

---

## 8. The ablation, and what it took three passes to say

| arm | the mistake | pins red |
| --- | --- | --- |
| A1 | the interned arm probes the RAW container | 1 — *the raw and interned arms probe two distinct containers* |
| A2 | `canon` enters probes before members | **0 — a REDUNDANT guard** |
| A3 | the fold arm starts from an EMPTY table | 1 — *the fold arm seeds the reserved words…* |
| A4 | `idToken` counts reserved words as names | 1 — *idToken splits reserved words…* |
| A5 | `publish` is last-wins | 1 — *publish never replaces a population…* |
| A6 | `publish` accepts an EMPTY member set | 1 — *publish refuses an empty population…* |

Five of six discriminate, each through exactly one pin. **A2 is a redundant
guard and is recorded as one rather than claimed as coverage** (round 809):
canonicalisation is applied to BOTH sides, so whichever occurrence wins the
canonical slot, the two sides still agree on it — the insertion order reads as
load-bearing and is not.

The first pass read **4 of 6 arms green**, and the three mechanisms behind that
are worth more than the table:

* **A1 was a pin that did not exist.** Swapping the container does not change
  the ANSWER — both hold the same values, so both arms still report the same
  hit count, which is exactly the same-answers property every other pin
  asserts. The fault is to the MEASUREMENT and only the container's IDENTITY
  can see it.
* **A1's first repair was blind in a way the arms' own ABBA rotation caused.**
  Recording each arm's container and comparing the two at the end is defeated by
  the rotation: a fault present only in the even branch is overwritten by the
  odd one, and the end state reads healthy. The observable had to become
  **sticky** — a claim about the whole replay rather than about its last
  iteration. *A rotation that protects a measurement can hide a fault in it.*
* **A5/A6 were blind because the fixture MASKED them.** Both publish pins seeded
  the snapshots in between, and `seed` installs the snapshot directly — so a
  last-wins or a premature capture was overwritten before it could be observed.
  Split into two pins over populations that DISAGREE, through a `seedProbes`
  seam that installs only the probe sequence.
