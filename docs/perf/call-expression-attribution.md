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
full. And the ~240-line `signatures.isEmpty()` branch, with its seven emission
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
* `getCalleeType` costs 474 ms and **half its results are thrown away** at the
  any/error bail three sections later (26,496 of 52,413). That is not a caching
  question (§ 0 of `docs/ARCHITECTURE-RETHINK.md` closed those) — it is a question
  of whether the bail's verdict is knowable more cheaply than by resolving.

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
