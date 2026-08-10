# Which JDK, which JIT — measured 2026-08-10

*Owner-directed ("is azul faster? should our CI use azul?"), not a queue item. One
box, one session; every table below is a WITHIN-ROUND paired comparison and no
absolute here is comparable to another section or another date (round 826 measured
the same code 12.8% apart from round 824).*

Binary: this HEAD at `javaTarget = 25`, 78-file tsc `compiler` profile, `-Xmx4g`.
Warm = `BenchMain` median of 6 in-process rebuilds after 6 warm-ups, each arm in
its OWN JVM (round 867). Cold = process wall clock. Arm order rotated per rep.

## 0. The answers, in one place

| question | answer |
|---|---|
| Is Azul Zulu faster than Temurin? | **No.** Temurin wins 3 of 4 cells; Zulu is **+13.1% slower COLD** check-only. |
| Should CI switch to Azul? | **No — stay on Temurin 26.** |
| Is the Graal JIT faster? | **Warm yes** (−5.4% check-only, −10.2% emit vs Temurin), **cold no** (+8 to +16%). |
| Is JDK 26 faster than 25? | **Not established** — see § 3, a textbook non-replication. |

## 1. Vendor, controlled — Zulu 26.0.2+10 vs Temurin 26.0.2+10

The **same upstream build number**, so this isolates vendor packaging and nothing
else. Negative = Zulu faster.

| | delta (median) | wins | range |
|---|---:|---|---|
| warm check-only | −3.01% | 2/3 | −4.0 … +4.6% |
| warm emit | +4.89% | 0/3 | +0.1 … +6.0% |
| cold check-only | **+13.13%** | 0/2 | +10.7 … +15.5% |
| cold emit | +6.15% | 0/2 | +4.3 … +8.0% |

**Temurin is the faster of the two**, decisively cold. The single cell favouring
Zulu is mixed 2/3 with a range crossing zero, i.e. not a result.

**Consequence for anyone measuring here: the dev box defaults to Zulu 26, so a
COLD number taken locally runs ~13% pessimistic against CI's Temurin.** Warm
numbers are close enough to compare.

## 2. JIT — Graal vs HotSpot C2, against the Temurin baseline

Measured against Temurin (the faster HotSpot); using Zulu as the baseline would
have flattered Graal by the whole of § 1. Negative = Graal faster.

| | check-only | emit |
|---|---:|---:|
| Oracle GraalVM JVM, **warm** | **−5.41%** (3/3) | **−10.17%** (3/3) |
| CE GraalVM JVM, **warm** | −3.58% (3/3) | −2.45% (3/3) |
| Oracle GraalVM JVM, cold | +8.23% (0/2) | +4.60% (0/2) |
| CE GraalVM JVM, cold | +15.80% (0/2) | +8.69% (0/2) |

Warm medians, check-only: `graaloracle` 6,091 · `graalce` 6,150 · `zulu26` 6,408 ·
`temurin26` 6,420 ms. Emit: 6,782 · 7,075 · 7,378 · 7,662.

Textbook Graal — **loses cold, wins warm**, every warm cell 3/3 sign-consistent.

**CONFOUND, stated rather than buried: the Graal arms are JDK 25 and the HotSpot
arms JDK 26**, because GraalVM ships nothing on 26. So "Graal JIT" here means
"Graal JIT on 25 vs C2 on 26". It probably UNDERSTATES Graal: § 3 found 25 if
anything slightly slower warm than 26, so Graal wins carrying a handicap.

**What this is worth.** `bench.yml` warns that letting `setup-graalvm` win the
`JAVA_HOME` race would "silently re-baseline the whole xtsc series onto GraalVM's
JIT". That risk is now priced: **~5% check-only, ~10% emit, warm.** It is also a
free ~5–10% for the DAEMON, which is the warm artifact — larger than any JDK
vendor effect and worth considering separately from the bench, which should stay
on Temurin as a stable and conservative reference.

## 3. Version — and a non-replication worth keeping

Zulu 26 vs Ubuntu OpenJDK 25 (**confounds vendor with version** — it cannot answer
either question, which is why § 1 exists):

| | batch 1 | batch 2, ABBA phase inverted | pooled |
|---|---|---|---|
| check-only | −5.65%, 3/3 | −2.79%, 2/3 | −3.33%, 5/6 |
| emit | **+7.82%, 0/3** | **−0.62%, 2/3** | +2.39%, 2/6 |

**Batch 1's emit result — a clean, sign-consistent 3/3 "Zulu is 7.8% slower" —
reversed on replication.** Exactly round 840(c): a sign-consistent paired batch is
not a result. The second batch cost one run block and is the only thing that
separated drift-landing-on-an-arm from an effect. Design note worth reusing:
**inverting the ABBA phase between batches makes an order artifact flip sign while
a real effect replicates.**

## 4. The artifact ladder on this HEAD, same session

| artifact | check-only | emit |
|---|---:|---:|
| warm JVM (daemon regime) | 6,853 ms | 7,834 ms |
| native, Oracle GraalVM + PGO | 8,808 | 8,839 |
| tsc 6.0.3 | 14,054 | 17,246 |
| cold JVM one-shot | 28,055 | 29,710 |
| tsgo 7.0.0-dev | 2,215 | 2,891 |

**The warm JVM is 2.05x FASTER than tsc check-only and 2.20x on emit.** Parity with
tsc is not a future milestone in the warm regime; the remaining gap is **tsgo**
(3.09x check-only, 2.71x emit). The cold arm — 2.00x SLOWER than tsc — is the
number CI published for its whole history, and it is a JVM-startup story rather
than a compiler one.
