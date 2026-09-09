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
| 3 | `TypeInstantiator` + file-level `TypeMapper`/`createTypeMapper` (`TypeInstantiator.kt`) — the INSTANTIATION seam: `instantiateType` / `instantiateSignature` / the fn-aware pair / the contextual pair / the two outer-arg substituters (design § 6 Stage 0, "instantiation" in the core order) | the `// Generic type instantiation` region of `Checker.kt` (291 lines), verbatim; each call site a one-line private delegation | `Checker.getTypeOfSymbol` (resolution of member/parameter types), `Checker.getUnionType` / `getIntersectionType` (normalization — identity moved in row 1, the reduction rules did not), `Checker.instantiateTupleElements` (the tuple rebuild, added (P18.28)/(CHK.96) stage 2), `TypeInterner.reference` — all through the FINAL class, no interface, no lambda | **`symbolTypes`** (the id-keyed type table, handed in as the object; written for every rebuilt member/parameter symbol) | ~290 | corpus 16,819/0/3 byte-identical; cost_gate +0.00% every counter; huge_methods 0 (815 classes); ab-interleaved 6 pairs −188 ms (−0.81%) B-wins-3/6 NOISE-DOMINATED (no wall effect); JFR alloc 1,903 vs 1,999 samples, same leaf families, no new frame; PrintInlining: the 10 B `Checker::instantiateType` hop `inline` ×55 / `inline (hot)` ×32 (refused only at 20 cold or size-capped callers), `instantiateSignature` hop `inline` ×15, `instantiateTypeFnAware` hop `inline (hot)` ×4 — the 1,265 B body was never inlinable before the split either (A: 1,241 B `callee is too large` ×81, identically); the three standing hot sites row-for-row identical across arms; core `--rerun` compile 77.8/79.2 s → 86.8/80.6 s (run 2 quoted: flat) |
| 4 | `NameResolver` (`NameResolver.kt`) — the NAME / MODULE RESOLUTION seam: the alias ladder (`resolveAlias` / `resolveAliasTarget` / `resolveImportTargetFallback` / `resolveAliasJsModuleSpecifier` / `resolveImportedSymbolGeneral` + computer), the specifier ladder (`resolveModuleSpecifier` + computer, the two relative resolvers, `augmentationTargetFile`), the scope probes `resolveNamePath` / `findSymbolInExports`, and the checker-local symbol-target LINK STORE (design § 6 Stage 0, "name resolution" in the core order) | 15 functions + 3 fields taken VERBATIM from `Checker.kt` (593 lines; a reverse-transform of the moved region `diff`s byte-identical against the original spans); the 11 with a surviving caller became one-line private delegations, the 4 whose only readers moved with them got no hop | **fourteen** `Checker` members, all reached through the FINAL class: `ambientModuleSurfaceMember`, `ambientRequireAliasTarget`, `blockLevelImportOf`, `createModuleSymbol`, `enclosingImportsOf`, `findEnclosingImport`, `findSymbolInAllNamespaceScopes`, `isImportBindingDecl`, `normalizePath`, `resolveAmbientModuleExportEquals`, `resolveExportedSymbolThroughStars`, `resolveExpressionToSymbol`, `resolveModuleExportAssignment`, `resolveQualifiedName` (16 call sites) | **none** — `symbolTargets` is handed in as the object; the two memos are owned here | ~593 (`Checker.kt` 199,963 → 199,405; `NameResolver.kt` 669) | suite 18,484/0/3 (18,477 baseline + the 7 new pins), all eleven named invariant gate classes confirmed green; **all 420 per-pass `--passTiming` counter rows and the 46 diagnostics byte-identical against a rebuilt pristine HEAD** (a stronger statement than cost_gate's 20 aggregates), cost_gate exit 0; the only differing section is the node-kind histogram, which a SAME-BINARY control moves more (70 lines A-vs-A vs 64 A-vs-B) — the documented crawl-worker race; huge_methods exit 0, 0 over limit, 835 classes (834 + `NameResolver`), `Checker.<init>` 5,701/8,000; ab-interleaved 6 pairs −152 ms (−0.57%) B-wins-2/6 NOISE-DOMINATED, both arms 46 errors; PrintInlining every hop `inline`/`inline (hot)` with ZERO refusals, two standing hot sites row-identical and `getTypeOfExpression` shown UNSTABLE across processes on one binary (A's 2nd run == B); JFR alloc: `NameResolver` never an allocated type, symmetric sampler tails, counts A 2,527/2,626 vs B 2,737/2,534 (no separation); core compile 1m25s both arms; build warning-clean |

| 5 | `NameResolver` step **4b-i** (`NameResolver.kt`) — the PER-FILE LOOKUP core: the per-file scope tables and their two build passes, the INV.3(b)(ii) visibility sets and their (INC.71) deferral, the probe funnel, the four consults built on them (`lookupPerFile` / `lookupInFileScope` / `globalsForFile` / `lookupPerFileForNode`), the (CHK.49) lib-value recovery, the (BIND.1) owning-file probes and the four first-hit program scans (design § 6 Stage 0, "name resolution") | 20 functions + 11 fields taken VERBATIM from `Checker.kt`; 15 with a surviving caller became one-line delegations, 5 whose only readers moved got no hop | **seven** `Checker` members: `augmentationContextSymbol`, `findTypeParamInStatements`, `globalAugmentationAddedSymbols`, `installGlobalsLookupClassifier`, `isModuleFile`, `libGlobals`, `moduleLocalContributesGlobally` — `libGlobals` and `globalAugmentationAddedSymbols` are deliberately READS and NOT constructor inputs (see the note) | **none** — the eleven fields the family owns moved with it | ~656 (`Checker.kt` 199,405 → 198,781; `NameResolver.kt` 669 → 1,418) | suite 18,484/0/3 with all 17 named invariant gate classes green; **all 420 per-pass `--passTiming` rows and the 46 diagnostics byte-identical against PRE-4a pristine**, i.e. one receipt covering rows 4 and 5 together; cost_gate exit 0, counter column identical to pristine; huge_methods exit 0, 0 over limit, 835 classes, `Checker.<init>` 5,701 → 5,656; ab-interleaved 6 pairs −120 ms (−0.45%) B-wins-3/6 NOISE-DOMINATED, both arms 46 errors; **PrintInlining shows the split IMPROVED the hottest hop** — `lookupPerFileForNode` was `4 inline (hot) + 57 too large` as a monolith and its 9-byte hop is now `57 inline + 41 inline (hot)` with ZERO refusals, the body unchanged; build warning-clean |
| 6 | `NameResolver` step **4b-ii** (`NameResolver.kt`) — NAMESPACE / HERITAGE / TYPE-NAME resolution, which COMPLETES the name-resolution seam: the (CHK.76) enclosing-namespace consult and the (CHK.77) merged-level machinery under it, the (CHK.78) augmentation-context pair, the INV.3(d) global-contribution predicate, round 748's `lexicalTypeSymbolForNode`, `resolveQualifiedName`, the heritage pair with `isInAmbientContext`, the (CHK.79) surface walk, `resolveTypeNameToSymbol` + its two helpers, and the INV.3(d) namespace-qualified family | 19 functions + 2 fields taken VERBATIM from `Checker.kt` | **+9 new** (`QUALIFIED_LEFT_MEANING`, `ambientModuleOfImportAlias`, `enclosingAmbientBlockMember`, `isDtsFile`, `lexicalBlockScopedEnumNames`, `mergeSharedKeepNames`, `moduleFiles`, `moduleNamedExportsOf`, `umdGlobalNames`) **and −4 ABSORBED** from rows 4 and 5 (`resolveQualifiedName`, `ambientModuleSurfaceMember`, `augmentationContextSymbol`, `moduleLocalContributesGlobally` now live here) → **NET 26 distinct reads for the whole collaborator** | **none** | ~788 (`Checker.kt` 198,781 → 198,022; `NameResolver.kt` 1,418 → 2,284). **STEP 4 TOTAL: 199,963 → 198,022, −1,941 lines** | suite 18,484/0/3, all 20 named gate classes green (incl. `KotlinExternalsGeneratorTest`, another module); **all 420 per-pass `--passTiming` rows and the 46 diagnostics byte-identical against PRE-4a pristine — one receipt for the whole of step 4**; cost_gate exit 0, counter column identical to pristine; huge_methods exit 0, 0 over limit, 835 classes, `Checker.<init>` 5,656 → 5,634; PrintInlining ZERO refusals on every hop, both STABLE standing hot sites identical to pristine (`getTypeOfExpression` deliberately not quoted — row 4's note shows it is unstable across processes); ab-interleaved 6 pairs +39 ms (+0.15%) B-wins-4/6 NOISE-DOMINATED, both arms 46 errors; warning-clean |
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
What a later stage must make explicit is exactly the FOUR reads: an instantiator
that took a `TypeResolver` and a `TypeNormalizer` as constructor inputs would have
an empty ambient row — that is the shape row 4 of this family should reach for.

(P18.28) / (CHK.96) stage 2 added the fourth, `Checker.instantiateTupleElements`,
and it is a debt of the same kind: the anonymous-object arm must rebuild a TUPLE
as a tuple (slots mapped, `readonlyTuple` / `tupleRestIndex` / per-slot optionality
carried) rather than let the member walk flatten it to `{ 0: T; length: N; }`, and
the rebuild needs the checker's tuple constructor. It is the one arm in this file
whose ORDER matters — it sits above the member walk — and its failure is SILENT,
since every tuple consumer tests `tupleElementTypes` and a flattened tuple simply
stops being one (measured: `Map<K, V>`'s `MapIterator<[K, V]>` had no readable
slots, so a `for (const [k, v] of map)` head read `any`). A later stage that gives
the instantiator a `TypeBuilder` input absorbs this read with the other three.

### 4 — NameResolver

The first extraction whose ambient row is fourteen entries, and they split cleanly
in two, which is the point of recording them: an AMBIENT-MODULE group (the
`declare module "spec"` surface — `ambientModuleSurfaceMember`,
`ambientRequireAliasTarget`, `resolveAmbientModuleExportEquals`,
`resolveModuleExportAssignment`, `createModuleSymbol`,
`resolveExportedSymbolThroughStars`) and a SCOPE/EXPRESSION group (name lookup,
qualified names, the enclosing-import probes, `normalizePath`). A resolver
constructed with an `AmbientModuleIndex` and a `ScopeResolver` would have an empty
row; that is the shape row 5 of this family reaches for. There are **no ambient
writes** — the only mutable containers are `symbolTargets` (handed in as the object
the init passes fill, row 3's rule) and the family's own two memos, whose sole
readers moved with them.

Two deliberate deviations from the queue item's function list, both measured rather
than argued. `resolveExportedSymbolThroughStars` and `moduleNamedExportsOf` stay in
`Checker.kt`: they are 7- and 3-line MEMO WRAPPERS whose computers
(`computeExportedSymbolThroughStars`, `getModuleNamedExports`) are large
checker-resident star-following walks with ~20 call sites spread through the file,
so moving the wrapper alone would buy ~31 lines and cost ~20 delegation hops plus
extra ambient entries. `ambientModuleFilelessCache` stays because its only reader,
`ambientModuleBlockIsFileless`, is a step-**4b** function.

The other deviation is forced by the warning-clean build rather than chosen:
`getSymbolTarget`, `setSymbolTarget`, `computeModuleSpecifier` and
`computeImportedSymbolGeneral` have **zero** callers left in `Checker.kt` once the
family moves (their 2 / 16 / 1 / 2 call sites are all inside the moved spans), so a
`private` delegation for them would be an unused-member warning. They get no hop —
which is the right answer anyway: the LinkStore that `getSymbolTarget` /
`setSymbolTarget` front moved wholesale, and `Checker` no longer names
`state.symbolTargets` at all.

The verbatim claim is checked mechanically rather than asserted: reversing the three
documented transformations over the moved region (`checker.` prefixes stripped,
`symbolTargets` → `state.symbolTargets`, `fun` → `private fun`) `diff`s clean
against the original 593 source lines. Exactly 16 ambient prefixes, 2 link-store
rewrites and 15 visibility changes were applied — each count asserted, not eyeballed.
An independent MULTISET check over the same region (orchestrator, different method)
agrees: the only lines the move does not account for are the 15 signatures whose
visibility changed and the new file's header and class KDoc. The collaborator's
public surface was then narrowed to exactly its 11 entry points — the four no-hop
functions are `private`, so the class's surface and `Checker`'s delegation count
are the same number by construction.

RECEIPT DETAIL WORTH KEEPING, because two of the four § 10 instruments needed a
SAME-BINARY CONTROL before they could be read. (i) The counter receipt is stronger
than `cost_gate.py`: all **420 per-pass counter rows** of `--passTiming` are
byte-identical between pristine HEAD and the split, as are the 46 diagnostics — a
claim about every registered pass, where the gate speaks for 20 aggregates. (ii) The
ONLY section of that output which moves is the node-kind histogram, and the same
binary run twice moves it MORE (70 differing lines A-vs-A against 64 A-vs-B;
`Identifier` reads 375,438 / 373,363 on one binary and 378,748 on the other) — it is
the `+=`-from-the-crawl-workers race CLAUDE.md already documents, and reading it as
a treatment effect is a trap this row exists to warn the next extraction about.
(iii) PrintInlining: every delegation hop C2 compiles reads `inline` or
`inline (hot)` with ZERO refusals (`resolveAlias` 28/4, `resolveModuleSpecifier`
20/5, `resolveAliasTarget` 6/4, `resolveImportedSymbolGeneral` 3/4);
`checkArgumentsAgainstSignature` and `isTypeAssignableTo` are row-identical across
arms, and `getTypeOfExpression` — which rows 1 and 3 record as row-identical — is
**NOT stable across processes on one binary**: A read `1 inline (hot) + 372 too
large` and A's second run `382 too large`, exactly arm B's reading. So that site
cannot separate arms here, and the earlier rows' "row-for-row identical" claim was
made without this control. (iv) JFR allocation: `NameResolver` is never an allocated
TYPE (constructed once per `Checker`, as § 10 requires), no allocation family is
lost or gained beyond a symmetric sampler tail (37 types only-in-A, 43 only-in-B),
and the sample counts do not separate the arms (A 2,527/2,626 against B 2,737/2,534
over two runs each).
A pure move's invariant IS "nothing changed", which the corpus and the eleven named
gate classes pin better than any hand-written case could (row 2's reasoning) — all
eleven were confirmed GREEN in the gating run, not merely assumed. What the new
`NameResolverTest` (7 pins) adds is the thing only a test at this level can state:
the MODULE-SPECIFIER LADDER is a function of the file set and the options ALONE, so
it is exercised through a `NameResolver` built with an EMPTY `globals`, empty
`moduleResolutions` and an empty symbol-target store — an edit that reaches for
checker scope state from the specifier ladder fails there instead of silently
deepening this row. Its sharpest pin asks an unresolvable specifier TWICE: a miss is
memoized as the `UNRESOLVED_MODULE_SPEC` sentinel and mapped back to null on read
(round 483's single-lookup form), so an edit that "simplifies" the sentinel returns
the sentinel STRING as a resolved file name — from the second call onward only,
which a single-ask pin cannot see.

### 5 — NameResolver, step 4b-i (the per-file lookup core)

**The queue item's own instruction was UNSAFE and is not followed.** It asks for
`libGlobals`'s declaration to be hoisted above the collaborator's construction site so
it can be a constructor input. `libGlobals` is initialized by `parseBuiltinLib()`,
whose SIDE EFFECT fills `realLibUnknownNames` — a field whose own KDoc records that it
is "DECLARED BEFORE [libGlobals] on purpose — the Kotlin field-init order gotcha".
Hoisting that chain above line 705 reorders lib parsing against ~9,300 lines of field
initialization. So `libGlobals` and `globalAugmentationAddedSymbols` (both declared
BELOW the construction, where a constructor input would capture null) are ambient
READS instead, which is sound because every reader here runs during or after the init
passes. The general rule for the rest of this arc: **a constructor input must be
declared above line 705, and a field whose initializer has a side effect on another
field cannot be moved at all.**

**One coupling is BIDIRECTIONAL and intended.**
`Checker.installGlobalsLookupClassifier` stays in `Checker` — it reads the walk-scoped
`currentFileLocals` — while the two taxonomies it classifies against
(`classifierModuleLocalNames` / `classifierNonModuleVisible`) live here; so it reads
them off this class and calls `ensurePerFileVisibility()`, and
`computePerFileVisibility` calls it back. `augmentationContextSymbol` is a second such
pair (it calls `lookupPerFile` and `nodeSymbolOf` back). Neither is a defect to remove
in a split; both are named so a later stage can price them.

**THE EXTRACTION IMPROVED HOT INLINING, which is row 1's finding on the compiler's
hottest lookup.** `lookupPerFileForNode` has ~67 callers and runs ~2M times per
self-compile. As a monolithic `Checker` method C2 refused it at 57 sites
(`4 inline (hot) + 57 too large`); the 9-byte delegation hop now reads
`57 inline + 41 inline (hot)` with ZERO refusals while the body's own rows are
unchanged (`7 inline (hot) + 58 too large`). `globalsForFile` is the same shape.
**Receipt-reading trap: Kotlin mangles an `internal` member's JVM name with a
`$<module>` suffix**, so a `PrintInlining` grep for the source name silently reads
ZERO rows for exactly the three hot hops that matter here.

**The four first-hit program scans moved VERBATIM and are FLAGGED, not fixed**:
`resolveIdentifierInFile` / `findTypeAliasByName` / `findTypeParamDeclByName` /
`findNamespaceLocalInterface` answer a (BIND.1)-class cross-file question by scanning
the program for a first hit. A split is not the place to change that.

**A REVIEW HAZARD FOUND HERE AND FIXED SEPARATELY (`2db1c14ca`).**
`UNRESOLVED_MODULE_SPEC` holds a literal NUL byte, and after 4a it sat at offset 7,910
— inside the 8,000-byte window git's binary heuristic scans. So `NameResolver.kt`, the
file this whole arc grows into, rendered as `Bin … bytes` with NO line diff, and
commits `da92e5bd3` and `84dd8ad5d` are unreviewable by diff for it. The escape is
provably a no-op: the compiled class is BYTE-IDENTICAL across it. `Checker.kt` escapes
only by accident, its own NULs sitting at byte 4.6M.

### 6 — NameResolver, step 4b-ii (namespace / heritage / type-name), and the seam CLOSED

**The ambient row gets BETTER as a family completes, and this is the measurement that
shows it.** 4b-ii absorbs FOUR reads the earlier rows recorded — `resolveQualifiedName`
and `ambientModuleSurfaceMember` from row 4, `augmentationContextSymbol` and
`moduleLocalContributesGlobally` from row 5 — because those functions now live inside
the collaborator, so the calls stop crossing the boundary. Net for the whole
`NameResolver`: **26 distinct checker reads, no writes, for 2,284 lines.** The lesson
for the rows still to come: **an intermediate row's ambient count is the WORST that
family will look**, and splitting a seam across commits temporarily inflates it — which
is an argument for reading the ledger by FAMILY, not by row.

**The constructor-input trap bit a third time and cost nothing, because row 5 had
written the rule down.** `umdGlobalNames` (9975), `moduleFiles` (10004),
`mergeSharedKeepNames` (9964), `lexicalBlockScopedEnumNames` (15485),
`QUALIFIED_LEFT_MEANING` (10042) and `isDtsFile` (4635) are ALL declared below the
construction site at `Checker.kt:666`, so none could be a constructor input without
capturing null. They are ambient reads. `QUALIFIED_LEFT_MEANING` could not have moved
in any case — two of its readers are outside the moving set.

**A THIRD JVM-NAME-MANGLING MECHANISM, beside `internal`'s `$<module>` suffix:
`SymbolFlags` is a VALUE class**, so `lookupInEnclosingNamespaces` compiles as
`lookupInEnclosingNamespaces-bd7vo6s` and a `PrintInlining`/`javap` grep for the source
name reads ZERO rows for it. Both mechanisms now sit in CLAUDE.md, because both fail in
the direction that reads as "this hop was never compiled".

**The public surface reconciles EXACTLY**, which is the structural check worth repeating
for row 7: 40 non-private `NameResolver` members against 40 `nameResolver.` references
in `Checker.kt` — 38 delegations plus the two classifier taxonomy reads. The only
apparent extras in `javap` are the JVM property accessors for those two `var`s and the
value-class mangled name.

**What a later stage must still pay.** The 26 reads split into an AMBIENT-MODULE group,
a SCOPE/EXPRESSION group and a small set of pure predicates (`isModuleFile`, `isDtsFile`,
`isImportBindingDecl`). Two couplings are BIDIRECTIONAL by design and are not defects to
remove in a split: `installGlobalsLookupClassifier` stays in `Checker` because it reads
the walk-scoped `currentFileLocals`, and `augmentationContextSymbol` calls
`lookupPerFile`/`nodeSymbolOf` back. A resolver given an `AmbientModuleIndex` and a
`ScopeResolver` as inputs would have an empty row; that is the shape row 7 reaches for.
