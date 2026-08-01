# (CALL.1) step (a) — what is actually inside `checkSingleCallExpressionTypes`

*Round 734. Third in the sequence `docs/perf/dispatch-table.md` (732, which found the
six hot handlers) → `docs/perf/spine-leave-attribution.md` (733, which found this
function) → here. Derived by instrumentation (`CallSections`, opt-in
`--callSections`), verified by the whole corpus suite, an identical profile
`--listAll` probe-on vs probe-off, and a cost gate at +0.00% on all 20 counters.
The instrumentation is behaviour-free when off.*

> **HEADLINE — BRANCH B. The cost is signature resolution and argument relations.**
> **78% of the function is type-system work**: `checkArgumentsAgainstSignature`
> alone is 1,357 ms (53%) and `getCalleeType` 474 ms (18%). The (CALL.1) item's
> Branch A — "the never-firing emission sites' pre-work dominates … hoisting it
> behind a cheap pre-test removes 1–2 s" — is wrong twice over. **Quantitatively:
> everything in the function that is not type-system work totals 557 ms, of which
> ~70 ms is the probe itself — a theoretical maximum prize of ~490 ms, i.e. 1.6%
> of a 30.5 s compile, inside the ±2% drift band and therefore not even
> measurable by wall time.** **Mechanically: there is no pre-gate emission work to
> hoist.** All 22 `getLineAndCharacterOfPosition`, all 17 `expressionTrueEnd` and
> all 11 `typeToString` sites are DOWNSTREAM of the decision to emit — 16 of them
> literally inside `if (length > 0) {`. What the never-firing prologue costs is
> the *gates*, and the gates already are the cheap pre-test.
> **No optimisation was landed. The lever is the relation engine (M3.1); the
> concrete next unit is (CALL.2), § 6.**

---

## 1. What was built

`CallSections` in `src/commonMain/kotlin/SpineDispatch.kt`, plus boundaries inside
`checkSingleCallExpressionTypes`:

* **The function was split into a wrapper and `…Core`.** The wrapper branches once
  on `CallSections.mode` and otherwise calls the core directly — no `try`/`finally`
  and no bookkeeping in production. When on, it opens the partition and closes it
  from a `finally`.
* **A running-timestamp split between the core's top-level sections** (`at`), 16 of
  them in source order. Nothing in the control flow was restructured: a boundary is
  one statement inserted between two existing top-level statements.
* **Five nested sub-measures** — `checkArgumentsAgainstSignature` on each of the two
  paths that call it, `checkArgumentsAgainstOverloads`, the TS2793
  "implementation would have succeeded" probe, the five dedicated walkers in the
  single-signature branch — plus a sixth that measures the whole never-firing
  prologue as ONE span (see § 2's calibration note for why that one exists).

**Why the running section lives in the object.** Unlike a spine handler, this
function has ~20 early `return`s, so a section can be left without reaching the next
boundary. `CallSections.end` closes whatever is open. The pay-off is that
`calls[s]` is the number of invocations that REACHED section `s`, so the drop
between consecutive sections is the exit count — **the exit profile is free** (§ 3),
no `hit` counters needed.

`CallSectionProbeTest` pins that (a) the probe is behaviour-free ON versus OFF on a
fixture that emits the instrumented function's OWN codes (TS2554 and TS2345 — a
clean fixture would make the comparison vacuous), (b) nothing is recorded when OFF,
(c) the sections partition every invocation and the derived exit counts sum back to
the invocation count, and (d) the nested sub-measures are no more frequent than the
sections containing them.

## 2. Calibration — two artifacts, both measured, both corrected

Round 733's lesson was that a cold startup calibration reads 40 µs against a true
42 ns. Calibrating IN SITU is necessary but, it turns out, not sufficient.

| draft | what it measured | read | why it was wrong |
|---|---|---|---|
| 1 | one empty span from `begin` to the core's first boundary | 922 ns | spans the wrapper's non-inlinable call into a 3,587-bytecode method — a cold transition, not a timestamp pair. Drove six sections NEGATIVE. |
| 2 | 8 empty spans via `repeat(8) { at(...) }` | 360 ns | a `repeat` loop puts a **back-edge safepoint poll inside every empty span**, so stop-the-world pauses are attributed to the calibration |
| 3 | 8 empty spans, **unrolled** | 306 ns | still high — see the differential below |

**The honest figure is DIFFERENTIAL, and it is ~86–92 ns.** The prologue is measured
twice in the same run: as seven separate sections (280 ms) and as ONE span (253 ms).
The difference is exactly six extra boundaries over 52,413 invocations →
**86 ns each** (the previous run gave 92 ns over the same construction). That
independently agrees with an A/B of `--passTiming` with and without `--callSections`,
which moved `checkSpine` by **+29 ms** — invisible against a ±2% drift band.

**So: total probe inflation inside the partition is ~70 ms of 2,564 ms = 2.7%**, and
the report's `net` column (which subtracts the pessimistic 306 ns) is a LOWER bound
while `raw` is an upper one. The tables below quote RAW. Sound for relative
attribution; not a production cost model.

## 3. The exit profile — free, and it disqualifies a quarter of the function

Compiler profile (`build/bench/tsc-project-*`), **52,413 invocations**.

| leaves the function | invocations | share |
|---|---:|---:|
| the `calleeType === anyType \|\| errorType` bail | **26,496** | **50.6%** |
| the single-signature branch | 22,145 | 42.2% |
| the overload branch | 3,640 | 6.9% |
| the explicit-type-argument branch | 101 | 0.2% |
| the union-callee branch | 31 | 0.1% |
| **the `signatures.isEmpty()` branch** | **0** | **0%** |
| any of the seven prologue walkers | **0** | **0%** |

Two facts fall straight out. **Half of every call expression in the program is
resolved, then discarded at the any/error bail** — after `getCalleeType` has run in
full. **(Round-758 correction: half the CALLS, 8–10% of the TIME — the discarded
resolutions cost 1,452 ns against 16,491 ns for the kept ones. See § 6.)** And the ~240-line `signatures.isEmpty()` branch, with its seven emission
sites (TS2348 / TS6234 / TS2721 / TS2722 / TS2723 / TS2349 ×3), is never entered
here at all, so its `binderResults × top-level-statements` scan — the one piece of
genuinely pre-gate work in the function — never runs on this profile.

## 4. The attribution

Raw ms, final run; percentages of the 2,564 ms raw partition.

| region | ms raw | share |
|---|---:|---:|
| **type-system work** | **2,007** | **78.3%** |
| — `checkArgumentsAgainstSignature` (single-sig path) | **1,357** | **52.9%** |
| — `getCalleeType` | **474** | **18.5%** |
| — TS2793 impl-would-have-succeeded probe (`allArgumentsMatch`) | 101 | 3.9% |
| — `checkArgumentsAgainstOverloads` | 53 | 2.1% |
| — `getCallSignaturesOfType` | 19 | 0.7% |
| — `checkArgumentsAgainstSignature` (type-args path) | 3 | 0.1% |
| **everything else** | **557** | **21.7%** |
| — the seven never-firing prologue walkers, as one span | 253 | 9.9% |
| — TS2722 optional-member gate + TS2347/null-callee/any-bail gates | 102 | 4.0% |
| — the five dedicated walkers in the single-sig branch | 59 | 2.3% |
| — union / no-sigs / type-args / overload residues | ~73 | 2.8% |
| — the probe's own boundaries | ~70 | 2.7% |

Per-section detail (raw ms; `reached` is the invocation count, so ns/call is
comparable only within a column):

| section | ms raw | reached | ns each |
|---|---:|---:|---:|
| B216 dependent indexed-access | 60 | 52,413 | 1,152 |
| `reduce<U>` keyof callback | 27 | 52,413 | 522 |
| tuple-union `.filter` optional | 23 | 52,413 | 439 |
| compose-chain `.map` member | 19 | 52,413 | 369 |
| `Object.create` primitive arg | 26 | 52,413 | 501 |
| CJS default-as-namespace | 66 | 52,413 | 1,252 |
| super call / `super.method` | 59 | 52,413 | 1,126 |
| **`getCalleeType`** | **474** | 52,413 | **9,053** |
| TS2722 optional member | 50 | 52,413 | 959 |
| TS2347 + null/undefined callee + any bail | 52 | 52,413 | 992 |
| union callee | 19 | 25,917 | 741 |
| `getCallSignaturesOfType` | 19 | 25,886 | 746 |
| no-call-signatures branch | 15 | 25,886 | 576 |
| explicit type arguments branch | 28 | 25,886 | 1,091 |
| **single signature branch** | **1,560** | 22,145 | **70,463** |
| overload branch | 60 | 3,640 | 16,505 |

Each row carries one ~90 ns boundary, so a row at 300–500 ns/call is measuring
mostly its own boundary. That is exactly why the prologue is ALSO measured as a
single span: seven rows summing to 280 ms are one span of **253 ms**.

## 5. The item's Branch A, priced and disproved

> *"if the never-firing emission sites' pre-work dominates —
> `getLineAndCharacterOfPosition` ×22, `expressionTrueEnd` ×16, `typeToString` ×12,
> all computed before the gate that decides whether to emit — then hoisting that
> work behind cheap pre-tests removes 1–2 s."*

The counts are right (22 / 17 / 11 in the function, 18 `diagnostics.add`). The claim
about them is not, on two independent grounds.

1. **STATIC: none of them is pre-gate.** Every one of the 22
   `getLineAndCharacterOfPosition` calls is downstream of the decision to emit — 16
   sit directly inside `if (length > 0) {`, the rest inside `if (display != null)`,
   `if (pname != null)`, or a `run{}` block past all its `?: return@run` gates.
   There is nothing to hoist: the gates ARE the cheap pre-test the item proposes
   adding. (Reproduce: list the line before each call site over the function's line
   range.)
2. **DYNAMIC: the never-firing region is 253 ms, not 1–2 s.** All seven prologue
   walkers fire zero times on this profile (`returnedIn=0` for each) and cost
   253 ms *in gates alone*. Add every other non-type-system byte of the function
   and the total is 557 ms, ~70 ms of which is the probe. **A maximum of ~490 ms —
   1.6% of the compile, and the ±2% drift band on a 30.5 s compile is ~610 ms.**
   The prize is smaller than the noise that would have to measure it.

This is the third consecutive round in which a plausible reading of an aggregate
predicted a lever 5–17× larger than the measurement (732: 883 ms vs 1.0–2.5 s;
733: 176 ms vs 1–3 s; here: ≤490 ms vs 1–2 s). The common error is unchanged:
**the population was never priced before the fix was designed.**

## 6. What the numbers DO point at

`checkArgumentsAgainstSignature` is now the largest single measured cost in the
compiler: **1,357 ms over 22,145 calls = 61 µs each**, and it is a **1,534-line
function** — larger than the one this round attributed. It is 53% of
`checkSingleCallExpressionTypes`, which is itself ~10% of the compile. Two
observations bound the follow-on:

* Whole-compile counters for the same run: `getTypeOfExpression` 3,911 ms /
  701,736 calls (recompute ×2.7), relations at depth 0 699 ms, narrowing walks
  3,260 ms. So the 1,357 ms is argument type computation plus relation work — the
  relation engine's own territory (M3.1), reached through this path.
* ~~`getCalleeType` costs 474 ms and **half its results are thrown away** at the
  any/error bail three sections later (26,496 of 52,413). That is not a caching
  question (§ 0 of `docs/ARCHITECTURE-RETHINK.md` closed those) — it is a question
  of whether the bail's verdict is knowable more cheaply than by resolving.~~
  **CLOSED, ROUND 758 — and this bullet is the arc's own textbook case of a
  FREQUENCY spent as a POPULATION.** "Half its results" is 50.6% of the CALLS;
  measured by outcome (`CallSections.N_CALLEE_BAIL` / `N_CALLEE_LIVE`, two runs):

  | | calls | share of calls | ms raw | share of the section | ns each |
  |---|---:|---:|---:|---:|---:|
  | **result DISCARDED** | **26,496** | **50.6%** | **37 / 46** | **8.0% / 9.6%** | **1,452** |
  | result USED | 25,917 | 49.4% | 426 / 435 | 92% / 90% | 16,491 |

  **A discarded resolution costs 11× less than a kept one, so the implied
  ~237 ms is 38 ms — out by 6.2×, and 0.14% of the compile against a ±2.0%
  band.** The question answers itself in reverse: **the bail's verdict already
  IS cheap**, because an `any`/`error` callee is exactly the one that fails
  resolution fast. § 0's law again, with no cache in sight. Full derivation:
  `docs/perf/claim-audit-round758.md` § 5.2.

**(CALL.2)** — attribute inside `checkArgumentsAgainstSignature` the same way, and
price the arg-type vs relation split before proposing anything. The harness
generalises: `CallSections` needs only new section constants.

## 7. Verification

* Full corpus suite: **12,892 tests, 0 failures, 3 skipped** (12,887 + 5 new
  `CallSectionProbeTest` pins).
* Compiler profile `--listAll`: production and `--callSections` produce
  **identical** diagnostics (46 errors, identical sorted lines).
* `scripts/cost_gate.py`: all 20 deterministic counters **+0.00%**.
* Probe cost, measured not asserted: `checkSpine` 25,328.6 ms without the probe vs
  25,357.9 ms with it (**+29 ms**, +0.1%).

## 8. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --callSections build/bench/tsc-project-*
# report + a per-section CSV between the "csv" markers
```

---

# Round 793 — (ENGINE.2g): does round 792's pre-gate generalise?

## 9. The headline

Round 792 skipped `checkMemberAccessMissing` entirely for 31% of its calls
because **all ~42 of its emissions assert one proposition** — a property is
absent — so one cheap refutation kills all of them. (ENGINE.2g) asked where else
that works. The inventory (every figure RE-MEASURED at HEAD, law 1) is:

| candidate engine | ms at HEAD | fires on the compiler profile | state |
|---|---:|---:|---|
| **the `checkSingleCallExpressionTypes` PROLOGUE — 7 walkers** | **219** | **0** | **taken this round** |
| `emitTs18048` closure-captured receiver (B464) | 171 | 0 | already round-489 pre-gated |
| `cpaComputeArgCtxTypes` | 242 (189 net of its callees) | n/a | already gated round 788 |
| `emitTs18048` optional-property receiver | 50 | 0 | own type work, no shared key |
| `checkPrivateMemberAccess` | 44 | 0 | too small to price |
| TS2793 "impl would have succeeded" probe | 94 | ~0 | deferrable — left in the queue |
| the five single-signature dedicated walkers | 61 | — | not opened |

**The proposition form does NOT generalise, and that is the round's first
finding.** The seven prologue walkers emit TS2345 / TS18048 / TS2339 / TS2349 /
TS2754 — five different claims — so no single refutation can kill them the way
"the property resolves" killed 42 emissions. What they share is a **KEY**: each
is reachable only for a narrow syntactic shape of the CALLEE, so one
classification of the callee refutes all seven at once. **The transferable method
is "one cheap question in front of many emissions", not "one proposition".**

Measured with `--ccetPreGate` (computes the gate, honours nothing, splits the
prologue's measured time by the verdict):

| profile | invocations | gate refuses | of those, FIRED | kept | of those, FIRED |
|---|---:|---:|---:|---:|---:|
| compiler | 52,413 | **51,394 (98.1%)** | **0** | 1,019 | 0 |
| services | 69,555 | **68,200 (98.1%)** | **0** | 1,355 | **4** |
| harness | 78,724 | **76,837 (97.6%)** | **0** | 1,887 | **14** |

**196,431 refused invocations, 0 firings; all 18 firings in the kept
complement.** `--ccetPreGateBogus` (the gate refuses everything) reports **14**
on harness, so the falsifier column is alive — on the *compiler* profile it
reports 0, because nothing there reaches a prologue emission at all, and that is
worth stating plainly: **the control this round needed could not be run on the
profile the prize was measured on.**

## 10. The prize, and why it is smaller than the row it came from

The prologue reads **219 ms as one span** (round 734: 253 ms) over 52,413
invocations. That number is not the production cost: the span contains **six
intermediate `CallSections.at()` boundaries** that production never executes. At
the in-situ calibration (299–306 ns) those are 96 ms; at the differential
boundary cost CLAUDE.md sanctions (~86–92 ns) they are 28 ms. So the production
prologue is **123–191 ms**, and the gate refuses 98% of it.

Landed, the same probe measures the seven partition rows at **66 ms raw against
261 ms at HEAD (−195 ms)** — of which 28–92 ms is boundaries that were never
production's to pay. Net: **≈ 105–165 ms, 0.4–0.6% of a 26.5 s check-only
compile**, minus a gate that costs 587 ns per call as measured (one boundary
included, so ~280–500 ns true, ~15–26 ms).

**And law 2 (round 788: an aggregate that is skippable is not thereby
recoverable) does NOT bite here — the counters say so.** The only resolution the
skipped prologue performs is B216's `getTypeOfExpression(recv)`, and
`getCalleeType` runs a few lines below on the same expression, so the natural
expectation was that the work would simply move. It does not:

| counter | Δ |
|---|---:|
| `typeOfExpr.calls` | **−1.86%** (−12,310) |
| `globals.lookups` / `globals.misses` | −0.49% / −0.45% |
| `narrow.memoServed` | −0.40% |
| `mapped.hits` / `mapped.keyed` | −0.42% / −0.10% |
| `typeNode.cacheable` / `cacheHits` / `bypassed` | −0.02% / −0.03% / −0.03% |
| `output.errors`, `spine.nodes`, `narrow.walks` | **+0.00%** |

**Nothing rose.** The B216 receiver typing is reached 10,933 times on the
compiler profile and costs 23 ms; the counter falls by 12,310, i.e. by those
calls plus the nested typings inside them, and stays down.

## 11. The equivalence, by construction and then by measurement

The gate returns `true` — run the prologue as before — whenever any walker could
act:

* `super(…)` / `super.m(…)`: TS2754, the base-constructor argument check, and
  `handleSuperMethodCall`, all of which also `return` out of the function.
  `getCalleeType("super")` answers `any`, so the general path never argument-checks
  a super call — skipping the prologue would make it SILENT, not wrong, which is
  exactly the kind of change a diagnostic-count grid cannot see.
* a property-access callee named `reduce` / `filter` / `transform` / `create` —
  the four name-keyed walkers' own first gates, verbatim.
* a property-access callee with no type arguments and **at least one
  `StringLiteralNode` argument** — B216's key arguments.
* a file with any CJS default-as-namespace shape at all (B154).

**The B216 leg is the one that needs an argument, and it has two halves.** It
cannot EMIT without a string-literal argument (`expr.arguments[pIdx] as?
StringLiteralNode ?: break`) — that much is a syntactic necessary condition. The
half that makes the skip safe rather than merely quiet is that it cannot reach
any SIDE-EFFECTING call without one either: `resolveStructuredTypeMembers` and
`getIndexedAccessType` both sit *below* that test in the key loop, so the only
mutation an invocation in the skip set could have performed is
`getTypeOfExpression(recv)`. That is round 754's cache-mutation-ORDER hazard,
bounded to a single call and then measured away by the grid.

Measured: the 8-profile `--listAll` grid, diffed set-for-set in BOTH directions
against the same binary run with `--ccetPreGate` (which honours nothing, so that
run IS the pre-change output): **46/46/46/46/46/46/46/94, 0 added and 0 removed
on all eight**; `--partitionCheck 2` **EQUIVALENT — 46**; corpus suite
**13,405 → 13,412 / 0 failures / 3 skipped**.

## 11b. The warm A/B: sign yes, magnitude no — and the box was not quiet

`scripts/ab-warm.sh`, 2 pairs, 8 iterations each, every iteration reporting
`files/errors 78/46` (the driver's self-falsification held):

| | A (HEAD) | B (gated) | Δ |
|---|---:|---:|---:|
| pair 1 | 10,737 ms | 10,668 ms | −69 ms (−0.64%) |
| pair 2 | 11,066 ms | 10,884 ms | −182 ms (−1.64%) |
| **median** | **10,902** | **10,776** | **−126 ms (−1.15%), B wins 2/2** |

The driver prints `VERDICT: WIN of 1.2%`, and it should NOT be quoted as one:
**arm A's sd is 2.13% and arm B's 1.42%, both above the ~1% quietness criterion**,
and the per-pair spread (113 ms) is the same size as the delta (126 ms), so
CLAUDE.md's law 7 fails outright. **The sign is confirmed 2/2; the magnitude is
the partition's 105–165 ms.** That the warm median lands inside that interval is
agreement, not confirmation.

One likely cause of the noise is on the record rather than hidden: a single file
write happened on this box during the measurement. Round 774 measured a −6.70%
A/A phantom from polling a log; one write is far less than that, but the arm sd
is what it is and the verdict is discarded on its own evidence.

## 12. What did not work, and what the round leaves behind

* **The first two candidates were dead on arrival for opposite reasons.** B464
  and `cpaComputeArgCtxTypes` are the two biggest non-engine rows in the
  property-access path and both already carry a pre-gate — this method has
  already been applied to them. `checkPrivateMemberAccess` (44 ms) and the
  optional-property TS18048 walker (50 ms) are keyed on the receiver's *kind*,
  which is complementary to B464's rather than shared with it, so one
  classification cannot serve both.
* **A tempting mis-reading of the prologue's own table.** Its seven rows read
  41 / 8 / 4 / 3 / 10 / 44 / 39 ms net, which invites the conclusion that B216
  and the CJS lookup are the cost. They are not: at 52,413 invocations a single
  probe boundary is 5–16 ms, so five of those seven rows are mostly their own
  instrumentation. The measurement that decides is the ONE-SPAN row (219 ms) and
  the nested `getTypeOfExpression` sub-measure (23 ms over 10,933) — a row of
  300–800 ns/call in a partition whose boundary is 300 ns is not a measurement of
  anything.
* **Left in the queue:** the TS2793 `implRelated` probe (94 ms over 23,214 calls)
  computes `getOverloadImplementationRelated` + `getImplementationSignature` +
  `allArgumentsMatch` eagerly, for a related-info message consumed only when an
  argument diagnostic is actually emitted — round 791's DEFERRAL shape rather
  than this round's gate shape, and it needs 791's verifier, not 792's.

## 13. Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt); P=build/bench/tsc-project-*
# the partition, with the gate live (six of the seven rows now reach ~1,019)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --callSections $P
# the gate: its price, its yield, and the falsifier column (honours nothing)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ccetPreGate $P
# the control — run it on HARNESS, where the prologue actually fires
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ccetPreGateBogus build/bench/tsc-harness-*
```
