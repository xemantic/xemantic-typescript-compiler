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
