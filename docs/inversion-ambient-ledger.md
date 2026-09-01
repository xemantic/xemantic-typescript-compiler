# Inversion ambient ledger — (INV.0) Stage 0 extractions

One row per extraction out of `Checker.kt` (owner directive 2026-09-02; the
contract is `docs/INVERSION-DESIGN.md` § 10). The columns that matter are the
AMBIENT ones: which checker fields the extracted collaborator still READS and
WRITES after the move — the census of what must become explicit for every
later inversion stage. "none" is the target state; a non-none row is a debt
the next stage must either pay or justify.

| # | Collaborator | Extracted from | Ambient reads | Ambient writes | Lines moved | Receipts |
|---|--------------|----------------|---------------|----------------|-------------|----------|
| 1 | `TypeInterner` (`TypeInterner.kt`) — canonical type identity: `Type.Reference` / `Type.Union` / `Type.Intersection` interning (INV.5(a), design § 4 pillar 4) | `getOrInternReference`, `internUnion`, `getIntersectionType`'s intern tail + the six intern-cache maps of `CheckerState` | **none** — owns its six maps; every input is a parameter | **none** | ~60 | corpus 16,781/0/3 byte-identical; cost_gate +0.00% every counter; huge_methods 0; ab-interleaved +0.26% B-wins-2/6 NOISE-DOMINATED (no wall effect); JFR alloc 2,041 vs 2,036 samples, same families, no new frame; PrintInlining: 13 B/12 B hops `inline (hot)`, bodies hot-inline ×7/×10 vs ×0/×3 BEFORE (the 277 B monolith never hot-inlined); core `--rerun` 84.7/80.5 → 79.5/80.9 s |
| 2 | `Relation` + `Ternary` (`TypeRelationCache.kt`) — the relater's cache seam | the nested `private class Relation` / `private enum class Ternary` of `Checker.kt` | **none** — its probe hooks reach the process-wide `MapCensus`/`PassTiming` instruments (measurement machinery, not checker state) | **none** | ~79 | corpus 16,803/0/3 byte-identical; cost_gate +0.00%; huge_methods 0. A pure RELOCATION — no new call hop, so the § 10 wall/allocation/inlining receipts are not exercised (they exist to price delegation hops; a file move adds none) |
| 3 | `TypeInstantiator` + file-level `TypeMapper`/`createTypeMapper` (`TypeInstantiator.kt`) — the INSTANTIATION seam: `instantiateType` / `instantiateSignature` / the fn-aware pair / the contextual pair / the two outer-arg substituters (design § 6 Stage 0, "instantiation" in the core order) | the `// Generic type instantiation` region of `Checker.kt` (291 lines), verbatim; each call site a one-line private delegation | `Checker.getTypeOfSymbol` (resolution of member/parameter types), `Checker.getUnionType` / `getIntersectionType` (normalization — identity moved in row 1, the reduction rules did not), `TypeInterner.reference` — all through the FINAL class, no interface, no lambda | **`symbolTypes`** (the id-keyed type table, handed in as the object; written for every rebuilt member/parameter symbol) | ~290 | corpus 16,819/0/3 byte-identical; cost_gate +0.00% every counter; huge_methods 0 (815 classes); ab-interleaved 6 pairs −188 ms (−0.81%) B-wins-3/6 NOISE-DOMINATED (no wall effect); JFR alloc 1,903 vs 1,999 samples, same leaf families, no new frame; PrintInlining: the 10 B `Checker::instantiateType` hop `inline` ×55 / `inline (hot)` ×32 (refused only at 20 cold or size-capped callers), `instantiateSignature` hop `inline` ×15, `instantiateTypeFnAware` hop `inline (hot)` ×4 — the 1,265 B body was never inlinable before the split either (A: 1,241 B `callee is too large` ×81, identically); the three standing hot sites row-for-row identical across arms; core `--rerun` compile 77.8/79.2 s → 86.8/80.6 s (run 2 quoted: flat) |

## Notes per row

### 1 — TypeInterner

The six maps moved wholesale (`referenceCache`/`unionInternCache`/
`intersectionInternCache` + their packed-Long `M0.3(iii)` fast-path twins);
their only three access sites in `Checker.kt` became one-line delegations
(`getOrInternReference` → `reference`, `internUnion` → `union`, the
`getIntersectionType` tail → `intersection`). Normalization stays with the
callers — the interner is identity ONLY, which is what makes its ambient
surface empty. Constructed once per `Checker` inside `CheckerState`
(lifetime-coupled: `Type.id`s are a per-checker, per-thread sequence,
INV.6(6c0), so a longer-lived interner would conflate types by id collision).

Receipt detail worth keeping: the extraction IMPROVED hot inlining rather than
costing it — the pre-split `getOrInternReference` was a 277-byte body that
C2 refused at every hot site (`callee is too large` ×39, zero `inline (hot)`
rows), while the split's 13-byte hop inlines everywhere and the 273-byte
`TypeInterner::reference` body itself reads `inline (hot)` ×7 (union: ×10 vs
×3 before). The three standing hot sites (`checkArgumentsAgainstSignature`,
`getTypeOfExpression`, `isTypeAssignableTo`) are row-for-row identical across
arms. Logs: scratchpad `inv0-*.log` of the (P18.5) session.

### 2 — Relation + Ternary (relocation)

The four relation instances stay in `CheckerState`; every `get`/`set` call
site is byte-for-byte unchanged. What moved is the TYPE, into the file the
relater's algorithm will grow into — so the relater's own extraction round
starts from a named seam instead of a 191k-line neighbourhood. The class's
(WARM.31) boxed-key amplifier and (HASH.1) `packIdPair` notes moved verbatim.
No new local test: a relocation's invariant IS "nothing changed", which the
whole corpus pins better than any hand-written case could.

### 3 — TypeInstantiator

The first row whose ambient columns are NOT "none", stated as the debt it is:
the family resolves the types it substitutes into (`getTypeOfSymbol`), normalizes
what it rebuilds (`getUnionType`/`getIntersectionType` — step 1 moved IDENTITY only,
the union/intersection reduction rules still live with the checker) and writes
the rebuilt symbols' types into `symbolTypes`. Those three checker methods went
`private` → `internal` and are reached through the final `Checker` — a direct
call, which is what § 10 asks (no interface on hot dispatch, no captured lambda).
`createTypeMapper` is a pure function and became file-level, pinned without a
checker (`TypeInstantiatorTest`, 3 pins); the checker's six ad-hoc `TypeMapper
{ … }` lambda sites and `typeToStringWithMapper` (display, stays) are untouched.
What a later stage must make explicit is exactly the three reads: an instantiator
that took a `TypeResolver` and a `TypeNormalizer` as constructor inputs would have
an empty ambient row — that is the shape row 4 of this family should reach for.

