# (TYPE.3) — `walkFunctionBodiesInExpr`, the last unopened region of the arc

*Round 756. Ninth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → `call-expression-attribution.md` (734) →
`argument-check-attribution.md` (735) → `narrow-walk-attribution.md` (736, the
arc's only landed win) → `type-of-expression-attribution.md` (737) →
`var-decl-attribution.md` (738) → `condition-narrowing-attribution.md` (755) →
here. Derived by instrumentation (`CtaSections` **level D**, opt-in,
behaviour-free when off), pinned by 9 new tests, verified by a byte-identical
compiler-profile `--listAll` in production and probe modes.*

> **HEADLINE — THE FUNCTION SPENDS 36% OF ITSELF ON THE WORK IT IS NAMED FOR.**
> `checkFunctionBody` is **65 ms of the 181 ms**. The rest is **finding** the
> bodies (65 ms, 36%) and **a callee-signature resolution run at every call
> whether or not there is a body to contextualize** (49 ms, 27%). The walk
> visits **199,131 nodes to reach 1,510 function-like ones (0.76%)**, of which
> only **636 have a block body to check (0.32%)**.
> **Three findings the milliseconds did not give.**
> **(1) 874 of those 1,510 — 58% — are EXPRESSION-BODIED ARROWS whose arm walks
> nothing at all, and that is a FALSE NEGATIVE**: `() => (function () { const
> s: string = 5; … })` reports nothing where tsc reports TS2322. Pinned with
> three controls.
> **(2) Four rows are ZERO in the entire compile** — the whole B150/B585
> object-literal-method machinery is JS-gated and tsc's source is `.ts`, so it
> never runs despite 755 object-literal arm entries. A `.js` control fires both
> rows, so the zeros are a measurement and not a dead probe.
> **(3) The walker has exactly ONE live entry point.** `invocationsDOutside == 0`:
> every one of its 199,131 invocations is inside the `A_WALKFN` window, so the
> two legacy call sites in `checkTypeAssignabilityInStatements` are reachable
> only from inside its own body walks.
> **NOTHING LANDED, correctly.** 181 ms is **0.68%** of a 26,800 ms compile and
> the largest row is **0.24%**, against a ±2.0% band of ~540 ms. **Deleting the
> entire walker is a third of one noise band.**

---

## 1. First: the number reproduces, exactly

Round 755's carry-forward is that an item defined by a measured number must
re-measure it before a round is spent inside it — its own target had halved
while it sat in the queue. This one did not move at all:

| | round 738 | round 756 |
|---|---:|---:|
| `A: walkFunctionBodiesInExpr` | 181 ms | **181 ms** |
| row openings | 28,940 | **28,940** |
| per opening | 6,280 ns | **6,280 ns** |

All three digits identical. That is not luck: the walk's population is AST
SHAPE (which expressions contain function bodies), and unlike round 755's
target it does not move when the declared types move. The prediction that it
would be stable is the one prior this round got right.

## 2. What was built — and why level D could not copy levels A–C

`CtaSections` level D: 10 rows, a 17-slot arm census, its own index space
(`dNanos`/`dCalls`/`dArm`) so the level-A/B/C layouts and their pins are
untouched. `--ctaSections` / `--ctaSectionsCoarse` drive it, as before.

**Levels A, B and C are all non-recursive, and `walkFunctionBodiesInExpr` is
not.** Their shape — `if (depth != 1) return` — would have charged the entire
recursive descent to whichever row happened to be open at depth 1, which is the
dispatch row, and answered nothing. Level D instead makes `beginD` **close the
caller's running row and hand it back**, and `endD` **reopen it**:

```
beginD(): prev = curD; charge prev; curD = D_ENTRY        → returns prev
endD(prev): charge curD; curD = prev                      → caller resumes
```

Every row is therefore **SELF time, exclusive of nested invocations**, and the
rows sum to the walk's true total. The consequence to keep in mind when reading
the body rows: `checkFunctionBody` walks statements and those statements
re-enter this walker, so a body row is the body's own checking **minus** the
nested walks it spawns — which are themselves in the table, one row down.

Two consequences for reading the tables below.

* **The count column is boundary CLOSES, not invocations.** A body row is
  closed once per transition PLUS once per nested walk it spawns. Per-invocation
  populations come from the arm census, never from that column.
* **The window is an explicit flag** (`inWalkFn`, opened by the two `A_WALKFN`
  call sites) rather than `curA == A_WALKFN`. That is what lets it survive
  `COARSE`, where level A's interior anchors do not fire — i.e. what makes level
  D's own ON-vs-COARSE differential possible at all.

## 3. Calibration — measured, and honestly bounded

Four runs of the SAME binary, compiler profile, `--noEmit`:

| run | level D boundaries | level D partition |
|---|---:|---:|
| ON #1 | 660,771 | 265 ms |
| ON #2 | 660,771 | 279 ms |
| COARSE #1 | 369,322 | 256 ms |
| COARSE #2 | 369,322 | 230 ms |

**The ON-vs-COARSE differential is too noisy to price the boundary**: Δ = 29 ms
over Δ291,449 boundaries = 100 ns, but the within-mode spreads are 14 ms and
26 ms, so the honest range is **31–168 ns**. Saying so is the point — round 755
found its own ON run ~21% inflated, and a calibration quoted tighter than its
inputs is worse than none.

Two comparisons against the level-D-free binary (where `A_WALKFN` = 181 ms) land
at the top of that range:

* COARSE: (243 − 181) / 369,322 = **168 ns**
* ON: (272 − 181) / 660,771 = **138 ns**

and only the top of the range reconciles the partition with the independently
measured 181 ms (at 168 ns the net rows sum to **179 ms**, a 1% residual; at
31 ns they sum to 249 ms, 38% over). The net column below therefore charges
**168 ns per boundary**.

**Why the choice barely matters:** the three rows that carry the findings have
≤ 29,787 boundaries and move by **less than 5 ms across the entire 31–168 ns
range**. Only `D: dispatch` is sensitive (65 ms at 168 ns, 122 ms at 31 ns), and
its share moves 36% → 66% — which changes how big the walk is, not what the
findings are.

## 4. The partition

Compiler profile. Raw = mean of the two ON runs.

| row | raw ms | boundaries | **net ms** | **% of 181** | population |
|---|---:|---:|---:|---:|---|
| **D: dispatch + pass-through arms** (the walk) | 135 | 414,525 | **65** | **36%** | 199,131 node visits |
| **D: B210 `calleeDeclaredCtxParams`** | 54 | 29,787 | **49** | **27%** | 29,787 call/new |
| **D: `checkFunctionBody` — ArrowFunction** | 49.5 | 1,324 | **49** | **27%** | **374** block bodies |
| **D: `checkFunctionBody` — FunctionExpression** | 16.5 | 268 | **16** | **9%** | **262** bodies |
| D: B210 `contextualizeFnExprFromAnnotation` | 2.5 | 15,736 | ~0 | 0% | 15,736 |
| D: wrapper transition (probe-only) | 12 | 199,131 | ~0 | — | — |
| D: `getTypeOfObjectLiteral` (objlit `this`) | 0 | **0** | **0** | **0%** | **0** |
| D: `walkObjectLiteralMemberBody` (JS) | 0 | **0** | **0** | **0%** | **0** |
| D: B585 objlit contextual type nodes | 0 | **0** | **0** | **0%** | **0** |
| D: B585 `objLitArgCalleeParamTypeNode` | 0 | **0** | **0** | **0%** | **0** |
| **sum** | | 660,771 | **179** | **99%** | vs 181 measured |

Per body, exclusive of nested walks: an arrow body **131 µs**, a function
expression **61 µs**. Both are expensive; there are 636 of them.

**Two cross-checks, from opposite sides.** Level D's outermost invocations are
**28,940** — exactly level A's `A_WALKFN` row openings, counted independently.
And the net partition closes on the independently measured 181 ms to 1%.

## 5. The arm census — where the findings are

199,131 visited nodes, max depth 18, **6.88 nodes per entry**.

| arm | nodes |
|---|---:|
| leaf (no arm — identifier / literal / class expr / …) | 111,117 |
| PropertyAccessExpression | 30,659 |
| CallExpression | 29,370 |
| BinaryExpression | 12,476 |
| Paren / As / TypeAssertion / Satisfies / NonNull | 6,070 |
| ConditionalExpression | 2,301 |
| Spread / Await / Yield / Void / Delete / TypeOf / Prefix / Postfix | 1,805 |
| ArrayLiteralExpression | 1,350 |
| ElementAccessExpression | 1,138 |
| **ArrowFunction, expression body — NOT walked** | **874** |
| ObjectLiteralExpression | 755 |
| NewExpression | 417 |
| **ArrowFunction, block body — walked** | **374** |
| **FunctionExpression — walked** | **262** |
| TemplateExpression | 161 |
| TaggedTemplateExpression | 2 |
| CommaListExpression | **0** |

**The walk is a property-access / call spine crawl.** Those two arms are 68% of
the 88,014 non-leaf visits; leaves are 56% of everything. It visits **199,131
nodes to reach 1,510 function-like ones (0.76%)**, and only **636 of those
(0.32%) have a body to hand to `checkFunctionBody`**. *That* is why the row is
7.7% of its handler — not, as round 738 guessed in passing, because the bodies
it walks are cheap. They are 61–131 µs each. There are 636 of them.

### 5.1 The false negative

**874 of the 1,510 function-like nodes — 58% — are arrows with an EXPRESSION
body**, and that arm is `(expr.body as? Block)?.let { … }`: no block, nothing
walked, and no descent into the expression to look for functions inside it.

The obvious defence is "the check spine anchors the inner statement anyway".
**It was tested, and it is false:**

```ts
const s: string = 5                                              // TS2322 ✓
const f = function () { const s: string = 5; return s }          // TS2322 ✓
const f = () => { const s: string = 5; return s }                // TS2322 ✓
const f = () => (function () { const s: string = 5; return s })  // SILENT ✗
```

Three controls attribute the gap to the arrow's body shape and to nothing else.
tsc reports the last one. Pinned as it is today by `an expression-bodied arrow
hides a nested body mismatch - a known gap`, which will fail — correctly and
loudly — the day the arm learns to descend. **This is a correctness lead, not a
performance one**, and it is filed as such: closing it ADDS work to a walker
this round priced at 0.68%.

> **CLOSED IN ROUND 757 — and the population is SIX, not 874.** See § 11.

### 5.2 Four rows that are zero, and one arm that is

`getTypeOfObjectLiteral` for the object-literal `this` type,
`walkObjectLiteralMemberBody`, and both B585 contextual-type-node computations
are **never reached in the entire compile** — despite 755 object-literal arm
entries. All four are gated on `isJsLikeFileName`, and tsc's source is `.ts`.
The `.js` control in the pin lights `D_OBJLIT_THIS` and `D_OBJLIT_MEM` on the
same shape, so the zeros measure the input rather than a broken probe. Round
755 got its `instanceof` = 38 / `in` = 42 finding exactly this way.

`CommaListExpression` is 0 as well.

### 5.3 The unconditional resolution

`calleeDeclaredCtxParams` resolves the callee's declared signature at **all
29,787 call/new expressions the walk reaches**, at 1,824 ns each, **to
contextualize at most 636 bodies**. `contextualizeFnExprFromAnnotation` then
runs 15,736 times — once per argument with a declared parameter type — and is
essentially free (~187 ns), because it returns immediately for a non-function
argument.

So a pre-test — *does any argument of this call have a function shape* — would
skip essentially the whole 49 ms. **It is 0.18% of the compile.** Priced and
not attempted: it is a tenth of a noise band, and its soundness would first
need `contextualizeFnExprFromAnnotation` proved null-returning for every
non-function argument. Recorded because it is the SHAPE that keeps recurring —
rounds 738 and 755 also bottomed out in a resolution — not because it is a
lever.

### 5.4 One asymmetry, recorded and not explained

Nested walker entries spawned from inside a body: **950 from 374 arrow bodies
(2.5 each) against 6 from 262 function-expression bodies (0.02 each)** — a
100× difference. Derivable from the close counts (`dCalls[D_ARROW] = 374
transitions + 950 nested`, `dCalls[D_FNEXPR] = 262 + 6`) and internally
consistent with the boundary arithmetic. No theory offered.

## 6. The size

| | ms | share of a 26,800 ms compile |
|---|---:|---:|
| the whole walker | 181 | **0.68%** |
| its largest row | 65 | **0.24%** |
| all body checking (`checkFunctionBody`) | 65 | 0.24% |
| the unconditional callee resolution | 49 | 0.18% |
| **the drift band (round 755, 5 null pairs)** | **±540** | **±2.0%** |

**Deleting the entire walker is a third of one noise band**, and it could not be
deleted anyway without first re-deriving the false-negative surface. This closes
as a measurement.

## 7. The predictions, scored

Written down in full before a single boundary was placed; this table is the
record.

| | prediction | measured | |
|---|---|---|---|
| P0 | the 181 ms reproduces within ±25% | **181 ms / 28,940 / 6,280 ns — all three digits identical** | **HELD** |
| P1 | `checkFunctionBody` ≥ 60% of the row; dispatch ≤ 15% | bodies **36%**, dispatch **36%** | **FALSIFIED, both clauses** |
| P2 | `calleeDeclaredCtxParams` is the largest non-body row, ≥ 20 ms | **49 ms**, largest non-body row | **HELD** |
| P3 | no row > 120 ms; nothing clears the band; closes as a measurement | largest **65 ms = 0.24%** | **HELD** |
| falsifier | any row > 540 ms ⇒ attempt the change | largest is 65 ms | **not met** |

Two of four wrong — the arc's steady rate since round 732. P1 is the
interesting one: I predicted the *opposite* of round 738's throwaway
explanation and was wrong in the same direction it was, for a third reason
neither of us named. The bodies are not cheap (61–131 µs) and they are not
numerous (636); what makes the row small is that **the walk spends more finding
them than checking them**, and a third as much again on a resolution that is
there for them.

## 8. What did NOT work

* **The ON-vs-COARSE differential.** Δ29 ms against a 26 ms within-mode spread
  — it bounds the boundary at 31–168 ns and no better. The usable calibration
  came from comparing both modes against the level-D-free binary. *A
  differential is only as sharp as the smaller of its two spreads.*
* **A pre-test on `calleeDeclaredCtxParams`** — 49 ms = 0.18%, and unproved
  sound. See § 5.3.
* **Deleting the walker** — 0.68%, and § 5.1 says its coverage is not
  redundant everywhere.
* **Reading `dCalls` as an invocation count.** It is boundary closes; for a
  recursive save/restore partition a row is also closed by every nested entry
  made while it is open. The per-body costs in § 4 come from the arm census.

## 9. Verification

* `CtaSectionProbeTest`: **20 tests, 0 failures** (11 pre-existing + 9 new).
* Filtered batch (`*Cta*` `*ObjLit*` `*ObjectLiteral*` `*Arrow*` `*Inv0*`
  `*Spine*` `*FunctionExpr*` `*Contextual*`): **2,405 tests, 0 failures**.
* Compiler profile `--listAll`: **46**, composition unchanged (TS2591×43 /
  TS2304×2 / TS2584×1, zero TS2322), and `--listAll --ctaSections` is
  **byte-identical** to it.
* Build warning-clean.

## 10. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
# the partition + the arm census (grep 'TYPE.3')
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ctaSections build/bench/tsc-project-*
# its calibration counterpart — one boundary pair per invocation
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ctaSectionsCoarse build/bench/tsc-project-*
```

## 11. Round-757 addendum — (FN.1) closed, and the finding is the population

The `ArrowFunction` arm now descends into an EXPRESSION body, under the arrow's
own parameter/type-parameter scope (a new row, `D_ARROW_EXPR_SCOPE`; a
zero-parameter arrow skips the install). § 5.1's four-line table now reads
`TS2322 ✓` on all four lines.

**What the fix found on the compiler profile: nothing.** `--listAll` is
byte-identical at 46, and all 8,837 corpus baselines are unchanged. The reason
is in the arm census, measured before and after:

| | before | after | Δ |
|---|---:|---:|---:|
| nodes visited | 199,131 | 206,098 | **+3.5%** |
| max depth | 18 | 35 | +17 |
| ArrowFunction, expression body | 874 | 911 | +37 |
| **ArrowFunction, block body (a body to check)** | **374** | **380** | **+6** |
| **FunctionExpression (a body to check)** | **262** | **262** | **+0** |
| `checkFunctionBody` closings | 1,592 | 1,615 | +1.4% |

**The 874 expression-bodied arrows contain exactly SIX block bodies between
them.** The false negative is real — four fixtures fail on unmodified
`2f728c1e` and pass after — but on 200 kLOC of TypeScript its incidence is six
bodies, all of which happened to be clean. *A census that counts REACHED nodes
answers "how often is this arm taken", not "how much is behind it"*; § 5.1
quoted the first number as though it bounded the second, and it is 146× out.

**Cost**: the added work is the `D_ARROW_EXPR_SCOPE` row (8 ms over 707 installs
— `getTypeFromTypeNode` per annotated parameter dominates it) plus the +3.5%
more nodes. Level D goes 262 → 276 ms probe-inflated; net of the 25,213 extra
probe boundaries at ~168 ns, **≈10 ms ≈ 0.04% of the compile**. The walker was
0.68% and is now ~0.72%.
