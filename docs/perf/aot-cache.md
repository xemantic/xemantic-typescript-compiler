# Shipping the JDK 25 AOT cache — the invalidation contract, (JIT.2b), round 832

*Owner-decided 2026-08-05: **build the invalidation, then ship**. Round 828
(`aot-cache-round828.md`) measured the prize and the hazard; this document is the shipped
design, the verification it rests on, and the risks a user still carries.*

**The prize** (round 828, not re-measured here): **1.638×** on the 78-file tsc compiler
profile — 22,223 → 13,565 ms — and 2.36× on a small project, with diagnostics
byte-identical. The mechanism is JEP 515 recorded method profiles, not JEP 483 class
loading, which is why the training workload has to be a *real* project: a cache trained on
one file buys 5.9%, one trained on the compiler profile buys 39.0%.

**The hazard** (round 828 § 7): the JVM performs **no invalidation whatsoever**. With
`Checker.class` removed from the jar and a fresh mtime, the plain run correctly died with
`NoClassDefFoundError` and **the cached run exited 0 printing `OK — 0 errors`**, serving
921 of 926 application classes from the stale cache. `-XX:AOTMode=on` does not change it.
A compiler that silently type-checks against code that no longer exists is worse than a
slow one, and that is the whole reason this round exists.

## 1. The contract — FAIL SAFE, with no way to opt out of it

> **The cache is used only when every byte of its provenance has just been verified.
> Anything else runs without it.**

A false refusal costs seconds. A false acceptance costs a wrong answer. Every ambiguity in
the design is therefore resolved toward refusal, and there is deliberately **no flag,
environment variable or configuration that makes an unverified cache usable** — the
ablation that proves the pins discriminate is an edited copy of the script, not a
supported mode.

Concretely, `scripts/xtsc` hands the JVM a `-XX:AOTCache=` only when all of:

| condition | failure mode it closes |
|---|---|
| every classpath entry's **sha-256** matches the manifest | the round-828 hazard: a rebuilt/patched artifact |
| the classpath entry **order and count** match | a dependency added, removed or reordered |
| the **JDK build** matches (`$JAVA_HOME/release` digest) | a JDK upgrade under the same cache |
| the **OS/arch** match | a cache copied between machines |
| the **launcher version** constant matches | a change to the java command line the cache was trained under |
| the cache file's own **size and sha-256** match | truncation, tampering, a half-written training run |

…and the recomputed manifest text is **byte-identical** to the stored one. The comparison
is a string equality on a plain-text block, so it is auditable by reading it — there is no
per-field logic that could be got subtly wrong, and adding a field to the block
automatically invalidates every existing cache.

**Nothing consults a timestamp.** mtime is not in the fingerprint at all: round 828's
decisive experiment gave the mutated jar a *fresh* mtime, and a jar rewritten with a
preserved one is exactly the case a stat-based check would wave through. The negative
control `a touched but unchanged artifact keeps its cache` pins the other direction.

**Two more properties fall out of the JVM's own behaviour and are relied on explicitly:**

- The launcher passes `-XX:AOTCache=` and **never `-XX:AOTMode=on`**. In the default
  `auto` mode a cache the JVM itself cannot use produces a diagnostic and a normal run.
  Verified against a deliberately truncated cache: three `[error][aot]` lines, then a
  correct compile.
- The cache file's name embeds its fingerprint (`xtsc-<16 hex>.aot`), so a rebuilt artifact
  *misses by construction* rather than by a deletion step. "Delete on upgrade" is therefore
  hygiene — `train` prunes every other cache in the directory — and not something
  correctness depends on.

## 2. What ships

| file | role |
|---|---|
| `scripts/xtsc` | the guarded launcher — resolve classpath, verify, run with or without the cache |
| `scripts/xtsc-aot` | `train` / `status` / `manifest` / `clean` |
| `scripts/xtsc-aot-lib.sh` | the fingerprint and the decision, shared by both |
| `src/jvmTest/kotlin/AotCacheGuardTest.kt` | the seam pins for the decision |

```sh
scripts/xtsc-aot train build/bench/tsc-project-637d5746   # once, at install time
scripts/xtsc --noEmit /path/to/project                    # every run
scripts/xtsc-aot status                                   # why am I not getting the cache?
```

Classpath resolution is `XTSC_CP`, else `$XTSC_HOME/lib/*.jar` (the distribution shape),
else this repo's `build/libs` + `build/bench/cp.txt`. The cache lives in
`${XDG_CACHE_HOME:-~/.cache}/xtsc` (override with `XTSC_AOT_DIR`) — per user, never beside
the artifact, which may be read-only, shared or root-owned.

### The four design decisions, and why

**Where the checksum is recorded.** In a plain-text manifest beside the cache, and *also*
in the cache's file name. Two layers: the name makes a wrong cache unreachable, the
manifest makes a *planted* one refusable. The pin
`a mutated classpath entry is refused` deliberately defeats the first layer so it can test
the second.

**What the check costs — measured, because it must not eat the 8.6 s it protects.**
**~80 ms** end to end on the reference box: ~30 ms for the fingerprint (8 jars, ~10 MB,
one `sha256sum` process for all of them) and ~50 ms for the 49 MiB cache digest. That is
**0.9% of the 8,658 ms the cache saves**. The per-entry form of the same loop cost 80 ms
by itself, purely in `fork` — batching the digests and the `stat`s is what made a full
content check affordable, and is why no stat-based fast path was needed. The JDK identity
comes from the `release` file (a read) rather than `java -version` (a 30 ms JVM start).

**First run, before a cache exists.** It runs at normal speed, silently
(`XTSC_AOT_VERBOSE=1` prints the decision). No hint, no nag, no auto-training.

**Training is explicit, never lazy.** Auto-training on first use would make some unlucky
user's first compile ~28 s slower, and — decisively — the launcher cannot choose a
*representative* project, which is the one thing that determines whether the cache is worth
39% or 5.9%. `train` is what a package's post-install step calls, and it is written to be
safe when killed: it dumps to a temp file, renames, and only then writes the manifest, so
an interrupted run leaves at most an orphan `.tmp` — never a pair the launcher would
accept. It self-verifies before reporting success.

## 3. The stderr warnings — round 828 got the stream wrong, and it mattered

Round 828 recorded four `Failed to link AdapterHandlerEntry` warnings as a *stderr*
cosmetic. They are on **stdout**, interleaved with the diagnostics:

```
[0.018s][warning][aot,codecache,stubs] Saved blob's name 'LLLLLLIILLLL' is different …
[0.018s][warning][aot                ] Failed to link AdapterHandlerEntry (fp=…) …
```

so anything parsing `xtsc` output sees them. The launcher passes
`-Xlog:aot*=off:stdout -Xlog:aot*=error:stderr`: JVM AOT logging leaves the diagnostics
stream entirely, and genuine AOT **errors** move to stderr where they still print. Verified
both ways — a valid cache now yields a stdout with zero `[` lines, and a deliberately
truncated cache still prints all three `[error][aot]` lines and still compiles correctly.
Only *warnings* are lost, and `XTSC_AOT_VERBOSE=1` restores the whole log at `info`.

Two narrower flags were tried first and rejected on measurement:
`-Xlog:aot+codecache+stubs=error` misses the plain-`aot`-tagged line, and
`-Xlog:aot*=error:stderr` adds a stderr sink **without reconfiguring the default stdout
one**, so every warning still appeared. The `:stdout` selector is the load-bearing half.

## 4. Verification

**(a) The stale-cache experiment — round 828's exact scenario.** `Checker.class` removed
from the jar, fresh mtime, the round-828 cache present:

| arm | outcome |
|---|---|
| unguarded `java -XX:AOTCache=…` | **exit 0, `OK — 0 errors`** — the wrong answer |
| unguarded `java`, no cache (the truth) | exit 1, `NoClassDefFoundError: …/Checker` |
| **`scripts/xtsc` (guarded)** | **exit 1, `NoClassDefFoundError` — decision `SKIP no-cache-file`** |
| **`scripts/xtsc`, stale pair planted under the new fingerprint's name** | **exit 1 — decision `SKIP manifest-mismatch`** |

`xtsc-aot status` names the differing field:
`cp 1 a540f27f… 5634940 …jar` vs `cp 1 813e42db… 2641175 …jar`.

**(b) The pins discriminate.** `AotCacheGuardTest` drives the decision through
`XTSC_AOT_DECIDE_ONLY` over a synthetic classpath — milliseconds, no training. Run against
an ablated copy of `xtsc-aot-lib.sh` with the manifest comparison removed, exactly one pin
fails (`a mutated classpath entry is refused`, reading `USE …`) and the rest stay green.
A pin that survives its own ablation is a redundant guard, not coverage — see § 6.

**(c) Correctness with a valid cache.** `--noEmit --listAll` on the compiler profile,
identical command shape both arms, `[`-prefixed and `time:` lines stripped, sorted:

- **diff EMPTY**, both md5 `4caacf248ce417899c2972c16a82f1ed` — *the same digest round 828
  recorded*, so the output is stable across rounds as well as across arms. **(Round-841
  note: this cross-round claim is SOUND — 828 and 832 used the same capture recipe. It is
  the LATER claims, in § 9.3 and § 10.5, that were not. Read § 11 before comparing any
  digest in this file with any digest in another.)**
- 46 `error TS` lines and `FAILED — 46 error(s)` on both; neither capture empty (round 804)
  nor containing `more error(s)` (round 811).
- The cached arm's stdout now has **zero** `[` lines, so the two captures agree line-count
  for line-count (55 = 55) with no stripping at all.

**(d) The win survives the guard.** Three rotated pairs through the launcher, `--noEmit`,
self ms — so the cached arm pays the full ~80 ms verification on every run:

| pair | plain (`XTSC_AOT=off`) | cached | delta | ratio |
|---|---:|---:|---:|---:|
| 1 | 27,396 | 17,398 | −9,998 | 1.575× |
| 2 | 24,853 | 14,708 | −10,145 | 1.690× |
| 3 | 24,813 | 14,780 | −10,033 | 1.679× |

**Median 24,853 → 14,780 ms = 1.68×, 3/3 sign-consistent, a 147 ms spread across the three
deltas against a ~10,000 ms median delta.** Both arms are the same script, differing only
in the decision, which is the only comparison this round claims: the box was **shared with
another project's build throughout**, so the absolutes are inflated and — per the standing
rule — not comparable to round 828's or any other round's. The guard costs 0.8% of the
delta it protects.

**(e) An unprompted real-world confirmation.** Mid-round, a Gradle run rebuilt
`build/libs/…jar`. Nothing was told about it; the next `xtsc-aot status` reported
fingerprint `358cef8a…` → `aafecab2…` and `SKIP no-cache-file`, and the training run that
followed reported `pruned 1 stale cache(s)`. The upgrade path works on a real rebuild, not
only on a synthetic one.

## 5. Residual risks a user should know about

1. **The guard binds provenance, not intent.** It proves the cache was trained from *these*
   jars, on *this* JDK build, on *this* OS/arch. It cannot prove the training run was
   correct, and it is not a security boundary: anyone who can write your cache directory
   can usually write your jars.
2. **~49 MB of disk per install**, and ~28 s of training against a representative project.
3. **Cross-machine and cross-JDK-build portability is untested** — and now moot: both are
   in the fingerprint, so such a cache is refused rather than trusted.
4. **The distribution must be jars.** A dump from an exploded class directory is impossible
   (`Cannot have non-empty directory in paths`), so every A/B script in `scripts/` is
   structurally unable to carry an AOT arm.
5. ~~**Untested interactions:** emit mode, `--serve`, `--workers N`…~~ — **CLOSED round 839,
   § 7.** Emit, `--workers 2/4/8`, `--serve` and the whole 13,900-test corpus suite were all
   run through a cached JVM against an uncached arm, with **no divergence anywhere**. What
   is still untested: `--watch`/`--incremental` and the other seven dashboard profiles. (A
   cache trained under emit is § 9 — shipped; one trained under `--workers 4` is § 10 —
   measured, a net loss, not shipped.)
6. **The `AdapterHandlerEntry` warnings are nondeterministic.** Round 828 saw four, this
   round saw two on one cache and none on another. Their absence is not evidence the
   silencer works; § 3's verification is.

## 6. What did not work

- **`-Xlog:aot+codecache+stubs=error`** — the tag set round 828 quoted covers only one of
  the two warning shapes.
- **`-Xlog:aot*=error:stderr` alone** — configures an additional stderr sink; the default
  stdout sink keeps its own level and keeps printing. Unified logging is per *output*, not
  global.
- **Reproducing the warnings on demand** — they did not appear on the freshly trained cache
  used for the timing arms, so the silencer could only be verified by proving (i) stdout is
  clean when they *do* appear and (ii) genuine errors survive the flag.
- **`zip -d` for the stale-jar experiment** — not installed on this box; the jar was
  rewritten with Python's `zipfile`, which re-deflates and so also changes the entry sizes.
  A larger perturbation than round 828's, in the same direction.

## 7. Round 839 — the shipped cache, validated in every mode we ship

*Rounds 828 and 832 measured and shipped the cache on ONE mode of ONE profile: `--noEmit`
on the compiler profile. A cache validated on one mode and shipped for all of them is how a
field bug gets made, so this round ran the other modes against an uncached arm.*

Setup: HEAD `955f19d2` with the jar rebuilt from it (5,638,817 B), JDK 25.0.3, Gradle and
Kotlin daemons stopped (13.9 GB available), box otherwise idle, the agent not touching it.
Cache trained by `scripts/xtsc-aot train build/bench/tsc-project-637d5746` — 51,277,824 B,
fingerprint `ffb9eed14dcc6396`, self-verified `USE`. Every arm below is the *same* command
shape, differing only in whether the cache is handed to the JVM.

**THE HEADLINE: no divergence, anywhere.** Every one of the 33 whole-project compiles, the
8 server requests, the 4 emitted output trees and the 2 full corpus-suite runs agreed. The
`--noEmit --listAll` diagnostics of all 29 one-shot compiles and all 8 server requests share
one md5 (`59d930db849399aea5e03e25fedb8e4e`, 46 errors), across cached and uncached,
sequential and `--workers 2/4/8`, one-shot and server.

### 7.1 Emit mode — CLOSED

The one that mattered: `--noEmit` never runs the Transformer or the Emitter, and the
training run is `--noEmit`, so emit executes code the cache has no profile for.

| arm | self | diagnostics | emitted | per-file sha256 |
|---|---:|---|---:|---|
| plain, run 1 | 26,109 ms | 46 errors | 78 files | — |
| **cached, run 1** | **17,448 ms** | 46 errors | 78 files | **identical to plain** |
| cached, run 2 | 17,401 ms | 46 errors | 78 files | identical |
| plain, run 2 | 25,752 ms | 46 errors | 78 files | identical |

`dist/` was deleted before each run and the whole tree re-hashed after it (`find … |
sha256sum`); all four trees are byte-identical file for file, and the diagnostic captures
diff empty. **1.49× with emit**, against **1.66×** for `--noEmit` on the same binary
(23,799 → 14,314 ms).

**The emit TAIL is where the ratio goes, and it costs MORE under the cache**: emit adds
26,109 − 23,799 = **2,310 ms** to a plain run and 17,448 − 14,314 = **3,134 ms** to a cached
one. Suggestive rather than settled (n=2 per arm, ~300 ms of run-to-run spread), but it is
the expected shape: a plain run reaches the emitter after 24 s of JIT warm-up, a cached run
reaches it after 14 s, and the cache carries no emitter profile because `train` passes
`--noEmit`. That is a ~800 ms lead (~5% of a cached emit run) available to a training run
that emits — see § 7.6.

### 7.2 The corpus suite through a cached JVM — CLOSED

Gradle's test worker cannot carry an AOT arm (the cache binds to a classpath whose dump-time
form must be a prefix of the runtime one, and Gradle builds the worker's), so the suite was
driven directly by `scripts/CorpusRunner.java` in one plain `java` process — see
`scripts/aot-corpus-suite.sh`. Classpath: **the trained jar + its 7 dependency jars in that
exact order**, with the test classes and the JUnit/power-assert jars appended, which is what
makes the *shipped* cache applicable to a test JVM.

| arm | classes | run | failed | ignored | wall | peak RSS |
|---|---:|---:|---:|---:|---:|---:|
| plain | 638 | 13,897 | 10 | 3 | 141.4 s | 1,947 MB |
| cached | 638 | 13,897 | 10 | 3 | 135.1 s | 1,383 MB |

`13,897 run + 3 ignored` is exactly the 13,900 the Gradle gate counts. **The per-class
result file diffs EMPTY between the arms, failure lines included** (identical md5).

**The cache was genuinely IN USE, not merely opened** — the discriminator round 832 records:
`-Xlog:class+load=info` on the cached run shows **922** `com.xemantic` classes loaded with
`source: shared objects file`, the same population round 828 measured (921 of 926) — the
remainder of the 5,188 `com.xemantic` classes seen are test classes, which no cache contains.

**The 10 failures are harness artifacts, present identically in both arms**: 8 in
`AotCacheGuardTest` (`URI is not hierarchical`) and 2 in `HugeMethodLimitTest` (`main classes
are not a directory on the classpath: jar:file:…`). Both classes read the *classpath layout*
— they need Gradle's exploded main-classes directory, and this harness runs from the jar by
construction, because a jar is what an AOT cache can be trained from at all. They are green
under Gradle. No corpus baseline failed on either arm.

### 7.3 `--workers N` — CLOSED, with a distribution

The standing rule is that a `--workers` correctness claim needs a diagnostic-count
distribution over ≥5 runs per level, never one capture (rounds 754 and 824 each shipped a
false green from a single draw). **20 cached runs (5 each at sequential/w2/w4/w8) plus 9
uncached ones: 46 errors and the same sorted-diagnostics md5 in all 29.**

| arm | median self | runs |
|---|---:|---:|
| cached, sequential | 14,514 ms | 5 |
| cached, `--workers 2` | 12,779 ms | 5 |
| **cached, `--workers 4`** | **12,343 ms** | 5 |
| cached, `--workers 8` | 16,368 ms | 5 |
| plain, sequential | 22,975 ms | 3 |
| plain, `--workers 4` | 18,966 ms | 3 |
| plain, `--workers 8` | 20,342 ms | 3 |

Blocked runs, not a rotated interleave, so read these as directional. Two things they do
say. **The two levers overlap**: workers buy 1.18× on top of the cache (14,514 → 12,343)
where they buy 1.21× without it (22,975 → 18,966), and the best combination measured,
cached `--workers 4` against plain sequential, is **1.86×** — not the 1.66 × 1.36 = 2.26×
the two headline ratios would multiply to. And **`--workers 8` under a cache is worse than
cached SEQUENTIAL** (16,368 vs 14,514 ms), a reversal of the uncached order (20,342 vs
22,975): the more of the run is C2-compiled from the start, the sooner 8 workers stop paying
for themselves. w4 remains the optimum, as round 826 found.

### 7.4 `--serve` — CLOSED, and the answer is "nothing, once warm"

| request | plain server | cached server |
|---|---:|---:|
| 1 (cold) | 23,536 ms | **13,987 ms** |
| 2 | 10,060 ms | 8,067 ms |
| 3 | 7,419 ms | 7,633 ms |
| 4 | 7,268 ms | 7,310 ms |

All 8 requests: 46 errors, the same md5. **The cache halves the server's FIRST request —
the same ~9.5 s it saves a one-shot run — and is worth nothing warm** (7,268 vs 7,310 ms,
inside the noise). The two are the same lever seen twice: both remove interpreter warm-up,
so a long-lived server does not need a cache, and a cached one-shot run gets roughly half
way to a warm server (14.3 s vs 7.3 s) without needing one.

**A gap this exposed: `scripts/xtsc` cannot reach `--serve` at all.** The launcher's main
class is `…compiler.MainKt`, while the server/daemon dispatcher is
`…compiler.server.XtscMainKt`; the arms above were run by invoking `java` directly. Not a
correctness hazard — the launcher simply has no server mode. **CLOSED round 840, § 8.**

### 7.5 What did NOT work

- **Driving the suite through Gradle.** `JAVA_TOOL_OPTIONS` reaches the test worker only via
  the daemon's environment, and the worker's classpath is Gradle's, not the trained one —
  the cache would have been silently unused and the run would have looked exactly like a
  green one. Hence the standalone runner, and hence the class-load count in § 7.2.
- **A standalone suite run without `kotlin-power-assert-runtime-jvm`** — the first attempt
  read `4,998 failed` in *both* arms with `NoClassDefFoundError: kotlin/powerassert/
  CallExplanation`. `kotlin.powerassert.*` is a separate artifact from the stdlib and is not
  in `build/bench/cp.txt`. The failure is loud, but note it was identical in both arms:
  a same-in-both-arms result is not automatically a *valid* one.

### 7.6 Residue after this round

- ~~**A cache trained under emit** (~800 ms, ~5% of a cached emit run — § 7.1)~~ — DONE,
  § 9. ~~One trained under `--workers 4`~~ — **measured round 840(d), § 10: +3.5% on the
  sequential path for −0.9% on w4, so `train` stays sequential.**
- **`--watch` / `--incremental` under a cache**, and the other seven dashboard profiles.
- ~~**The `--serve` gap in the launcher** (§ 7.4)~~ — CLOSED round 840, § 8.

## 8. Round 840 — (AOT.4)(a), the launcher reaches the dispatcher

`scripts/xtsc` and `scripts/xtsc-aot` now both name
`com.xemantic.typescript.compiler.server.XtscMainKt`, whose `main` is a strict superset of
`com.xemantic.typescript.compiler.main` (it handles `--serve` / `--daemon` and otherwise
delegates verbatim; `defaultSocketPath()` is a pure `java.io.tmpdir` read, so a one-shot run
gains nothing but two class loads).

**Both scripts had to swap together**, because the main class is the `mainclass` field of
the fingerprint block: a half-swap trains under a fingerprint the launcher never looks up,
so every launch would read `SKIP no-cache-file` — silently, forever, with the lost 1.638×
as the only symptom. `AotCacheGuardTest` now pins the two against each other.
`XTSC_AOT_LAUNCHER_VERSION` was **not** bumped: `mainclass` is its own field, so the swap
invalidates by itself, and the constant is for command-line changes no other field records.

**The invalidation behaved as the contract says.** With round 839's cache
(`ffb9eed14dcc6396`, manifest line `mainclass …compiler.MainKt`) still in
`~/.cache/xtsc`, `xtsc-aot status` reported fingerprint `76c8ecdc710ee12e`, decision
`SKIP no-cache-file`, exit 0 and the retrain hint — an uncached run, not an error.

**Verified end to end** (small 2-file scratch project, 2 × TS2322):

| arm | evidence |
|---|---|
| one-shot `scripts/xtsc --noEmit --listAll` | 2 errors, 1,553 ms |
| `scripts/xtsc --serve --socket …` | `xtsc compile server listening on …`, then `request 1 served in 1541 ms`, `request 2 served in 218 ms` |
| `scripts/xtsc --daemon --socket …` ×2 | output **diff-identical** to the one-shot run (`time:` stripped); 1,523 ms cold, 217 ms warm, 0.25 s of client CPU |
| retrained cache | `train` → 37,457,920 B, self-verified; `status` → `USE …`; a cached run diffs identical to the plain one at 487 ms |
| cache genuinely used | `-Xlog:class+load=info`: **795 of 795** `com.xemantic` classes `source: shared objects file`, `XtscMainKt` and `CompileServer` among them |

Unix domain socket paths are capped near 100 bytes, so a `--socket` under a deep scratch
directory is refused by name (`CompileServer.socketPathProblem`) — which is itself proof the
dispatcher was reached, and the reason the round's socket lived at `/tmp/xtsc-r840.sock`.

## 9. Round 840(c) — (AOT.4)(c), a cache trained WITH emit

Round 839 § 7.1 left one priced, unclaimed residue: `scripts/xtsc-aot train` ran `--noEmit`,
so the shipped cache carried **no profile for the Transformer or the Emitter at all**, and
the emit tail measured *worse* under a cache than without one. It priced training-with-emit
at ~800 ms and did not test it. This round tested it, on both workloads, because the cache
is shared: a gain on the emit path could have been a loss on the check-only path.

### 9.1 The 2×3 grid

Jar rebuilt from the tree, JDK 25.0.3, Gradle and Kotlin daemons stopped (13.7 GB
available), the box otherwise idle and **not watched** while running (round 774). Two
caches trained back to back on `build/bench/tsc-project-637d5746`: **A** = `--noEmit`
(today's behaviour, 51,240,960 B), **B** = emitting (54,108,160 B, **+5.6%**, +1.5 s to
train). 54 whole-project compiles in three batches — a 6-config rotation (5 reps), an
A-vs-B rotation (6 reps), and the shipped-trainer verification (4 reps).

**Paired, within-rep deltas — the only quotable comparison** (B − A, `time:` self ms):

| workload | n | B faster in | median Δ | median % | range |
|---|---:|---:|---:|---:|---|
| **emit** | 15 | **14/15** | **−1,132 ms** | **−5.4%** | −2,428 … +60 |
| `--noEmit` | 15 | 10/15 | −150 ms | −0.8% | −1,373 … +650 |

Per-arm medians (all 15 runs each): `An` 16,687 / `Bn` 16,738 ms — **identical** — against
`Ae` 19,953 / `Be` 18,792 ms. Per-arm sd is 4.9–5.5%, so the arms overlap completely and
only the pairing resolves the effect; that is also why the batch-1 check-only reading
(5/5 wins, median −1,161 ms) must be **discarded** — batch 2 read 3/6 wins, median +192 ms,
and the verification batch 2/4. **The check-only path is a wash, in both directions.**

### 9.2 Where the emit win is, and that round 839's mechanism was right

Differencing each rep's emit run against its own `--noEmit` run isolates the emit tail:

| arm | emit tail (median of 15) |
|---|---:|
| cache A (`--noEmit`-trained) | 3,148 ms |
| **cache B (emit-trained)** | **2,216 ms** |
| plain, no cache (n=5) | 2,139 ms |

**−932 ms of tail, against round 839's predicted ~800 ms.** The emitter profile is the whole
story: an emit-trained cache restores the tail to roughly what an *uncached* run pays, which
is exactly the anomaly round 839 spotted (a cached run reaches the emitter after ~14 s of
warm-up instead of ~24 s, so it had less compiled emitter code, not more). Nothing here
supports a "longer training run warms the checker too" reading — if that were the mechanism
the check-only path would have moved, and it did not.

### 9.3 Correctness

All 54 compiles produced **46 errors** and one sorted-diagnostics md5
(`59d930db849399aea5e03e25fedb8e4e`, ~~the same digest rounds 828/832/839 recorded~~ —
**CORRECTED round 841: the same digest ROUND 839 recorded. Rounds 828 and 832 recorded
`4caacf24…`, which is a DIFFERENT RECIPE over the same output, not a different output.
See § 11.** The within-round claim — 54 compiles, one digest — is untouched), and every
one of the 30 emitting runs produced **78 files** and one whole-tree sha256
(`a3ccd863f3523f5aefe4215576e920a7531c4ccd0be550b9b1362593f8ca280e`), `dist/` being deleted
before each run and re-hashed after it. Emit-training changes nothing about the answer.

### 9.4 The packaging problem, and `--outDir`

The measurement is the easy half. `train` runs against **the user's own project**, so an
emitting training run that used the project's `outDir` would write JS into their `dist/` —
clobbering a real build, or failing outright on a read-only tree. That would be
unacceptable at any speed, so the win only exists if the output can go elsewhere.

It now does: `ProjectCompiler.build` takes an `outDir` override, exposed as **`--outDir`**
(resolved against the CWD, like tsc's; inert under `--noEmit`; deliberately not threaded
through `--incremental`/`--watch`), and `train` emits into a `mktemp -d` it deletes on every
exit path, killed runs included. `ProjectOutDirTest` pins the compiler half — *the project
tree is untouched*, the rootDir-relative shape survives, and the bytes do not depend on
where they are written — and `AotCacheGuardTest.the trainer trains with emit into a
throwaway directory` pins the script half.

Verified end to end with the shipped command on the rebuilt jar: `xtsc-aot train` produced a
54,009,856 B cache, self-verified `USE`, left **no `dist/` in the profile** and **no leftover
`/tmp/xtsc-train.*`**, and beat a `--noEmit`-trained cache **4/4** on emit (−2,428, −727,
−1,132, −272 ms) while reading 2/4 and +92 ms median on `--noEmit`.

**Nothing about the fail-safe contract changes.** The training *workload* is not part of the
fingerprint and must not be: the manifest binds the cache to the code it was trained from,
not to how it was exercised. An existing `--noEmit`-trained cache therefore stays valid and
usable — it is merely ~5% slower on an emitting compile until the next `train`.

### 9.5 What this round did NOT measure

- ~~A cache trained under `--workers 4`~~ — **measured round 840(d), § 10: a NULL, and a
  small net LOSS.** `--serve`/`--daemon` **under** a cache through the launcher — (AOT.4)(c)
  item (1) — is still open.
- `--watch`/`--incremental` under a cache, and the other seven dashboard profiles.
- Whether the emit win holds on a project whose emit is a larger share of the compile than
  the tsc profile's ~13%. The direction should be the same and the *share* larger, but that
  is an argument, not a measurement.
- Any interaction between `--outDir` and `--incremental`/`--watch`: the override is simply
  not passed on those paths, and the usage text says so.

## 10. Round 840(d) — a cache trained under `--workers 4`: MEASURED, and NOT SHIPPED

*Round 840(c) established "train the cache under the mode you actually run" and got
−1,132 ms on emit for nothing on the check-only path. The obvious next dimension is the
one `train` still fixes: it runs **sequentially**. Round 839 § 7.3 had already made this
worth asking, because cached `--workers 4` (12,343 ms) is **the fastest configuration this
project has**. This round trained a second cache under `--workers 4` and measured both
directions. **The answer is no.***

### 10.1 The grid

Same box, same hygiene: HEAD's jar (5,639,211 B), JDK 25.0.3, Gradle and Kotlin daemons
stopped before training (8.8 GB free), the agent not touching the box while runs were in
flight (round 774). Both arms run `java` directly with the launcher's own flag set
(`-XX:AOTCache=…`, `-Xlog:aot*=off:stdout`, `-Xlog:aot*=error:stderr`, `-Xmx4g`), from the
jar + its 7 dependency jars, main class `…server.XtscMainKt` — the guard's ~80 ms is
identical in both arms and is excluded deliberately.

Two caches, trained back to back on `build/bench/tsc-project-637d5746`, **differing only in
worker count** (both emit, i.e. both are today's shipped trainer):

| cache | training run | size | training wall |
|---|---|---:|---:|
| **S** | sequential (shipped) | 54,063,104 B | 29,127 ms |
| **P** | `--workers 4` | 54,145,024 B (+82 KB) | 22,077 ms |

Workload: `--noEmit --listAll` on the compiler profile at **seq / w4 / w8**, so 6 configs.
**Two independent batches of 5 reps each, rotated interleave** (batch 2 offset by 3), 60
whole-project compiles. Batch 2 reused the same two caches, so it replicates the
*measurement*, not the training draw.

### 10.2 The result — the two batches agree at every level

Paired, within-rep **P − S** (the only quotable comparison):

| level | batch 1 | batch 2 | **both (n=10)** | P faster in | verdict |
|---|---:|---:|---:|:---:|---|
| **sequential** | +501 ms | +679 ms | **+545 ms (+3.5%)** | **2/10** | **a COST** |
| **`--workers 4`** | −110 ms | −114 ms | **−112 ms (−0.9%)** | 8/10 | inside the band |
| **`--workers 8`** | −854 ms | −610 ms | **−732 ms (−4.7%)** | 8/10 | a gain, on a level nobody should use |

Per-arm medians over all 10 runs each, with per-arm sd:

| level | cache S (sequential-trained) | cache P (w4-trained) |
|---|---:|---:|
| sequential | **15,761 ms** (sd 2.6%) | 16,093 ms (sd 3.8%) |
| `--workers 4` | **12,214 ms** (sd 4.0%) | 12,181 ms (sd 7.1%) |
| `--workers 8` | 15,483 ms (sd 3.3%) | 14,861 ms (sd 4.2%) |

**So the trade is real and it runs the wrong way.** Training under `--workers 4` buys the
w4 path **0.9%** — below the ±1.0% warm band and ±2.0% cold band, i.e. nothing — and
charges the **sequential** path **3.5%**, which is the path `scripts/xtsc` takes when the
user passes no flags. Both effects reproduced in two independent batches at the same sign
and within ~180 ms of each other. Under round 840(c)'s own headline lesson — *one batch of
five rotated sign-consistent pairs was still wrong* — replication is what makes this
quotable, and here it is what makes the **null** quotable rather than the win.

### 10.3 Why this dimension has a trade and the emit one did not

Round 840(c)'s change was free because emit code and check code are **disjoint** — the
Transformer/Emitter profile was simply *absent*, and adding it took nothing away. Worker
count is not a disjoint population: it is the **same** checking code reached through a
different thread structure, and a JEP 515 profile is a finite, shared record of how each
method was actually called. A w4 training run records receiver types and branch counts
gathered from four worker threads; a sequential run then executes those same methods with
a different call/inlining shape and gets a profile that fits it slightly worse. The +82 KB
and the direction of both effects (helps w4 and w8, hurts seq, monotone in worker count)
are consistent with that reading. **It is a reading, not a measurement** — nothing here
inspects the recorded profiles.

The transferable lesson is the sharper half: **"train under the mode you run" is not a
general law, it is a claim about one dimension at a time.** In a dimension where the modes
share code, training under one mode is a *trade*, and the question is which side the
default sits on. Here the default is sequential, and it loses.

### 10.4 The other two questions round 839 left, re-measured

Within cache S (the shipped trainer), paired within-rep across levels, n=10:

| comparison | paired median | faster in | ratio of medians |
|---|---:|:---:|---:|
| cached seq → cached **w4** | **−3,550 ms (−22.5%)** | **10/10** | **1.290×** |
| cached seq → cached w8 | −120 ms (−0.8%) | 6/10 | 1.018× |

- **Cached `--workers 4` remains the fastest configuration this project has**, by a wide
  and perfectly sign-consistent margin (10/10 at both caches; 1.29× on S, 1.32× on P).
- **Round 839's "`--workers 8` under a cache is WORSE than cached sequential" does NOT
  reproduce.** It read 16,368 vs 14,514 ms there; here the two are indistinguishable
  (15,483 vs 15,761 ms, paired 6/10, −0.8%). Round 839's ladder was *blocked runs, not a
  rotated interleave*, and said so; this one is rotated and paired. What survives is the
  weaker and still-useful statement: **w8 buys nothing over sequential under a cache**,
  where uncached it bought 1.13× — the cache does erode the top of the worker ladder, it
  just does not invert it.

### 10.5 Correctness

**All 60 compiles: 46 `error TS` lines and ONE sorted-diagnostics md5,
`59d930db849399aea5e03e25fedb8e4e`** — ~~the digest rounds 828/832/839/840(c) recorded~~
**CORRECTED round 841: the digest rounds 839 and 840(c) recorded (and, on a different
harness, rounds 826 and 836–838). Rounds 828/832 recorded `4caacf24…` under a DIFFERENT
capture recipe — § 11.** The within-round claim is untouched — across
both caches and all three worker levels, i.e. 20 runs per level and 30 per cache. No capture
was empty (round 804) and none contained `more error(s)` (round 811). That is also the
≥5-runs-per-level distribution the standing `--workers` rule demands, at 10 per level rather
than 5, and it is 132 post-fix `--workers` runs in total across rounds 825/826/839/840(d).

### 10.6 The decision, and what was NOT measured

**`scripts/xtsc-aot train` stays sequential.** A ~0.9% gain on an opt-in flag does not buy
a 3.5% loss on the default path. `AotCacheGuardTest.the trainer trains sequentially` pins
it so a future round does not "just add `--workers 4`" on the strength of round 839 § 7.3.

Not measured: a cache trained under `--workers 2` or `8`; whether re-training changes the
draw (batch 2 reused both caches, so training-run variance is unsampled — one training run
per arm); an **emitting** compile under either cache (this round's workload is `--noEmit`
throughout, so the round-840(c) emit win is neither re-confirmed nor at risk); the
interaction with `--serve`; and the other seven dashboard profiles. Nothing here touched
`src/commonMain` or `src/jvmMain`, so `cost_gate.py` and `huge_methods.py` were not run.

## 11. Round 841 — the TWO diagnostics-digest lineages, and why "the same digest" was false

*Round 841 (2026-08-07), documentation-drift audit. No measurement was re-run and no
compiler was built; this section is `grep` plus one arithmetic reproduction over a capture
already on disk. It corrects a claim that was being used as CORRECTNESS EVIDENCE, which is
why it gets a section rather than a footnote.*

### 11.1 The contradiction

This file asserted, in three places, that the compiler profile's sorted-diagnostics md5 is
stable across rounds. But it named **two different values** for it:

| digest | recorded by |
|---|---|
| `4caacf248ce417899c2972c16a82f1ed` | rounds **828, 832, 833, 834, 835** |
| `59d930db849399aea5e03e25fedb8e4e` | rounds **826, 836, 837, 838, 839, 840(c), 840(d)** |

§ 4(c) said `4caacf24…` was "the same digest round 828 recorded"; § 9.3 and § 10.5 said
`59d930db…` was "the same digest rounds 828/832/839 recorded". Both cannot be true, and the
value was load-bearing: it is the correctness half of every AOT round, and the 8-profile
grid quotes it to argue a change is output-neutral.

### 11.2 The answer: two capture RECIPES, one output

**It is not two outputs. It is two normalisations of the same output**, and the tell is the
line count — 46 versus 54.

- **`59d930db849399aea5e03e25fedb8e4e` = `grep -a 'error TS' <raw --listAll stdout> | sort`
  — 46 lines, ABSOLUTE paths, nothing stripped.** *Reproduced exactly*, round 841, from an
  independent capture already committed to the tree
  (`build/r817/grid-tsc-project-post.txt`, a round-817 8-profile-grid capture that predates
  every round in the table above). That is the decisive fact: the digest is a property of
  the recipe, not of a particular round's binary.
- **`4caacf248ce417899c2972c16a82f1ed` = the WHOLE `--listAll` stdout with `[`-prefixed and
  `time:` lines removed, then sorted — 54 lines** (§ 4 of this file and
  `aot-cache-round828.md` § 4 both state the recipe, and both report the 54). Those 8 extra
  lines are the run banner, `project:`, `config:`, `files:`, `unresolved imports:`,
  `diagnostics:`, `by code:` and `FAILED — N error(s)`. A 54-line digest can never equal a
  46-line one, so no amount of stability in the compiler could have made those two values
  agree.

**The repo already knew this and one round said so.** Round 836's session note
(`PLAN-PHASE-5.md`) records: *"The digests differ from round 835's because this round's
capture normalises to `grep 'error TS' | sort` rather than sorting the whole `--listAll`
output."* The 8-profile grid switched recipes at round 836; the AOT rounds switched with it
at 839. What went wrong is that § 9.3 and § 10.5 then reached back past the switch and
claimed identity with 828/832 anyway.

`4caacf24…` was **not** reproducible from the on-disk round-817 capture under four
variants of its stated recipe (locale, `time:`-line handling). The most likely reason is
that rounds 828–835 ran through `scripts/xtsc` from the jar with a differently-spelled
project path, and the recipe hashes the banner and `project:`/`config:` lines that carry it.
Settling that would need one `--listAll` capture from a jar run — worth ~2 minutes to
whoever next has the binary warm, and worth nothing otherwise, because § 11.3 makes the
question moot.

### 11.3 What this does and does not invalidate

**Does not invalidate any measurement.** Every round's *within-round* claim — "all N runs
of this round produced ONE digest, cached and uncached" — is untouched: both arms of every
comparison were captured by the same command, which is the only property those claims need.
No correctness result in this file, in `aot-cache-round828.md`, or in any round note is
weakened.

**Does invalidate the cross-round identity argument as stated.** "The output is stable
across rounds because the digest matches round 828's" is only an argument between rounds
using the *same* recipe. Both struck sentences have been corrected in place.

### 11.4 The rule, and a live trap it exposes

**A digest is a property of (output × recipe). Quote the recipe with the digest, or do not
quote the digest across rounds.** Two consequences:

1. **There is a THIRD lineage in the tree, and it matches nothing recorded.**
   `scripts/grid838.sh` — the committed 8-profile capture harness, and the natural thing
   for the next round to reach for — pipes through
   `sed "s#${DIRS[$P]}/##g"`, stripping the absolute project prefix from every error line.
   On the same round-817 capture that reproduces `59d930db…` exactly, that recipe yields
   **`84bbe7f0a60d81c40349527a068b8647`**. So a round that runs `grid838.sh`, compares its
   compiler-profile digest against the `59d930db…` written all over `PLAN-PHASE-5.md`, and
   reads the mismatch as a regression, will be chasing a `sed`. The path strip is the
   *better* recipe — it is the only one of the three that is portable across boxes and
   checkouts — so the fix is to re-baseline onto it deliberately and say so, not to remove
   the `sed`.
2. **Prefer the count-plus-diff over the digest for cross-round work.** "46 errors,
   `added=0 removed=0` against a rebuilt before-arm" survives a recipe change; a bare md5
   does not. The digest's real job is the *within-round, many-runs* check (30 runs, one
   value), and at that job it is excellent.
