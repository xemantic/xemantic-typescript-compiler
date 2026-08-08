# The classpath-reader audit (round 858)

## 0. Why this document exists

Three consecutive rounds found an instrument silently loading something other
than the code under test, exiting 0, and printing a plausible number:

| round | instrument | what it really loaded |
|---|---|---|
| 853 | `grid838.sh`, `cost_gate.py`, `huge_methods.py` | a STALE pre-module-split class dir — which is why a `+0.00%` counter streak looked so reassuring: a frozen binary cannot drift |
| 857 | `aot-corpus-suite.sh` | an AOT prefix that was no longer a prefix of the trained classpath, so both arms would have run uncached, agreed, and proved nothing |
| 858 | `build/bench/cp.txt` and its readers | a hand-frozen Jul-8 dependency tail |

The shape is identical every time, and it is the reason this file is a permanent
record rather than a session note: **a harness that resolves an artifact by a
path rather than by a checked contract will eventually resolve the wrong one, and
nothing downstream will notice.**

## 1. The stale artifact

`build/bench/cp.txt`, dated **2026-07-08**, 7 entries:

```
kotlinx-coroutines-core-jvm-1.11.0   kotlinx-io-core-jvm-0.9.0
kotlinx-serialization-core-jvm-1.9.0 kotlinx-serialization-json-jvm-1.9.0
kotlinx-io-bytestring-jvm-0.9.0      kotlin-stdlib-2.4.0
annotations-23.0.0
```

The build had since moved to **kotlin-stdlib 2.4.10, kotlinx-io 0.9.1,
serialization 1.11.0**. Every jar cp.txt names **still exists on disk**, so
nothing failed — the file simply kept answering.

Note what cp.txt is NOT: it is not the post-split *launcher* classpath. Round 857
measured that one going 8 → 14 jars (ktor ×3, slf4j, `-api`, `-daemon`). That is
the **daemon**'s classpath. `cp.txt`'s readers run `MainKt`/`BenchMain` from the
**core** module, whose `jvmRuntimeClasspath` is legitimately 7 entries. Conflating
the two would over-state this finding; the defect here is the VERSIONS, plus one
reader that genuinely does need the daemon classpath (§ 2).

## 2. The per-reader audit

| reader | classpath source | verdict |
|---|---|---|
| `ab-interleaved.sh` | resolves **fresh** through the gradle init script on every run | **SOUND** — never read `cp.txt` |
| `cost_gate.py` | resolves **fresh** (round 853's fix) | **SOUND** |
| `ab-warm.sh` | fresh, cached in `cp-warm.txt`; cache used while newer than `core/build.gradle.kts` | **SOUND TODAY, GUARD INCOMPLETE** — see below |
| `aot-draw-variance.sh` | `cat build/bench/cp.txt`, no guard at all | **BROKEN** — see below |

**`ab-warm.sh` — the incomplete guard.** Its cache content is *current*
(2.4.10 / 0.9.1 / 1.11.0), and the guard currently *fails* anyway (cache Aug 1,
build files Aug 7), so it has been resolving fresh. But the guard compares the
cache only against the **module's** `build.gradle.kts`, and the versions live in
`gradle/libs.versions.toml` — which a bump leaves the module file untouched by.
**The guard was blind to precisely the change that produces a stale tail.** That
is the latent hole, and it is the same mechanism that froze `cp.txt`.

**`aot-draw-variance.sh` — broken twice over.** It built
`<core jar>:$(cat cp.txt)` and ran `XtscMainKt` — a class in the **daemon**
module, absent from that classpath. Verified:

```
Error: Could not find or load main class com.xemantic.typescript.compiler.server.XtscMainKt
```

It has been dead since the module split, exactly as `scripts/xtsc` was until
round 857 found it. It fails **loudly**, so it could never have produced a wrong
number — only no number.

**The documented "pre-split refusal" does not guard `cp.txt`.** It greps the
**init script**, not the cache; and in `ab-warm.sh` it sits inside the resolve
branch, so a cache hit skips it entirely. It was never the protection it was
being credited as.

## 3. Blast radius on this session's warm arc

| round | warm arm | cold arm | affected? |
|---|---|---|---|
| 845 `(JIT.1)` −33.6% | own builds in a throwaway worktree | — | **NO.** Both arms predate the module split and were built from their own commits; the note says so explicitly ("dependencies are identical"). It never touched either cp file. |
| 846, 848, 850, 851 | `cp-warm.txt` (**current**) | — | **NO** |
| 847, 849 | `cp-warm.txt` (**current**) | `cp.txt` (**stale**) | **the cold/warm RATIOS only** |

**So the warm conclusions stand.** Every warm number this session produced was
measured against the current dependency tail. What is confounded is narrower and
specific: the *cross-regime* statements in rounds 847 and 849 — "enter warms
3.62×", "a probe boundary is ~1.85× more expensive cold", "the handler order
swaps between regimes" — each compared a current-tail warm arm against a
stale-tail cold arm.

Both tails link and run (verified directly), so this was never a crash risk; the
question was only whether the stale tail *measures* differently. § 4 answers it.

## 4. What the staleness was actually worth

`scripts/round858-deptail-equivalence.sh` runs the identical cold compile on both
tails — same binary, same profile, same main class dir, rotated interleave — and
diffs a `--listAll` digest per arm, because a dependency that changed BEHAVIOUR
would matter far more than one that changed timing.

### 4.1 Behaviour: identical, and that is the load-bearing half

Both arms, **both batches**, produced the same 46-line digest:

```
59d930db849399aea5e03e25fedb8e4e   stale   batch 1
59d930db849399aea5e03e25fedb8e4e   fresh   batch 1
59d930db849399aea5e03e25fedb8e4e   stale   batch 2
59d930db849399aea5e03e25fedb8e4e   fresh   batch 2
```

That is the round-841 `grep 'error TS' | sort` lineage, unchanged. All 12 timed
compiles also answered `46 errors`. **The stale dependency tail never changed
what the compiler computed** — so nothing any round concluded about compiler
BEHAVIOUR is in question, which is the half that would have been expensive.

### 4.2 Timing: NOT a demonstrated effect — and batch 2 is why

| batch | stale (self, median) | fresh (self, median) | paired deltas | verdict |
|---|---|---|---|---|
| 1 | 23,633 ms (sd 0.78%) | 24,420 ms (sd 0.96%) | +616, +904, +858 | **+3.63%, fresh slower 3/3** |
| 2 | 23,752 ms (sd 0.84%) | 23,754 ms (sd 0.17%) | +79, −184, +317 | **+0.33%, 2/3** |

Batch 1 is exactly the shape round 840(c) warns about: a **sign-consistent 3/3
sweep, well outside both arm sds, that does not replicate.** Batch 2 says the
effect is ~0 and its own deltas straddle zero.

The mechanism is visible in the table: the **fresh** arm moved 24,420 → 23,754
between batches — **−2.7% on a byte-identical configuration**. That is drift, and
in batch 1 it happened to land on one arm. Both arms' within-batch sds are under
1%, so nothing inside a single batch could have revealed it.

**Reading, stated plainly: the stale-vs-current dependency tail is not a
demonstrated timing effect, and it is a demonstrated non-effect on behaviour.**
This is the second independent confirmation of the 840(c) rule inside one
session, and the first where following it *reversed* the conclusion rather than
merely confirming it — batch 1 alone would have been written up as a real 3.6%
and used to "correct" two earlier rounds.

### 4.3 So: does this session's warm arc stand?

**Yes, and no round needs re-taking.**

* Rounds 845, 846, 848, 850, 851 never touched the stale tail at all.
* Rounds 847 and 849 ran their COLD arms on it. Their cold/warm ratios are
  therefore built on two different dependency tails — but that difference is
  behaviourally nil and not a demonstrated timing effect, so the ratios stand
  within the resolution the measurements already claimed.
* Round 845's `(JIT.1)` **−33.6%, 4/4, 1.505×** is untouched by all of this: it
  built both arms in a throwaway worktree from commits that predate the module
  split, so its dependencies were identical by construction and neither cp file
  was involved.

One caveat worth keeping: a per-kind or per-handler **share** within a single arm
is a ratio taken inside that arm and is immune to a uniform tail difference
anyway. Only cross-regime **multipliers** could ever have been exposed.

## 5. The guard

`scripts/lib/dep-classpath.sh` is now the single resolver. It refuses a cache
unless **all three** hold:

1. it is non-empty;
2. it is newer than **every** build-definition input — `gradle/libs.versions.toml`
   first among them;
3. every entry it names still exists on disk.

Every refusal prints why. Every reader is rewired onto it, and
`build/bench/cp.txt` now has **zero readers**.

`DepClasspathGuardTest` (core, jvmTest) pins all three plus a source-level pin
that no script goes back to reading `cp.txt` directly. The ablation record — one
deliberate mistake per run, `scripts/round858-ablate.sh` — is in § 6.

## 6. The ablation record

`scripts/round858-ablate.sh`, one deliberate mistake per run, the harness
committed first (round 789 — the revert is `git checkout` of the very file the
work lives in). Baseline 6/6 green; each arm restored before the next.

| arm | mistake injected | pins run | failed | which |
|---|---|---|---|---|
| baseline | none | 6 | 0 | — |
| A | drop `libs.versions.toml` from the watched inputs | 6 | **1** | `a cache older than libs versions toml is refused` |
| B | drop the entry-existence loop | 6 | **1** | `a cache naming a jar that no longer exists is refused` |
| C | drop the non-empty test | 6 | **1** | `an empty cache is refused` |

Each guard has a **uniquely its own** failure — no redundancy, no blind pin.

**Arm A is the one to read closely.** It fails only the libs-versions pin while
`a cache older than the core module build file is refused` stays **GREEN** —
which is precisely the state `ab-warm.sh` was in before this round: a guard that
passes, looks like protection, and cannot see the change that actually produces a
stale tail. That contrast is the evidence the widening is real rather than
decorative.

## 7. The rule this leaves behind

**A cached classpath file is a claim about a BUILD, not about a path — and the
input it must be checked against is the one that carries the VERSIONS, not the
one that carries the module's own configuration.** A freshness guard aimed at the
wrong input is indistinguishable from no guard, and produces no error, no crash
and no wrong answer — only a measurement of something other than what ships.

