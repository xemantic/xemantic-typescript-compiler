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
a different call/inlining shape and gets a profile that fits it slightly worse. ~~The +82 KB
and the direction of both effects (helps w4 and w8, hurts seq, monotone in worker count)
are consistent with that reading.~~ **It is a reading, not a measurement** — nothing here
inspects the recorded profiles. **STRUCK IN PART, round 842 (§ 12.4): five caches trained by
an IDENTICAL command span 212,992 B, 2.6× the +82 KB cited here, and cache size does not
order speed (the largest of the five is second-slowest). The size argument carries no
information; only the direction-of-effect half of this reading survives.**

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

Not measured: a cache trained under `--workers 2` or `8`; ~~whether re-training changes the
draw (batch 2 reused both caches, so training-run variance is unsampled — one training run
per arm)~~ — **MEASURED round 842, § 12: the draw is worth up to 2.4% between two
identically-trained caches, so this round's +3.5% is ~1.5× a term it did not model. The
DECISION stands (a null cannot become a win); the number should not be quoted precisely.** an **emitting** compile under either cache (this round's workload is `--noEmit`
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

### 11.5 Round 853 — a FOURTH lineage, and the first LIVE reproduction of all of them

*Round 853 (2026-08-08), the record-integrity round that re-took round 848's flag sweep after
round 852 found `grid838.sh` reading a pre-module-split classpath. One verified capture,
six normalisations of it.*

Round 841 derived `84bbe7f0…` arithmetically, from a round-817 capture already on disk.
Round 853 ran the compiler profile on a **freshly built, guard-verified** binary
(`--noEmit --listAll`, core module class dir asserted to contain `ModeLedger`) and hashed the
one raw capture six ways:

| recipe | digest | lineage |
|---|---|---|
| `grep 'error TS'` \| project-prefix `sed` \| `sort` — 46 lines | `84bbe7f0…` | **grid838.sh**, rounds 852, 853 |
| `grep 'error TS'` \| `sort`, absolute paths — 46 lines | `59d930db…` | rounds 826, 836–840 |
| whole stdout minus `[`/`time:` lines, sorted | `f0a07cb0…` | *not* `4caacf24…` — see § 11.2 |
| `grep 'error TS'` \| `s#.*/src/#src/#` \| `sort` — 46 lines | **`4090b73e…`** | **round 848** |
| `grep 'error TS'`, unsorted (emit order) | `1bfb40e0…` | — |
| prefix-stripped, unsorted | `7b17ffe5…` | — |

Two things follow, and the second is worth more than the first.

1. **Round 848's `4090b73e…` is a fourth lineage, not a wrong output.** It is a
   basename-style strip (`src/…` rather than the full project path), and it reproduces
   *exactly* on round 853's verified capture. It was recorded during the window in which
   `grid838.sh`'s classpath was stale, so it was the natural suspect; it is exonerated.
2. **The compiler profile's diagnostics have been byte-stable from round 817 to round 853.**
   `59d930db…` was reproduced by round 841 from a round-817 capture and is reproduced here
   from a round-853 run of a freshly built binary — same 46 lines, same digest, ~36 rounds
   apart. Every "0 added / 0 removed" in between is corroborated from a direction none of
   those rounds could measure from.

The rule of § 11.4 is unchanged and now has a fourth witness: **quote the recipe with the
digest, and prefer `added=0 removed=0` against a rebuilt before-arm for anything cross-round.**

## 12. Round 842 — (AOT.5)(f): the TRAINING DRAW is worth up to 2.4%, and nobody had sampled it

*Every AOT number in this file before this section was measured with **one trained cache per
arm**. Rounds 840(c) and 840(d) both ran two independent batches, and both said so as
evidence — but a second batch re-uses the same cache files, so it replicates the*
measurement *draw and cannot touch the* training *one. (AOT.5)(f) named this "the line most
likely to overturn something already recorded". It does not overturn either decision, but it
does put a number on a term both of them treated as zero.*

### 12.1 The grid

Same box and hygiene as §§ 9–10: HEAD's jar (5,639,208 B), JDK 25.0.3, no Gradle or Kotlin
daemon resident (13.9 GB free), the box unwatched while runs were in flight (round 774).
**Five caches trained by an identical command** — `scripts/xtsc-aot train
build/bench/tsc-project-637d5746`, i.e. the shipped trainer, emitting and sequential, run
five times in a row with nothing changed between runs:

| draw | size | sha256 (16) | training wall |
|---|---:|---|---:|
| 1 | 54,124,544 B | `db39dfb60aa115c1` | 28,273 ms |
| 2 | 54,099,968 B | `f0074a64fcf0999b` | 27,522 ms |
| 3 | 54,185,984 B | `d4cca6c1b45c3144` | 27,419 ms |
| 4 | 54,206,464 B | `f581ffb0ee14333c` | 27,533 ms |
| 5 | 53,993,472 B | `3ebf2506019a14d6` | 27,545 ms |

All five carry the **same fingerprint** (`ebeb9d0f…`) and are therefore interchangeable to
the launcher — the caches differ, the provenance does not. **168 whole-project compiles**:
132 `--noEmit --listAll` across five rotated batches (A/B 4 arms × 6 reps including an
uncached anchor, C 3 × 8, D/E 5 × 6) and 36 emitting ones (F/G 3 × 6). Harness:
`scripts/aot-draw-variance.sh`, landed this round.

### 12.2 Check-only — a real effect, mostly persistent, ≤2.4%

Paired within-rep, the only quotable comparison (n = 32 where both arms ran in all five
batches, n = 12 for the draw-4/5 pairs):

| contrast | n | median | % | faster in |
|---|---:|---:|---:|:---:|
| **c5 − c3** (worst vs best) | 12 | **+322 ms** | **+2.4%** | c5 in 2/12 |
| c4 − c3 | 12 | +245 ms | +1.8% | c4 in 1/12 |
| c5 − c1 | 12 | +234 ms | +1.7% | c5 in 3/12 |
| c3 − c2 | 32 | −176 ms | −1.3% | c3 in 26/32 |
| c2 − c1 | 32 | +147 ms | +1.1% | c2 in 11/32 |
| c4 − c1 | 12 | +126 ms | +0.9% | c4 in 4/12 |
| **c3 − c1** (the two fast draws) | 32 | −72 ms | −0.5% | c3 in 21/32 |

Ranking, fast to slow: **c3 ≈ c1 < c2 ≈ c4 < c5**, and the ordering **replicates**: batches
D and E, run separately with different rotations, put the same three draws in the same first
three places (`c3 < c1 < c2`) and only swapped c4 and c5, which are 106 ms apart. So this is
a persistent property of a cache file, not run-to-run noise — one training run in five
produced a cache that is 2.4% slower than the best, permanently, for as long as it is
installed.

**The anchor, unchanged from earlier rounds:** cached vs uncached is **1.592×** (+8,440 ms
paired, 12/12), consistent with the 1.64–1.68× recorded in §§ 4/7 on other batches.

**And batch A was a false positive, exactly as round 840(c) warned.** Its medians spread
483 ms (3.5%) with c3 fastest and c2 slowest, which looks like a much larger effect than the
one that survived; batch B put c1 first and spread 194 ms. Had this round stopped at one
batch it would have reported the draw effect as ~3.5% instead of ≤2.4%. **The instrument
that separates them is replication, and it is the same lesson for the third round running.**

### 12.3 The emit half — same size, but the fine ordering does not carry

Three draws (1, 3, 5) re-run on the workload round 840(c)'s **shipped** win lives on
(`--listAll --outDir <throwaway>`), two batches of 6:

| contrast | n | median | % | faster in |
|---|---:|---:|---:|:---:|
| c5 − c3 | 12 | +253 ms | +1.6% | c5 in 5/12 |
| c3 − c1 | 12 | +216 ms | +1.4% | c3 in 4/12 |
| c5 − c1 | 12 | +186 ms | +1.2% | c5 in 2/12 |

Per-batch spread of medians 316 ms (2.1%) and 180 ms (1.2%) — the same magnitude as
check-only. What carries across workloads is only the **extremes**: c1 is fast in both and
c5 is slow in both (c5 − c1 is +234 ms check-only and +186 ms emitting, with c5 winning 3/12
and 2/12). What does **not** carry is the fine ordering — c3 is the fastest draw on
check-only and mid-pack on emit, and batches F and G disagree on the ordering outright. So
a draw is not simply "good" or "bad"; part of the effect is workload-specific.

**Correctness across all 36 emitting runs: 46 errors, one diagnostics md5 (`59d930db…`,
the § 11 recipe), 78 emitted files and ONE whole-tree digest, `0b59764c…`.** Across all 168
compiles of the round, one diagnostics digest, no empty capture (round 804) and no
`more error(s)` tell (round 811).

### 12.4 What this does to §§ 9 and 10

**§ 9 (emit training, SHIPPED) is safe.** Its win is −1,132 ms (−5.4%) on the emit path,
against a draw term measured here at ~1.2–1.6% on that same path — the effect is over three
times the noise it was competing with, and its companion "check-only is a wash" reading
(−0.8%) sits inside the draw band, which is consistent rather than contradictory.

**§ 10 (`--workers 4` training, REJECTED) keeps its decision and loses the precision of its
number.** Its headline is +545 ms (+3.5%) charged to the sequential path, from **one**
sequential-trained cache versus **one** w4-trained cache. The largest contrast between two
identically-trained caches here is +322 ms (2.4%). The recorded effect is therefore only
~1.5× the draw term it never modelled, and the two arms could have differed partly by draw.
**The decision does not move**: nothing was shipped, and the case against training under
`--workers 4` was a *null* on the w4 path plus a cost on the default path — the draw term
cannot turn a null into a win. **The number should not be quoted to three digits**, and
§ 10.2's per-level figures now carry that caveat.

**One supporting argument in § 10.3 is struck.** It read the w4-trained cache's **+82 KB**
as consistent with a materially different recorded profile. Five caches trained by an
*identical* command span **212,992 B (~208 KB)** here — 2.6× that difference — so a size
delta of that order carries no information at all. Worse for the reading: **size does not
order speed.** Draw 4 is the *largest* cache and second-slowest; draw 5 is the *smallest*
and slowest; draw 3 is mid-large and fastest. There is no usable predictor of a draw's speed
short of running it.

### 12.5 The rule, and what ships

**An A/B on a TRAINING configuration needs at least two independently trained caches per
arm.** Two batches of runs is the standing rule for a measurement and it is *not sufficient
here*, because both batches read the same two cache files: the training draw is a
between-arm confound that no amount of re-running can average out. This is the AOT-specific
form of round 776's law that a recorded baseline is a claim about a *build*, not a commit —
here it is a claim about a *draw*, not a configuration.

**Nothing ships.** "Train twice and keep the faster" is arithmetically self-defeating on this
box: identifying the faster of two draws takes ~10 paired compiles (~4 minutes) to resolve a
2.4% difference worth ~0.3 s per run, so it pays back only after ~800 compiles, and it makes
the installed cache non-reproducible by the documented command. `scripts/xtsc-aot train`
stays a single run.

**(AOT.5)(a) is closed in passing:** the shipped `~/.cache/xtsc` cache, un-retrained since
round 840's fingerprint change, was retrained (54,079,488 B, self-verifies `USE`) and
**pruned 1 stale cache** — the "delete on upgrade" half of § 2, observed doing its job on a
real fingerprint change rather than in a fixture.

### 12.6 What this round did NOT measure

- **Why** a draw is slow. Nothing here inspects the JEP 515 profiles; the mechanism is
  unexamined, and § 10.3's story about *what* differs between caches is now unsupported
  rather than disproved.
- Whether five draws is enough to characterise the distribution — the worst draw appeared
  once in five, which bounds nothing about how often a *worse* one appears.
- Draw variance under `--workers`, under `--serve`, or on any of the other seven dashboard
  profiles; and whether a cache's rank is stable across *box states* rather than just across
  batches an hour apart.
- Whether re-training the same draw index reproduces its rank (each of the five caches was
  trained exactly once — the round samples the draw, it does not repeat it).

## 13. Round 842 — (AOT.5)(b): `--serve`/`--daemon` UNDER a cache, through the launcher

*Round 839 measured `--serve` uncached, by invoking `java` by hand — the launcher could not
reach the mode at all until round 840 fixed it. Round 840 then verified `--serve`/`--daemon`
through the launcher but only WITHOUT a cache. This is the combination, and it is the last
mode we ship that had never been run as shipped.*

### 13.1 Correctness first

**32 server requests** (four per server, two arms, one unreplicated pair plus three rotated
ones), every one of them: **46 errors, one diagnostics md5 (`59d930db…`), and ZERO
in-process fallbacks.** That last column is not decoration — `--daemon` compiles in-process
when no server answers (`XtscMain.runAsClient`), so a request that silently failed to reach
the server would still print correct diagnostics and would otherwise be indistinguishable
from a served one. The harness greps the client's stderr for that fallback line on every
request. *That harness was ad-hoc (a scratch script, not committed): unlike § 12's draw
experiment it needs no state beyond a server and a socket, and the method is one loop —
start `scripts/xtsc --serve --socket <short path>` with `XTSC_AOT_VERBOSE=1`, wait for the
`listening on` line, send N `--daemon` requests, assert no fallback line, kill the server.
Note the socket path must be under ~100 bytes (`sockaddr_un`), which round 840 found the
hard way.*

### 13.2 The cache buys the first TWO requests, not just the first

Paired within-rep, three rotated server pairs (server-reported ms; each pair is a fresh
server per arm):

| request | rep 1 | rep 2 | rep 3 | median | cached faster in |
|---|---:|---:|---:|---:|:---:|
| **1 (cold)** | −8,013 | −9,322 | −8,375 | **−8,375 ms (1.66×)** | **3/3** |
| **2** | −1,993 | −2,528 | −3,070 | **−2,528 ms (1.32×)** | **3/3** |
| 3 | +297 | −755 | +211 | −211 ms | 1/3 |
| 4 | −626 | −196 | +396 | −196 ms | 2/3 |

Per-arm medians: cold **14,116** vs **23,375** ms; by the fourth request **7,272** vs
**7,313** ms — identical.

**Round 839's reading is reproduced through the shipped launcher and refined.** Its
statement was "the cache halves the first request and is worth nothing warm" (13,987 →
23,536 cold, 7,268 vs 7,310 warm). Both ends replicate. What it did not report, because it
sampled only "first" and "warm", is the **middle**: request 2 is still **1.32× faster under
the cache, 3/3**, so the cache does not merely remove the first request's class-loading and
interpreter cost — it shortens the C2 warm-up RAMP by about one whole request. From the
third request on the two servers are the same machine, which is the expected end state: the
JIT has re-derived everything the cache was carrying.

**Consequence for anyone choosing between the two levers:** they overlap almost entirely.
A server that will serve more than two requests gets nothing from the cache; a server that
serves one or two gets 1.66×/1.32×. The cache's real constituency remains the ONE-SHOT
compile, which is what `scripts/xtsc` does by default.

### 13.3 A trap this round walked into, and the instrument that caught it

**The first run of this experiment was an A/A and looked exactly like an A/B.** Both arms
compiled correctly, at 46 errors and the right digest, with the "cached" arm reading 21,801 /
10,376 / 8,081 / 7,021 ms — a perfectly plausible ladder. The launcher had in fact decided
**`SKIP no-cache-file`** for both.

The cause is that **`build/libs/*.jar` is not byte-reproducible**, and every classpath entry
is content-hashed into the fingerprint (§ 1, deliberately — mtime is not consulted). Measured
this round: `./gradlew jvmJar --rerun` **with no source change whatsoever** produced a
different sha256 *and* a different size (5,639,210 → 5,639,200 B). The suite run earlier in
the session had rebuilt the jar at 09:32; the cache trained at 09:24 became unreachable at
that moment, exactly as designed.

Three things follow, and the third is the one that costs rounds:

1. **This is the fail-safe direction working.** A stale cache is never used; the run is
   merely slower. Nothing about § 1 changes.
2. **"Retrain the shipped cache" is not a durable state on a development box.** (AOT.5)(a)
   was closed earlier this round and was invalid ~15 minutes later. The cache must be
   trained **after the last build**, which is what `xtsc-aot`'s "a packager runs it once,
   after the jars are in place" already says — but the docs nowhere said that a *no-op*
   rebuild suffices to invalidate it.
3. **A cached-arm measurement must PRINT THE DECISION.** `XTSC_AOT_VERBOSE=1` makes the
   launcher say `aot USE …` or `aot SKIP …` on stderr, and that line is the only thing
   separating a real cached arm from a silently-uncached one. A ratio near 1.0 would have
   been read as "the cache does nothing for `--serve`" and written down.

### 13.4 What this round did NOT measure

- A cache trained **with `--serve` in the workload** — (AOT.5)(c), untouched; this round
  trains as shipped (sequential, emitting) and only *runs* under the server.
- More than four requests per server, and any request other than the same project repeatedly
  — the warm floor here is one project's re-check, not a watch-style edit/recheck cycle.
- `--watch`/`--incremental` under a cache, and the other seven profiles ((AOT.5)(d)/(e)).
- Whether the ramp effect survives on a project small enough that request 1 is dominated by
  JVM start rather than by checking.

## 14. Round 857 — (MOD.7): the cache RETRAINED against the post-split layout

*The module split (MOD.3/MOD.4) moved the jar path, the dependency set, the dependency
ORDER and the entry point's module. The fingerprint hashes every classpath entry in order,
so the shipped cache became unreachable the moment the split landed — fail-safe, and
therefore silent. MOD.7 was the debt that left. This section is its discharge; nothing in
the contract of §§ 1–2 changes.*

### 14.1 The state the split left, measured rather than assumed

| | pre-split (round 842) | post-split (this round) |
|---|---|---|
| classpath | 8 jars | **14 jars** (+ ktor-io/-network/-utils, slf4j, `-api`, `-daemon`; stdlib 2.4.0 → 2.4.10, kotlinx-io 0.9.0 → 0.9.1, serialization 1.9.0 → 1.11.0) |
| first entry | the core jar | `annotations-23.0.0.jar` — the ORDER moved too, not just the set |
| fingerprint | `73c2f5feb9c0f857` | **`086a6cb1ae5b4203`** |
| decision | `USE` | `SKIP no-cache-file` |

**And the dev launcher could not start at all.** `xtsc_resolve_classpath`'s development
fallback is the staged `…-daemon/build/install/lib` (MOD.4b deleted the hand-listed jar
names in favour of Gradle's `xtscLib` Sync), and **nothing had run `assemble` since the
split**, so `scripts/xtsc` died with `cannot resolve a classpath … or build this repo
first`. That is the fail-safe direction — a loud refusal, not a wrong answer — but it means
**a fresh checkout or a `clean` leaves the launcher inoperable until `./gradlew assemble`**,
which no earlier section said.

After `assemble`: trained in 30.0 s, **54,816,768 B**, self-verified `USE`, and
`pruned 1 stale cache(s)` — the pre-split cache, removed by the same step that replaced it.

### 14.2 USED, not merely present

The discriminator is round 832's, and it is the only thing separating a cached arm from a
silently-uncached one: `-Xlog:class+load=info` on a run through the launcher shows
**955 of 960 `com.xemantic` classes with `source: shared objects file`**, `Checker` among
them, with the launcher printing `aot USE …` under `XTSC_AOT_VERBOSE=1`. Every timed run
below prints its own decision line for the same reason.

### 14.3 The guard still refuses a stale cache — re-run end to end on the new classpath

Round 828's experiment, repeated against the 14-jar layout with `Checker.class` removed
from a *copy* of the core jar:

| arm | outcome |
|---|---|
| unguarded `java -XX:AOTCache=…` | **compiled and reported `FAILED — 2 error(s)`** — i.e. type-checked against a class the jar no longer contains |
| unguarded `java`, no cache (the truth) | `NoClassDefFoundError` / `ClassNotFoundException` |
| **`scripts/xtsc` (guarded)** | **`aot SKIP no-cache-file`, then `NoClassDefFoundError`** |

`xtsc-aot status` on the mutated classpath names a new fingerprint (`c6eff5834a48b43b`) and
the absent cache. The hazard is exactly as live post-split as it was at round 828, and the
guard is exactly as effective.

**A property the copy step proves in passing, and which no section stated:** the fingerprint
records each entry's **basename**, never its path (`${entry##*/}` in
`xtsc_fingerprint_block`). A byte-identical copy of the lib dir at another path therefore
verifies `USE` against the same cache — the guard binds the ARTIFACT, not the install
location. That is what makes the experiment above a valid arm, and it is what lets a
distribution be relocated without retraining.

**Pins:** `AotCacheGuardTest` is **13/13 green** on the post-split tree. Ablated — the
manifest comparison deleted from `xtsc_aot_decide`, one mistake, on a COPY of `scripts/`
reached through `XTSC_TEST_LAUNCHER` — **exactly one pin fails, `a mutated classpath entry
is refused`**, reproducing round 832's ablation on the module the class now lives in.

### 14.4 The ladder, ARM and MODE named

**ARM: the shipped JVM launcher `scripts/xtsc`, from the staged 14-jar lib dir, main class
`…server.XtscMainKt`. MODE: check-only (`--noEmit --listAll`), sequential, compiler
profile.** Both arms are the same script and differ only in the decision, so the cached arm
pays the guard's full ~80 ms verification on every run. Four rotated pairs, box idle and
unwatched.

| pair | plain (`XTSC_AOT=off`) | cached | delta | ratio |
|---|---:|---:|---:|---:|
| 1 | 24,214 | 15,277 | −8,937 | 1.585× |
| 2 | 23,085 | 15,656 | −7,429 | 1.475× |
| 3 | 23,951 | 14,818 | −9,133 | 1.616× |
| 4 | 24,297 | 15,043 | −9,254 | 1.615× |

**Median 24,082 → 15,160 ms; paired median −9,035 ms, ratio 1.600×, cached faster 4/4.**
Consistent with the 1.59–1.68× of §§ 4/7/12 — and per the standing rule, only the paired
within-round delta is quotable; the absolutes are not comparable to any other round's.

### 14.5 Correctness: all 8 runs, and a fifth witness for § 11

All 8 compiles: **46 `error TS` lines**, no empty capture (round 804), no `more error(s)`
(round 811), and under the `grid838.sh` recipe **one digest across both arms:
`84bbe7f0a60d81c40349527a068b8647`** — *the same value round 841 derived from a round-817
capture and round 853 reproduced live*. So the compiler profile's diagnostics are byte-stable
from round 817 to round 857, now witnessed for the first time **through the shipped jar
launcher on the post-split classpath**, cached and uncached alike.

**§ 11 gains a fifth and sixth lineage, and the cause is new: the SORT.** This round's own
harness first reported `4b9635d6…` and `bcb1512a…` for what turned out to be the *same*
46 lines — it sorted in Python (`sorted()`, code-point order) where every recorded lineage
sorts in the shell (`sort`, locale collation). Applying the `grid838.sh` recipe verbatim to
the same capture yields `84bbe7f0…` exactly. **So the recipe includes the COLLATION, not
just the `grep` and the `sed`** — one more reason § 11.4's rule is "quote the recipe with the
digest", and to prefer `added=0 removed=0` against a rebuilt before-arm for anything
cross-round.

### 14.6 The corpus suite through the cache — and a harness the split had broken

`scripts/aot-corpus-suite.sh` built its AOT prefix as `<core jar>:$(cat build/bench/cp.txt)`
— the COMPILER's 7-entry dependency tail, in the compiler's order, and itself stale
(kotlinx-io 0.9.0, serialization 1.9.0). Post-split that is **not a prefix of the trained
classpath**, so the JVM would have declined the cache in `AOTMode=auto` and **both arms would
have run uncached, agreed perfectly, and proved nothing** — round 842 § 13.3's trap, in a
committed harness. It is the same mistake MOD.4b deleted from the launcher: a hand-assembled
classpath that cannot track a module change.

Fixed the same way: the prefix is now READ from the staged lib dir with the launcher's own
`find -maxdepth 1 -name '*.jar' | LC_ALL=C sort`, so it cannot drift again. And the
class-load count, previously a printed number, is now a **hard failure at zero** — the A/A
detector the round-842 trap argues for.

Re-run on the retrained cache:

| arm | classes | run | failed | ignored | wall | peak RSS |
|---|---:|---:|---:|---:|---:|---:|
| plain | 646 | 13,950 | 2 | 3 | 2:31.10 | 1,186 MB |
| cached | 646 | 13,950 | 2 | 3 | 2:18.33 | 2,021 MB |

**Per-class result diff EMPTY, failure lines included**, and **955 of 5,313 `com.xemantic`
classes served from the cache** (the remainder are test classes, which no cache contains).
The 2 failures are identical in both arms and are `HugeMethodLimitTest`'s two classpath-layout
pins (`main classes are not a directory on the classpath`) — green under Gradle, failing here
by construction because a jar is what a cache can be trained from. Round 839's other 11 are
gone from this count because `AotCacheGuardTest` moved to the daemon module at MOD.4 and this
harness runs the CORE module's test classes.

### 14.7 What (MOD.7) does NOT close

- **`build/bench/cp.txt` is still the pre-split, pre-bump dependency list**, and
  `ab-interleaved.sh` / `ab-warm.sh` / `cost_gate.py` / `aot-draw-variance.sh` all read it.
  Nothing here fixes those; they run from the exploded class dir and so can never carry an
  AOT arm anyway (§ 5.4), but a perf A/B taken against stale dependency jars is its own
  question and is left where it was found.
- `--watch`/`--incremental` under a cache, and the other seven dashboard profiles — the
  (AOT.5)(d)/(e) residue, untouched.
- The retrained cache is ONE draw (§ 12): its rank within the ±2.4% draw band is unsampled,
  and deliberately so — this round re-trains a layout, it does not compare configurations.
