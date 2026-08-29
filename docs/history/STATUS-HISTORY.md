**(CHK.71)(b) — THE BLOCKER WAS NOT B83.5 BUT A **FOURTH SHADOW SHAPE**, AND IT LANDS;
THE RECEIVER HALF IS REFUSED AGAIN ON A *DIFFERENT* ROW (2026-08-28).** A BLOCK-scoped
declaration inside a NESTED function shadowing an ENCLOSING FUNCTION's local was covered by
none of round 351 (top-level decls), round 460 (two decls in one body) or round 455 (a
GLOBAL/file-level collision) — whose condition is literally
`outerBound && !currentLocalTypes.containsKey(nm)`, the inherited case inverted. A shipped
ours-only TS2322 at every assignment to the inner name, judged against the WRONG
declaration's type; twelve lines reproduce it, tsgo 7.0.2 is silent, and no optional chain
is anywhere near it. **The optional-chain receiver half is re-priced, not re-refused for the
same reason**: the two `moduleNameResolver.ts` rows are GONE (they were this shadow shape)
and the grid is `added=0 removed=0` with both halves — what refuses it now is **one knip
row**, `compilers.ts:60:49 TS18047`, because tsc narrows a receiver to non-null in the TRUE
branch of a truthy test on an optional chain and we do not. **The blocker is now
optional-chain truthiness narrowing, a nameable and reducible mechanism.** A pin written as
a CONTROL measured as a POSITIVE (on the parent the first TS2322 is the inner assignment
reported against the outer type), and two of four pin expectations were wrong because the
message strips nullish — tsgo prints ours verbatim. **GATES.** Suite **16,422 / 0 / 3** (+5,
exactly the new pins), no corpus baseline moved; grid `790c337141b167657e4f1f3a219474aa`,
`added=0 removed=0`; cost_gate exit 0, `output.errors` **46**; huge_methods 783 / 0;
partition-equivalence EQUIVALENT all 78, floor 65 ms (one draw); capture-equivalence
DIVERGED **964** in 43 of 76, `definitions=0 moreAny=0` — the standing state exactly; knip
**49**, jsonrepair **4**.

