# AOT — GraalVM native-image, measured

*Round 771. Owner-directed investigation ("the fastest TypeScript compiler"), not a
queue item. Nothing in the build system was changed: the image is built from the
existing `build/classes/kotlin/jvm/main` + `build/bench/cp.txt` classpath by a
standalone `native-image` invocation.*

## 0. The artifact points — all FIVE, and how to read them (index, round 828)

This document is the arc's artifact-point index. There are **five** ways to run the
compiler; each was measured in a different round, so **the absolutes below are NOT
comparable to each other** — only a within-round paired delta ever is (round 826 measured
the same sequential path 12.8% apart from round 824's with identical code). Read the column
as "what this artifact was worth *in its own round*".

| artifact point | figure | round / box | authority |
|---|---:|---|---|
| JVM, cold single-shot | 26.3 s (r771) / 25.3 s (r823) / 22.2 s (r828, from the jar) | 771 / 823 / 828 | § 1, § 2c, `aot-cache-round828.md` § 1 |
| JVM, warm steady state (`--serve`) | ~~11.6 s~~ — **SUPERSEDED, round 843 (2026-08-07): ~7.0 s** (BenchMain medians 7.14/6.92 s; `--serve` ladder 7.10–7.45 s) | 771, 4-core box / 843, 8-core box | **`docs/perf/warm-jvm-attribution.md`**, then § 1, § 4 |
| GraalVM CE native-image | ~~13.4 s~~ — **SUPERSEDED as the authority, see § 0a: CI median 11.2 s check-only / 13.4 s emit over 75 rows** | 771, RETIRED 4-core box / CI ubuntu-latest since 2026-07-30 | **§ 0a**, then § 1, § 2 |
| Kotlin/Native release | 21.8 s (r772) / 20.0 s (r823) | 772 / 823 | § 2b, § 2c |
| **JDK 25 AOT cache** | **13.6 s — 1.638× its own round's plain arm, 6/6 wins**; **re-measured on the POST-SPLIT layout, round 857: 15.2 s = 1.600×, 4/4 pairs** | 828 / 857, 8-core box | **`docs/perf/aot-cache-round828.md`**, then `aot-cache.md` § 14 |

**The AOT cache is the newest point and the cheapest one to obtain**: no second toolchain,
no separate binary, byte-identical diagnostics, trained by one ordinary compile plus ~2.9 s.
Two things a reader must carry away from its file rather than this table: **the win is not
start-up** (~28 ms of it is; the other 8.6 s is C2 compiling early from recorded profiles),
and **the JVM never invalidates a stale cache** — a jar whose classes have changed is
silently ignored in favour of the cached bytecode, which is why shipping it is conditional.

## 0a. The native arm is measured CONTINUOUSLY now, and it is at tsc PARITY (round 841)

*Added round 841 (2026-08-07) during a documentation-drift audit. Nothing below contradicts
a measurement in this file; what it corrects is which measurement is the AUTHORITY, and it
adds the one number this file never stated.*

**Since commit `a1ff6033` (2026-07-30) the CI bench builds the GraalVM image from source on
every push and runs it as a FOURTH arm, in BOTH modes** (`.github/workflows/bench.yml`
"Build the AOT binary" + `--xtsc-native`; `continue-on-error`, so a failed image publishes
a 3-way row with `—` instead of silently substituting something else). So the 4-core box's
three runs are no longer the best evidence about this artifact — **75 rows × 2 modes = 150
native/tsc ratios** are, on ubuntu-latest, GraalVM CE for JDK 25.

| mode | native median | tsc median | **native ÷ tsc** | ≤1.05× | ≤1.10× | >1.20× |
|---|---:|---:|---:|---:|---:|---:|
| check-only (`--noEmit`) | **11.20 s** | 10.68 s | **1.04×** | 41/75 | 49/75 | 4/75 |
| emit | **13.43 s** | 13.10 s | **1.01×** | 47/75 | 61/75 | 2/75 |
| **both pooled** | — | — | **1.02×** (min 0.89, max 1.32) | 88/150 | 110/150 | 6/150 |

Latest row at the time of writing (`e355a990bfaa`, 2026-08-07): check-only native **10.54 s
vs tsc 10.57 s = 1.00×**; emit native **12.84 s vs tsc 13.23 s = 0.97×** — i.e. *faster than
tsc*, in the mode that does the most work.

**THE FRAMING CORRECTION, AND IT IS THE POINT OF THIS SECTION.** "xtsc is ~2.4× tsc" is a
statement about the **JVM cold single-shot arm** and it remains true (CI check-only median
**2.51×**, latest row 2.41×; emit median 2.25×, latest 2.30×). It is **not** a statement
about the compiler, because the *same compiler* shipped as a native image measures **1.02×
tsc**. Parity is artifact-scoped — this file has said so since round 771 — but the arc has
been quoting the JVM ratio as *the* gap while a parity-level artifact was being measured
continuously in CI and never entered this index. Whenever a ratio is quoted, name the ARM
as well as the MODE.

Three caveats a reader must carry, so this table is not over-read in the other direction:

- **It is one row per push, xtsc median-of-1 against tsc median-of-3, on a shared GitHub
  runner.** The spread is real (native/tsc ranges 0.89–1.32) and the *medians* are the
  claim, never a row. The ±13% box-noise rule applies here exactly as everywhere else.
- **`13.4 s` was never wrong and is not retracted** — it is a 2026-07 measurement of three
  runs on a 4-core box that no longer exists, and it happens to sit close to the CI *emit*
  median. What is corrected is its status as the authority, and § 2's speedup RATIOS (1.71–
  1.98× the cold JVM) are likewise that box's; CI's native-vs-JVM ratio medians are 2.41×
  check-only / 2.18× emit, because the JVM arm on a shared runner is slower, not because the
  image got faster.
- **CI exercises a ONE-SHOT compile only.** It does not run `--serve`/`--daemon` on the
  image, does not diff the native output against the JVM's (that is `native.yml`'s job, and
  it is `workflow_dispatch`-gated), and reports only the error COUNT — 46, matching the JVM
  arm on every row. Round 771's § 2 byte-identity grid is still the only output-identity
  evidence, and it was taken on the retired box from a `MainKt` image.

## 1. The result

Compiler profile, `--noEmit` (check-only), this box (4 cores, 7.7 GB), daemons stopped.
**Round-841 note: "this box" is the RETIRED 4-core host, and these three runs are no longer
the authority for the native arm — see § 0a for the 75-row CI series. The numbers below
stand as taken; only their standing changed.**

| mode | wall | vs cold JVM |
|---|---:|---:|
| JVM cold single-shot | **26,272 ms** | 1.00× |
| GraalVM CE native-image, cold | **13,350 ms** | **1.97×** |
| ~~JVM warm steady state~~ | ~~**11,580 ms**~~ | 2.27× |

**Round-843 note (2026-08-07), warm row only.** The warm ABSOLUTE is
**superseded**: re-measured on the current 8-core box the warm steady state is
**~7,030 ms** (two BenchMain process-medians, 7,143.2 / 6,916.7 ms;
independently reproduced at 7.1–7.45 s by a real `--serve` socket ladder), while
the cold anchor re-measures at 22,971 ms. **The `2.27×` column is left exactly
as taken and is NOT recomputed**: it is a WITHIN-round paired ratio
(26,272 / 11,580) from round 771's own BenchMain run on the retired 4-core box,
and CLAUDE.md's rule is that only within-round pairs are quotable — mixing a
2026-07 numerator with a 2026-08 denominator would manufacture a number neither
round measured. Today's within-round equivalent, both arms taken in round 843,
is **22,971 / 7,030 = 3.27×**. The cause of the warm movement is **unattributed**
— see `docs/perf/warm-jvm-attribution.md` § 2.1, which names the (JIT.1)
huge-method arc as the leading hypothesis and states plainly that the confirming
warm A/B was not run.

**The AOT binary captures ~85% of the available warmup prize and gives up 15% against
JVM peak** — on a COLD one-shot run, which is how a CLI compiler is actually used.
Resident set **392 MB** against a 4 GB JVM heap allowance; binary 55 MB; build 2m 1s.

## 2. The 8-profile gate — every profile byte-identical

`--noEmit --listAll`, JVM vs native, sorted full-text diff (the `time:` line stripped).
**A count-only comparison is not a gate — two runs can agree on 46 and disagree on
which 46.**

| profile | files | JVM ms | native ms | speedup | saved | errors | diff |
|---|---:|---:|---:|---:|---:|---:|---|
| tsc | 80 | 27,110 | 13,714 | 1.98× | 13.4 s | 46 | IDENTICAL |
| compiler | 78 | 26,288 | 13,399 | 1.96× | 12.9 s | 46 | IDENTICAL |
| deprecatedCompat | 81 | 25,699 | 13,320 | 1.93× | 12.4 s | 46 | IDENTICAL |
| typingsInstallerCore | 88 | 26,316 | 13,641 | 1.93× | 12.7 s | 46 | IDENTICAL |
| jsTyping | 84 | 25,977 | 13,553 | 1.92× | 12.4 s | 46 | IDENTICAL |
| services | 252 | 34,142 | 18,190 | 1.88× | 16.0 s | 46 | IDENTICAL |
| server | 274 | 35,783 | 20,876 | 1.71× | 14.9 s | 46 | IDENTICAL |
| harness | 312 | 35,898 | 20,954 | 1.71× | 14.9 s | 94 | IDENTICAL |

Two things make this more than eight green rows. **The error grid is
46/46/46/46/46/46/46/94, which reproduces the dashboard's known composition exactly** —
so the seven freshly materialized profiles are correct, not merely self-consistent. And
**the saving is roughly CONSTANT in absolute terms (12.4–16.0 s) across a 4× range of
project size**, which is the signature of a fixed warmup cost: the speedup RATIO falls
(1.98× → 1.71×) precisely because the denominator grows while the saving does not.
A per-node or per-file cost would not behave that way.

## 2b. Kotlin/Native measured against it — GraalVM wins by 1.63×

*Round 772. Both are AOT; they are different backends (SubstrateVM vs LLVM), and the
question "should we also ship a Kotlin/Native binary" needed a number rather than a
preference.* Compiler profile, `--noEmit`, same box, `linkReleaseExecutableLinuxX64`.
**Round-841 note: this is a WITHIN-round paired comparison and is unaffected by § 0a — the
Graal-vs-K/N verdict (1.63×) stands. Only the GraalVM row's ABSOLUTE is superseded as an
artifact-point authority; do not carry `13,350 ms` out of this table as a current figure.**

| runtime | wall | vs JVM cold | vs Graal | RSS | binary |
|---|---:|---:|---:|---:|---:|
| JVM, cold single-shot | 26,272 ms | 1.00× | 0.51× | — | — |
| ~~JVM, warm steady state~~ | ~~11,580 ms~~ | 2.27× | 1.15× | — | — |
| **GraalVM native-image** | **13,350 ms** | **1.97×** | 1.00× | **392 MB** | 55 MB |
| **Kotlin/Native release** | **21,787 ms** | 1.21× | **0.61×** | 1,190 MB | **27 MB** |

**Kotlin/Native is byte-identical to the JVM** (sorted `--listAll` diff empty, 46 errors)
and is **1.63× SLOWER than GraalVM**, at **3× the resident memory**, in **half the binary
size**. Its timings are extremely stable — 21.83 / 21.88 / 21.83 s, a 0.2% spread, against
the JVM's JIT-driven variance — which is what an AOT binary with no warm-up should look
like.

**The prior stated in the queue before measuring was correct and is now confirmed**: a type
checker is allocation-heavy (AST nodes, interned types), and K/N's optimizer and GC trail
Graal's on exactly that profile — the 3× RSS is the same finding seen from the memory side.
Note also that K/N barely beats the JVM *cold* (1.21×) and is well behind the JVM *warm*
(~~11.6 s~~ — **round 843, 2026-08-07: ~7.0 s**, see § 1's warm-row note and
`docs/perf/warm-jvm-attribution.md`; the point this sentence makes gets *stronger*, not
weaker): it removes warm-up, then gives most of the gain back in weaker codegen.

**So GraalVM is the shipping path for speed.** Kotlin/Native's remaining argument is
reach — a 27 MB binary with no JVM anywhere, on platforms Graal would need its own
toolchain for — and `linuxX64Test`, which would run the corpus natively. Neither is a
performance argument.

## 2c. (INV.7b) closed — re-measured round 823 on the 8-core box

*Round 823, HEAD `8c8f5511`, on the upgraded host (8 cores / 15.6 GB, swap still ZERO).
The queue item had stood BLOCKED-ON-RESOURCES since round 610b and PARKED-BY-OWNER since
617; the owner unparked it. Note that round 772 had in fact already linked and measured a
release binary — the item was simply never checked off.*

**THE ONE CORRECTION A READER NEEDS FIRST.** The queue quotes "a native one at **13.4 s**
(round 775)" as this arc's native artifact point. **That is the GraalVM native-image number
(§ 1), not a Kotlin/Native one, and round 775 is not where it came from — it is § 2's `tsc`
row from round 771.** Kotlin/Native release has never been near 13 s: round 772 measured
21.8 s and round 823 measures 20.0 s. Three AOT artifacts exist (JVM warm `--serve`,
GraalVM image, K/N binary) and only one of them is 13 s.

### The link

`linkReleaseExecutableLinuxX64` at the committed `org.gradle.jvmargs=-Xmx4g`, daemons
stopped first, nothing else running: **BUILD SUCCESSFUL in 8m53s** (round 772 saw 8m05s on
the old box — the optimizing link is LLVM-bound, not core-bound, so eight cores buy it
nothing). Binary **27,493,088 bytes (26.2 MiB)**, against the debug binary's 62,058,232.

Sampled every 5 s across 107 samples: **peak system used 6,083 MB, lowest available
9,530 MB**, peak single-process RSS **4.40 GB** (the Gradle daemon, at its 4g ceiling —
so unlike the *test* link of round 822, which reached 8.5 GB RSS outside its heap, the
release link of the main executable stays roughly inside `-Xmx`). **This link had ~9.5 GB
of headroom and was never close to the OOM-killer**, which retires round 610b's
BLOCKED-ON-RESOURCES verdict on the evidence rather than by assertion.

### Correctness — byte-identical at the 46-error floor

`--noEmit --listAll` on the compiler profile, JVM vs native release, `time:` line stripped,
sorted full-text diff: **EMPTY**. Both `FAILED — 46 error(s)`, 55 output lines each, and
neither capture contains `more error(s)` (round 811's truncation tell). RSS for the single
verification run: JVM 855 MB at `-Xmx4g`, native **1,102 MB**.

### The bench row

Five interleaved COLD pairs, compiler profile, `--noEmit`, box otherwise idle, every one of
the ten runs reporting 46 errors:

| arm | n | wall median | min | max | sd | spread |
|---|---:|---:|---:|---:|---:|---:|
| JVM cold single-shot | 5 | **25,299 ms** | 24,884 | 27,201 | 867 | 9.2% |
| Kotlin/Native release | 5 | **20,045 ms** | 19,239 | 21,510 | 783 | 11.3% |

**Native is 1.26× a cold JVM — a 5.25 s saving — and remains ~1.5× SLOWER than the GraalVM
image and ~1.7× slower than the JVM warm steady state.** Round 772's ratio (1.21×)
reproduces; both absolute numbers came down ~4–8%, which the box change and 50 rounds of
compiler work cannot be separated into.

**One round-772 claim does NOT reproduce: "its timings are extremely stable — a 0.2%
spread".** Round 823 measures an 11.3% spread over five runs (19.2–21.5 s), *wider* than
the JVM's 9.2% in the same interleave. Three runs are not a spread; do not quote AOT
determinism from n=3, and note that the argument built on it — "which is what an AOT binary
with no warm-up should look like" — has no support here.

### Verdict

(INV.7b) is **DONE and its conclusion is unchanged**: K/N remains a *reach* artifact
(26 MiB, no JVM, corpus runs natively per round 822), never a speed one. Nothing here
disturbs § 2b's ranking, and the shipping-artifact question stays the owner's (AOT.1).

## 3. `-O3 -march=native` buys nothing — and that is informative

| build | median of 3 |
|---|---:|
| default (`-O2`, portable) | 13,335 ms |
| `-O3 -march=native` | 13,325 ms |

**0.07%, far inside noise.** So the residual 15% against JVM peak is NOT instruction
selection or vectorization — it is the absence of **profile-guided optimization**, the
inlining and branch-layout decisions C2 makes from a live profile. GraalVM CE cannot do
PGO; only Oracle GraalVM can. Do not spend further rounds on codegen flags.

## 4. The warm drift band, calibrated — ±1.0%, not ±2.0%

Six independent A/A processes (`BenchMain`, warmup 2, 8 measured iterations each),
every one reporting `files=78 errors=46`:

```
medians: 11,722  11,831  11,779  11,664  11,382  11,429 ms   (sd 187)
A/A pair deltas: +0.93%   -0.98%   +0.41%
```

**Warm band ≈ ±1.0% = ±114 ms**, against the cold protocol's ±2.0% = ±536 ms — the warm
protocol is **4.7× more sensitive in absolute terms**, and ten warm iterations fit in
the time of four cold runs.

> **Round-843 note (2026-08-07) — the DENOMINATOR of this whole section moved.** Every
> percentage below is against an 11,580 ms warm run; the warm run is now **~7,030 ms**
> (`docs/perf/warm-jvm-attribution.md` § 2). The band as a PERCENTAGE (±1.0%, calibrated
> from a spread) is unaffected and re-calibration is not required; what changes is its
> absolute width — **±1.0% is now ±70 ms, not ±114 ms** — and the "warm" column of the
> rejected-items table below, whose entries all grow by a factor of **1.65**
> (e.g. (CALL.4)'s 441 ms reads **6.3%**, not 3.8%; the `getTypeOfExpression` ceiling
> **11.7%**, not 7.1%). **The table is left as taken rather than rewritten**, because its
> ms column is a set of COLD measurements and re-basing them on a warm denominator that
> was never paired with them would be exactly the cross-round arithmetic this file warns
> against in § 0 — but a reader re-opening one of these items must apply the 1.65 and
> re-measure warm, not quote the printed percentage.

### 4a. Reproduced round 774 — and the band is a property of a QUIET box

`scripts/ab-warm.sh` (round 774, (AOT.2)) is the driver for this protocol. Its A/A
validation ran the identical shape twice — 3 pairs, warmup 2, 8 iterations, same class
dir on both arms, all 48 iterations reporting `files=78 errors=46`:

| run | conditions | A/A pair deltas | process-median sd |
|---|---|---|---|
| 1 | agent polling the log ~100× while it measured | −0.19% / **−6.70%** / +2.47% | 278 ms (2.4%) |
| 2 | box left completely alone | −0.36% / −0.54% / +1.02% | **48 ms (0.41%)** |
| round 771 | (six A/A processes) | +0.93% / −0.98% / +0.41% | 187 ms |

**Run 2 reproduces the ±1.0% band and beats it; run 1 does not come close, on the same
binary.** So the band is not a property of this box — it is a property of a box nobody
is touching, which follows directly from round 740's "a single xtsc run already consumes
~3.15 of 4 cores": the spare capacity a `tail` of the log eats is the same capacity the
measurement needs. **Both runs correctly report NOISE-DOMINATED**, which is what an A/A
should say; the discriminator between them is the per-arm sd the driver prints, not the
verdict. Quote a warm number only from a run whose arm sd is ≲1%.

**Consequence for the arc's rejected items.** Several were rejected as "inside the
band" when measured cold. Against a ±114 ms warm band:

| item | size | cold verdict | warm |
|---|---:|---|---|
| (CALL.4) `applyConditionNarrowing` residue | 441 ms | 1.6%, in-band | **3.8% = 3.9× the band** |
| `getTypeOfExpression` perfect-cache ceiling | 823 ms | 2.9% | **7.1%** |
| single-visit discipline | 670 ms | 2.3% | **5.8%** |
| the identity pre-test round 736 rejected | ~410 ms | in-band | **3.5%** |
| (DISPATCH.1) per-kind table, realistic | 100–300 ms | 0.4–1.1% | 0.9–2.6%, marginal |
| globals lookups (AUDIT.3) | 36–71 ms | 0.13–0.26% | **still dead** |

**MEASURABLE IS NOT WORTH LANDING.** None of these got bigger; only our ability to
see them changed. Two cautions before anyone re-opens one: the round-755 finding that
(CALL.4)'s population is a type-predicate RESOLUTION (M3.1 work, not machinery) is
untouched by this, and round 736's identity pre-test was rejected as **UNSOUND**, not
merely small — that verdict stands regardless of band.

## 5. Why these numbers are credible

- The JVM cold figure is iteration 0 of the in-process warm probe, and it independently
  reproduces round 739's separately measured 26,896 ms cold check-only.
- Every warm figure comes from a run whose every iteration reported `files=78
  errors=46`, so no state leaked across iterations and all of them timed the same
  compile.
- The native binary's output is byte-identical to the JVM's on all eight profiles.
- Three native runs span 226 ms (1.7%).

## 6. Context — what this is worth against the arc

The entire performance arc (rounds 482–759) landed **−11.42%** (round 738's emit gate,
itself a scope correction) and **−4.53%** (round 736's narrowing memo). **This is −49%
of a cold compile for zero compiler changes**, and it is orthogonal to all of it: it
removes the JVM warmup tax, not the compiler's work.

It also reframes the tsgo comparison. Our steady-state COMPUTE is 11.6 s, not 26.3 s,
so the architectural deficit to tsgo (2.36 s, emit-inclusive) is roughly **5×, not
13×** — and a Kotlin port of tsgo would **inherit the warmup tax this removes for
free**, since it would be the same JVM one-shot process.

## 7. Caveats

*Round-841 audit: two of these five were overtaken by CI and are struck below; three stand.*

- **CE has no PGO.** The 15% shortfall against JVM peak is the pessimistic case. **(STANDS.)**
- ~~One box, three runs per configuration. The grid is single-run per profile.~~
  **SUPERSEDED round 841 for the timing claim only** — 75 CI rows × 2 modes since
  2026-07-30 (§ 0a). The *grid* caveat stands: § 2 is still single-run per profile, on the
  retired box, and CI does not re-run it.
- ~~Emit mode not measured natively; all figures are `--noEmit`.~~ **SUPERSEDED round 841**
  — CI has published a native EMIT arm on every row since 2026-07-30 (median 13.43 s,
  **1.01× tsc**, § 0a). This was § 9's "what to do next" item 2, and it is done.
- **Build integration is a Guardrails decision** and was deliberately NOT taken. **(STANDS
  as a shipping decision — (AOT.1) — though the bench workflow does build the image on
  every push, so the `nativeImage` Gradle task itself is long since integrated.)**
- The corpus suite has NOT been run against a native binary (it is a JVM test harness);
  the 8-profile byte-identity grid is the evidence, and it is weaker than the suite.
  **(STANDS. Note the corpus HAS since been run against the JDK 25 AOT cache — round 839's
  `scripts/aot-corpus-suite.sh` — but that is a different artifact, and CI's native arm
  checks only that the error COUNT is 46.)**

## 8. Reproduction

The blocker is that this box has **no C toolchain at all** — no gcc, no binutils, no
libc headers, no crt objects — and `sudo` needs a password. native-image fails in
`CCompilerInvoker` after 3.7 s without one. Assembling one unprivileged:

```sh
# 1. GraalVM CE for JDK 25 (matches the JDK the classes are built with)
curl -sLO https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.2.4/\
graalvm-community-jdk-25i2-25.0.4_linux-x64_bin.tar.gz && tar xzf graalvm-community-*.tar.gz

# 2. Toolchain by unprivileged package extraction. `apt-get download` and `dpkg -x`
#    both work as non-root. Download each package SEPARATELY — one unresolvable
#    name aborts a whole batch.
apt-get download binutils binutils-common binutils-x86-64-linux-gnu libbinutils \
  libctf0 libctf-nobfd0 libsframe1 libjansson4 gcc-13 gcc-13-base cpp-13 \
  libgcc-13-dev libcc1-0 libc6-dev libc-dev-bin linux-libc-dev libcrypt-dev \
  rpcsvc-proto zlib1g-dev libisl23 libmpc3 libmpfr6 libgmp10 libzstd1 \
  gcc-13-x86-64-linux-gnu cpp-13-x86-64-linux-gnu     # <- the last two are essential
for d in *.deb; do dpkg -x "$d" "$ROOT"; done
```

**Three traps, each presenting as a different failure:**

1. **Ubuntu's `gcc-13` package contains only SYMLINKS** (494 KB, 40 entries) — the real
   binary is in `gcc-13-x86-64-linux-gnu`, and `cpp-13` splits the same way. Without
   them the wrapper dies with `gcc-13: not found` while `ls` shows `gcc-13` present.
2. **`cc1` needs the extracted `libisl.so.23` etc.**, so the wrapper must export
   `LD_LIBRARY_PATH=$ROOT/usr/lib/x86_64-linux-gnu`. Presents as
   `cc1: error while loading shared libraries`.
3. **`libc.so` is an ld SCRIPT with ABSOLUTE paths** referencing
   `/usr/lib/x86_64-linux-gnu/libc_nonshared.a`, which exists only inside `$ROOT`.
   Rewrite the `/usr/lib/...` paths in it (and any sibling script) to `$ROOT/...`; the
   `/lib/x86_64-linux-gnu/libc.so.6` runtime paths resolve on the real box and must be
   left alone. Presents as `ld: cannot find .../libc_nonshared.a`.

A `gcc` wrapper on PATH then supplies `-B`/`-isystem`/`-L` for the extracted tree; the
smoke test is compiling a `#include <zlib.h>` hello against `-lz`.

```sh
native-image -cp "$MAIN_CLASSES:$(cat build/bench/cp.txt)" -o xtsc-native \
  --no-fallback -H:ConfigurationFileDirectories=<agent-config> -J-Xmx5g \
  com.xemantic.typescript.compiler.MainKt
```

**Reflection metadata is nearly empty, and that is the headline for portability**: a
tracing-agent run (`-agentlib:native-image-agent=config-output-dir=…`) over a full
compile produces **1,338 bytes**, all 18 entries kotlinx-coroutines internals (atomic
field updaters). There is **zero application reflection** — the compiler embeds its libs
as Kotlin string constants rather than resources, so no resource config is needed
either. `--no-fallback` succeeds first try.

## 9. What to do next

*Round-841 status pass over the round-771 list.*

1. **Adopt the warm protocol for compiler-level A/B** (`BenchMain`, ≥6 iterations after
   2 warmups, medians across processes). ±1.0% instead of ±2.0%, and faster per sample.
   **DONE — `scripts/ab-warm.sh`; the band is calibrated in § 4/§ 4a.**
2. ~~**Emit-mode native measurement**, so the published CI ratio can be restated in the
   mode it is actually measured in.~~ **DONE 2026-07-30 (commit `a1ff6033`) — CI publishes
   BOTH modes on all four arms; § 0a has the series.** It answered more than it was asked:
   the native arm is at **tsc parity in both modes** (1.04× / 1.01×).
3. **PGO** — needs Oracle GraalVM; the only remaining source of the 15%. **STILL OPEN.**
4. **Build integration**, which is the owner's decision, and with it the question of
   whether the shipped artifact is a native binary, a JVM jar, or both. **STILL OPEN as
   the SHIPPING decision ((AOT.1)); the Gradle `nativeImage` task exists and CI runs it on
   every push, so only the packaging/owner half remains.**
5. **NEW, round 841 — the native arm's remaining evidence gaps**, all of them things CI
   deliberately does not do: no output DIFF against the JVM per row (only the error count),
   `--serve`/`--daemon` never run on a native image at all (the `UnixDomainSocketAddress`
   closed-world question from round 840(b) is still open), and § 2's byte-identity grid has
   not been re-taken since the entry point moved to `server.XtscMainKt`.
