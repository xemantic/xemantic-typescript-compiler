# Sharing one bind across `--workers` checkers — the blocker, the price, the staging

*Round 881 (2026-08-11), opening the item round 880 named. This is the design record for the
`Checker`-does-not-mutate-binder-output refactor. **Read the price section before writing any
code** — the prize is real but smaller than "N workers each re-bind the whole program" suggests,
and the reason is arithmetic that has already killed one queue item this arc.*

## 0. What is true today

Under `--workers N` each worker constructs its own `Binder` and binds every program file for
itself (`TypeScriptCompiler.cpcBindAndCheck`). Round 876 removed a *further* redundant bind that
ran on the sequential prefix and was read by nobody; what remains is one full bind per worker,
concurrent with the others.

Measured warm on the tsc compiler profile (78 files, 9,977 k chars), round 880:

    wall(N) = 1,447 ms + 7,717/N          (N=4 predicts 3,376; measured 3,035-3,232)

with the 1,447 ms per-worker fixed term splitting as **bind 515 ms + ~930 ms of program-wide
checker work**, and the per-char rate running **0.776 ms/k-char in a worker against 0.565
sequentially — a +37% contention overhead**.

## 1. The blocker, exactly

`Checker.mergeSingleSymbol` (Checker.kt) is the whole of it:

```kotlin
val existing = target[name]
if (existing != null) {
    existing.flags = existing.flags or symbol.flags     // MUTATES a binder Symbol
    existing.declarations.addAll(symbol.declarations)   // MUTATES its list
    if (existing.valueDeclaration == null && symbol.valueDeclaration != null)
        existing.valueDeclaration = symbol.valueDeclaration
    if (symbol.exports != null) { …; mergeSymbolTable(existing.exports!!, symbol.exports!!) }
} else {
    target[name] = symbol                                // ADOPTS a binder Symbol by reference
}
```

Two distinct problems, and the second is the one that is easy to miss:

1. **Mutation.** `existing` is mutated in place, so a second checker merging the same tables
   double-applies every merge (flags are idempotent under `or`, but `declarations.addAll` is
   **not** — it appends duplicates).
2. **Adoption.** The `else` branch puts the *binder's own* `Symbol` object into `globals`. So
   after one checker has run, `BinderResult.locals` and `globals` share objects, and the merges
   performed for file 2 mutate a symbol that file 1's `BinderResult` still points at. This is why
   `PartitionCheck` documents that "a worker must never reuse an already-checked bind" — a bind is
   not merely read by a checker, it is *consumed* by one.

Both are reached from `Checker`'s `init` (`init:mergeLibGlobals`, and the per-file
`mergeSymbolTable(globals, result.locals)` loop).

## 2. The price — read this before designing

**Sharing alone buys ZERO wall.** Round 879/880's law: a per-worker fixed cost hoisted into the
serial prefix leaves `wall = F + A/N`, identical, because the N copies were already concurrent.
One shared bind computed serially before the workers start costs the same 515 ms of wall that N
concurrent binds cost today.

The prize is therefore **not** "stop doing it N times". It is:

- **(a) Parallelising the single bind — 515 -> ~515/N, so ~386 ms at N=4** (~12% of a warm
  rebuild). This is only available *once* the bind is shared, because today each worker's bind is
  already parallel with the others and there is nothing left to overlap.
- **(b) Some fraction of the +37% contention term.** Four workers each build and walk a complete
  symbol graph; one shared graph is a large reduction in resident data and in memory traffic. At
  N=4 the contention term is worth ~526 ms of wall, so this is the *bigger* half if it lands — but
  it is **unmeasured**, and it must not be quoted as a number until it is. GC is already acquitted
  (heap-insensitive: `-Xmx4g` 14,896 vs `-Xmx8g` 14,865 ms), so if (b) is real its mechanism is
  cache/bandwidth, not collection.

Total plausible: **~400-900 ms of a ~3,100 ms warm rebuild, i.e. 13-29%**, of which only (a) is
currently justified by measurement. **Do not start this expecting the 515 ms x N that the
duplication suggests.**

## 2a. Stage 1 result — the population is **tiny**, and that changes the plan

Measured with `--mergeCensus` on the compiler profile (78 files, sequential):

    mergeSingleSymbol: adopts 406, mutates 175 (of which reach an adopted symbol: 164),
                       declarations appended 175

Against the ~105 k symbols a worker mints. The reason is already in the tree: **INV.3(d) retired
the merge for module-only names**, so only genuinely global names reach `globals` at all — every
module file's locals stay module-scoped and are never merged, never adopted, never mutated.

So the blocker is **406 adopted objects and 175 mutations**, not a whole symbol graph. Stage 2
(copy on adoption) is a surgical change to one `else` branch, and stage 3 (the mutation case)
touches 164 sites' worth of state, not thousands. **This is much smaller than § 3's warning
implies** — but § 3 still applies in full, because the risk was never the SIZE of the population;
it is that those 406 symbols are exactly the ones whose identity `globals` hands out.

**What the census does NOT answer**, and what stage 1 must still close before stage 2 is written:
`mergeSingleSymbol` is the only mutation site this census watches. Whether the checker mutates
binder-owned `Symbol`s ANYWHERE ELSE (a stray `flags or=`, a `valueDeclaration =`, an `exports`
install) is a separate question, and a bind cannot be shared until it is answered no. Grep for
writes to `Symbol` fields reachable from a `BinderResult`, and prefer a census over a reading.

## 3. What makes it hard — the identity invariants it collides with

The obvious fix — copy each symbol on adoption so the binder's objects are never touched — changes
`Symbol` **identity**, and identity is load-bearing across the checker:

- `symbolTypes[sym.id]`, `declaredTypes`, `symbolTargets` (LinkStore) and the intern caches are
  **id-keyed**, and CLAUDE.md records that id-allocation drift "reshuffles ~350 boundary tests".
- INV.3(c) depends on *which instance* is returned: `globalsForFile` must hand back the
  **merged-globals instance** for legitimately visible names while `perFileScope` holds the
  first-occurrence script symbol — "different objects for lib/script names" is the stated
  invariant, and byte-identity depends on it.
- `canonicalEnumSymbol` freezes a per-`sym.id` verdict, and round 751 showed a frozen-wrong entry
  is indistinguishable from a correct one by inspection.
- Round 825's worker id slices assume ids never cross a worker boundary; a *shared* symbol graph
  minted before the workers fork sits in none of their slices, which is fine, but every later
  worker-minted id must still not collide with it.

So this is not a one-line change guarded by the corpus; it is a staged refactor with a
verification obligation at each stage.

## 4. Staging — smallest landable steps, each gated

1. **Census the mutation, don't reason about it.** Instrument `mergeSingleSymbol` to count
   adoptions (`else` branch) vs merges, and how many merged symbols are reached again by a later
   file. Round 801's order: the produced-vs-consumed ratio decides the design, and a count of call
   sites does not. Land the census behind a flag with its own pin.
2. **Make the merge non-destructive for the ADOPTION case only** — `target[name] = symbol.copy()`
   — leaving the mutation case alone. This is the half that makes `BinderResult.locals` immutable
   with respect to the checker, and it is separable. Gate: byte-identical `--listAll` on all eight
   profiles *and* the corpus, because it moves id allocation.
3. **Then the mutation case**, by giving the checker its own merged symbol rather than mutating
   the first-seen one.
4. **Only then** share one `BinderResult` list across workers, and **only then** parallelise the
   single bind — which is where prize (a) is actually collected.
5. Re-measure (b) with the per-worker census (`--frontEnd`'s worker rows) before claiming it.

**Verification at every stage** is the one already in use and it is not negotiable here: the
14,263-test suite, `cost_gate.py` counters, the 8-profile `--listAll` grid, `--workers` diagnostic
digests over >= 5 runs per level, and — since round 878 — an **emit-mode `diff -r`**, because a
symbol-identity change can move emitted bytes while leaving every diagnostic alone.

## 5. The alternative that should be priced first

Stage 1's census may show the adoption/mutation population is small enough that the whole refactor
is not the cheapest route to the same wall. The other route to lowering the 1,447 ms floor is
making the **~930 ms of program-wide checker work** partition-scoped one pass at a time — which
needs no identity change at all, and which round 879 showed is already how most of the tail
behaves (~98 ms per *assigned* file against ~1.1 s duplicated). That work is incremental, gateable
per pass, and carries none of the id-drift risk. **Price both before committing to this one.**
