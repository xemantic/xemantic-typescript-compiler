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
| GraalVM CE native-image, cold | **13,350 ms** (13,277 / 13,335 / 13,503) | **1.97×** |
| JVM warm steady state | **11,580 ms** | 2.27× |

**The AOT binary captures ~85% of the available warmup prize and gives up 15% against
JVM peak** — on a COLD one-shot run, which is how a CLI compiler is actually used.

Resident set: **392 MB** for the native binary against a 4 GB JVM heap allowance.
Binary size 55 MB. Image build time 2m 1s.

**Correctness: `--listAll` output is byte-identical to the JVM's**, 54 lines,
sorted-diff empty, 46 errors / 78 program files on every run. This is the gate — a
faster binary that answers differently is not the same compiler.

## 2. Why this number is credible

- The JVM cold figure (26,272 ms) is iteration 0 of the in-process warm probe, and it
  independently reproduces round 739's separately measured 26,896 ms cold check-only.
- The warm figure is the median of iterations 2–11 of `BenchMain`, every one of which
  reported `files=78 errors=46` — so no state leaked across iterations and all twelve
  timed the same compile. See that harness's own doc comment.
- Three native runs span 226 ms (1.7%), well inside any drift band.

## 3. Context — what this is worth against the arc

The entire performance arc (rounds 482–759) landed **−11.42%** (round 738's emit gate,
itself a scope correction) and **−4.53%** (round 736's narrowing memo). **This is −49%
of a cold compile for zero compiler changes**, and it is orthogonal to all of it: it
removes the JVM warmup tax, not the compiler's work.

Two consequences for the arc's method, neither yet acted on:

1. **Every A/B in the arc was measured cold**, i.e. against a 26.3 s baseline of which
   14.7 s is warmup. The same absolute saving is a much larger fraction of an 11.6 s
   warm run, and ten warm iterations fit in the time of four cold ones, so the standard
   error of the mean falls sharply. Items rejected as "in-band" — (CALL.4) at 441 ms
   "1.6%", the `getTypeOfExpression` ceiling at 823 ms "2.9%" — deserve re-examination
   under a warm null-pair calibration. **That calibration has NOT been done; do it
   before quoting any of this as a re-opening.**
2. It reframes the tsgo gap. Our steady-state COMPUTE is 11.6 s, not 26.3 s, so the
   architectural deficit to tsgo (2.36 s, emit-inclusive) is roughly 5×, not 13×.

## 4. Caveats — none of this is a claim yet

- **One profile, one box, three runs.** The 8-profile grid has not been run natively.
- **GraalVM CE has no PGO.** The 15% shortfall against JVM peak is the pessimistic
  case; Oracle GraalVM with profile-guided optimization would likely close part of it.
- **`-march=native` was not used** (the builder suggests it) — untested headroom.
- Max heap left at the default (80% of RAM); untuned.
- **Build integration is a Guardrails decision** (build system change) and was
  deliberately NOT done. This is a probe.

## 5. Reproduction

The blocker is that this box has **no C toolchain at all** — no gcc, no binutils, no
libc headers, no crt objects — and `sudo` needs a password. native-image fails in
`CCompilerInvoker` after 3.7 s without one. Assembling one unprivileged:

```sh
# 1. GraalVM CE for JDK 25 (matches the JDK the classes are built with)
curl -sLO https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.2.4/\
graalvm-community-jdk-25i2-25.0.4_linux-x64_bin.tar.gz && tar xzf graalvm-community-*.tar.gz

# 2. Toolchain from unprivileged package extraction. `apt-get download` works as
#    non-root; `dpkg -x` needs no root either. Download each package SEPARATELY —
#    one unresolvable name aborts a whole batch.
apt-get download binutils binutils-common binutils-x86-64-linux-gnu libbinutils \
  libctf0 libctf-nobfd0 libsframe1 libjansson4 gcc-13 gcc-13-base cpp-13 \
  libgcc-13-dev libcc1-0 libc6-dev libc-dev-bin linux-libc-dev libcrypt-dev \
  rpcsvc-proto zlib1g-dev libisl23 libmpc3 libmpfr6 libgmp10 libzstd1 \
  gcc-13-x86-64-linux-gnu cpp-13-x86-64-linux-gnu     # <- the last two are essential
for d in *.deb; do dpkg -x "$d" "$ROOT"; done
```

**Three traps, each of which looks like a different failure:**

1. **Ubuntu's `gcc-13` package contains only SYMLINKS** (494 KB, 40 entries) — the real
   binary is in `gcc-13-x86-64-linux-gnu`, and `cpp-13` splits the same way. Without
   them the wrapper dies with `gcc-13: not found` while `ls` shows `gcc-13` present.
2. **`cc1` needs the extracted `libisl.so.23` etc.**, so the wrapper must export
   `LD_LIBRARY_PATH=$ROOT/usr/lib/x86_64-linux-gnu`. Presents as
   `cc1: error while loading shared libraries`.
3. **`libc.so` is an ld SCRIPT with ABSOLUTE paths** and references
   `/usr/lib/x86_64-linux-gnu/libc_nonshared.a`, which exists only inside `$ROOT`.
   Rewrite the `/usr/lib/...` paths in `libc.so` (and any sibling script) to `$ROOT/...`;
   the `/lib/x86_64-linux-gnu/libc.so.6` runtime paths resolve on the real box and must
   be left alone. Presents as `ld: cannot find .../libc_nonshared.a`.

A `gcc` wrapper on PATH then supplies `-B`/`-isystem`/`-L` for the extracted tree, and
the smoke test is compiling a `#include <zlib.h>` hello against `-lz`.

```sh
native-image -cp "$MAIN_CLASSES:$(cat build/bench/cp.txt)" -o xtsc-native \
  --no-fallback -H:ConfigurationFileDirectories=<agent-config> -J-Xmx5g \
  com.xemantic.typescript.compiler.MainKt
```

**Reflection metadata is nearly empty and that is the headline for portability**: a
tracing-agent run (`-agentlib:native-image-agent=config-output-dir=…`) over a full
compile produces **1,338 bytes**, and all 18 entries are kotlinx-coroutines internals
(atomic field updaters). There is **zero application reflection** — the compiler embeds
its libs as Kotlin string constants rather than resources, so nothing needs resource
config either. `--no-fallback` succeeds first try.

## 6. What to do next, in order

1. **Warm null-pair calibration** (`BenchMain`, A vs A) — establishes the warm drift
   band, and decides whether the arc's "in-band" rejections were measurement artifacts.
2. **Native 8-profile grid** — the same byte-identity gate as § 1, all eight profiles,
   both lib arms. Until this passes, § 1 is a probe and not a result.
3. **`-march=native` and PGO** — the two untested sources of the remaining 15%.
4. **Only then** the build-integration decision, which is the owner's.
