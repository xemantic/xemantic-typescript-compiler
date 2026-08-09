# (WARM.19) — what a REAL `--serve` daemon request pays, and what of it is avoidable

*Round 871, 2026-08-09. Nineteenth in the sequence `dispatch-table.md` (732) →
… → `warm-jvm-attribution.md` (843–846) → `warm-spine-attribution.md` (847) →
`lib-type-rederivation.md` (849) → `warm-tail-attribution.md` (859–861) →
`warm-flow-graph-attribution.md` (864) → `warm-leaf-profile.md` (868/870) →
here. **The first measurement in this arc taken through the artifact that
actually ships.***

---

> ## HEADLINE — THREE ANSWERS, ONE OF WHICH IS A ~0 AND ONE OF WHICH LANDED
>
> **(1) A daemon request pays essentially NOTHING over an in-process rebuild —
> 2 ms, 0.03%.** Every warm number rounds 843–870 produced came from `BenchMain`,
> an in-process repeated rebuild; the shipping artifact is the `--serve` daemon.
> Bracketing a request three ways — client wall ⊃ server `elapsedMs` ⊃ the
> compiler's own `time:` — puts **everything `runCli` does around the build**
> (argument parse, the round-848 mode ledger, diagnostic formatting, stdout
> capture, JSON encode) at **1–3 ms warm**. The in-process figure and the daemon
> figure are the same number, and now for a measured reason rather than round
> 843's coincidence of totals.
>
> **(2) The 279 ms a CLIENT sees on top of that is a fresh client JVM, not the
> daemon.** Isolated with a request the server refuses in constant time
> (`--watch`): **279 ms median**, of which the bash launcher is **9 ms**. That is
> **3.9% of the 7.15 s a user waits**, it is paid outside the server, and the
> thin native client the `XtscMain` design already names is what removes it.
>
> **(3) What a request RE-DOES across requests was 78 file reads + 78 PARSES,
> identically for an identical request and a one-file-changed one — and the
> parse half is now shared.** `CrawlParseCache` (this round) reuses a crawl parse
> across `ProjectCompiler.build` calls on a **content + `ParserFlags`** key.
> Priced first, two ways that agree: an amplifier says **133 ms per parse round**
> and the controlled row says **138 → 14 ms of crawl WALL**. **≈1.9% of a warm
> request**, and in the editor workload the daemon now parses **one file per
> edit** instead of 78.
>
> What did NOT change and is not a candidate: the **bind** (5.5% — deliberately
> per-consumer, symbol merging mutates its inputs) and the **check** (92% —
> closed by round 772: tsc's sources are `export *` barrels, so a leaf edit's
> reverse-dependency closure is 77 of 78 files).

---

## 1. How this was measured

One binary throughout, one box (8 cores, 15.6 GB, **zero swap**), `--noEmit`,
the compiler profile (`build/bench/tsc-project-637d5746`: 78 files, 46 errors,
9,977,097 chars). Gradle and Kotlin daemons stopped **before** the first sample
and **after** every gradle-invoking step (round 851). `XTSC_AOT=off` throughout:
this round measures the daemon, not the cache, and any build invalidates an AOT
cache anyway (round 842).

Harnesses, all committed before the source change they measure (round 789):

| harness | what it does |
|---|---|
| `scripts/round871-serve-ladder.sh` | N requests to ONE `--serve` daemon; three nested brackets per request; `--front` adds `--frontEnd`; `--edit F` models an editor; `--ampseq` / `--cacheseq` drive the two arms |
| `scripts/round871-client-overhead.sh` | the client side alone, via a request the server refuses in 0 ms |
| `scripts/round871-grid.sh` | the 8 dashboard profiles, each compiled twice through one daemon, diffed |

**Nothing here is a new probe.** The three brackets are all printed by the
shipping binary already; the per-phase table is `--frontEnd` (FRONT.1/FRONT.2),
run *from inside a request*.

Every request in this document answered **46 errors** and sorted-capture digest
**`84bbe7f0`** (the `grid838.sh` recipe: `grep 'error TS'`, project prefix
`sed`-stripped, `sort`; round 841's fourth lineage). 33 requests over three
daemon processes for the ladders, plus 16 for the grid.

---

## 2. § The three brackets — where a request's time is

Warm requests, one daemon, `--frontEnd` armed (medians of requests 5–8):

| bracket | ms | what is between it and the next one in |
|---|---:|---|
| client wall | 7,153 | the client JVM, the Unix socket, the JSON |
| server `elapsedMs` | 6,866 | `runCli`: argument parse, mode ledger, formatting, stdout capture, response encode |
| compiler `time:` | 6,865 | — this is `ProjectCompiler.build`, the same call `BenchMain` brackets |

`server − compiler` is **1–3 ms on every warm request** across all three ladders
(and 18–43 ms on the COLD first request, which is class loading inside the
formatter). `client − server` is **279–311 ms**.

### 2.1 The client side, isolated rather than differenced

`--watch` is refused by `CompileServer.respondTo` with `elapsedMs = 0` before any
compile is attempted, so a refused request's client wall **is** the client-side
cost, with the compile subtracted by construction:

| arm | draws | median |
|---|---:|---:|
| refused request (client JVM + socket + JSON) | 8 | **279 ms** |
| launcher only (`XTSC_AOT_DECIDE_ONLY=1`; bash + the AOT decision, no JVM) | 8 | **9 ms** |

So **~270 ms is a fresh client JVM**. It reproduces the `client − server` gap in
the compile ladders to within ~15 ms, and it is round 843's "168–222 ms" figure
re-measured post-module-split.

**This is not a defect and it is not the daemon's.** It is the cost the native
thin client exists to remove — `XtscMain`'s own KDoc says so, and CI has built
that image on every push since round 841.

---

## 3. § The per-request census — what a request re-does

`--frontEnd`, from inside the request. **Eight warm draws over two independent
daemon processes**, pre-change binary:

| phase | ms (range over 8 draws) | median | % of request |
|---|---|---:|---:|
| config load + `@types` + root glob | 2–4 | 3 | 0.04% |
| **import-graph crawl (WALL)** | **131–209** | **153** | **2.2%** |
|  · of which read+decode (CPU sum, 78 calls) | 74–352 | 112 | — |
|  · of which pre-parse (CPU sum, 78 calls) | 653–1,261 | 805 | — |
| bind (all program files, 123 calls incl. libs) | 345–443 | 385 | 5.5% |
| checker construct + `getDiagnostics` | 6,304–6,803 | 6,450 | 91.3–92.8% |
| post-checker (transform/emit/tails) | 3 | 3 | 0.04% |

and, on **every single request**:

```
files read: 78 (9977097 chars)   core parse loop: 78 reused / 0 fresh
```

Read that line carefully, because it is the finding: **`78 reused` is INV.1(e)
working WITHIN the request** — the compilation core reusing the crawl's parse —
and **`files read: 78` is the crawl doing all 78 parses again.** The pre-parse
reuse mechanism is a `HashMap` local to `ProjectCompiler.build`; it does not, and
before this round could not, survive to the next request.

**The lib parses DO survive**, and always have: `RealLibSnapshots.parseCache` is
process-global (M2.1(c)). The `bind` row's 123 calls against 78 program files is
those lib files being re-BOUND every request — deliberately, because
`mergeSymbolTable` mutates the symbols it merges.

### 3.1 The editor workload — and why it was identical

The same ladder with one file rewritten before every request (`--edit
src/compiler/core.ts`, 77 of 78 files byte-identical to the previous request)
produced, on the pre-change binary, **exactly the same counters**: 78 files read,
78 parsed, 78 reused, the same crawl / bind / check rows. Nothing was reused
across requests, so nothing could distinguish the two workloads.

That is the shape of the prize: **the daemon workload is 77/78 unchanged and the
compiler was treating it as 0/78.**

---

## 4. § Pricing the parse — why the census cannot do it, and what can

The crawl's 153 ms is an **envelope**, not a price. Two reasons:

- the crawl is a **concurrent pipeline** (`flatMapMerge`, reads on the IO
  dispatcher, parses on `Dispatchers.Default`), so its read+parse CPU sums to
  **6–9×** its wall and no share of the CPU sum transfers to the wall;
- it carries a **fixed floor** — BFS frontier waves, module resolution,
  coroutine dispatch — that eliminating the parse cannot touch.

So it was priced by **amplification** (round 759): `--parseAmp N` performs `N`
EXTRA parses of each crawled file, inside the same span and on the same
dispatcher, making the crawl WALL `floor + (1 + r)·C`; two values of `r` cancel
the floor.

Two daemon processes, rotations `0,1,2,3,3,2,1,0` and `3,2,1,0,0,1,2,3`, warm
requests only, crawl WALL in ms:

| r | process 1 | process 2 |
|---:|---|---|
| 0 | 201.1, 157.0 | 173.0, 184.9 |
| 1 | 243.1, 378.3 | 275.9, 301.2 |
| 2 | 453.4, 414.0 | 507.7, 417.3 |
| 3 | 603.8, 527.9 | 623.0, 537.2 |

Least squares:

```
process 1:  crawl(r) = 179.9 + 128.3 r
process 2:  crawl(r) = 171.0 + 137.7 r
```

**One parse round over the program is 133 ms** (the two slopes replicate to 7%),
and the crawl's floor is **33–52 ms**. Against a ~6,900 ms warm request that is
**1.9%** — above the ~1% decision floor, which is what justified building
anything.

**The amplifier's falsifier is arithmetic, not timing, and it passed on every
draw**: `parseAmpSink` must be exactly `r ×` the program's statement count, and
it read `r × 4,530` every time. That is what rules out a JIT that hoisted the
extra parses away.

---

## 5. § What landed — `CrawlParseCache`

`path → PreParsedFile`, served only when the record's **content** and
**`ParserFlags`** also match. That is INV.1(e)'s own gate, hoisted from a
per-build local to a process-global map, and nothing else about the front end
changes: the file is still read, still decoded, still handed to the same core.

**Why the invalidation cannot go stale.** The crawl reads every file on every
request anyway — this removes none of that 74–352 ms of read CPU — so the bytes
are in hand before the question is asked. **Different bytes are a different key.**
There is no mtime, no size and no stat anywhere in it, and therefore no window in
which a stale tree could be served.

**Why sharing a tree is sound.** Three properties, none of them new:

1. the parse is a **pure function of `(source, fileName, flags)`** — the one
   stamp that looks global, `TypeParameter.internSalt`, is `fileName.hashCode()`;
2. the AST is **written only by `indexSourceFile`** — `NodeBase`'s three `var`s
   are stamped at the end of `Parser.parse()` and by nothing else; the
   Transformer synthesises fresh nodes with `copy()` and the Checker keeps its
   state in side tables;
3. **`RealLibSnapshots` has relied on exactly this since M2.1(c)** for the lib
   files, which are the largest files in any program. This is the same split
   applied to the program's own files: **the parse is shared, the bind is not.**

**Memory is bounded at one entry per path** — an edit REPLACES. Measured, not
argued: a 7-edit daemon ladder reports `78 paths held` at every request.

**Threading** follows the discipline the crawl already uses: `lookup` is
read-only and runs on the concurrent workers; `store` runs only in the
single-threaded fold after a frontier's flow has drained (round 825 — a plain
`HashMap` write from N workers is a race with no exception to find it by).

`--parseCacheOff` is the in-binary OFF arm, so the capture is a controlled row
rather than a two-build difference (round 793).

---

## 6. § The capture, measured three ways

**(a) The controlled row, blocked arms in ONE daemon process** (`--cacheseq
off,off,off,off,off,on,on,on,off,off,off,on,on,on`), crawl WALL:

| arm | draws | median |
|---|---|---:|
| `--parseCacheOff` | 113.2, 166.4, 151.0, 120.9, 138.0 | **138 ms** |
| cache on | 16.4, 13.6, 9.9, 11.8, 27.6 | **14 ms** |

**−124 ms.** Arms are BLOCKED rather than interleaved on purpose: a single OFF
request dropped into a run of ON ones reads **896 ms**, because the parser has
not executed for several requests and is no longer hot. That is a real property
of a daemon and a trap for anyone interleaving these two arms request-by-request.

**(b) Against the pre-change binary**, whose steady warm crawl was 131–209 ms
(median 153) over 8 draws in 2 processes, the post-change steady crawl is
10.7–31.2 ms: **−129 ms**.

**(c) The amplifier** (§ 4) says one parse round is **133 ms**, independently and
without either arm existing.

Three instruments, **122–133 ms = 1.8–1.9% of a warm request**.

**No whole-request A/B is quoted.** The wall's per-draw spread is ±5% and this
effect is 1.9%; rounds 858 and 869 § 13 both measured that a two-batch
`ab-warm.sh` cannot separate drift from an effect of this size, and a controlled
row needs no help.

### 6.1 The editor workload, after

Same `--edit` ladder, cache live. Cumulative census per request:

```
req 1   crawl 1164 ms    0 hit /  78 miss,  78 paths held
req 2   crawl  108 ms   77 hit /  79 miss,  78 paths held
req 3   crawl   26 ms  154 hit /  80 miss,  78 paths held
…
req 8   crawl   19 ms  539 hit /  85 miss,  78 paths held
```

**Exactly one miss per request** — the edited file — and **78 paths held**
throughout: the map does not grow with the number of edits.

---

## 7. § Verification

- **Suite 14,165 / 0 failures / 3 skipped** over all four modules (`xml.etree`),
  = 14,150 + the 15 new pins, exactly.
- **`cost_gate.py` +0.00% on all 20 counters**, twice.
- **`huge_methods.py --fail-over 0`**: 0 methods over the limit.
- **The 8 dashboard profiles, each compiled twice through ONE daemon**
  (`scripts/round871-grid.sh`): request 1 is all-MISS (the pre-change
  behaviour), request 2 is all-HIT, and **`added=0 removed=0` on all eight**,
  with crawl WALL falling 1,084 → 96, 763 → 23, 274 → 11, 197 → 28, 113 → 28,
  203 → 47, 278 → 38, 288 → 43 ms.
  The usual two-class-dir grid was deliberately NOT used: a one-shot CLI performs
  exactly one `build`, so it can never register a cache hit, and a green grid of
  two CLI binaries would be evidence of nothing.
- **Cross-PROJECT reuse falls out and was observed**: the eight profiles are
  nested subsets of the tsc tree, and by the last one the daemon holds 1,249
  paths and serves the earlier profiles' files to the later ones (`tsc-cli`'s
  FIRST request already reports 78 hits).
- **33 ladder requests over 3 daemon processes**, every one `46 errors`, digest
  `84bbe7f0`.

### 7.1 Pins and the single-mistake ablation

`CrawlParseCacheTest`, 15 pins, all over strings/ints/booleans and one identity
comparison (never an AST node in an assertion). Seven ablations, **one arm per
invocation**, each reverted before the next, on a committed tree, every arm
dry-run first for a real diff that reverts clean (rounds 807/855/856/789):

| arm | the mistake | red pins |
|---|---|---:|
| A1 | the hit condition drops the CONTENT compare | 4 |
| A2 | …drops the FLAGS compare | 1 |
| A3 | the content compare becomes a LENGTH compare | 2 |
| A4 | the PATH stops being part of the key | 3 |
| A5 | the OFF arm stops being off on the READ side | 1 |
| A6 | …on the WRITE side | 2 |
| A7 | the driver never stores | 4 |

Two things reported rather than claimed:

- **A1 and A3 initially shared a red set**, because the "a byte difference
  misses" pin happened to use a same-LENGTH edit. It now uses a different-length
  one, so A1 fails both it and the same-length pin while A3 fails only the
  same-length one. One mistake, one failing set.
- **A1 reddened three COUNTER pins and NEITHER edit pin, and that is a finding
  about the compiler, not a weak fixture.** The compilation core re-checks
  content at `ParsedSource` (INV.1(e)), so a mis-keyed hit there degrades to a
  redundant parse and a **correct** type-check. The place with no second gate is
  the **CRAWL**, which has already used the stale tree's `moduleSpecifiers` to
  decide which files exist. A pin was cut for exactly that — *an edit that adds
  an import changes which files the crawl reaches* — and A1 now reddens it.
  **That is the wrong-answer path this cache has to be keyed against: not a
  wrong type, a missing FILE.**

---

## 8. § What this does NOT show

- **Nothing here re-opens incremental CHECKING.** Round 772 stands: on tsc's own
  `export *` sources a leaf edit's closure is 77 of 78 files, and 92% of a
  request is the check. This round touched the front end only.
- **The 133 ms is this project's parse cost**, on 9.98 MB of TypeScript at 78
  files. A project with more, smaller files pays more crawl floor and less parse;
  a smaller project gets less of both.
- **Heap.** The cache retains one program's ASTs between requests, which a
  one-shot CLI never did. It is bounded (one entry per path) and it was not
  measured. A daemon serving many large projects holds the union.
- **The `client − server` 279 ms was not optimised**, only attributed and
  located. It is a client-process cost and the native image is the answer.
- **No AOT arm.** `XTSC_AOT=off` throughout, deliberately.

---

## 9. Reproducing

```bash
./gradlew assemble                    # stages …-daemon/build/install/lib (round 857)
./gradlew --stop && pkill -f 'KotlinCompile[D]aemon'

# the three brackets + the per-request census
scripts/round871-serve-ladder.sh /tmp/r871 8 --front --tag front

# the client side alone
scripts/round871-client-overhead.sh /tmp/r871 8

# the price of one parse round (two processes, rotated)
scripts/round871-serve-ladder.sh /tmp/r871 0 --ampseq 0,0,0,0,1,2,3,3,2,1,0 --tag amp1
scripts/round871-serve-ladder.sh /tmp/r871 0 --ampseq 0,0,0,3,2,1,0,0,1,2,3 --tag amp2

# the controlled row (blocked arms, one process)
scripts/round871-serve-ladder.sh /tmp/r871 0 \
    --cacheseq off,off,off,off,off,on,on,on,off,off,off,on,on,on --tag cache2

# the editor workload
scripts/round871-serve-ladder.sh /tmp/r871 8 --front --edit src/compiler/core.ts --tag edit2

# the 8 profiles, all-MISS vs all-HIT, through one daemon
scripts/round871-grid.sh

# the ablation
scripts/round871-ablate.sh --dry && scripts/round871-ablate.sh A1 A2
```
