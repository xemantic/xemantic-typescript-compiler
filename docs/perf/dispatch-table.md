# (DISPATCH.1) step (a) — the derived per-kind spine handler table

*Round 732. Derived by instrumentation (`SpineDispatch`, opt-in `--dispatchProbe` /
`--dispatchGated`), verified by running the whole corpus suite and the compiler
profile with the table APPLIED. The instrumentation is behaviour-free when off.*

> **HEADLINE — THE PREMISE IS FALSIFIED. Do not land (DISPATCH.1)(c) as specified.**
> The measured upper bound on what a per-kind handler table can remove is
> **883 ms of an 18.5 s spine**, and that number is inflated by the probe's own
> `when(h)` indirection; the production-realistic figure is ~100–300 ms
> (0.3–1% of the compile), not the 1.0–2.5 s / 6–14% the queue item predicted.
> The item's own falsification clause applies. What the derivation found instead
> is where the spine's time actually is — six handlers, 71% of it — and that is
> a per-handler problem, not a dispatch problem. See § 5.

> **ROUND-867 (WARM.14) — THE ANSWER IS IN § 9, AND IT SUPERSEDES § 8's RANGE.**
> `s_p` — the production cost of ONE consultation that is entered and
> immediately declines — is **2.286 ns [2.148–2.512]**, so the prize is
> **`R` = 73.2 ms [68.7–80.4] = 1.06% of a warm rebuild [1.00–1.17%]**, 1.47%
> of the `checkSpine` row. § 8's `[0, 352] ms` becomes `[69, 80]` and its
> 187 ms point estimate was 2.6× too high. The lever is REAL and MARGINAL, the
> implementation shape is decided (a per-kind `Long` bitmask, whose skeleton
> § 9.6 prices at ~11.3 ms), and nothing was built. **§ 9.**

> **ROUND-866 (WARM.13) — READ § 8 BEFORE QUOTING ANYTHING ABOVE AS A CLOSURE.**
> Everything in §§ 1–7 is COLD. Warm the verdict is *unidentified*, not
> confirmed: the prize is bounded in **0–352 ms (0–5.1% of a warm rebuild)** and
> `--dispatchGated` is structurally unable to narrow it, because what it measures
> is `G − R` — its own machinery's price MINUS the prize. That machinery is now
> measured at **+715 ms, +14.4% of the warm `checkSpine` row, 8/8
> sign-consistent**, which is large enough to hide the whole prize and is what
> made § 5's cold GATED run come out slower. Round 847 § 5 of
> `warm-spine-attribution.md` had already re-taken the probe's UPPER bound warm
> (352 ms); its discount to "40–120 ms" is reasoning, not measurement.

---

## 1. What was built

`src/commonMain/kotlin/SpineDispatch.kt` plus three hooks in `Checker.kt`:

* **`spineEnterHandlerById` / `spineLeaveHandlerById`** — by-id twins of the
  production prologues: the same 46 enter and 13 leave calls, with the same
  `spineXxActive` guards, in the same order, reachable one at a time.
* **`spineEnterNodeProbed` / `spineLeaveNodeProbed`** — the probe paths. In
  `PROBE` they run every handler and record per-(handler, kind) consult counts,
  nanos, and observed WORK; in `GATED` they run only `enterTable[kindId]` /
  `leaveTable[kindId]`.
* **`SpineDispatch.work()`** — 23 call sites inside the OPEN handlers, each at a
  point where the handler does something observable (emits, pushes/pops a frame,
  writes an ambient map). This is what turns "which kinds does an unclosable
  handler act on" from a guess into a measurement.

Production is untouched: `spineEnterNode`/`spineLeaveNode` branch on
`SpineDispatch.mode != OFF` once and otherwise run their original straight-line
prologues. Two structural extractions were needed to make every handler
reachable by id — the three inline `cta-m3` blocks became
`spineCtaM3PropertyAnchor` / `spineCtaM3BodyWalkerAnchor` /
`spineCtaM3StatementAnchor`, and the `when (kindId)` tail became
`spineEnterKindDispatch`. The cost gate shows every one of its 20 deterministic
counters unchanged.

**Node populations come from the single-threaded check spine**, never from
`PassTiming.nodeKindHistogram` — round 717 measured that census as racy (857,350
vs 854,550 across two runs of the same binary), which is harmless for "which
kinds dominate" and fatal for "which kinds can this handler fire on".

## 2. Soundness rule for a closure

A closure is the claim *"this handler does nothing observable for any kind
outside this set"*. Only two justifications are accepted:

* **(a) syntactic** — the handler body is one top-level
  `when ((node as NodeBase).kindId)` over `NodeKind` constants with an inert
  `else`, or an `if (kindId != K) return` / `if (node !is K) return` prefix.
  This is a fact about the source, machine-checkable, not a guess about
  behaviour.
* **(b) `is Statement`** — the gate is `if (node is Statement)`, whose kind set
  is `SpineDispatch.STATEMENT_KINDS` (32 kinds, the transitive `Statement` /
  `Declaration` implementors in `Ast.kt`).

Everything else is **OPEN** and stays in the always-run list: parent-keyed edges
(`when (val p = node.parent)`), nodeId registries (`ctaM3NarrowThen[nid]`,
`ccetRestores`, `cpaLoopVarRestores`), and frame-owner identity
(`frames.last().owner === node`). **An empirically-observed work set is never
promoted to a closure** — the corpus is large but it is not a proof.

## 3. The table

46 enter handlers: **35 closed, 11 open.** 13 leave handlers: **3 closed, 10 open.**

| phase | handler | closure |
|---|---|---|
| enter | `ctaSpineEnter` | **OPEN** — `ctaM3NarrowThen[nodeId]` registry, written at an *ancestor* |
| enter | `cpaSpineEnter` | **OPEN** — for-in/of body arm keys on the PARENT |
| enter | `ccetSpineEnter` | **OPEN** — for-in/of body + if-then arms key on the PARENT |
| enter | `spineCtaM3PropertyAnchor` | PROPERTY_DECLARATION |
| enter | `spineCtaM3BodyWalkerAnchor` | BLOCK |
| enter | `spineCtaM3StatementAnchor` | VARIABLE_STATEMENT, EXPRESSION_STATEMENT, RETURN_STATEMENT, IF_STATEMENT |
| enter | `spineArithEnterNode` | **OPEN** — 5 parent-edge arms (if branches, binary right, conditional branches, for-in body, property-assignment initializer) |
| enter | `spineIanyEnterNode` | **OPEN** — `spineIanyEdgeEnter(parent, node)` runs for every node with a parent |
| enter | `spineDaEnterNode` | STATEMENT_KINDS + SOURCE_FILE, MODULE_BLOCK |
| enter | `spineOsEnterNode` | **OPEN** — second half is a `when (parent.kindId)` |
| enter | `spinePdEnterNode` | SOURCE_FILE, BLOCK, MODULE_BLOCK, VARIABLE_STATEMENT, EXPRESSION_STATEMENT, RETURN_STATEMENT, IF_STATEMENT |
| enter | `spineItEnterNode` | IDENTIFIER, PROPERTY_ACCESS_EXPRESSION |
| enter | `spineFpEnterNode` | CALL_EXPRESSION |
| enter | `spineAiEnterNode` | NEW_EXPRESSION |
| enter | `spineSyEnterNode` | BINARY_EXPRESSION, PREFIX_UNARY_EXPRESSION, TEMPLATE_EXPRESSION |
| enter | `spineCoEnterNode` | TYPE_ASSERTION_EXPRESSION, AS_EXPRESSION |
| enter | `spineB94EnterNode` | 9 kinds (var-stmt, 3 for kinds, arrow, fn-expr, method, ctor, set-accessor) |
| enter | `spineCeEnterNode` | ENUM_DECLARATION, IDENTIFIER, ELEMENT_ACCESS_EXPRESSION |
| enter | `spinePmrEnterNode` | 10 kinds (7 fn-like + expr-stmt, property-access, binary) |
| enter | `spinePiEnterNode` | CLASS_DECLARATION, CLASS_EXPRESSION |
| enter | `spineGxEnterNode` | BINARY_EXPRESSION |
| enter | `spineAcEnterNode` | 6 fn-like kinds |
| enter | `spineEvEnterNode` | SOURCE_FILE, BLOCK, MODULE_BLOCK |
| enter | `spineUyEnterNode` | CLASS_DECLARATION, INTERFACE_DECLARATION, TYPE_ALIAS_DECLARATION, YIELD_EXPRESSION |
| enter | `spineSrEnterNode` | IDENTIFIER |
| enter | `spineIaEnterNode` | BINARY_EXPRESSION |
| enter | `spineTdEnterNode` | 10 type-parameter-bearing kinds |
| enter | `spineExEnterNode` | PROPERTY_ACCESS_EXPRESSION |
| enter | `spineSmEnterNode` | 10 kinds |
| enter | `spineClEnterNode` | BINARY_EXPRESSION |
| enter | `spineSuEnterNode` | OBJECT_LITERAL_EXPRESSION |
| enter | `spineTcEnterNode` | TYPE_ASSERTION_EXPRESSION, AS_EXPRESSION |
| enter | `spineDelEnterNode` | DELETE_EXPRESSION |
| enter | `spineCpEnterNode` | CLASS_DECLARATION, CLASS_EXPRESSION |
| enter | `spineAbEnterNode` | CLASS_DECLARATION, CLASS_EXPRESSION |
| enter | `spineIyEnterNode` | YIELD_EXPRESSION |
| enter | `spineAaEnterNode` | CLASS_DECLARATION, CLASS_EXPRESSION |
| enter | `spineIdcEnterNode` | PREFIX_UNARY_EXPRESSION, POSTFIX_UNARY_EXPRESSION |
| enter | `spineNaEnterNode` | NEW_EXPRESSION |
| enter | `spineAfEnterNode` | IDENTIFIER |
| enter | `spineTpoEnterNode` | 10 kinds |
| enter | `spineUbdEnterNode` | STATEMENT_KINDS (+ the 3 for kinds, already inside) |
| enter | `spineCaEnterNode` | STATEMENT_KINDS + 8 (source-file, module-block, 2 clauses, binary, 2 unary, regex literal) |
| enter | `spineAtEnterNode` | 7 kinds |
| enter | `spineNuEnterNode` | BINARY_EXPRESSION, PROPERTY_ACCESS_EXPRESSION, ELEMENT_ACCESS_EXPRESSION, FOR_OF_STATEMENT |
| enter | `spineCmEnterNode` | BINARY_EXPRESSION |
| leave | `ctaSpineLeave` | **OPEN** — frame-owner identity |
| leave | `cpaSpineLeave` | **OPEN** — parent-keyed `run{}` blocks + nodeId restore list + frame pop |
| leave | `ccetSpineLeave` | **OPEN** — nodeId restore list + frame pop |
| leave | `spineArithLeaveNode` | **OPEN** — own-kind `when` FOLLOWED by a `frames.last().node === node` pop |
| leave | `spineIanyLeaveNode` | **OPEN** — frame-node identity |
| leave | `spineDaLeaveNode` | **OPEN** — frame-owner identity |
| leave | `spineOsLeaveNode` | **OPEN** — frame-owner identity |
| leave | `spinePdLeaveNode` | **OPEN** — frame-owner identity |
| leave | `spineCaLeaveNode` | **OPEN** — frame-owner identity |
| leave | `spineNpLeaveNode` | **OPEN** — the `else` arm reaches every Expression kind (while/do condition) |
| leave | `spineIrLeaveNode` | FUNCTION_DECLARATION, FUNCTION_EXPRESSION, ARROW_FUNCTION, METHOD_DECLARATION, GET_ACCESSOR |
| leave | `spinePmrLeaveNode` | **OPEN** — frame-owner identity |
| leave | `spineTpoLeaveNode` | **OPEN** — frame-owner identity |

**Two traps the derivation caught that reading the first three lines of each
handler would not:**

1. `spineArithLeaveNode` *looks* closed — its body opens with
   `when ((node as NodeBase).kindId) { BINARY_EXPRESSION -> …; VARIABLE_DECLARATION -> … }`
   — but a frame pop follows the `when`. Its measured working-kind set is **29
   kinds**.
2. `spineNpLeaveNode` *looks* closed to `{BINARY_EXPRESSION}` plus two loop
   kinds; its `else` arm is `if (node !is Expression) return`, i.e. every
   expression kind.

**Observed working-kind sets for the OPEN handlers** (from `work()`, over the
compiler profile — a lower bound, never a licence to close):

| handler | kinds observed working |
|---|---|
| `ctaSpineEnter` (registry arm) | 3 — BLOCK, EXPRESSION_STATEMENT, RETURN_STATEMENT |
| `cpaSpineEnter` (for-body arm) | 1 — BLOCK |
| `ccetSpineEnter` (for-body + if-then) | 2 — BLOCK, RETURN_STATEMENT |
| `spineArithEnterNode` (edge arms) | 28 |
| `spineIanyEnterNode` (edge arms) | 27 |
| `spineOsEnterNode` | **n/a — NOT INSTRUMENTED** (its parent arms have no `work()` call, so this 0 could never have been anything else; round-758 audit, round-750's rule) |
| `ctaSpineLeave` | 6 |
| `cpaSpineLeave` (restores) | 1 — BLOCK |
| `ccetSpineLeave` (restores) | 2 |
| `spineArithLeaveNode` | 29 |
| `spineIanyLeaveNode` | 31 |
| `spineDaLeaveNode` / `spinePdLeaveNode` | 3 — SOURCE_FILE, BLOCK, MODULE_BLOCK |
| `spineCaLeaveNode` | 6 |
| `spinePmrLeaveNode` | 6 |
| `spineTpoLeaveNode` | 5 |

The frame-pop family (`spineDa/Pd/Ca/Pmr/TpoLeaveNode`, `ctaSpineLeave`) is the
only OPEN group with a plausibly closable set — 3 to 6 owner kinds each. It is
also the cheapest group: 20–46 ms each. Closing it is worth ~20 ms in total.

## 4. Verification

* **Corpus suite with the table APPLIED to every node** (default `mode` flipped
  to `GATED`, whole suite run, then reverted): **12,882 tests, 0 failures, 3
  skipped.** This is the gate that sees kinds the profiles never exercise.
* **Compiler profile `--listAll`**: base / `--dispatchProbe` / `--dispatchGated`
  produce **byte-identical** diagnostics (46 errors, identical sorted lines).
* Production suite with the instrumentation off: 12,882 / 0 / 3.
* `scripts/cost_gate.py`: all 20 counters unchanged.

## 5. The assessment — why the estimate does not survive

Compiler profile, `--dispatchProbe`, 856,962 nodes.

| quantity | value |
|---|---|
| handler consultations per node, today | **59** (46 enter + 13 leave) |
| handler consultations per node, under the table | **21.65** |
| consultations removed | 32.0 M of 50.6 M = **64%** |
| **time in removable consultations** | **883 ms** (probe-inflated) |
| time in kept consultations | 17,609 ms |
| probe timestamp-pair overhead (subtracted) | 38 ns/call |

**64% of the consultations are worth 4.8% of the time.** That is the whole
result. The removable consultations cost 27 ns each *as measured*, and that 27 ns
is mostly the probe's own `when(h)` tableswitch plus the loss of inlining — in
production these handlers are straight-line inlined calls whose non-matching
path is one `kindId` load and one compare, i.e. 2–6 ns. The production prize is
therefore roughly **100–300 ms**, ~0.3–1% of a ~30 s compile.

Two independent confirmations that this is not an artefact of the estimate:

* **`--dispatchGated` measured SLOWER than production**: spine enter 11,699 ms
  vs 11,157 ms (+4.9%), leave 8,683 ms vs 8,087 ms (+7.4%). Skipping 37 of 59
  handlers did not pay for the array-indexed `when(h)` dispatch on the 21.65
  that remain. A production DISPATCH.1 would emit a straight-line per-kind
  `when` instead of an indirection, so this is not proof that the idea *must*
  lose — but it bounds how much headroom there is to win it back.
  **ROUND-758 CAVEAT: this is ONE run per mode, not interleaved, and no drift
  band is quoted.** +4.9% / +7.4% on a box whose interleaved null band is ±2.0%
  is directionally consistent with the row above but is not evidence at the
  strength "independent confirmation" implies. The real confirmation is the
  883 ms / 27 ns-per-consultation figure, which is a population.
* **IDENTIFIER, the item's own evidence**: 381,670 nodes, kept **1,142 ms**,
  removable **340 ms**. The kept part is not overhead — it is
  `spineIanyEnterNode` (376 ms), `ccetSpineEnter` (187 ms), `spineCeEnterNode`
  (178 ms) and the OPEN frame machinery, all doing real per-identifier work.
  Only 10 of 46 enter handlers and 12 of 13 leave handlers can be skipped at an
  identifier, and the 36 that can are the cheap ones.

**Why round 716's 1.0–2.5 s estimate was wrong.** It read "IDENTIFIER costs
2,746 ns/node and almost no handler wants an identifier" and concluded the cost
was consultation. The derivation shows the opposite: **22 of the 59 handlers
genuinely act at an identifier**, because the ones keyed on parent edges, frame
identity and nodeId registries are exactly the ones that cannot be closed — and
they are also the expensive ones. The decisive probe ("skip `spineEnterNode`
entirely for bare Identifiers → byte-identical output") skipped real work that
the compiler profile happens not to need; it was never a measurement of
consultation overhead.

## 6. Where the spine's time actually is

This is the finding worth carrying forward. Six handlers are **71% of the
measured spine**:

| handler | ms | closed? |
|---|---:|---|
| `cpaSpineLeave` | 4,366 | OPEN |
| `ccetSpineLeave` | 3,046 | OPEN |
| `spineCtaM3StatementAnchor` | 2,900 | closed to 4 kinds — and it is *already* only reached at 61,445 of 856,962 nodes, so 47 µs per node it acts on |
| `spineIanyEnterNode` | 1,025 | OPEN |
| `ccetSpineEnter` | 920 | OPEN |
| `ctaSpineEnter` | 586 | OPEN |
| *(next 53 handlers combined)* | *~4,766* | |

These are the `cta` / `cpa` / `ccet` frame skeletons (the g1/g2/g3 legacy-parity
scaffolding) plus the implicit-any pass. Their cost is not dispatch and not the
type system — it is the per-node bookkeeping those three skeletons do to
reproduce the legacy walkers' ambient state. Per-kind, the concentration is the
same shape: CALL_EXPRESSION 3,636 ms over 52,509 nodes (69 µs each),
VARIABLE_STATEMENT 2,835 ms over 14,712 (193 µs each), RETURN_STATEMENT
1,839 ms over 15,662 (117 µs each).

> **ROUND-758: those three per-kind figures are INDEPENDENTLY REPRODUCED —
> 3,752 / 2,841 / 1,752 ms — by the `--passTiming` per-kind counter, which until
> this round printed `enter+leave` while summing ENTER ONLY (it read
> 929 / 1,624 / 950, i.e. 4.0x / 1.7x / 1.8x low). Two instruments of completely
> different construction now agree to 0.2-5%, which is what makes both
> believable.** The corrected table also settles § 5's premise: **IDENTIFIER is
> 44.5% of the nodes and 8.4% of the spine's time (1,853 ms of 22,104), while
> the five statement-anchor kinds (VARIABLE_STATEMENT, EXPRESSION_STATEMENT,
> RETURN_STATEMENT, IF_STATEMENT, BLOCK) are 10% of the nodes and 40% of the
> spine.** Full table: `docs/perf/claim-audit-round758.md` § 5.1.

**The next unit of work is therefore per-handler, not per-kind**: attribute
`cpaSpineLeave` and `ccetSpineLeave` internally (they are 7.4 s together, 40% of
the spine) and find out what 5.4 µs and 3.5 µs per node is being spent on.

> **ROUND-733 ANSWER — and a correction to this section.** That attribution
> was done (`docs/perf/spine-leave-attribution.md`), and the phrase
> "cta/cpa/ccet legacy-parity frame bookkeeping" above is **wrong**: **88.4%
> of those two handlers' time is the cpa and ccet passes' OWN checking work**
> (`checkPropertyAccessInExpr`, `checkSingleCallExpressionTypes`) inside the
> frame-ambient block. The ambient install+restore is 360 ms and the ancestor
> climbs are 176 ms of 8,195. **A handler's per-handler nanos are its WORK,
> not its scaffolding** — this table says where the time is, never why, and
> inferring the why from it is the same mistake round 716 made one level up.
> The real target it uncovers is `checkSingleCallExpressionTypes`: 53.6 µs per
> CallExpression, 2.9 s over 52,413 of them, queued as **(CALL.1)**.

## 7. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTiming --listAll --dispatchProbe build/bench/tsc-project-*
# report + a per-(phase,handler,kind) CSV between the "csv" markers
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTiming --listAll --dispatchGated build/bench/tsc-project-*
```

To re-verify the table over the corpus, flip `SpineDispatch.mode`'s initialiser
to `GATED`, run `./gradlew jvmTest`, and revert. (`SpineDispatchProbeTest`
save-and-restores the mode so it survives that flip.)

---

# § 8 — (WARM.13), round 866: the WARM re-price, and why GATED cannot settle it

*Round 866, 2026-08-09. Appended; §§ 1–7 are the round-732 record and are
unchanged. Instruments: `BenchMain`'s new `gated` / `plain` / `gatedrows` /
`gatedfull` tiers, `scripts/round866-warm-gated.sh`,
`scripts/round866-warm-gatedrows.sh`, `scripts/round866_analyze.py`,
`scripts/round866_rows_analyze.py`. Compiler profile, 78 files / 46 errors on
every one of the 40 rebuilds below.*

> **HEADLINE — THE COLD VERDICT IS NOT CONFIRMED WARM AND IS NOT OVERTURNED
> EITHER: IT IS *UNIDENTIFIED*. The prize is bounded in `0 – 352 ms` (0 – 5.1%
> of a warm rebuild) with a point estimate of `187 ms = 2.7%` under one stated
> assumption, and the arm everybody would reach for — `--dispatchGated` — is
> STRUCTURALLY unable to narrow it, because what it measures is `G − R`: its own
> machinery's price MINUS the prize, one equation in two unknowns.**
>
> What IS now measured, 8/8 sign-consistent across two batches: **the gated
> machinery costs `+715 ms`, `+14.4%` of the `checkSpine` row, when it is made
> to skip nothing.** That number is why round 732's cold GATED run came out
> slower, and it is large enough to hide the entire prize.

## 8.1 First: the finding this round was commissioned to look for already existed

The brief was that (DISPATCH.1)'s closure is a COLD verdict. It is not — round
847 § 5 of `docs/perf/warm-spine-attribution.md` re-took the probe's upper bound
warm: **340–362 ms (mean 352), 10–11 ns per skipped consultation, over the same
32,006,965 skipped consultations**, a skipped consultation warming **2.95×**
against the spine's own 3.38×, and concluded the lever's relative value is
regime-invariant.

**That finding is not in this file, which is the file a next agent greps for
this question.** Cross-referencing it is half of this round's deliverable. What
round 847 did NOT do, and this round does, is run the GATED arm warm at all.

## 8.2 `--dispatchGated` is a LOWER-bound instrument, not a stand-in

Read `Checker.spineEnterNodeProbed`'s GATED branch before quoting it:

```kotlin
val tbl = SpineDispatch.enterTable[kid]
for (i in tbl.indices) spineEnterHandlerById(tbl[i], node)
```

Against production's straight-line `if (spineXxActive) spineXxEnterNode(node)`
sequence, that adds, **per kept handler**, a loop iteration with a bounds check
and a 46-arm `when(h)` tableswitch to a call the JIT can no longer inline into
46 distinct sites — plus, per node, one extra call frame and an
`Array<IntArray>` load. So

* it is **faithful in SEMANTICS** — the corpus and the profile are byte-identical
  under it (§ 4), which is what makes the derived table's soundness a fact; and
* it is **pessimistic in COST** by an amount nothing here measures.

Which is why § 5's "`--dispatchGated` measured SLOWER than production" was never
evidence about the table, only about the harness — a caveat § 5 half-states
already, and which round 866 makes structural.

## 8.3 The three-arm decomposition — and the one term it cannot reach

Per node the spine makes **59** handler consultations, of which the derived
table keeps **K = 21.65** and skips **S = 37.35** (round 732's census; `spine.nodes`
is still 856,962, cost gate unchanged). Write

* `s_p` — a skipped consultation's cost in PRODUCTION (a pure reject). The prize
  a production per-kind table collects is exactly `R = S·s_p·N`.
* `d` — the gated machinery's tax on a **kept** consultation;
  `d'` — its tax on a **rejecting** one; `A` — its per-node fixed cost.

Three arms, all arming the `rows` pass probe **identically** so its boundaries
cancel (round 793), all comparing the ONE row the table can move:

| arm | what it runs | its delta against `rows` |
|---|---|---|
| `rows` | production dispatch | — (the control) |
| `gatedrows` | gated machinery, DERIVED table | `A + K·d − R` |
| `gatedfull` | gated machinery, table holding EVERY handler for every kind | `A + K·d + S·d'` |

`gatedfull` is the arm round 732 never had: the same machinery, skipping
**nothing** by construction, so its delta contains no `R` at all. (A full table
IS the production handler set, so it is a pure cost arm — its output is
identical and a missed restore degrades to running production semantics slowly,
never to a wrong answer.)

**Measured**, 2 batches × 4 processes, each process 3 warm-up + 6 measured
rebuilds then 4 tier rebuilds as two adjacent pairs, tier ORDER rotated across
processes so neither arm always holds the coldest instrumented slot:

| arm | n | median Δ`checkSpine` | mean | min | max | sign |
|---|---:|---:|---:|---:|---:|---|
| `gatedfull` | 8 | **+715.5 ms (+14.45%)** | +694.6 | +454.4 | +846.4 | **8/8 slower** |
| `gatedrows` | 8 | +75.2 ms (+1.51%) | +25.3 | −275.8 | +282.5 | 5/8 slower |

control `rows` `checkSpine`: n=16, mean 4,960.4 ms, sd 218.3 (**4.40%**).

From those two rows,

```
S·(s_p + d') = Δ(gatedfull) − Δ(gatedrows) = 640.2 ms = 20.00 ns per skipped consultation
R            = A + K·d − Δ(gatedrows)
```

and **`d` is identified by no arm here.** The corners:

| assumption | R |
|---|---:|
| `d = d'` (a uniform per-consultation tax, 14.15 ns, `A = 0`) | **187.3 ms** = 2.7% of a warm rebuild, 3.8% of the row |
| `d = 0` (the whole tax falls on the rejecting consultations) | ~0 ms |
| round 847's independent probe cap | ≤ 352 ms |

`d ≤ d'` is the only inequality that can be argued (a rejecting handler is the
most inlinable thing in the prologue, so it loses the most by being reached
through a tableswitch), and it does not close the gap. **`R ∈ [0, 352] ms`.**

The 20.00 ns per skipped consultation cross-checks against round 847
independently: its probe attributes **10–11 ns** to the same consultation with
the tableswitch and the call CALIBRATED OUT, so ~10 ns of tableswitch + call on
top is exactly the residue this arm should see. Two instruments of different
construction, agreeing.

## 8.4 The wall arm, and a second instance of round 840(c)

Before the row arms, the same comparison was run on WALL time (`gated` vs the
new null `plain` tier, 2 batches × 2 processes × 2 pairs):

| batch | n | median Δ | gated faster |
|---|---:|---:|---|
| 1 | 4 | **−102.3 ms (−1.49%)** | 3/4 |
| 2 | 4 | **+237.7 ms (+3.53%)** | 0/4 |
| all | 8 | +117.4 ms (+1.73%) | 3/8 |

**The two batches disagree in SIGN.** Batch 1 alone reads as "GATED is 1.5%
faster warm — build the table"; batch 2 alone reads as "3.5% slower — closed".
Per-arm sd is 2.23% / 1.53%, both over the ~1% quiet-box rule. This is round
840(c)/858's law for the third time in this arc, and it is why the round moved
to the row: the wall carries the front end and the ~416 tail passes, ~34% of a
warm rebuild that is, for this question, pure drift.

## 8.5 What would settle it, and what it must beat

One number decides: **`s_p`, the production cost of one rejecting handler
consultation, in the production inlining regime.** `R = 32.0 M × s_p`, so the
1% floor (≈69 ms of a ~6,900 ms warm rebuild) is cleared at **s_p ≥ 2.2 ns** —
which is roughly "one field load, one compare, one predicted branch", i.e. the
lever is plausible on its face and has never been measured.

It cannot be measured by a timestamp pair: at ~2–14 ns it is far below the warm
boundary cost (97–202 ns, round 850), so **the instrument is round 759's
amplification** — `r` extra consultations of a handler that provably rejects,
inserted in the PRODUCTION prologue behind a static flag, with `s_p` read off
the slope and two values of `r` cancelling the boundary algebraically. Its
hazard is equally clear and is why it was not attempted here as an afterthought:
a rejecting consultation is exactly the shape a JIT can prove side-effect-free
and delete, so the arm needs round 759's arithmetic falsification (a sink that
is an exact multiple of the amplified count), not a plausible-looking slope.

And any implementation must ALSO beat its own dispatch tax, because that is what
killed GATED: a production table that keeps `R` must dispatch its kept 21.65
consultations for less than `R/K·N` ≈ **10 ns each** under the uniform corner.
Two shapes, each with a named hazard:

* **A dense per-kind `when (kindId)` with straight-line call lists** — near-zero
  dispatch, but 138 arms × up to ~20 calls is far over HotSpot's 8,000-bytecode
  `HugeMethodLimit`, i.e. *never JIT-compiled* and a measured −33.6% warm cliff
  (round 845). It must be split by a CONTIGUOUS key range with the hottest kinds
  in the entry function (round 802) — that is a design constraint, not a detail.
* **A per-kind `Long` bitmask** (46 enter handlers fit in 64 bits): one array
  load per node, then the existing straight-line sequence with each
  `if (spineXxActive)` field load replaced by a bit test on a register-resident
  local. No huge method, and it removes the 46 per-node field reloads that the
  intervening calls force today — but its own 46 branches remain, and whether
  that clears 10 ns per kept consultation is itself unmeasured.

## 8.6 What this section does NOT show

* **No production table was built and nothing was optimized.** The four new
  tiers and two scripts are the whole landing.
* `d` is unidentified, so `187 ms` is a point estimate under a stated
  assumption, never a measurement. The measurement is `Δ(gatedfull)` and
  `Δ(gatedrows)`; everything else is arithmetic on top of them.
* One profile, one box, `--noEmit`, check-only.
* The `gatedrows` arm's spread (−276 to +283 ms against a control sd of 4.40%)
  is wide enough that its median carries ±~100 ms, which propagates straight
  into `R`.

---

# § 9 — (WARM.14), round 867: `s_p` MEASURED, and the question settled at ~1%

*Round 867, 2026-08-09. Appended; §§ 1–7 are the round-732 cold record and § 8
is round 866's warm re-price — both unchanged. Instruments: `SpineAmp` +
`SpineDispatch.enterSkipMask`/`leaveSkipMask` + `Checker.spineAmpPass`,
`--spineAmp N`, `BenchMain`'s `amp<N>`/`ampc<N>` tiers,
`scripts/round867-warm-amp.sh` (batch 1), `scripts/round867-warm-amp2.sh`
(batch 2, the one quoted), `scripts/round867_analyze.py`. Compiler profile,
78 files / 46 errors on every one of the 48 rebuilds below.*

> **HEADLINE — `s_p = 2.29 ns` [envelope 2.15–2.51], so
> `R = 32.0 M × s_p = 73.2 ms` [68.7–80.4] = **1.06% of a warm rebuild**
> [1.00–1.17%], or 1.47% of the `checkSpine` row it lives in. Two independent
> batches, three independent `r`-pairs each, agreeing to 1%.**
>
> § 8's `[0, 352] ms` is now `[69, 80] ms`, and its 187 ms point estimate — the
> `d = d'` uniform-tax corner — is **2.6× too high**. The lever is REAL and it
> is MARGINAL: one band, at the very edge of what `ab-warm.sh` (±1.0%) can
> confirm, and not the 2.7% that corner suggested.

## 9.1 What was amplified, and why that population

`s_p` is the production cost of a handler consultation that is entered and
immediately declines. At 2 ns it is two orders of magnitude below a warm probe
boundary (97–202 ns, round 850), so the instrument is round 759's
AMPLIFICATION, not a timestamp pair: per node, `r` extra passes over the
consultations under ONE bracket, and two values of `r` cancel the boundary
algebraically.

The population is **exactly the (handler, kind) pairs the derived table would
skip** — `SpineDispatch.enterTable`/`leaveTable`'s complement, taken as a
bitmask — and not "all 59 consultations", because the prize is by definition
what a table stops consulting. The probe MEASURES that population rather than
inheriting it, and it lands on § 8's number to the unit:

| | measured by `SpineAmp` | § 8 / round 732 |
|---|---:|---:|
| bracketed nodes | **856,962** | 856,962 |
| would-consult slots | **32,006,965** | 32,006,965 |
| S (skipped consultations per node) | **37.3493** | 37.35 |

Weighting needs no argument: the bracket runs at every node of the real compile,
so each (handler, kind) pair is amplified exactly as often as it occurs. Round
758's law still binds the write-up — a count is not a cost, and this IS the
forbidden multiplication — so § 9.4 states what varies inside the population and
§ 9.5 states the one bias the instrument cannot reach.

## 9.2 Why batch 1's design was wrong, and what batch 2 changed

Batch 1 ran BOTH arms in ONE process at `r` = 8/16/32 and read a per-arm sd of
**16–38%**. Two artifacts, both visible in its own numbers:

1. **The two arms share one compiled `spineAmpPass`.** They differ only in the
   mask they pass it, so whichever arm runs first writes the branch profile the
   other is compiled against — and an arm whose consultation branches were
   profiled as NEVER TAKEN pays an uncommon trap for each of them. (`p4`'s
   control arms were the batch's cheapest and its real arms its dearest, by 2×.)
2. **The first amplified rebuild in a process is the one that warms
   `spineAmpPass`** — the uninstrumented loop never touches it.

Batch 2 gives **each arm its own JVM**, discards a leading throwaway amp
rebuild, and widens the spread to `r` = 16/48/96 (the boundary is a per-node
constant, so a wider spread divides the same noise by a larger denominator).
Per-arm sd falls to **2.0–5.0%**.

## 9.3 The measurement (batch 2: 8 processes, 24 analysed rebuilds)

`p(r)`, ns per bracketed node, median of 4 processes:

| arm | r=16 | r=48 | r=96 | sd |
|---|---:|---:|---:|---:|
| REAL | 1,841.05 | 4,956.18 | 9,723.74 | 3.8 / 2.6 / 5.0% |
| CONTROL | 274.75 | 697.23 | 1,327.89 | 4.9 / 2.0 / 2.3% |

Slopes (ns per PASS per node) and the three independent `r`-pairs:

| pair | slope REAL | slope CONTROL | `s_p` = (Δslope)/S |
|---|---:|---:|---:|
| (16,48) | 97.348 | 13.203 | **2.253 ns** |
| (48,96) | 99.324 | 13.139 | **2.308 ns** |
| (16,96) | 98.534 | 13.164 | **2.286 ns** |

**Spread across the three pairs: 2.4%.** Per-process slopes (REAL sd 5.8%,
CONTROL sd 1.9%) give a worst-case envelope of **2.148–2.512 ns**.

**Batch 1 replicates it from the other design**: its median-based pairs read
2.462 / 2.162 / 2.262 ns, median **2.262** against batch 2's **2.286** — a 1%
disagreement between a 26–38%-sd batch and a 2–5%-sd one. Round 840(c)'s
replication requirement is met, and by two batches that differ in construction
rather than only in draw.

```
R = 32,006,965 × 2.286 ns = 73.2 ms = 1.06% of a ~6,900 ms warm rebuild
                                    = 1.47% of the 4,960 ms `checkSpine` row
envelope:                    68.7 – 80.4 ms = 1.00 – 1.17%
```

## 9.4 The falsification — ARITHMETIC, not a plausible slope

A repeated predicate whose result is unused is what a JIT deletes, and the
failure is silent: a clean linear fit of nothing, which here would have read as
`s_p ≈ 0` and CONFIRMED the closed direction for the wrong reason. Four checks,
all reported by the probe itself and all green on **48/48 rebuilds across both
batches**:

1. **The sink identity.** `consults == r × expected`, EXACTLY, at every `r`
   (256,055,720 = 8 × 32,006,965; 3,072,668,640 = 96 × 32,006,965). `expected`
   is accumulated once per node by a different code path (a `countOneBits` of
   the masks) from the one the passes count with (a per-slot branch).
2. **The control consults zero** over a population that is `> 0` — so its
   suppression is measured, not asserted.
3. **Non-inlinability, statically.** `spineAmpPass` is **1,429 bytecodes**, 4.4×
   over HotSpot's 325-byte `FreqInlineSize`, so it cannot be inlined into the
   `r` loop and cross-iteration hoisting is structurally impossible rather than
   merely unlikely. (A 307-byte leave-only second method was merged into it for
   exactly this reason.)
4. **The control arm is a floor the real arm must clear**, and it clears it by
   7.5×.

Every rebuild also answered the same program (78 files, 46 errors), which is the
behaviour check: the amplified set is the table's SKIP set, whose handlers do
nothing at those kinds (§ 4).

## 9.5 What varies inside the population, and the one bias left

**Within the population.** The 59 handlers decline for different reasons — an
own-kind test, a PARENT-edge test, a FRAME-owner identity test, a nodeId
registry probe — and the table's skip set is the own-kind-closable part, whose
per-slot shape is nonetheless not uniform: **36 of the 59 handlers are under
`FreqInlineSize`** (median handler size 257 bytecodes) and 23 are over it, i.e.
some consultations are a call the JIT can never remove and others are an
inlinable guard. `s_p` is the MEAN over the population as it occurs, which is
the right quantity for `R` and the wrong one for reasoning about any single
handler.

**The bias the instrument cannot reach**, stated plainly because it is this
round's `d`: the amplified consultation happens at `spineAmpPass`'s call site,
not at `spineEnterNode`'s. For the 23 over-limit handlers both sites must call;
for the other 36 the two sites' inlining decisions could differ, and the
DIRECTION of that difference is unknown and unmeasured. A second, smaller and
signed bias: the amplified slot pays one bit test (~0.22 ns, from the control
arm's 13.16 ns / 59 slots) that production does not, so `s_p` is over-read by
roughly that much — pushing `R` toward the low end of its envelope.

## 9.6 The decision, and what a builder must beat

`R` clears the 1% floor — and only just, with the envelope's low end AT 1.00%.
So:

* **(DISPATCH.1) is not confirmed closed warm, and is not a 2.7% prize either.
  It is a ~1% lever**, which is a real one by this arc's standards and a
  marginal one by its measurement standards: `ab-warm.sh`'s band is ±1.0%, so a
  landed table could not be confirmed by the harness that would judge it, and
  would have to be defended on `cost_gate.py` counters plus this decomposition.
* **(WARM.13) reopens as an IMPLEMENTATION item** (nothing was built this
  round), with one new number that changes its outlook: **the control arm is a
  direct measurement of the proposed per-kind `Long`-bitmask dispatch
  skeleton** — a mask load plus 59 register-resident bit tests — at **13.16 ns
  per node**, i.e. **~11.3 ms over the whole compile, 15% of the prize**. That
  is the first evidence that a table can be built for less than it saves:
  round 866's `+715 ms` GATED tax was the `IntArray` walk, the 46-arm
  tableswitch and the extra frame, NOT the idea. Net, the bitmask shape is
  worth ~62–73 ms (0.90–1.06%).
* The **8,000-bytecode cliff is a hard design constraint**, not a detail: a
  dense per-kind `when (kindId)` with straight-line call lists (138 arms × up to
  ~20 calls) is far over `HugeMethodLimit`, i.e. never JIT-compiled and a
  measured −33.6% warm cliff (round 845). Split by a CONTIGUOUS key range, keep
  the hottest kinds in the entry function, and never a fall-through chain
  (round 802). The bitmask shape avoids the cliff entirely, which is the second
  reason to prefer it.

## 9.7 What this section does NOT show

* **Nothing was optimized and no table was built.** The landing is the probe,
  its two tiers, two scripts, an analyzer and five pins.
* `s_p` is the mean over the skip population at the amplifier's call site; § 9.5
  names the residual, and it is the reason the answer is quoted as a range.
* One profile, one box, `--noEmit`, check-only, and a `~6,900 ms` warm
  denominator taken from round 843 rather than re-measured here.
* The marginal (repeat) nature of an amplified cost is argued to be small here
  — production consults the same 46 `spineXxActive` fields at every one of
  856,962 nodes, so they are hot in production too — but it is argued, not
  measured.

## 9.8 Reproducing

```bash
./gradlew compileKotlinJvm compileTestKotlinJvm
./gradlew --stop && pkill -f 'KotlinCompile[D]aemon'
bash scripts/round867-warm-amp2.sh a      # 4 processes, ~7 min
bash scripts/round867-warm-amp2.sh b      # 4 more
python3 scripts/round867_analyze.py --drop-first build/bench/round867amp2/*.log
```

or, cold and in one shot, from the CLI:

```bash
java -cp <cp> com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTiming --spineAmp 48  build/bench/tsc-project-*
java -cp <cp> com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTiming --spineAmp -48 build/bench/tsc-project-*
```
