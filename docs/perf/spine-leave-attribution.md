# (SPINE.1) step (a) — what is actually inside `cpaSpineLeave` and `ccetSpineLeave`

*Round 733. Sibling of `docs/perf/dispatch-table.md` (round 732), which found
these two handlers and named them as the next target. Derived by
instrumentation (`SpineSections`, opt-in `--spineSections`), verified by the
whole corpus suite, an identical profile `--listAll`, and a cost gate at
+0.00% on all 20 counters. The instrumentation is behaviour-free when off.*

> **HEADLINE — THE PREMISE IS FALSIFIED, TWICE OVER.**
> The (SPINE.1) item describes these handlers as "the cta/cpa/ccet
> legacy-parity frame SKELETONS — per-node bookkeeping that exists to
> reproduce the deleted walkers' ambient state, not type-system work".
> **They are not. 88% of their measured time is the cpa and ccet passes' own
> checking work** — `checkPropertyAccessInExpr` and
> `checkSingleCallExpressionTypes`, running inside the frame-ambient block.
> And the item's named optimisation target, the ancestor climbs, is
> **176 ms, not the predicted 1–3 s** — wrong by 6–17×.
> Do not land step (b) as specified. See § 4 for what the numbers do point at.

---

## 1. What was built

`SpineSections` in `src/commonMain/kotlin/SpineDispatch.kt`, plus splits in
two `Checker.kt` handlers:

* **A running-timestamp split between each handler's top-level sections.**
  Both handlers are a SEQUENCE of independent sections, so the sections
  PARTITION one call: 7 for `cpaSpineLeave`, 4 for `ccetSpineLeave`. Nothing
  in the handlers' control flow was restructured — a `split` is one statement
  inserted between two existing sections, which is why the derivation cannot
  have changed what the sections do.
* **Nested sub-measures** around the three ancestor climbs (`cpaM2ChainOk`,
  `cpaM2StmtPosition`, `ccetM3ChainOk` — each split into a timing wrapper and
  an untouched `…Core`) and around both frame-ambient installs
  (`withCpaFrameAmbient` / `withCcetFrameAmbient` gained a `sec`/`kind`
  parameter defaulting to `NONE`, so only the LEAVE call sites record). The
  ambient measure is reported twice: whole-wrapper (install + work + restore)
  and install+restore ONLY, which is what separates scaffolding from work.
* **A per-section `hit` counter** — how often the section's gate PASSED, as
  opposed to how often it was consulted.

Production is untouched: every entry point returns on one static field read,
and `t`/`split`/`close`/`hit` are `inline` so the production cost is a
load-and-branch rather than a call. `SpineSectionProbeTest` pins that (a) the
probe is behaviour-free when ON versus OFF on a fixture that emits BOTH
instrumented passes' own codes (TS2339 and TS2554 — a clean fixture would make
the comparison vacuous), (b) nothing is recorded when OFF, (c) the disjoint
sections are entered exactly once per node, and (d) the nested measures'
call counts match the section hit counts.

**Calibration trap, recorded because it cost a run.** The first draft
calibrated the probe with a 200-iteration loop at JVM startup and read
**40,573 ns per timestamp pair** — the cold interpreter — which made every
net figure in the report NEGATIVE. The calibration is now IN SITU: an empty
span opened and closed back-to-back at the top of `cpaSpineLeave`, once per
node, measuring **42 ns** under the run's real JIT state.

## 2. The numbers

Compiler profile (`build/bench/tsc-project-*`), 856,962 spine nodes, net of
42 ns per probe timestamp pair.

**Partition total: 8,195 ms net / 8,590 ms raw.** Round 732 measured the same
two handlers, un-split, at 7,412 ms — so the splits inflate by ~10%, the same
way round 732's per-handler nanos were inflated by its `when(h)` indirection.
**Sound for relative attribution, not as absolute production costs.**

The inflation is measured directly, apples-to-apples on the same binary and
the same flags: `--passTiming --listAll` **without** `--spineSections` gives
`checkSpine` 23,607 ms / checker-init 27,979 ms; **with** it, 24,478 ms /
28,776 ms. **The probe costs ~800 ms**, which is what ~13 extra timestamp
pairs per node at 42 ns plus the nested measures should cost — and it is the
whole gap between this partition and round 732's un-split figure.

**What the landed instrumentation costs when OFF** is not measurable and is
not claimed to be: 13 additional static-field reads and not-taken branches per
node in two handlers, ~11 ms on 856,962 nodes, two orders of magnitude below
the ±2% (≈560 ms) drift band of a 30.7 s compile. The deterministic gate is
`cost_gate.py` (+0.00% on all 20 counters); wall time cannot resolve this and
no A/B is offered for it.

### `cpaSpineLeave` — 4,934 ms net

| section | ms net | hits | consulted |
|---|---:|---:|---:|
| anchor stmt (m3a/m3b) | **3,304** | 45,626 | 856,962 |
| owner cond/subject (m3b) | **1,349** | 19,551 | 856,962 |
| VariableDeclaration recordings | 180 | 14,681 | 856,962 |
| frame pop | 48 | 9,536 | 856,962 |
| loop-var restores | 25 | 139 | 856,962 |
| heritage EWTA (m3c) | 17 | 2 | 856,962 |
| PropertyDeclaration init (m3c) | 11 | 5 | 856,962 |

### `ccetSpineLeave` — 3,254 ms net

| section | ms net | hits | consulted |
|---|---:|---:|---:|
| call/new/tagged anchor (m3) | **3,136** | 52,972 | 856,962 |
| VariableDeclaration recordings | 78 | 14,735 | 856,962 |
| frame pop | 33 | 11,110 | 856,962 |
| override restores | 7 | 43 | 856,962 |

### The nested sub-measures — where the work/scaffolding line falls

| sub-measure | ms net | calls | ns each |
|---|---:|---:|---:|
| `withCpaFrameAmbient` (install + work + restore) | **4,574** | 79,865 | 57,274 |
| `withCcetFrameAmbient` (install + work + restore) | **3,027** | 67,707 | 44,718 |
| — cpa ambient install + restore ONLY | 200 | 79,865 | 2,505 |
| — ccet ambient install + restore ONLY | 160 | 67,707 | 2,364 |
| `cpaM2ChainOk` | **77** | 83,649 | 932 (mean ancestor depth 6) |
| `ccetM3ChainOk` | **91** | 53,068 | 1,733 (mean ancestor depth 9) |
| `cpaM2StmtPosition` | **8** | 68,914 | 119 |

### The split that matters

| | ms | share of the 8,195 |
|---|---:|---:|
| inside the frame-ambient block | 7,601 | **92.8%** |
| — the passes' own work (`checkPropertyAccessInExpr` / `checkSingleCallExpressionTypes`) | **7,241** | **88.4%** |
| — ambient install + restore | 360 | 4.4% |
| outside the ambient (gates, restores, pops) | 587 | 7.2% |
| — the three ancestor climbs | 176 | 2.1% |
| — everything else, over 856,962 consultations each | ~411 | 5.0% |

## 3. The item's two hypotheses, priced

1. **"nodeId-memoizing the `cpaM2StmtPosition`/`cpaM2ChainOk` ancestor climbs
   … should remove 1–3 s."** The ENTIRE cpa climb population is **85 ms**
   (77 + 8); with `ccetM3ChainOk` the whole climb budget is **176 ms**. A memo
   removes a fraction of that at best, and `docs/ARCHITECTURE-RETHINK.md` § 0's
   law bites directly: a keyed probe would not pay for a 932 ns walk over a
   mean ancestor depth of 6. **Falsified by 6–17×.** Do not build the memo.
2. **"gating the parent-keyed `run{}` blocks on the PARENT's kindId — a second
   dispatch axis."** The three parent-keyed sections are `owner` (1,349 ms),
   `EWTA` (17 ms) and `PropertyDeclaration` (11 ms). `owner` has 19,551 hits
   at the anchor section's ~72 µs/hit ≈ 1,340 ms of WORK, leaving ≲30 ms of
   gate; EWTA and PropertyDeclaration are 28 ms TOTAL across 856,962
   consultations each. **A parent-kind axis is worth ≲60 ms** — the same
   conclusion round 732 reached for the node-kind axis, one level down.
   **ROUND-758 FLAG (unverified, not falsified): the `owner` half of that
   derivation IMPUTES a per-hit rate from a DIFFERENT section rather than
   measuring `owner`'s own.** 19,551 is a FREQUENCY, and the per-hit rate it is
   multiplied by is the ANCHOR section's population divided by the ANCHOR
   section's hits. The conclusion is probably right — the two sibling
   parent-keyed sections *are* 28 ms measured, and 60 ms is a tenth of a noise
   band either way — but it is an imputation, and rounds 732/734/758 were each
   burned by exactly this step. A nested span inside the `owner` block settles
   it in one run. `docs/perf/claim-audit-round758.md` § 4 (S5).

Both hypotheses are dead, and so is the framing that produced them. The
cheap-per-node sections behave exactly as `docs/perf/dispatch-table.md` § 5
found for handler consultation: **five of `cpaSpineLeave`'s seven sections
together cost 281 ms across 856,962 nodes each** — consultation is not the
expense, here or anywhere else measured in this codebase.

## 4. What the numbers DO point at

The concentration is per-call-site, and it is extreme:

| anchor | ms net | anchors | µs each |
|---|---:|---:|---:|
| `ccet` at CALL_EXPRESSION | 2,931 | 52,413 | **55.9** |
| `cpa` at VARIABLE_STATEMENT | 1,356 | 14,062 | 96.4 |
| `cpa` at BINARY_EXPRESSION (a condition/subject) | 833 | 6,939 | 120.0 |
| `cpa` at EXPRESSION_STATEMENT | 928 | 16,372 | 56.7 |
| `cpa` at RETURN_STATEMENT | 886 | 14,605 | 60.7 |

The cpa anchors walk a whole statement subtree, so a per-anchor figure in the
tens of µs is expected there. **The ccet one is not a subtree walk.**
`checkSingleCallExpressionTypes` is called once per CallExpression node and
costs **53.6 µs** (55.9 minus the 2.3 µs ambient install) — it is a
**920-line straight-line function with 18 `diagnostics.add` sites and 7
`run{}` blocks, executed in full for every one of the program's 52,413 call
expressions**, and it is the largest per-node cost measured anywhere in this
compiler. 2.9 s, ~10% of a ~28 s compile, in one function.

**Cross-check against round 732's per-kind table** (which is per-KIND across
ALL 59 handlers, not per-handler): it recorded CALL_EXPRESSION at 3,636 ms;
this attribution finds 3,082 ms of that in `ccetSpineLeave` alone. It recorded
VARIABLE_STATEMENT at 2,835 ms; 1,379 ms of it is `cpaSpineLeave`'s anchor
(most of the rest is `spineCtaM3StatementAnchor`, the third of round 732's six
hot handlers). The two derivations agree.

## 5. Verification

* Full corpus suite: **12,887 tests, 0 failures, 3 skipped** (12,882 + 5 new
  `SpineSectionProbeTest` pins).
* Compiler profile `--listAll`: production and `--spineSections` produce
  **identical** diagnostics (46 errors, identical sorted lines).
* `scripts/cost_gate.py`: all 20 deterministic counters **+0.00%**.

## 6. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTiming --listAll --spineSections build/bench/tsc-project-*
# report + a per-(section, kind) CSV between the "csv" markers
```

---

## 7. ROUND-799 RE-MEASUREMENT AT HEAD — THE CLOSURE HOLDS

Round 799 considered these two handlers as its lever (round 798's
`--dispatchProbe`: `cpaSpineLeave` 3,005 ms, `ccetSpineLeave` 2,732 ms) and
re-derived § 2 rather than inheriting it, per the standing "re-measure a number
before spending a round inside it" rule. `--spineSections`, compiler profile,
HEAD `5fdf3634`:

| | round 733 | **round 799** |
|---|---:|---:|
| partition total, net | 8,195 ms | **5,831 ms** (−29%) |
| — the passes' OWN checking work | 7,241 = **88.4%** | 4,916 = **84.3%** |
| — ambient install + restore | 360 = 4.4% | 341 = 5.8% |
| — outside the ambient | 587 = 7.2% | 574 = 9.8% |
| — the three ancestor climbs | 176 = 2.1% | **186 = 3.2%** |
| `cpaM2ChainOk` / `cpaM2StmtPosition` / `ccetM3ChainOk` | 77 / 8 / 91 | 75 / 13 / 98 |
| `ccet` anchor, per CALL_EXPRESSION | 55.9 µs | 49.6 µs |

**Both of § 3's verdicts stand, five rounds and a 29% shrink later.** The
handlers got smaller because the passes they call got faster (rounds 787–797);
the scaffolding did not move in absolute terms at all — 341 + 574 = ~915 ms
across ELEVEN sections consulted 856,962 times each at 5–190 ns, with the
climbs 186 ms of it. There is no per-node lever here, and the 4,916 ms of own
work is (ENGINE.2) / (CALL.5) territory, not a spine question.

Round 799 therefore declined this target and took the (IANY.1) residue instead —
smaller in total (506 ms) but **concentrated**: 249 ms of it is a single arm.
`docs/perf/implicit-any-attribution.md` § 12 records the comparison.
