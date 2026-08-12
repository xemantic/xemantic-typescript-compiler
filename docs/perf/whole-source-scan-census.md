# (WARM.19) The whole-source `indexOf` family — census, gate, and price

Round 895. The `indexOf` sibling of `docs/perf/whole-program-regex-census.md`,
which censused the *regex* half of the same problem. Round 894 § 10 found this
family by accident while separating key-side leaves out of the HashMap census;
this document measures it, gates it, and prices the gate.

## 0. The headline

**WARM**, both arms alternating inside ONE process (`BenchMain … 6 8
srcscan,srcscanoff,srcscan,srcscanoff`, median rebuild 5,189 ms):

| | pre-895 (`srcscanoff`) | post-895 (`srcscan`) |
| --- | ---: | ---: |
| whole-source scan calls | 3,827 | 3,827 |
| characters handed to `indexOf` | **488,469,784** | **22,894,093** |
| characters walked building filters | 0 | 9,977,097 |
| scan nanos | 85.4 / 86.9 ms | 3.03 / 2.99 ms |
| build nanos | 0 | 19.9 / 17.9 ms |
| **mechanism total** | **86.2 ms** | **21.9 ms** |
| needles actually found | **14** | **14** |

**-64.3 ms per warm rebuild = -1.24%.** Two draws per arm, alternating, agreeing
to 1.7% (OFF) and 9% (ON). The population is IDENTICAL in both arms — same
3,827 calls over the same 488,469,784 characters, same 14 hits — which is the
same-answers control, taken in the same run as the price.

The same census cold, for the record: 436.3 ms -> 90.5 ms, i.e. **-345.8 ms of a
23.2 s compile**. Cold overstates both mechanisms and overstates the scan more
(§ 5).

Both arms are the same binary; the arm is a mode field, so nothing here is
confounded by a stale class dir or a differently-built jar (round 795).

**No wall-time A/B is claimed.** The four instrumented rebuilds read
`overheadMs` of -70, -116, -317, +261 — noise, exactly as a 1.24% effect must
look on a box that settles at ~1% (rounds 840(c)/858/886). What is claimed is
the mechanism's own nanos, measured inside the run, paired, on one binary.

## 1. What the family is

~135 `Checker` functions ask, once per file in the program, whether that file's
whole text contains some literal:

```kotlin
private fun checkShebangError() {
    for (result in binderResults) {
        …
        val source = result.sourceFile.text
        if (!srcHas(source, "Shebang is only allowed on the first line")) continue
        …
    }
}
```

These are corpus-unique pins: the gate names a string that occurs in exactly one
TypeScript conformance fixture. On tsc's own sources **essentially none of them
ever match** — 14 hits in 3,827 calls, 0.37% — so before this round the program
spent 488 M character-positions per compile establishing that.

Rounds 859/862/863 created this shape deliberately, and correctly: they replaced
whole-program *regexes* (which are far worse — a pattern with no four-character
literal prefix gets no Boyer-Moore and is attempted at every position) with "an
EXACT hand-written scan anchored on a literal via `indexOf`". The `indexOf` form
is ~10x cheaper per character. What nobody counted is that there are 149 of them.

### 1a. The static population

`scripts/round895_srcscan_apply.py` classifies every `source.*` scan site in
`Checker.kt`:

| form | sites | rewritten |
| --- | ---: | :-- |
| `contains("literal")` | 67 | yes |
| `indexOf("literal")` | 23 | yes |
| `indexOf("literal", from)` | 28 | yes |
| `indexOf(dyn)` / `indexOf(dyn, from)` | 23 | yes |
| `lastIndexOf("literal", from)` | 11 | yes |
| **`indexOf('c', from)` — a CHAR** | **64** | **no** |
| `lastIndexOf('c', from)` — a CHAR | 5 | no |

**The char searches are the majority of the SITES and almost none of the cost**:
they are bounded to a node position and terminate within a few characters, and a
single character is below the filter's window width anyway. Rewriting them would
have been pure overhead. That split is the reason a static site count is not a
population — round 758's law, one instrument over.

149 sites were rewritten. 3,827 of them execute per compile, not 149 x 78 =
11,622, because most sit behind an earlier `continue` in their walker's per-file
loop.

## 2. The mechanism

`SourceScanFilter` (`SrcScan.kt`) records, in a bitset, a hash of every
4-character window of a file's text. A needle can only occur if **every** one of
its own 4-character windows occurs, so a single clear bit is a proof of absence.

**False negatives are impossible by construction.** If `needle` occurs in `text`
at position `p`, then for every `j` the window `needle[j until j+4]` *is* the
window `text[p+j until p+j+4]`, which the build visited and whose bit it
therefore set. `mayContain` returns `false` only when some window's bit is
CLEAR. Both the hash and the 7-bit character folding can collide; both make
windows look PRESENT that are not, i.e. both produce only false POSITIVES, and a
false positive costs one real scan — **the real `indexOf` remains the oracle**.

The fold is one expression written twice (build loop and `hashOf`), which is the
one way this could break silently, so `SrcScanTest` pins the two equal and
`--verifySrcScan` re-runs the real scan wherever the filter refused.

Table size scales with the file and is clamped to [8 Kbit, 512 Kbit] so it stays
in L2 even for `checker.ts` (3.15 M chars, 31.6% of the profile).

### 2a. Why the cache is length-keyed and identity-probed

A `HashMap<String, SourceScanFilter>` would hash the file text — 10 M characters
once per file, for nothing. `SrcScanCache` is a 1,024-slot open-addressed table
keyed on `text.length` and matched with `===`. **A miss is never wrong, only
slower**: it rebuilds. One instance per `Checker`, so a `--workers` run shares no
mutable state.

## 3. The measured population

`--srcScanCensus` (counters plus one timestamp pair per scan and per build —
affordable uniquely here, because a whole-source scan is tens of microseconds
against a ~90 ns pair):

```
calls          3827        callChars    488,469,784
found            14        tooShort             78
refused        3723        refusedChars 465,575,691
scanned         104        scannedChars  22,894,093
builds           78        buildChars     9,977,097
scanNanos    24.6 ms       buildNanos      65.9 ms
```

**The filter refuses 97.3% of the calls and 95.3% of the characters.** Of the 104
calls it admits, 14 find something — so 90 are false positives, i.e. the filter's
false-positive rate over this needle set is 90 in 3,813 = 2.4%.

`refused + scanned + tooShort == calls` exactly; the partition is pinned.

## 4. The residual, and why it is not worth chasing

Post-895 the mechanism is **73% filter build** (65.9 of 90.5 ms). The build walks
10 M characters at 6.6 ns/char against `indexOf`'s 0.89 ns/char, i.e. **one build
costs about 7.4 whole-program scans and removes 49 of them.**

Two obvious ideas were rejected on arithmetic:

- **A smaller, L1-resident table.** At 16 KB `checker.ts`'s ~400 k distinct
  windows fill 95% of the bits and the refusal rate collapses for the one file
  that is a third of the program. The table must scale with the file.
- **Build lazily / only for files that pay.** Every file is queried ~49 times;
  there is no population to skip.

## 5. Cold would have been wrong, in both directions, by different factors

The round took the cold census first and then the warm one, and the two disagree
enough that quoting the cold ratio would have been a mis-measurement:

| mechanism | cold | warm | warm-up |
| --- | ---: | ---: | ---: |
| `String.indexOf` over 488 M chars | 436.3 ms | 86.2 ms | **5.07x** |
| the filter build over 10 M chars | 65.9 ms | 18.9 ms | **3.49x** |
| **net removed** | **345.8 ms (1.49% of cold)** | **64.3 ms (1.24% of warm)** | |

The build is a hand-written scanner and warms 3.5x — almost exactly CLAUDE.md's
round-859 figure of 3.3x for that class. The JDK intrinsic warms **5.1x**, more
than the ~3.4x a whole rebuild warms, so **a cold census OVERSTATES what gating a
scan is worth** — the opposite of round 859's regex case, where the cold table
UNDERSTATED an ungated `java.util.regex` because it does not warm at all. Cold
and warm are different regimes for text scanning in both directions; take the
warm one.

### 5a. And this is NOT all of round 894's 116 ms

Round 894's JFR census put the whole `String.indexOf` leaf family at
**116.3 ms/rebuild** (115.8 at round 888). This round measures the STRING-needle
part of it — the 149 gateable sites — at **86.2 ms warm**. The remainder is
predominantly the **69 CHAR-search sites** (§ 1a), which are also
`java.lang.String.indexOf` frames in a JFR dump, are bounded to a node position,
and are not gateable by anything: a single character is below the window width.

So: round 894's number was right, and ~26% of it was never addressable. Reading
a leaf-frame family as one prize is the same error one instrument over from
round 758's population-vs-frequency law.

## 6. What no gate here can see, and what does

A false negative from the filter makes a pin walker skip its whole body, which
**deletes a diagnostic** — and nothing counts that:

- `cost_gate.py` reads `+0.00%` on all 18 counters, which is the EXPECTED answer
  (these walkers touch no checker counter and emit nothing on the profiles), not
  a green light — round 853;
- `huge_methods.py` is unmoved;
- the wall is inside the noise.

What can see it: the **8-profile `--listAll` grid** (`scripts/round895-grid.sh`),
the **14 k corpus suite** (every fixture exercises the filtered path — there is
deliberately no minimum-length threshold), and `--verifySrcScan` with its
`--srcScanBogus` positive control.

### 6a. The committed grid harness has been a ONE-profile grid

`bench-compile-tsc.sh` names the compiler profile `tsc-project-<commit8>` (a
historical name) and the other seven `tsc-<name>-<commit8>`. Every grid harness
in `scripts/` globs `build/bench/tsc-project-*`, which matches **the compiler
profile and nothing else** — round 888's output directory holds exactly one
profile's captures. `scripts/round895-grid.sh` enumerates profile directories by
the presence of a `tsconfig.json` and REFUSES to run with fewer than 8.

Round 895's grid, all eight profiles, both arms of one binary:

```
tsc-deprecatedCompat      46 diagnostics  added=0 removed=0
tsc-harness               94 diagnostics  added=0 removed=0
tsc-jsTyping              46 diagnostics  added=0 removed=0
tsc-project (compiler)    46 diagnostics  added=0 removed=0
tsc-server                46 diagnostics  added=0 removed=0
tsc-services              46 diagnostics  added=0 removed=0
tsc-tsc                   46 diagnostics  added=0 removed=0
tsc-typingsInstallerCore  46 diagnostics  added=0 removed=0
```

## 7. Reproducing

```
# the census, both arms of one binary
java -cp <core classes>:<deps> com.xemantic.typescript.compiler.MainKt \
     --noEmit --srcScanCensus                     build/bench/tsc-project-*
java … --noEmit --srcScanCensus --srcScanFilterOff build/bench/tsc-project-*

# warm, in one process, both arms:
BenchMain <proj> 6 8 srcscan,srcscanoff,srcscan,srcscanoff

# the output grid (needs all 8 profiles materialized)
scripts/bench-compile-tsc.sh --project all --no-emit --no-log
scripts/round895-grid.sh

# the rewrite and its inversion check
python3 scripts/round895_srcscan_apply.py --check
python3 scripts/round895_srcscan_verify.py <before.kt> <after.kt>
```
