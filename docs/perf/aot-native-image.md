# AOT — GraalVM native-image, measured

*Round 771. Owner-directed investigation ("the fastest TypeScript compiler"), not a
queue item. Nothing in the build system was changed: the image is built from the
existing `build/classes/kotlin/jvm/main` + `build/bench/cp.txt` classpath by a
standalone `native-image` invocation.*

## 1. The result

Compiler profile, `--noEmit` (check-only), this box (4 cores, 7.7 GB), daemons stopped.

| mode | wall | vs cold JVM |
|---|---:|---:|
| JVM cold single-shot | **26,272 ms** | 1.00× |
| GraalVM CE native-image, cold | **13,350 ms** | **1.97×** |
| JVM warm steady state | **11,580 ms** | 2.27× |

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

| runtime | wall | vs JVM cold | vs Graal | RSS | binary |
|---|---:|---:|---:|---:|---:|
| JVM, cold single-shot | 26,272 ms | 1.00× | 0.51× | — | — |
| JVM, warm steady state | 11,580 ms | 2.27× | 1.15× | — | — |
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
(11.6 s): it removes warm-up, then gives most of the gain back in weaker codegen.

**So GraalVM is the shipping path for speed.** Kotlin/Native's remaining argument is
reach — a 27 MB binary with no JVM anywhere, on platforms Graal would need its own
toolchain for — and `linuxX64Test`, which would run the corpus natively. Neither is a
performance argument.

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

- **CE has no PGO.** The 15% shortfall against JVM peak is the pessimistic case.
- One box, three runs per configuration. The grid is single-run per profile.
- Emit mode not measured natively; all figures are `--noEmit`.
- **Build integration is a Guardrails decision** and was deliberately NOT taken.
- The corpus suite has NOT been run against a native binary (it is a JVM test harness);
  the 8-profile byte-identity grid is the evidence, and it is weaker than the suite.

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

1. **Adopt the warm protocol for compiler-level A/B** (`BenchMain`, ≥6 iterations after
   2 warmups, medians across processes). ±1.0% instead of ±2.0%, and faster per sample.
2. **Emit-mode native measurement**, so the published CI ratio can be restated in the
   mode it is actually measured in.
3. **PGO** — needs Oracle GraalVM; the only remaining source of the 15%.
4. **Build integration**, which is the owner's decision, and with it the question of
   whether the shipped artifact is a native binary, a JVM jar, or both.
