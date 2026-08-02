# (FRONT.2) — the bind, opened; and a region that is genuinely flat

*Round 801. Ninth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → `call-expression-attribution.md` (734) →
`argument-check-attribution.md` (735) → `narrow-walk-attribution.md` (736) →
`type-of-expression-attribution.md` (737) → `var-decl-attribution.md` (738) →
`front-end-attribution.md` (738 part 2) → `property-access-attribution.md`
(787–795) → `implicit-any-attribution.md` (798–800) → here.*

> **HEADLINE.** Rounds 798–800 closed the last of round 732's six big spine
> handlers, so step 1 re-derived the map and asked which region is now the
> largest that has never been opened. The answer was **not** the ~400 tail
> passes — they are 2,962 ms but **FLAT**, and the number that says so is new —
> and it was **`Binder.bind`, 1,549 ms (6.0% of the compile), never partitioned
> in 800 rounds.**
>
> Opened, it is `bindStatements` **31 ms**, `bindLexicalScopes` **~470 ms** and
> `FlowGraphBuilder.build` **~1,050 ms**, with a residue of **−13 ms** — the
> first exhaustive-by-construction partition in this arc. Inside the flow
> build, one collector holds a quarter: `collectReassignedNamesInRange`,
> **275–444 ms over 2,014 closures**.
>
> **Two candidate levers were built and measured, and BOTH came in at
> essentially zero — the honest verdict is that the region has no lever.**
> Removing **367,189 String allocations** from the scan measured **ZERO**.
> Deferring the suffix set emptied its row (**53 ms → 0.9 ms**) and then the
> census killed the claim: **1,143 of 1,143 sets are eventually materialised**,
> so the work is **MOVED into the checker, not deleted** — round 788's law,
> answered against the round's own change rather than for it. The remaining
> ~280 ms is a text scan the round-433 cache has already reduced to 1,220
> executions, and the ~700 ms residue is the flow walk itself at 3 µs per flow
> node.
>
> **So `bind` joins `checkArgumentsAgainstSignature` (797) and the spine-leave
> handlers (733/799) as a measured, bounded, closed region.**

---

## 1. Step 1 — the re-derived map, and the row that changed the plan

Median of **3 probe-free `--passTiming` runs** at `d26c6988`, on a box freed of
its Gradle and Kotlin daemons *inside the measuring script* (round 800 read
every row ~270× too large for exactly that reason, and the ratios survived, so
no internal check caught it). Wall 29.12 / 28.57 / 28.39 s.

| | 798 ms | **801 ms** | note |
|---|---:|---:|---|
| whole compile (wall) | 29,304 | **28,570** | |
| checker-init | 25,557 | **24,806** | |
| `checkSpine` | 21,578 | **20,610** | 83.1% of init, 72.1% of the compile |
| — `spineEnterNode` | 11,942 | **11,226** | |
| — `spineLeaveNode` | 6,705 | **6,549** | |
| — unresolved-names family | 1,233 | **1,200** | |
| — `forEachChild` | 532 | **520** | |
| flow-narrowing walks | 1,158 (17,851) | **1,122 (17,853)** | stable after 796 |
| `getTypeOfExpression` ⚠️ double counts | 3,127 | **2,961** | |
| relations (depth-0) | 661 | **628** | |
| type-node resolution (depth-0) | 491 | **485** | |
| **the ~400 tail passes** | 3,140 | **2,962** | **12.0% of init — and FLAT, § 2** |
| outside-pass (init work in no `pass()`) | — | **975** | never named before |
| **front end** | 3,755 | **3,764** | 13.2% of the compile |

**What was stale.** Nothing large. The 798 column held up: every row is within
a few percent, and the movement is the compile being ~2.5% faster overall
(rounds 798–800 landed 481 ms). The genuinely *new* rows are the two the
previous columns never carried — the tail's internal distribution and
`outside-pass` — and it is the first of those that redirected the round.

## 2. The tail passes are FLAT, and that is a measurement, not an inference

`--passTiming` already prints every pass, so this needed no instrumentation —
only someone to add it up. Median of the three runs:

| | |
|---|---:|
| tail passes | **400** |
| their sum | **2,962 ms** |
| the largest (`checkSpreadPropertyOverrides`) | **75 ms = 0.26% of the compile** |
| top 20 | 980 ms = 33% |
| top 50 | 1,841 ms = 62% |
| top 100 | 2,634 ms = 89% |
| passes doing ANY `getTypeOfExpression` work | **2 of 400** |
| passes doing ANY narrowing walk | **0 of 400** |

Two readings, and the second is the interesting one.

1. **There is no per-pass lever.** The biggest pass in the tail is a third of
   the ±2% band. A round spent inside any one of them could not produce a
   measurable result, and the shape is a long thin tail: 300 passes hold 11%.
2. **The tail is not type-system work at all** — 398 of 400 passes never call
   `getTypeOfExpression` and none narrows. It is ~400 traversals with
   syntactic predicates. That is a *structural* cost (one walk per pass), and
   structure is what M0.4 and (DISPATCH.1) address; both are already measured
   and closed (round 659's 25% recovery, superseded-as-mis-ordered; round 732's
   4.8%). Deleting passes for wall time was closed twice over in rounds 619/620.

So the tail is a location with a known, priced, closed treatment — which is
why this round went to the front end instead.

## 3. Level 1 — `Binder.bind`, exhaustive by construction

`bind()` is literally three statements, so the partition cannot leak, and the
boundary cost is **3 timestamp pairs per FILE** (123 files ≈ 33 µs against a
~1,530 ms row). This is the first partition in the arc with no boundary-cost
caveat at all — every previous one needed round 734's differential calibration.

| row | ms | share of bind | population |
|---|---:|---:|---|
| `bindStatements` (the conventional decl bind) | **31** | 2% | 123 files |
| `bindLexicalScopes` (INV.2(c)) | **~470** | 31% | **876,201 node pops** |
| `FlowGraphBuilder.build` | **~1,050** | 67% | **236,587 flow nodes / 123 graphs** |
| residue | **−13** | | timestamp noise, 0.8% |

`bind` is called **123** times, not 78: the program's 78 files plus the lib
`.d.ts` set. Both whole-tree walks therefore cover more than the spine's
856,962 nodes, which is why `lexNodePops` (876,201) is slightly larger.

**The census is what makes the nanos readable.** Round 758's law is usually
quoted in one direction (a count is not a measure); here the converse bites
too — a total with no population attached cannot be compared to anything, and
"the flow build is 1,050 ms" only becomes actionable as "4.4 µs per flow node",
which is ~1000× an allocation and therefore *not* the minting.

## 4. Level 2 — inside the flow build

Three spans per CLOSURE (2,014), not per node.

| row | ms | calls |
|---|---:|---:|
| `collectReassignedNamesInRange` (B464) | **275–444** | 2,014 |
| — of which the text scan + cache | **271–404** | 2,014 |
| — of which the suffix-set build (eager) | **38–40** | 1,143 |
| `collectClosureLocalNames` (B464) | **3–4** | 2,014 |
| `collectEnclosingVarDecls` (B467) | **16–20** | 2,014 |
| **residue: the flow walk itself** | **~700** | 236,587 flow nodes |

The census behind the first row:

* **2,014** closure `FlowStart`s.
* **273,226** returned name entries, **135 per closure** — the sets are big.
* **1,220** actual text scans over **6,256,904 characters**. So the round-433
  scan cache *is* working (1,220 scans, not 2,014), and it nevertheless
  re-reads **63% of the program's source text** during the bind.
* **382,520** identifier occurrences classified, of which **15,331** recorded —
  a ratio of **24×**.
* Only **1,143 of 2,014** closures reach the set build at all; the rest have an
  empty suffix.

## 5. Lever A — hoist the `substring`. Measured ZERO.

The legacy scanner allocated `source.substring(i, j)` for **every identifier
occurrence** and kept the string only for the ~1 in 24 that is an assignment
target. `name` is read at exactly one place, so moving the allocation below the
guard is trivially equivalent. The same change also replaced every
`getOrNull` neighbour read (whose `Char?` boxes on the JVM) with an unboxed
`charAtOr`.

**Both arms on one binary, twice each, identical boundary counts:**

| arm | run 1 | run 2 |
|---|---:|---:|
| fast (367,189 fewer allocations) | 314 ms | 362 ms |
| legacy | 317 ms | 320 ms |

**No effect.** 367k short-lived `String`s are a TLAB bump-pointer allocation and
a young-collection scan, and at this scale they cost less than the run-to-run
spread of the row they live in. Recorded as a measured negative, not a
disappointment: *an allocation count is not a cost*, and it is the same class
of error as round 758's population-vs-frequency — a count of operations was
mistaken for the work behind them.

The change is nevertheless **kept**, because it is the other arm of
`--verifyFlowScan`, which is the only instrument that can show the two
implementations agree.

**Equivalence: `scans compared 1220, diverged 0; entries compared 15331,
diverged 0`**, and the production `--listAll` is identical (46 errors, only the
`time:` line differs).

**The positive control is DEAD ON THIS PROFILE, and that is reported rather than
smoothed.** `--flowScanBogus` drops the `%=` form; on the compiler profile it
reports **0 divergences**, because tsc's own sources contain no `%=` in a
scanned range. It fires in the fixture (`FlowScanEquivalenceTest` asserts it),
which is exactly round 793's rule: *check the control fires on the same run that
reports the zero, and if it cannot, move the falsification to a fixture and say
so.*

## 6. Lever B — defer the suffix set. Measured 38 ms (0.13%). Landed.

The value `collectReassignedNamesInRange` returns is consumed at **one** place —
`root in flowNode.reassignedAfterNames`, reached only from a narrowing walk —
and narrowing walks fell **75%** between rounds 758 and 798. Building 1,143 hash
sets averaging 135 entries during the *bind* is therefore paying, at the
earliest possible moment, for answers almost nobody asks for: the (IANY.1)
shape one phase earlier.

`SuffixNameSet` is a `Set<String>` view over the shared scan's name array plus a
lower bound, materialising on first question. `isEmpty` is answered from the
bounds.

| arm | run 1 | run 2 | final |
|---|---:|---:|---:|
| suffix-set build, eager | 38.2 ms | 40.6 ms | 53.5 ms |
| suffix-set build, deferred | **0.7 ms** | **0.9 ms** | **0.9 ms** |

**And then the census answered the question the row cannot.**

```
suffix sets created 1143, materialized 1143      (deferred arm)
suffix sets created 1143, materialized 1143      (eager arm)
```

**Every set is eventually asked.** So the ~40–53 ms does not disappear — it
leaves the bind and reappears inside `checker construct + getDiagnostics`, at
the first narrowing walk that questions each `FlowStart`. This is **round 788's
law** (*skipping a CACHED computation MOVES work; what is recoverable is only
the part with no other consumer*) applied to the round's own change, and it
comes out **against** it.

**Verdict: NEUTRAL.** It is kept — it is exactly equivalent, verified, and it
makes the phase attribution truthful about where the cost is incurred — but it
is **not a win and is not quoted as one**, and no wall-clock A/B was run.

This is precisely the check round 800 introduced (`distinct` must fall faster
than `calls` for work to be *deleted* rather than *moved*), in its simplest
possible form: here `materialized / created` is 1.000 in both arms, and 1.000
means moved.

Verified: `--listAll` identical between the two arms on all eight profiles
(production path), and an in-process pin compares the two arms' diagnostics
directly.

## 7. The bound — why `bind` is now closed

`bind` is 1,549 ms = 6.0%. After this round:

| | ms | status |
|---|---:|---|
| `bindStatements` | 31 | 0.1% — nothing to find |
| `bindLexicalScopes` | ~470 | one iterative whole-tree walk, 876,201 pops = **540 ns/pop**; no sub-structure, and it is the INV.2(c) scope table every later phase reads |
| B464 text scan | ~280 | already cached to 1,220 executions by round 433; the remaining cost is reading 6.26 M chars once, and lever A shows the per-char work is not allocation |
| B464 suffix sets | 38 → 1 | **landed** |
| B464/B467 other collectors | ~20 | |
| the flow walk itself | ~700 | 236,587 flow nodes at **3.0 µs each** |

**The largest remaining item is the flow walk at ~700 ms = 2.4%**, and it is a
single-pass construction of a graph the checker requires. Nothing in this
region is a suppression, a cache, or a state nobody reads — the two things that
looked like one were measured and priced at 0 and 0.13%.

## 8. What did NOT work

* **The `substring` hoist (§ 5).** 367,189 allocations removed, 0 ms. The
  round's own prediction, falsified by its own instrument.
* **A memo over the returned suffix set was designed and abandoned before
  building it.** The result is a pure function of `(start, hi)` and every
  closure has a distinct `container.pos`, so the key is distinct per call by
  construction and the hit rate would be ~0. Recorded because the shape looks
  memoizable and is not.
* **The first draft of the deferral pin** asserted `setsMaterialized <
  setsCreated` inside one fixture — which depends on how many closures that
  fixture makes the checker consult, and it failed. Restated as a comparison of
  the two ARMS over the same source, which is exact. Round 798 hit the same
  class of error one pin earlier and the lesson did not transfer: *a pin whose
  truth depends on unrelated checker behaviour is not a pin.*
* **The bogus control on the profile (§ 5).** Dead where the prize was measured.
* **The deferral itself, once its own census was read (§ 6).** The row went to
  zero and the work went to the checker. Two rounds in a row have now had a
  row-level saving evaporate under the created-vs-materialised (or
  calls-vs-distinct) test; the test is cheap and should be run BEFORE the
  timing, not after.

## 9. Reproducing

```bash
CP="build/classes/kotlin/jvm/main:$(cat build/bench/cp.txt)"
P=build/bench/tsc-project-*

java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --frontEnd $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --frontEnd --flowEagerSet $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --frontEnd --flowScanLegacy $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --verifyFlowScan $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --verifyFlowScan --flowScanBogus $P
```
