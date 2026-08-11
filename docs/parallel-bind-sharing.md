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

**What the census does NOT answer**: `mergeSingleSymbol` is the only site it watches, and there are
**150 other write sites** in `Checker.kt` across `flags` (4), `valueDeclaration` (30), `members`
(19), `exports` (23), `parent` (11) and `declarations.add` (63). `Symbol.target` is written **0**
times — it moved to the LinkStore side table. A grep cannot classify those 150, because it cannot
tell a binder-owned receiver from a checker-minted one. Answered by § 2b instead.

## 2b. Stage 1 CLOSED — the checker mutates **zero** binder Symbols on an all-module program

`--bindMutationCheck` fingerprints every `Symbol` reachable from the `BinderResult`s (locals +
`nodeToSymbol`, recursing through `members`/`exports`; identity-keyed, which is sound because
`Symbol` is a plain class and not a `data class`) immediately before the `Checker` constructor —
where the whole check runs — and re-compares afterwards. It therefore sees every write site rather
than the ones a grep found. On the compiler profile:

    binder Symbols checked 15580, changed 0
      (flags 0, declarations 0, valueDeclaration 0, members 0, exports 0, parent 0)
    mergeSingleSymbol: adopts 406, mutates 175 (of which reach an adopted symbol: 164)

**Both lines are true at once, and their reconciliation is the finding:** the 406 adoptions and 175
mutations land on **lib** symbols, which come from `bindRealLibs` / `init:mergeLibGlobals` and are
not part of any `BinderResult`. **Program-file binder output is already immutable with respect to
the checker.**

**The zero has a positive control** (round 849's law — a zero from a blind instrument is
indistinguishable from a real negative). `ParallelSequentialBindSkipTest` drives two GLOBAL SCRIPT
files (no import, no export, so neither is a module) declaring the same name, which forces a merge
onto a program symbol, and asserts `declarationsChanged > 0`. It passes, so the arm can see.

**THE SCOPE OF THE RESULT, WHICH IS THE WHOLE CAVEAT.** It holds because every file on that profile
is a MODULE, and INV.3(d) keeps a module's locals out of `globals` entirely — so nothing merges.
A program containing global script files **does** mutate binder output; that is exactly what the
positive control demonstrates. So:

- sharing one bind across workers is sound **for an all-module program** with no further work;
- for a program with script files it needs stage 2 (copy on adoption) first;
- and the condition is cheap to test at runtime — `mergeAdopts`/the mutation counters over the
  program's own binder results — so a shared-bind path can be **gated on the program's shape**
  rather than blocked on the refactor.

That gate is the recommended next step, and it is much smaller than stages 2-4.

## 2d. THE ANSWER: tsc and tsgo already solve this, and the shape gate is SUPERSEDED

Both reference compilers are on this box now — tsc's sources at
`build/bench/tsc-project-*/src/compiler/`, and TypeScript 7.0.2's Go sources at `typescript-go-repo/`
(tag `typescript/v7.0.2`). Neither mutates binder output, and they do it the same way.

**tsc** (`checker.ts`) — copy-on-write plus a forwarding table:

```ts
if (!(target.flags & SymbolFlags.Transient)) { ...; target = cloneSymbol(resolvedTarget); }
...
function recordMergedSymbol(target, source) {
    if (!source.mergeId) { source.mergeId = nextMergeId; nextMergeId++; }
    mergedSymbols[source.mergeId] = target;          // var mergedSymbols: Symbol[] = []
}
function getMergedSymbol(symbol) { ... mergedSymbols[symbol.mergeId] ... }
```
and `mergeSymbolTable` writes the result back with `target.set(id, merged)`. Its motivation is NOT
parallelism — tsc is single-threaded — but that a bound `SourceFile` is reused across `Program`
instances in watch / incremental / the language service.

**tsgo** (`internal/checker/checker.go`) — the same clone, with the one change that makes it
parallel-safe:

```go
mergedSymbols map[*ast.Symbol]*ast.Symbol   // per-Checker, keyed by symbol IDENTITY
```

tsc writes `mergeId` **onto the shared symbol**; tsgo instead keys a per-`Checker` map by pointer, so
**nothing at all is written to a binder `Symbol`** and N checkers share one bind unconditionally. That
is why tsgo needs no shape gate — and why we should not build one.

### What round 884 measured: the clone ALONE is not enough

`--mergeClone` (tsc's `cloneSymbol` + a `Symbol.transient` flag, id deliberately carried over so the
copy is the same logical symbol to every id-keyed cache) was implemented and run against the corpus.
**Three tests regress**: `extendGenericArray`, `extendGenericArray2` (expected diagnostics, none
produced) and `jsExportMemberMergedWithModuleAugmentation` (TS2353 where TS2741 belongs).

The cause is that **our aliasing is load-bearing**. Because `globals[name]` IS the binder's object
today, every reader that reaches a symbol through `BinderResult.locals` / `nodeToSymbol` sees the
merged declarations for free. Copy it — at adoption or on first write, both break identically — and
those readers get the un-merged original. **That is precisely the hole `getMergedSymbol` fills**, and
it is why tsc has a forwarding table rather than just a clone.

So the remaining work is exactly one thing: **the forwarding table**, tsgo's shape.

1. `mergedSymbols` per `Checker`. Use an **id-keyed** map (`IntKeyMap`, already in the tree) rather
   than hashing the object: `Symbol.id` is an Int and the clone carries the original's id, so this
   gets tsc's cheap lookup with tsgo's safety.
2. Record at clone time; route readers through `getMergedSymbol`.
3. The cost is bounded and small: `globals.lookups` is **748,522** per compile, so even an extra probe
   on every one of them is ~15-22 ms ≈ **0.24-0.35%** of a warm sequential rebuild, against a measured
   **-5%** for the sharing it unlocks.
4. Then `--shareBind` becomes unconditional and `--mergeClone` becomes the default.

The hard part is not the table; it is finding the read sites. Round 884's three failures are the
cheapest possible map of them — start there, since each names a concrete path that needs the merged
view.

### Round 885: the table is IN, two sites routed, one path left — and it is a *third kind*

`mergedSymbols` is implemented as an **`IntKeyMap<Symbol>` keyed by `Symbol.id`** (the clone carries
the original's id, so an id already names "the same logical symbol", and an int probe beats hashing an
object — tsc keys by a `mergeId` it WRITES onto the source, which a shared bind cannot allow; tsgo
hashes the pointer). `getMergedSymbol` short-circuits on an empty table, so the default path is one
comparison. It is recorded in `cloneSymbolForMerge` and consulted at the two symbol -> type choke
points, `getDeclaredTypeOfSymbol` and `getTypeOfSymbol`.

**That fixed `jsExportMemberMergedWithModuleAugmentation`. The two `extendGenericArray` cases remain,
and their path is different in kind — worth reading before continuing:**

    pass("init:mergeLibGlobals")        { mergeSymbolTable(globals, libGlobals) }   // globals["Array"] = lib symbol
    pass("init:wireGlobalArrayTypes")   { globals["Array"] -> globalArrayType }     // Type CACHED IN A FIELD
    ...
    pass("init:mergeFileLocalsIntoGlobals")                                          // user's interface Array<T> merges

`globalArrayType` is built at step 2 from the pre-merge symbol and stored in a **field**. In-place
mutation works because that `Type` resolves its members lazily off the *same object* the merge then
edits. With a clone, the `Type` holds a `symbol` back-reference to the ORIGINAL and never sees the
user's `foo(): T`.

So the remaining sites are not name lookups at all — they are **`Type.symbol` back-references
dereferenced for member resolution**. tsc covers this with the same hop applied at ~147
`getSymbolOfDeclaration` call sites. For us the candidate choke point is wherever
`resolveStructuredTypeMembers` (and its kin) dereference `type.symbol` to read `declarations`.

**Do not fix it by re-ordering the passes.** Re-wiring `globalArrayType` after the file-locals merge
would make these two tests pass and leave the general defect — any type built before any later merge
has the same hazard. The dereference hop is the fix; the pass order is not the bug.

## 2c. The shape gate — SUPERSEDED by § 2d, kept for the reasoning (round 884, NOT implemented)

`--shareBind` shipped opt-in in round 883 (-5% warm at w4, replicated). What would make it default is
a gate deciding, before any checker runs, whether this program can share. The condition is **not**
"no merge happens" — it is narrower, and the difference is what makes a hand-rolled gate dangerous.

The merge site is:

```kotlin
for (result in binderResults) {
    if (isModuleFile(result.sourceFile.statements)) {
        for ((name, symbol) in result.locals)
            if (moduleLocalContributesGlobally(name, symbol)) mergeSingleSymbol(globals, name, symbol)
    } else { /* script file: the FULL merge — its locals ARE the global namespace */ }
}
```

and `mergeSingleSymbol` has two branches with **different consequences for a shared bind**:

- **`existing != null` (mutate).** The object mutated is `existing`, i.e. whatever is ALREADY in
  `globals`. For a SHARED name — a module local colliding with a lib/script global (`Symbol`, `Node`,
  `Performance`) — `existing` is the **lib** symbol, which is per-`Checker` and not shared. The
  program's `symbol` is only READ. **This case is harmless for sharing.**
- **`existing == null` (adopt).** `target[name] = symbol` aliases a PROGRAM symbol into `globals`. That
  alone mutates nothing — but it makes the program symbol reachable as `globals[name]`, so any LATER
  file merging the same name takes the mutate branch **on a binder-owned object**. That is the
  cross-worker corruption, and it needs two files contributing one global name.

So the sound condition is: **no program symbol is ever adopted into `globals`** (or, weaker but harder
to establish: adopted but never merged onto again). Round 882's measurement is the empirical form of
exactly this — 0 of 15,580 changed on an all-module program.

**Why it cannot simply call the predicate.** `moduleLocalContributesGlobally` is a `Checker` member
that reads checker state — `umdGlobalNames` (regex-collected before the merge) and, for the SHARED-name
case, the lib globals themselves. None of it exists before a `Checker` is constructed, which is where
the gate has to run. So the gate must either be hoisted along with the state it reads, or be a
**conservative superset** that refuses more than necessary and can never permit wrongly.

**A conservative superset that looks sufficient** (each clause AST-visible, no checker state):
refuse sharing if any file is a non-module (script) file; or declares `declare global`; or declares an
ambient `declare module "spec"`; or carries `export as namespace X`. Every one of those is a route to
a global contribution. **It has not been validated**, and the validation is not optional: the
obligation is to show the superset covers `moduleLocalContributesGlobally`'s true set, not to show it
passes on one profile. The cheap check is differential — run both, on all eight profiles plus the
corpus, and assert the superset never says "share" where the real predicate would have merged.

**Do not implement this from the summary above.** Read the merge site and both predicates first; the
reasoning here is a map, not a substitute, and a gate wrong in the permissive direction produces
silently wrong diagnostics on a program shape nobody in this repo's corpus has.

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
