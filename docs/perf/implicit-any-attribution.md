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
The exclusion is kept anyway: `pushImplicitAnyScope` is an observable MUTATION,
not a state definition, so dropping it is unsound BY ARGUMENT even where no
instrument sees it, and the row costs 1 ms. **Whether the 13k-baseline corpus
sees it was not measured** — round 792's rule says that is the only instrument
that could, and it is recorded here as a lead rather than claimed either way.

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
