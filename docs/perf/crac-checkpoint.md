# Snapshotting a warmed-up JVM — CRaC measured, 2026-08-10

## 0. The one-line result

**A CRaC checkpoint taken after 8 warm compiles restores in ~30 ms and its FIRST
compile runs at full warm speed — 6.8–7.3 s against 24–25 s cold on the same
JDK, same binary, same session. That is 3.4×, against the JDK 25 AOT cache's
1.64×, and the restored run's diagnostics are byte-identical.**

It is not shippable as-is. Read § 5 before quoting any of this: the restored
process keeps the **checkpoint's working directory**, which is the round-873
defect class exactly, and the cold-disk restore was never measured.

## 1. Why this was asked

Three different features get called "AOT" or "snapshot" and only one of them
stores compiled code:

| feature | what it stores | in our JDK? |
|---|---|---|
| JDK 25 AOT cache (JEP 483 + 515) | class loading/linking + **method profiles** | yes — shipped, `scripts/xtsc-aot` |
| Leyden AOT **code** caching | compiled code | **no** — `-XX:+AOTCodeCaching` is an unrecognized option |
| CRaC | full process image incl. **JIT code and heap** | not in the stock JDK; needs a CRaC build |

The AOT cache is profiles, not code, which is exactly why `docs/perf/aot-cache.md`
§ 7.4 measured it as "halves the first request, worth nothing warm". CRaC is the
one that answers "snapshot something already HotSpotted".

## 2. Setup

- **JDK**: Zulu 25.34.17-ca-crac (`jdk25.0.3`), i.e. the same Java version as the
  stock JDK on this box, so the swap changes the vendor and the CRaC support and
  not the language level. `javaTarget = 17`, so any JDK ≥ 17 can run our classes.
- **Engine**: `CRaCEngine` defaults to **`warp`**, Azul's userspace engine.
  **This box has no root and `CapEff: 0000000000000000`, and warp checkpointed
  and restored anyway** — the CRIU engine would have needed `CAP_CHECKPOINT_RESTORE`
  and `criu` is not even installed here.
- **Driver**: `CracBench` / `CracCheck` (scratchpad, not committed) — a Java main
  calling `com.xemantic.typescript.compiler.MainKt.runCli(["--noEmit", proj])` in
  a loop, with `jdk.crac.Core.checkpointRestore()` between the warm and measured
  phases. No sockets, so the checkpoint has no open-resource problem to solve.
- **Workload**: the 78-file `compiler` profile, `--noEmit`.

## 3. The numbers

Warm-up ladder on this JDK, no checkpoint (11 compiles, one process):

```
23,616  9,895  7,431  6,694  6,636  6,576  6,422  6,365  6,229  6,171  6,346
```

Checkpoint after 8 warm compiles, then restore twice from the same image:

| | restore 1 | restore 2 |
|---|---:|---:|
| JVM uptime when `main` resumes | 27 ms | 30 ms |
| **first compile after restore** | **6,812 ms** | **7,242 ms** |
| 2nd / 3rd / 4th | 6,555 / 6,730 / 6,942 | 6,728 / 6,484 / 6,464 |

A third restore, run from a **different working directory**, gave 7,316 ms.

So the first compile after restore is indistinguishable from the plateau
(~6.2–6.8 ms band on this JDK), where the first compile of a fresh JVM is
23.6–25.3 s. **Within-round, same binary: 3.4×.**

Costs: the image is **340–356 MB** on disk, and taking the checkpoint costs
**~240 ms**.

Cross-round context, NOT a within-round comparison and therefore not quotable as
a delta (CLAUDE.md's rule): the AOT cache measured 1.638× on this profile
(`aot-cache.md` § 4) and a warm `--serve` daemon plateaus ~6.5–7.0 s.

## 4. Correctness

`--noEmit --listAll` output of a restored run against a plain run of the same
binary: **identical apart from the `time:` line** (54 lines, 46 diagnostics,
`diff` empty once `^time:` is filtered). The suite was also run end to end on
the CRaC JDK — **14,234 tests, 0 failures, 3 skipped** — so the vendor swap
itself is behaviour-free.

## 5. Why it is not shippable yet

**(a) The restored process keeps the CHECKPOINT's working directory.** Measured
directly: checkpoint taken in `/home/claude/git/xemantic-typescript-compiler`,
restore launched from `/tmp`, and after restore both `user.dir` and
`new File(".").getCanonicalPath()` still read the checkpoint's directory.

This is **round 873's defect, one layer down**. That round found
`xtsc --daemon --noEmit` compiling the *daemon's* directory because
`CliArgs.project` defaults to `"."`; the fix was to send `workingDirectory` in
the request and install it into `SystemVfs.workingDirectory` for the duration of
one compile. A restored CLI has exactly the same shape — every relative path,
including the project path the user never typed, resolves against a directory
chosen when the image was built. **Any CRaC-based CLI must therefore re-install
the real cwd on restore**, through the same `SystemVfs.workingDirectory` funnel,
driven by a `jdk.crac.Resource` `afterRestore` hook. It is the one funnel that
already exists, which makes this cheap — but it is not optional, and it is
silent when wrong.

**(b) The cold-disk restore is unmeasured.** Every restore here ran seconds
after the checkpoint, with a 340 MB image hot in the page cache. Dropping the
page cache needs root, which this box does not have. The 27 ms resume is
therefore a best case; a first restore after a reboot has to fault that image in.

**(c) A daemon checkpoint needs resource hooks.** These runs deliberately had no
sockets. `CompileServer` holds a listening Unix socket, and CRaC requires open
resources to be closed before the checkpoint and reopened after, via the
`jdk.crac.Resource` API. Not hard, but it is work that this measurement skipped.

**(d) Staleness has no contract yet.** An image embeds the compiler build it was
taken from. `docs/perf/aot-cache.md` § 1's fail-safe fingerprint exists because a
stale AOT cache silently runs the previous build's bytecode; a stale CRaC image
is the same hazard with a bigger blast radius, since it also carries a heap.
Nothing here validates provenance.

## 6. Where the prize actually is

For the **daemon**, a snapshot is worth nothing: the daemon already is the warm
image, and the measurements above simply reproduce its plateau.

The prize is everything that pays cold today — the one-shot CLI and CI, at ~24 s
now and ~14 s with the AOT cache. A restored checkpoint lands at ~7 s, which
would also make the GraalVM native arm (13.4 s, `aot-native-image.md`) the slower
artifact for the first compile. That is a bigger lever than anything currently in
the queue, gated on (a) and (d) above.
