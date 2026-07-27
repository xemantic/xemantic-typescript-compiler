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
| `spineOsEnterNode` | 0 (not instrumented — parent arms have no `work()` call) |
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
