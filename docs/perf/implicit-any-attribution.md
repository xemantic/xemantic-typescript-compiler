# (IANY.1) — `spineIanyEnterNode`, the last unopened handler of round 732's big six

*Round 798, 2026-08-02. Compiler profile, HEAD `750849af` (probe) / `8cc57836`
(gate). Every figure below is `--ianySections`, whose boundary count is a
function of the node count alone.*

## 0. Why this handler, and how it was chosen

Round 798 re-derived the live map first (`docs/ARCHITECTURE-RETHINK.md` § 0,
median of 3 probe-free `--passTiming` runs) rather than picking from the two
leads round 797 handed forward. Both leads died on arithmetic:

| lead (round 797) | population | verdict |
|---|---:|---|
| (A) `getTypeOfExpression` on a PropertyAccess / Call ARGUMENT | 11.2 µs × 7,774 + 23.0 µs × 2,612 = **147 ms (0.50%)** | **dead** — it is the cost of typing a composite argument, which the check needs; a subset of the `argType` row round 797 bounded at ~600 ms with ~200 ms named as irreducible resolution. Round 737 closed the recompute direction and rounds 789–792 the property-access path. |
| (B) 8,574 arguments typed to `any` and discarded | **99 ms (0.34%)** | **dead as a lever** — nothing can know a type is `any` without computing it, so the recoverable part is zero. It stays a MODELLING signal, as round 797 said. |

The map then named its own target. Of the six handlers holding 71% of the spine
(round 732), five had been opened by an attribution round —
`cpaSpineLeave` → (SPINE.1)/(ENGINE.2), `spineCtaM3StatementAnchor` → (TYPE.2),
`ccetSpineLeave` → (CALL.1)/(CALL.5), plus `ccetSpineEnter` and `ctaSpineEnter`
→ (ENGINE.1). **`spineIanyEnterNode` had not**, and the round-798
`--dispatchProbe` still measures it at **1,063 ms raw / 1,031 ms net** over all
856,962 nodes.

## 1. What the handler is

The round-532 migration of `checkImplicitAnyParameters`: a DOWNWARD-CONTEXT
walker. At every node it runs (a) the PARENT-EDGE dispatch
`spineIanyEdgeEnter(p, node)` — a ~20-arm `when (p)` that defines the
contextual-typing state for the child's subtree, mirroring the edges the legacy
recursion passed explicit arguments over — and (b) the node's own kind arms,
which emit TS7005/TS7006 and push scopes.

Its per-kind cost, from the same `--dispatchProbe` run:

| node kind | ms | nodes | "works" (frames pushed) |
|---|---:|---:|---:|
| IDENTIFIER | 387 | 381,670 | 40,595 |
| CALL_EXPRESSION | 241 | 52,509 | 44,625 |
| PROPERTY_ACCESS_EXPRESSION | 100 | 67,902 | 13,774 |
| VARIABLE_DECLARATION | 87 | 15,710 | 0 |
| BINARY_EXPRESSION | 41 | 38,454 | 4,103 |

## 2. The question the probe was built to answer

`spineIanyCtx` has **no reader outside this handler's own family** — the edge
arms, `spineIanyFnExprEnter`, `spineIanyObjLitMethodEnter`,
`spineIanyPropAssignEdge` — and every one of those readers sits at a node INSIDE
the subtree the state was defined for. Two consequences:

1. A state defined for a **childless** child can never be read. `forEachChild`
   visits nothing for `IDENTIFIER` / `STRING_LITERAL_NODE` /
   `NUMERIC_LITERAL_NODE`, so the frame is pushed at the child's enter and popped
   at its leave with no node in between.
2. A CALL's own `kind = 1` state can never be read when **every argument is
   childless**, because its `typed` flag is consulted ONLY by the two argument
   edges — exactly the population (1) skips.

This is not a new idea in this function. The ASSIGNMENT arm has carried it since
round 472 — *"Resolve the LHS type ONLY when the RHS can consume a fn context
(bounds first-touch resolution-order changes and per-assignment cost to the
shapes that need it)"* — and was never generalised. Round 783's rule: a
deliberate exclusion is a debt with a named creditor.

## 3. The instrument

`IanySections` (`--ianySections`, OFF in production). Two spans per node — one
around the edge dispatch, one around the own arms — attributed to disjoint rows.
**The row is classified AFTER the span closes**, so the classifier never lands in
a row it is measuring, and **the boundary count is a function of the node count
alone** (2 × 856,962), i.e. identical with and without any gate that shortens
these spans: round 793's "removing a section removes its boundaries" correction
does not apply to a before/after read of these rows. `--ianyGateOff` restores the
pre-798 path in the SAME binary, so one build carries both arms (round 794's
precedent).

## 4. The measurement — both arms twice on one binary

| row | calls | pre-798 (ms, 2 runs) | gated (ms, 2 runs) |
|---|---:|---:|---:|
| edge: childless child, CALL/NEW parent | 100,745 | 332 / 329 | **5 / 8** |
| edge: childless child, other parent | 293,815 | 77 / 81 | **25 / 22** |
| edge: childless child, scope-push parent (EXCLUDED) | 11,032 | 1 / 1 | 4 / 1 |
| edge: child with a subtree | 451,292 | 397 / 374 | **497 / 475** ⬆ |
| own: CALL/NEW, all arguments childless | 27,213 | 64 / 66 | **13 / 16** |
| own: CALL/NEW, an argument has a subtree | 25,853 | 63 / 60 | **74 / 70** ⬆ |
| own: every other kind | 803,896 | 166 / 166 | 166 / 157 |
| **handler total** | | **1,102 / 1,080** | **789 / 753** |

* The skippable rows fall **473/478 → 45/47 ms**, i.e. 429 ms removed.
* **110 ms of that REAPPEARS** in the two rows marked ⬆ — round 788's law,
  precisely: a childless argument used to be the first to type the callee, and
  now a sibling with a subtree pays for the same (cached) resolution. What is
  recoverable is only the part with no other consumer.
* **The honest prize is therefore the HANDLER total: 320 ms** (medians 1,091 →
  771) against a within-arm spread of 22 / 36 ms — Δ is **8.9×** the larger
  spread. **≈1.1% of a check-only compile.**

Deterministic confirmation (COST.1 counters, reproducing to the unit across both
runs of each arm):

| counter | pre-798 | gated | Δ |
|---|---:|---:|---:|
| `typeOfExpr.calls` | 649,410 | 599,880 | **−7.63%** |
| `typeOfExpr.distinct` | 253,080 | 239,674 | −5.30% |
| `globals.lookups` | 758,673 | 725,348 | −4.39% |
| `globals.misses` | 742,183 | 709,402 | −4.42% |
| `typeNode.bypassed` | 110,653 | 110,776 | +0.11% |
| `narrow.walks` | 17,851 | 17,853 | **+2 walks** |

The two risers are the round-754/778 resolution-ORDER effect the gate cannot
avoid: skipping a resolution changes which context first performs it. Both are an
order of magnitude inside tolerance, and every output check below is identical.

**No wall-clock A/B, deliberately.** 1.1% IS the warm band (±1.0%), not well
above it, and round 788's law says a saving C2 has already compiled reads smaller
still in percent. Rounds 794/795/796 declined on the same ground; the counters
decide.

## 5. Equivalence

* **8-profile grid**, gated vs `--ianyGateOff` in the same binary:
  **46/46/46/46/46/46/46/94**, **0 added and 0 removed in BOTH directions** on
  every profile.
* `--partitionCheck 2`: **EQUIVALENT — 46**.
* Corpus suite: **13,449 / 0 failures / 3 skipped** (+8 pins).
* In-process: `IanyGateTest` runs a fixture under both settings of
  `IanySections.gateOff` and requires the diagnostic sets to be equal.

## 6. The exclusion, and the one piece of state that crosses the edge

The gate does NOT fire when the parent is one of the seven function-likes or a
`ModuleDeclaration`: those edges push an implicit-any SCOPE or a namespace symbol
rather than only defining a state, and **an arrow's EXPRESSION body is precisely
a childless child of one of them**.

Every remaining arm was audited to define a state and nothing else. The only
emission below a parent edge (`emitTs7006BeyondCtxArity`, in the `CallExpression`
and `VariableDeclaration` arms) is itself gated on the child being an
arrow/function expression, which is never childless. The one arm carrying state
ACROSS the edge is `VariableDeclaration`'s: `spineIanyVarDeclEnter` stashes
`spineIanyPendingAnnDecl`/`…Type` for the initializer edge to consume and clear.
A skipped edge leaves the stash set — unobservable, because the consumer's test is
the IDENTITY `spineIanyPendingAnnDecl === decl` and every declarator's own enter
rewrites both fields, so no later edge can read a stale pair. Pinned.

## 6b. Ablation discrimination — and a null result

| fault | what it does | pins failing |
|---|---|---:|
| **A** | the gate stops testing childlessness (refuses every non-scope-push edge) | **4** — the equivalence pin, the contextual-arrow pin, the object-literal-method pin, the declarator-stash pin |
| **B** | the scope-push exclusion removed | **0**, and the compiler profile stays byte-identical |

Fault B is the more informative of the two. The excluded population is REACHED
11,032 times per compile (its own probe row), so this is not round 753's "the
ablation tested nothing" — the code ran and changed no verdict we can observe.
**Whether the 13k-baseline corpus sees it was not measured** — round 792's rule
says that is the only instrument that could, and it is recorded here as a lead
rather than claimed either way.

### 6c. ROUND 799 — fault B against the corpus: it does not see it either

Ablation B was re-injected and run against the FULL suite:
**13,449 tests / 0 failures / 3 skipped — identical to the un-ablated baseline.**

The run is evidence rather than a no-op, on two independent checks:

* the fault was **live** — the same build logged
  `w: Checker.kt:54004:22 Unreachable code`, which only the injected
  `(true || …)` can produce, and `jvmTest` ran in that same build;
* the population is **reached by the most ordinary shapes there are** — an
  eight-line scratch file whose only content is four expression-bodied arrows
  counts **6 `E_SCOPE_LEAF` edges** (`--ianySections`), so a 13,449-baseline
  corpus reaches it in the thousands.

**Verdict, in the required words: the exclusion is KEPT ON ARGUMENT.** It is now
*unfalsified by every instrument we have* — the 8-profile grid (round 798) and
the corpus suite (this round) — and the code comment says so, so that the next
agent does not read it as evidence-backed.

**The argument, restated correctly.** Round 798 justified it as "a scope push is
an observable MUTATION, not a state definition". That is true and is not the
reason: a frame pushed at a CHILDLESS node's edge is popped at that same node's
leave with **no node entered in between**, and every reader of
`implicitAnyScopes` / `spineIanyCtx` is reached only from a `spineIany*`
dispatch AT a node — so the stack mutation is exactly as unobservable as the
context definition is. Both instruments agreeing is what that predicts.

What genuinely is *not* unobservable, and is the reason to keep it: the
`ArrowFunction` arm does not only push a scope, it calls
`contextualSigReturnTypeForCtx` — a type RESOLUTION whose side effects
(interning, `symbolTypes` writes) are global. Skipping it moves **first-touch
resolution ORDER**, the round-754/776/778 hazard class that has no output diff
to find it by, and the row it would save is **1 ms**. No pin can discriminate
this, and none is claimed to; the honest record is this paragraph.

## 7. What did NOT work

The first form of the IIFE pin asserted that `((v) => v)("s")` reports a TS7006
for `v`. **It does not, on a working binary** — round 694's IIFE-argument
contextual typing supplies the parameter type from the argument — so the pin
failed against a correct gate and was measuring an assumption about the fixture.
It was restated as the equivalence it was written to defend (both settings of
`gateOff`, same diagnostics), which is strictly stronger. That is round 797's law
recurring one round later: **verify that a fixture reaches the population it
pins.**

## 8. What is left in this handler

After the gate the handler is ~770 ms probed, and **63% of it is one row**:
`edge: child with a subtree`, 497 ms over 451,292 calls. That row is the arms
doing their actual work — plus, now, the callee typing the gate moved into it.
The remaining own-arm row (`own: CALL/NEW, an argument has a subtree`, 74 ms over
25,853) is `isCalleeResolvable` for the calls that genuinely need it. Extending
the gate further means answering *"can this subtree read a `kind = 0` state?"*,
which is NOT a one-kind test: the state propagates through paren / conditional /
logical-binary / array-literal / object-literal edges and through every parent
kind that has no arm at all (`AsExpression`, `NonNullExpression`, …). A
conservative subtree scan would be quadratic under nesting. **Anyone who takes it
must price the scan against the ~500 ms that is left, not against the 320 ms this
round removed.**

---

# ROUND 799 — the residue, sub-partitioned; a 55 ms dispatch gate; and why the obvious predicate is unsound

*Compiler profile, HEAD `5fdf3634` + this round's probe. Same instrument, one
more level: the `E_SUBTREE` row is now classified BY PARENT ARM, still AFTER the
span closes, so the boundary count is unchanged (2 × 856,962) and no round-793
correction applies to any before/after read below.*

## 9. Where the 500 ms is

Round 798 left "63% of the post-gate handler in one row … the arms doing their
actual work" — a residual with a name, which is exactly what the round-758 audit
forbids. Measured (production arm, two runs):

| row | ms (2 runs) | calls | ns each |
|---|---:|---:|---:|
| edge: childless, CALL/NEW parent | 5 / 8 | 100,745 | 57–84 |
| edge: childless, other parent | 21 / 20 | 293,815 | 68–74 |
| edge: childless, scope-push parent (EXCLUDED) | 2 / 2 | 11,032 | 188–197 |
| **subtree: CALL/NEW parent, child is an ARGUMENT** | **249 / 245** | 31,575 | **7,900** |
| subtree: CALL/NEW parent, child is the CALLEE | 5 / 6 | 11,778 | ~500 |
| subtree: VariableDeclaration parent | 19 / 19 | 14,762 | ~1,330 |
| subtree: ReturnStatement parent | 19 / 24 | 10,396 | ~2,100 |
| subtree: BinaryExpression parent | 60 / 61 | 50,454 | ~1,200 |
| subtree: scope-push parent (a real body) | 32 / 25 | 41,788 | ~700 |
| subtree: Property{Assignment,Declaration} parent | 13 / 18 | 8,179 | ~2,000 |
| subtree: pass-through parent | 43 / 44 | 32,889 | ~1,340 |
| subtree: **NO ARM AT ALL** | 61 / 61 | 249,471 | **246** |
| own: CALL/NEW, all arguments childless | 12 / 16 | 27,213 | ~530 |
| own: CALL/NEW, an argument has a subtree | 60 / 65 | 25,853 | ~2,420 |
| own: every other kind | 160 / 161 | 803,896 | ~200 |
| **handler total** | **769 / 781** | | |
| *of which the residue* | *506 / 507* | *451,292* | |

**Half the residue is ONE arm.** The CALL/NEW **argument** edge is 249 ms over
31,575 calls at **7.9 µs each** — 32% of the whole handler — against 246 ns for
the 249,471 edges that reach no arm at all. This is population-vs-frequency in
the direction the arc keeps getting wrong: the no-arm population is **8× more
frequent** and **4× cheaper in total**.

## 10. What landed: the arm pre-gate (55 ms)

`spineIanyEdgeEnter`'s dispatch is a chain of **19 sequential `is` checks ending
in `else -> {}`**. 249,471 of the 451,292 subtree edges — plus most of the
childless ones — match none of them, so the chain is pure consultation.
`spineIanyEdgeHasArm(kindId)` answers the same question with one M0.2
tableswitch. It is a no-op by construction: every arm is a concrete node class
stamped with exactly the kind the gate lists (`NodeKindIdTest` pins the
correspondence).

Both arms, twice, on ONE binary, with an IDENTICAL boundary count
(`--ianyArmGateOff` restores the pre-799 chain):

| | gated (2 runs) | pre-799 (2 runs) |
|---|---:|---:|
| the NO-ARM row | **61 / 61 ms** | 120 / 108 ms |
| **handler total** | **769 / 781** | 815 / 845 |

* The row **halves**, and the two arms' readings do not overlap — the 19-check
  chain costs **~200 ns per no-arm edge**.
* **Quote the ENCLOSING total, not the row** (round 798's own correction):
  medians 775 → 830 = **Δ 55 ms**, against a within-arm spread of 12 / 30. Δ is
  only **1.8×** the larger spread, so 55 ms is an estimate, not a tight bound —
  the row read (61/61 vs 120/108, no overlap) is what makes the sign certain.
* **55 ms is ~0.2% of a check-only compile.** It is landed because it is free and
  provable, NOT because it is a lever; **no wall-clock A/B was run**, and one
  would be meaningless an order of magnitude inside the ±1.0% warm band.
* Output: 46 diagnostics on the compiler profile in both arms, identical sorted
  lines; the 8-profile grid is in the round note.

## 11. The 249 ms row, and the obligation any gate on it must discharge

The arm computes `contextualFnArityForCallArg` (arrow/fn-expr arguments only)
and then `calleeParamGivesNoContext` — a callee resolution — to decide one
boolean, `typed`, on a `kind = 0` state with **no type in it**. That state is
read by five places: the `ArrowFunction` / `FunctionExpression` own arms
(`spineIanyFnExprEnter`), the `ObjectLiteralExpression` own arm, an objlit
`MethodDeclaration` (`spineIanyObjLitMethodEnter`), the `PropertyAssignment`
edge, and an arrow's expression-body edge. **So the arm is pure cost whenever
the argument's subtree contains no such reader.**

**The obvious reuse is `rhsCanConsumeFnCtx` — the round-472 sibling the whole
(IANY.1) arc is built on — and it is UNSOUND here.** It descends only
paren / conditional / `||` `??` `&&` `,` / objlit-property positions. The state's
actual propagation set is larger by two entries:

1. **`ArrayLiteralExpression`** passes `typed` to its elements (only an
   arrow/fn-expr element is cleared), so `f([{ m(a) {} }])` has a reader that
   `rhsCanConsumeFnCtx` reports as absent;
2. **every parent kind with NO arm** — `AsExpression`, `NonNullExpression`,
   satisfies, unary, spread, member access, … — does not redefine the state, so
   it simply stays current for the whole subtree below. `f(<any>{ m(a) {} })` is
   the same failure.

A sound predicate therefore has to descend the no-arm positions too, stopping at
the arms that REDEFINE the state (call/new own arms, var-decl / return /
expression-statement / property-declaration edges, non-logical binary operands,
function-like body edges). That is bounded, not quadratic — the redefining arms
cut it — but it is a real scan whose cost has to be priced against **249 ms**,
and round 788's law applies on top: `calleeParamGivesNoContext` resolves a
**cached** callee type, so part of any saving reappears in the next asker (round
798 lost 110 of 473 ms exactly this way).

**Not attempted this round, deliberately, and this section is the deliverable
instead**: the obligation is now stated exactly, and the one predicate a future
agent would reach for first is falsified with named counter-shapes.

## 12. How the alternative was priced — (SPINE.1) re-measured at HEAD

The round's other candidate was the two big leave handlers (`cpaSpineLeave`
3,005 ms, `ccetSpineLeave` 2,732 ms per round 798's `--dispatchProbe`), whose
round-733 closure was five rounds old. Re-measured with `--spineSections` at
HEAD, against round 733:

| | round 733 | **round 799** |
|---|---:|---:|
| partition total (cpa+ccet leave), net | 8,195 ms | **5,831 ms** (−29%) |
| — the passes' OWN checking work | 7,241 = **88.4%** | 4,916 = **84.3%** |
| — ambient install + restore | 360 = 4.4% | 341 = 5.8% |
| — outside the ambient (gates, pops, restores) | 587 = 7.2% | 574 = 9.8% |
| — the three ancestor climbs | 176 = 2.1% | **186 = 3.2%** |
| `ccet` anchor per CALL_EXPRESSION | 55.9 µs | 49.6 µs |

**The closure HOLDS.** The handlers shrank by 29% and every structural ratio
moved by two or three points; the scaffolding is still ~730 ms spread over
**eleven sections consulted 856,962 times each at 5–190 ns**, and (SPINE.1)'s own
named target — memoizing the ancestor climbs — is still 186 ms, wrong by an order
of magnitude, exactly as round 733 found. The 4,916 ms of own work belongs to
(ENGINE.2) / (CALL.5) and is not a spine question.

**So the choice was by concentration, not by size**: (ii) is ~730 ms with no
concentration anywhere in it; (i) is 506 ms of which **249 ms is one arm with a
named mechanism**. (i) wins even though its total is smaller.
