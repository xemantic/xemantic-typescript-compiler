# What tsgo does that we could use — a PRICED census (round 886)

TypeScript 7.0.2's Go sources landed on this box in round 884
(`typescript-go-repo/`, tag `typescript/v7.0.2`, gitignored). tsgo is **3.09x
faster than us check-only** on the bench, so the obvious question is which of its
devices are portable.

**The answer is mostly "none of the micro-ones, because we already have them, and
the rest are dead here" — and that is a measured statement, not an opinion.**
This document exists so the next agent does not re-derive it: every candidate
below was priced against round 874's kept warm leaf dumps
(`build/bench/round874/deep{1,2}.txt`) BEFORE any code was written, which is the
only reason the round did not spend itself on a 0.17% lever.

## 0. The instrument

`scripts/round886_mechanism.py` re-aggregates a `jfr print --stack-depth 512`
dump by **mechanism family** rather than by row — round 874's law, and the only
way any of this is visible, since every candidate here is spread over hundreds of
call sites and no single row clears 1.3%.

Two traps it inherits and honours: stacks must not be 5-frame truncated (it
asserts max depth), and samples must be filtered to the compile thread
(`xtsc-deep-stack`) or ~3% of the profile is the recorder watching itself.

Shares below are **shares of compile-thread samples in a fixed 90 s window**, two
independent processes (`run 1 / run 2`). Round 870's denominator law applies: to
turn one into ms, multiply by THAT round's median rebuild (~7.0-7.5 s), and never
compare a share across rounds without doing so.

## 1. The mechanism table

| family | leaf% | incl% | verdict |
| --- | ---: | ---: | --- |
| hash/set probing | **24.3 / 24.6** | 30.7 / 30.0 | SHAPE, not a lever — see § 3 |
| narrowing walk | 1.0 / 1.2 | 8.9 / 9.4 | already attributed (round 735) |
| string building | 6.8 / 5.8 | 7.1 / 6.1 | open, unattributed — see § 4 |
| per-scope map copies | 2.3 / 2.0 | 4.4 / 4.1 | known (C2) / (WARM.18) |
| relation engine | 0.5 / 0.4 | 3.7 / 3.6 | **not our bottleneck** |
| type interning | 0.7 / 0.7 | 1.8 / 1.9 | already packed-key (M0.3(iii)) |
| **boxed `Integer`** | 0.2 / 0.3 | 0.4 / 0.4 | — |
| **boxed `Long`** | **0.16 / 0.18** | 0.16 / 0.18 | **DEAD** — see § 2 |
| **flow reference paths** | **0.09 / 0.21** | 0.13 / 0.22 | **DEAD** — see § 2 |

## 2. The two tsgo devices that look applicable and are DEAD

Both were live hypotheses when this round opened, and both were killed by the
table above for the cost of running a script over a dump that already existed.

**(a) `CacheHashKey` — tsgo's 128-bit xxh3 keys.** tsgo replaced tsc's string
cache keys (`"12,34"`) with `CacheHashKey xxh3.Uint128`, streamed through a
`keyBuilder`, for the relation cache, the instantiation caches, the signature
keys and the flow reference keys (`checker.go:17313`, `relater.go:100`). Ours is
already a **packed `Long`** — exact, not a hash — for the relation cache
(`Checker$Relation`) and for the dominant intern shapes (`LongKeyMap`, M0.3(iii)).

What survives of the idea is that our `HashMap<Long, ·>` / `HashSet<Long>`
**box** their keys where tsgo's value-typed key does not: `Relation.cache`,
`relationComparisonStack`, `elaborationStack`, `functionElaborationStack`,
`resolvedPropertyTypes`, `walkMemo`. **Priced: `java.lang.Long` is 0.16-0.18% of
samples, i.e. ~12 ms.** Converting them to `LongKeyMap` is correct and worth
nothing. Do not spend a round on it.

**(b) `getFlowReferenceKey` — hashing the flow path instead of building it.**
tsgo streams symbol ids and property names into a 128-bit key
(`flow.go:1636`); we build a `String` per call and compare strings
(`getReferencePath`, 55 call sites). **Priced: 0.09-0.22% of samples.** Dead.

There is a real *correctness* difference here worth recording separately — tsgo
keys the root on the resolved **symbol** (`b.writeSymbol`), we key on the
identifier **text**, so our paths cannot distinguish two shadowed bindings of the
same name — but that is a soundness question, not a performance one, and it is
not what this document is about.

## 3. The one structural finding: hash probing is a quarter of the warm compile

`HashMap`/`HashSet` work is **24.3-24.6% of compile-thread samples as a LEAF**.
That replicates round 868's 26.8/25.9% on a different binary, so it is the single
largest family in this compiler and it is not going away by accident.

It is **not a lever**, and the owner ranking is why. Charging every hash sample to
its nearest non-stdlib owner (`scripts/round886_hash_owners.py`) gives a maximum
of **1.19%** (`ctaSpineEnter`) and a long tail — 25 owners to reach ~12%. This is
round 874's shape exactly: a family that is huge and a row list that is all below
any candidate floor.

**tsgo's answer to this family is architectural, and it is worth naming precisely
because it is the opposite of what one would guess.** It does not have fewer
lookups by caching harder; it has fewer lookups because per-node and per-symbol
checker state is reached through **one** store probe that returns a struct of
co-accessed fields — ~25 narrow `core.LinkStore[K, V]`s (`ValueSymbolLinks`,
`DeclaredTypeLinks`, `TypeAliasLinks`, …, `checker.go:669-694`), each grouping the
facts that are read together. tsc goes further and makes it a plain field on the
node. Ours is the third design: **one map per FACT**, so a site that needs three
facts about a symbol pays three probes.

Porting that wholesale is a checker-wide refactor and is NOT proposed here. What
IS available is the same idea applied where the facts are booleans, which is § 5.

## 4. Left open, and the one number a next round needs

**String building is 5.8-6.8% of samples as a LEAF and is unattributed.** The
whole-program regex class is closed (rounds 860-863; `java.util.regex` is
**0.0%**, not one sample in 16,036) — this is something else, and no section
probe in this repo brackets it. It is the largest unattributed family the dumps
show. Next step is an owner ranking (`round886_hash_owners.py string`), not a
guess.

## 5. What was taken: the anchor marks as a per-node flags word

The one place the § 3 idea applies at bounded cost.

Three spine-anchor mark tables — `ctaM3AnchoredStmts`, `cpaM3Anchored`,
`ccetM3Anchored` — were each a `HashMap<String, HashSet<Int>>`. Per mark that is
a String-keyed map probe, a `getOrPut` lambda check, and a `HashSet.add` that
**boxes** the nodeId (`Integer.valueOf` caches only -128..127; nodeIds run to the
tens of thousands).

**Priced at 1.16-1.31% of a warm rebuild (~85 ms), of which 1.05-1.26% is the
MARK** — the marks vastly outnumber the tests, which are 0.05-0.11%. Bounded:
exactly three tables, six call sites, and `grep` says the pattern occurs nowhere
else.

The replacement is tsgo's shape one layer down: it keeps per-node booleans as
**bits in a single `NodeCheckFlags uint32`** inside `NodeLinks`
(`types.go:345-358`), so a fact costs one lookup and a bit test rather than a set
per fact. The JVM analogue of "one lookup" is INV.2(b)'s per-file nodeId side
table — already the sanctioned idiom in this very walk
(`ByteArray(sourceFile.nodeCount)`, ~15 existing sites) — so the three facts
become three BITS of one `ByteArray`, the map probe is paid once per FILE via a
cached current-file array, and the mark becomes an array store.

Two invariants a change here must keep, both load-bearing:

- **Per-FILE keying.** Round 787: `nodeId` restarts at 0 in every `SourceFile`,
  so a program-wide array collapses one node per file onto each id.
- **Never cleared.** The legacy walkers run as later passes and test marks for
  files the spine finished long before, passing their own `fileName` rather than
  `spineFileName`.

Why the pin can see a mistake at all: these marks exist so the legacy walkers can
**truncate** emissions the spine already anchored, so a leaked mark truncates a
diagnostic that was never anchored (the file LOSES it) and a lost mark truncates
nothing (the diagnostic is emitted TWICE). `M3AnchorFlagsTest` asserts both
directions, plus growth past the initial array size.
