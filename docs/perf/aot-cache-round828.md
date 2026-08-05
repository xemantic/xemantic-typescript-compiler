# The JDK 25 AOT cache, measured — (JIT.2a), round 828

> **SUPERSEDED IN PART.** The owner decided (JIT.2b) on 2026-08-05 — *build the
> invalidation, then ship* — and round 832 did: **`docs/perf/aot-cache.md` is the shipped
> design**, and § 7's hazard below is now closed by `scripts/xtsc`. Two corrections this
> file's readers need: the `AdapterHandlerEntry` warnings in § 4 are on **stdout**, not
> stderr (so they land in the diagnostics stream), and they are **nondeterministic** —
> round 832 saw two on one cache and none on another.

*Owner-approved measurement round. Nothing in the build system, the launcher defaults or
`src/` was changed: every arm is a plain `java` invocation over the already-built
`build/libs/xemantic-typescript-compiler-jvm-0.1.0-SNAPSHOT.jar`. Shipping is a packaging
decision and goes back to the owner (see § 8).*

Box: 8 cores / 15.6 GB / **swap 0**, OpenJDK **25.0.3**, Gradle and Kotlin daemons stopped
before every measurement (14.6 GB available), HEAD `3b4fa4cd`. All figures `--noEmit`
(check-only) on the compiler profile `build/bench/tsc-project-637d5746` (78 files, 46
errors) unless stated.

**Only the within-round paired deltas below are quotable.** Round 826 measured the same
sequential path at 23.2 s and round 823 at 25.3 s with byte-identical code; do not compare
this round's absolutes to theirs.

## 1. Headline

| arm | n | median self | min | max | sd | spread | median wall |
|---|---:|---:|---:|---:|---:|---:|---:|
| plain JVM (jar) | 6 | **22,223 ms** | 21,673 | 22,387 | 287 | 3.2% | 22,366 |
| **AOT cache, trained on the compiler profile** | 6 | **13,565 ms** | 13,308 | 14,295 | 310 | 7.3% | 13,680 |
| AOT cache, trained on a 1-file project | 6 | 20,908 ms | 20,539 | 21,464 | 331 | 4.4% | 21,019 |

6-rep rotated 3-arm interleave (18 cold runs, 21:48–21:53 UTC), box otherwise idle, the
agent not touching it. **Every one of the 18 runs reported 46 errors.**

| comparison | median delta | speedup | win rate | per-pair deltas | spread |
|---|---:|---:|---:|---|---:|
| **AOT (real-trained) vs plain** | **−8,658 ms (−38.96%)** | **1.638×** | **6/6** | −8995 −8071 −8086 −8585 −8902 −8190 | **924 ms** |
| AOT (tiny-trained) vs plain | −1,314 ms (−5.92%) | 1.063× | 6/6 | −1469 −1383 −209 −1428 −1039 −1223 | 1,260 ms |
| real-trained vs tiny-trained | −7,344 ms | 1.542× | 6/6 | — | — |

**The real-trained result is emphatically not noise**: a 924 ms per-pair spread against an
8,388 ms median per-pair delta, 6/6 sign-consistent, both arm sds under 320 ms. **The
tiny-trained result is sign-consistent 6/6 but its MAGNITUDE is soft** — the per-pair
spread (1,260 ms) is about equal to the median delta (1,303 ms), and one pair reads only
−209 ms. Quote its direction, not its size.

## 2. The win is NOT start-up, and the third arm is what proves it

The queue framed the AOT cache as a **start-up** lever. It is not — or at least, that is
not where the 8.7 s is. `wall − self` isolates everything before the compiler's own clock
starts (JVM boot, class loading, `main` dispatch):

| arm | median wall | median self | pre-compile |
|---|---:|---:|---:|
| plain | 22,366 | 22,223 | 143 ms |
| AOT, real-trained | 13,680 | 13,565 | **115 ms** |
| AOT, tiny-trained | 21,019 | 20,908 | 111 ms |

**The cache buys ~28 ms of start-up and ~8,630 ms inside the compile.** Class loading was
never 8 seconds of a 22-second run and could not have been.

The mechanism is JDK 25's **AOT method profiling** (JEP 515) layered on JDK 24's AOT class
loading and linking (JEP 483): the training run records execution profiles, and the
production run hands them to C2 at start-up, so hot methods are compiled early instead of
being discovered by the interpreter over the first ten seconds. **The tiny-trained arm is
the control that confirms this**, and it was worth its six runs: a 1-file compile loads and
links essentially the same classes, so it carries the whole JEP-483 half of the cache — and
it delivers **5.9%**, while the arm that also carries real checker profiles delivers
**39.0%**. *The prize is the profile, not the class loading.*

## 3. Cross-project transfer — the cache is a property of the COMPILER

The cache trained on the 78-file compiler profile, applied to the unrelated 1-file scratch
project (5 pairs, wall time, since self is ~0.45 s here):

| arm | median wall | deltas | win rate |
|---|---:|---|---:|
| plain | 1,324 ms | — | — |
| AOT (compiler-trained) | **560 ms** | −779 −785 −762 −754 −732 | **5/5** |

**−764 ms = −57.7%, a 2.36× speedup, with a 53 ms spread across the five deltas.** So a
cache trained on a big project transfers to a small one — the profiled methods are the
*compiler's*, not the project's. That matters for the product: the training project's
IDENTITY is irrelevant, only its SIZE (how much of the checker it exercises) is.

## 4. Correctness — gate A passes

`--noEmit --listAll`, identical command shape on both arms, `time:` line and JVM `[...]`
log lines stripped, sorted full-text diff:

- **DIFF EMPTY.** 54 diagnostic lines each, identical md5 `4caacf248ce417899c2972c16a82f1ed`.
- Both `FAILED — 46 error(s)`; `grep -c 'error TS'` = 46 on both.
- Neither capture contains `more error(s)` (round 811's truncation tell), and neither is
  empty (round 804).
- Independently, all 18 timing runs and both training runs reported 46 errors.

One cosmetic defect: **every cached run prints four JVM warnings to stderr**, e.g.
`[0.016s][warning][aot,codecache,stubs] Saved blob's name 'LLLLLLIILLLL' is different from
the expected name 'LLLLLLLIILLL'` / `Failed to link AdapterHandlerEntry ... to its code in
the AOT code cache`. They are JVM-internal adapter-stub linkage, not diagnostics, and they
do not affect output — but a shipped launcher would have to silence them, and any script
that diffs raw output must strip lines starting with `[` (this round's gate does).

## 5. Training cost

One-command training (`-XX:AOTCacheOutput=<file>`) internally records a configuration and
then spawns a child `-XX:AOTMode=create` JVM — it prints that child command line, so the
two-step `record`/`create` form needed no separate experiment.

| training workload | wall | of which the compile | cache size |
|---|---:|---:|---:|
| compiler profile (78 files) | **28.06 s** | 25.18 s | **51,187,712 B (48.8 MiB)** |
| 1-file scratch project | 3.36 s | 1.20 s | 36,835,328 B (35.1 MiB) |

**Training costs one ordinary compile plus ~2.9 s of assembly.** The cache is ~49 MB — but
note the 1-file cache is already 35 MB, so most of the file is the archived JDK+Kotlin
class graph, not our profiles.

## 6. What did NOT work — the exploded class directory

**The AOT cache cannot be dumped from this repo's normal classpath.** With
`-cp build/classes/kotlin/jvm/main:…`:

```
[47.722s][error][aot] Error: non-empty directory 'build/classes/kotlin/jvm/main'
Error occurred during CDS dumping
Cannot have non-empty directory in paths
```

CDS/AOT requires a **jar-only** classpath at dump time. Consequence, and it is the round's
most transferable finding: **`scripts/ab-interleaved.sh`, `scripts/ab-warm.sh` and
`scripts/bench-compile-tsc.sh` all run from the exploded class dir, so none of them can
carry an AOT arm as written.** This round used a purpose-built 3-arm driver over the jar
for every arm, which is also what round 811's same-command-shape law demands. It also means
the arc's published cold anchors (exploded-dir runs) are not a valid baseline for an AOT
comparison — hence the plain jar arm measured here.

The asymmetry is worth knowing: **only DUMPING refuses a directory. At RUNTIME the cache is
served happily to an exploded-dir classpath** (§ 7).

## 7. Staleness — the JVM does NOT invalidate, and this is the shipping risk

Probed by `-Xlog:class+load=info`, counting how many of the 926 `com.xemantic` classes load
with `source: shared objects file`:

| scenario | app classes from cache | outcome |
|---|---:|---|
| matching jar | 921/926 | OK |
| **classpath is the exploded dir, not the jar** | 921/926 | OK |
| **jar content changed** (entry appended, size 5,624,962 → 5,625,095) | 921/926 | OK |
| jar mtime bumped, content intact | 921/926 | OK |
| **jar content changed AND mtime fresh** (a realistic rebuild) | 921/926 | OK |

`-XX:AOTMode=on` (cache mandatory) changes none of these — the cache is opened and used in
every one.

**The decisive experiment.** `com/xemantic/typescript/compiler/Checker.class` was physically
removed from the jar, with a fresh mtime:

- **positive control, no cache:** `Exception in thread "main" java.lang.NoClassDefFoundError: com/xemantic/typescript/compiler/Checker`
- **with the cache:** exit 0, `OK — 0 errors`.

**A stale AOT cache silently runs the OLD bytecode.** There is no warning, no refusal, and
no exit code to notice it by. That is a correctness hazard for anyone who ships a cache
next to a jar and later replaces the jar.

## 8. Recommendation — SHIP, but the invalidation is OURS to build

**For:** this is the largest single-lever win the performance arc has measured, for zero
compiler change and byte-identical diagnostics — **1.64× on a 22-second project and 2.36×
on a small one**, transferring across projects, at a training cost of one compile.
Structurally it is the same prize the GraalVM image chases (removing the warm-up tax) with
none of the toolchain: no C compiler, no second build matrix, no separate artifact to test.

**Against / the conditions, all of which must be met before it ships:**

1. **Invalidation must be built by us, because the JVM provides none (§ 7).** The cache
   must be bound to the exact artifact — regenerate it at install time, name or validate it
   by the jar's checksum, and delete it on upgrade. Shipping a prebuilt cache beside a jar
   with no such discipline risks users silently running a previous release's code. This is
   the reason the recommendation is conditional rather than unqualified.
2. **The distribution must be a jar** (§ 6). That is already true of a released artifact;
   it is only our own bench scripts that are not.
3. **~49 MB of disk per install**, and a ~28 s training run against a representative
   project at install time (a 1-file training run is 3.4 s but buys only 5.9% on a large
   compile — § 1).
4. **The four stderr warnings must be silenced** in the launcher (§ 4).
5. **Per-machine, not per-OS build matrix.** The queue's framing holds: the cache is
   generated on the user's machine, so no cross-OS artifact grid is needed. *Cross-JDK-build
   and cross-machine portability of a cache file was NOT tested here* — the recommendation
   assumes generate-locally and does not rely on shipping a cache binary.

**Verdict: worth shipping, gated on (1).** The packaging work is the owner's call; queued
as `BLOCKED-PENDING-USER` under (JIT.2).

## 9. Residue — measured nothing about these

- **Emit mode.** Every figure is `--noEmit`, like the rest of the arc.
- **The other 7 profiles.** One profile, one project size; § 3 is the only transfer evidence.
- **The corpus suite under a cache.** Gate A (§ 4) plus 20 same-error runs is the evidence,
  and it is weaker than the suite — exactly the caveat § 7 of `aot-native-image.md` records
  for the native binary.
- **Cross-JDK-version / cross-machine cache portability**, and interaction with the warm
  `--serve` path and with `--workers N`.
- **A cache trained WITH `--workers 4`**, which profiles a different set of code paths.

## 10. Reproduction

```sh
JAR=build/libs/xemantic-typescript-compiler-jvm-0.1.0-SNAPSHOT.jar
CP="$JAR:$(cat build/bench/cp.txt)"          # jar-only: an exploded dir fails to dump
P=build/bench/tsc-project-637d5746

# train (one command; internally record + a child -XX:AOTMode=create)
java -XX:AOTCacheOutput=xtsc.aot -Xmx4g -cp "$CP" \
     com.xemantic.typescript.compiler.MainKt --noEmit "$P"

# production
java -XX:AOTCache=xtsc.aot -Xmx4g -cp "$CP" \
     com.xemantic.typescript.compiler.MainKt --noEmit "$P"
```

Useful probes: `-Xlog:aot=info` (`Opened AOT cache …`), `-Xlog:class+load=info` (`source:
shared objects file` per class — the only way to tell "the cache was opened" from "the
cache was used"), `-XX:AOTMode=on` to make the cache mandatory.
