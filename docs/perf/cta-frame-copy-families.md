# (WARM.18) The per-scope copy families round 869 left behind — round 891

Round 869 replaced two of the six per-scope whole-map copy families
(`spineOs` / `spinePd`, the annotation scopes) with one live map plus an undo
log, measured the whole family at **205 ms = 2.80%** of a warm rebuild and the
two it took at **129.7 ms = 1.74%**, and said in as many words why it stopped
there (`warm-leaf-profile.md` § 11): the remaining four are the families where a
wrong scope does not crash — it silently resolves a name to an OUTER binding.

This round answers the question that was left open, in the order CLAUDE.md
requires: **conditions first, price second, code third.** Two of the four are
refused, one is converted, and one is refused *with its price now measured*
rather than guessed.

Everything here is the compiler profile, warm, `BenchMain <proj> 3 5 <tiers>`.

## 1. The census — unchanged since round 869

`--frontEnd`, deterministic (identical on every instrumented rebuild taken this
round and in round 869):

| family | pushes | entries copied | mean | max | writes | undo |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `EpochMap(localTypes)` | 28,828 | 471,726 | 16.3 | 272 | 44,320 | 0 |
| `EpochSet(paramBindings)` | 35,015 | 39,522 | 1.1 | 20 | 7,969 | 0 |
| `spineOs` annotation frames | 34,155 | **0** | — | — | 17,600 | 17,600 |
| `spinePd` annotation frames | 21,674 | **0** | — | — | 16,854 | 16,854 |
| **`CtaFrame.varTypes`** | **30,433** | **1,145,523** | **37.6** | **100** | 2,564 | 0 |
| `CtaFrame` localTypes+declNodes+shadowed | 9,525 | 1,089,527 | 114.3 | 406 | *n/a* | 0 |

The `n/a` is round 849's law and is repeated here because it is load-bearing for
the refusal below: the `CtaFrame` local family's writes go through a plain
`HashMap` that the cta sandwich installs into `currentLocalTypes`, so no hook
sees them. **That 0 is UN-INSTRUMENTED, not measured.**

## 2. The price — amplification, per family, on the binary that still has it

`copyamp<r>` performs `r` EXTRA whole-map copies at every censused site and
takes **no timestamp pair anywhere**; the answer is read off the whole-rebuild
wall, so `wall(r) = base + r·C` and two values of `r` cancel `base`
algebraically. Round 891 adds three per-family arms — `copyampcv<r>`
(`CtaFrame.varTypes`), `copyampcl<r>` (the cta local family), `copyampcta<r>`
(both) — for the reason round 869 added `copyampos`: the prize of replacing ONE
family has to be measured directly, not as the difference of two whole-family
slopes each carrying its own error.

Falsification is arithmetic and held on every rebuild:
`ampSink == r × entries` (e.g. `18,328,368 = 16 × 1,145,523`).

### 2a. `CtaFrame.varTypes` — two batches, opposite rotations, 12 draws

| batch | rotation | r=0 | r=8 | r=16 | slope (0→16) |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | `16,8,0,0,8,16` | 5,394.0 | 5,590.4 | 6,251.2 | **53.6** ms/rep |
| 2 | `0,8,16,16,8,0` | 5,731.5 | 5,704.4 | 5,957.8 | **14.1** ms/rep |
| pooled (12 draws) | | 5,562.8 | 5,647.4 | 6,104.5 | **33.9** ms/rep |

**The two batches disagree by ~4x and the pooled figure is what is quoted.**
This is round 840(c)'s law biting harder than it did in round 869 (±16%): the
FIRST instrumented rebuild in a process is the slowest draw and is worth up to
15%, and with only two draws per arm it lands entirely on whichever arm ran
first — `r = 16` in batch 1 (inflating the slope), `r = 0` in batch 2
(deflating it). Discarding the leading draw from each batch and pooling gives
**32.6 ms/rep** with the two sub-intervals agreeing to 2.5% (32.2 and 33.0),
which is the only internal consistency this instrument offers here.

**So: ~33 ms = 0.59% of a warm rebuild [0.47-0.64% across treatments].** Had
batch 1 been run alone this document would be claiming 53.6 ms = 0.94%.

### 2b. The cta local family — ONE batch, and it is labelled as such

| batch | rotation | r=0 | r=8 | r=16 | slope (0→16) |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | `16,8,0,0,8,16` | 5,404.6 | 5,869.0 | 6,286.9 | **55.1** ms/rep |

Its two sub-intervals agree well (58.0 and 52.2 ms/rep) — better than either
`varTypes` batch — but discarding the leading draw moves it to 43.3, so the
honest reading is **43-55 ms = 0.8-1.0%** from a **single, unreplicated batch**.

### 2c. The two `Epoch*` families, derived rather than measured

The two measured families put one copied entry at **30-51 ns**
(33.9 ms / 1.146 M and 55.1 ms / 1.090 M). Applied to the census:

- `EpochMap(localTypes)`: 471,726 entries ≈ **14-24 ms = 0.25-0.42%**
- `EpochSet(paramBindings)`: 39,522 entries ≈ **1-2 ms = 0.03%**

This is a DERIVATION, and CLAUDE.md's own rule about them applies (a count
share is not a ms share). It is quoted only to establish that both sit at or
below the decision floor, which is enough to decide the question this round is
asking.

## 3. The condition table — the thing that actually decides it

Round 869's law: a per-scope copy is replaceable by one live map + an undo log
EXACTLY when the stack is strictly LIFO, no key is ever removed, and no reader
mutates or retains the map.

| family | one LIFO stack? | key removal? | reader retains / iterates? | writes/entries | verdict |
| --- | --- | --- | --- | ---: | --- |
| `CtaFrame.varTypes` | **YES** — `ctaFrames`, `addLast`/`removeLast`, at most one frame per owner node, `reset` at the file boundary | **none** — the only mutations of a frame map in 217 `varTypes` references are THREE write paths (`ctaSpineEnter`'s declaration recording, `checkVarDeclAssignability`'s, `ctaTypeParamsIntoLocals`' parameter writes) plus the `extraVarTypes` `putAll` | handed BY REFERENCE to ~15 legacy call sites, every one synchronous inside one spine dispatch; `toMutableMap()` (the legacy nested walk) still yields a genuine detached copy; **no `.keys`/`.values`/`.entries`/`.forEach`/`.iterator`/`.sorted` reader anywhere** | 2,564+ / 1,145,523 = **0.22%** | **CONVERTED** |
| `CtaFrame` localTypes+declNodes+shadowed | stack yes (one copy site, `ctaFnBodyFrame`) — but the maps are installed into AMBIENT FIELDS (`currentLocalTypes` &c) that **≥12 other sites re-install with different objects**, so "the map a write lands in" is not a function of the frame stack | not audited | not audited | **UN-INSTRUMENTED** | **REFUSED** — see § 4 |
| `EpochMap(localTypes)` | **NO** — THREE spine stacks (ccet, cpa, the cta narrowing frame) plus ≥12 ad-hoc `currentLocalTypes = EpochMap(currentLocalTypes)` install/restore sites in legacy helpers whose restore is a **POINTER SWAP**, not a pop | — | — | 44,320 / 471,726 = 9.4% | **REFUSED** — the precondition cannot even be *stated* over the family |
| `EpochSet(paramBindings)` | same three stacks | — | — | 7,969 / 39,522 = 20% | **REFUSED** — 0.03% |

## 4. Why the cta local family is refused even though it is the bigger prize

It is 0.8-1.0% against `varTypes`' 0.59%, so this is not a ranking by size. Three
facts, in the order they were established:

1. **Its produced-versus-consumed ratio is unknown.** Round 801's law is that
   the ratio comes FIRST, and round 849's is that a zero from a hook the caller
   short-circuits is not a negative result. The census reports `writes 0` and
   that is the un-instrumented zero, not a measurement — so the one number that
   decides whether an undo log is cheaper than the copy does not exist yet for
   this family.
2. **Its sibling is a RESET, not a shadow.** A fn-body frame copies
   `localTypes`, `localDeclNodes` and `shadowedNames` from its base but gives
   `ambiguousNames` a FRESH EMPTY set. An undo log expresses "shadow the
   enclosing scope" in O(writes); it expresses "start empty" in O(size), which
   is the very cost being removed. So the family is not one mechanism.
3. **The narrowing frame copies `localTypes` into a DIFFERENT family**
   (`EpochMap(top.localTypes)`), and that family is the one refused in § 3 for
   not being a stack at all. Converting the cta half while the `EpochMap` half
   stays a pointer-swap regime means two disciplines over the same ambient
   field, which is exactly the shape CLAUDE.md warns produces a silent
   outer-binding resolution.

Queued as **(WARM.18b)** with the instrument it needs named: a counting facade
over `CtaFrame.localTypes` (the same shape `VarScopeStack.View` uses here, which
is what made `varTypes`' write count complete for the first time), run for one
census, before any code is written.

## 5. What landed, and the controlled row

`VarScopeStack` (commonMain, `internal`, its own file). It is `AnnScopeStack`'s
shape with two differences forced by this family:

- the map is handed OUT to legacy helpers that WRITE into it, so the scope is a
  `MutableMap` **facade** (`view`) whose `put`/`putAll`/`remove` route through
  the undo log, rather than a private map with a `put` method;
- a frame does not always copy — a BARE (non-`Block`) then-statement narrowing
  frame deliberately SHARES its parent's map. Sharing is exactly "open no
  scope", so it is the frame's `varScoped` flag, which is also what
  `ctaSpineLeave` pops on. Push and pop are therefore mirrored by construction.

The row is CONTROLLED in round 793's sense — the change moves no boundary and no
population:

| | before | after |
| --- | ---: | ---: |
| `CtaFrame.varTypes` pushes | 30,433 | 30,433 |
| entries copied by it | 1,145,523 | **0** |
| undo records | 0 | **16,182** |
| entries copied, all six families | 2,746,298 | 1,600,775 |

**70.8x less work over an identical population.** And the amplifier is the
change's own falsifier: `copyampcv16/8/0` re-run on the new binary reads
**`ampSink 0`** at every `r` — it finds nothing left to amplify, so the copies
are gone rather than merely uncounted.

**The write count moved 2,564 -> 16,182 and that is not a regression, it is the
census becoming complete.** The pre-891 hook was attached to two of the three
write paths; `ctaTypeParamsIntoLocals`' parameter writes and the `extraVarTypes`
`putAll` went through the map by reference and were never counted. The facade is
the only way into the family now, so the number is exact for the first time —
and it is still **1.4%** of the entries it replaces.

`clear()` on the view throws: no `varTypes` reader clears the map, and a clear
cannot be recorded in O(1) — a silent one would drop the enclosing scopes'
entries with no way to restore them at the pop.
