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
| 7 | `Relater` (`Relater.kt`) — the RELATION seam: the whole of Phase 4's items 4a-4e, i.e. the algorithm that decides whether one `Type` is assignable to / comparable with / identical to another (design § 6 Stage 0, "relations" in the core order; ledger row 2 put the `Relation` cache in `TypeRelationCache.kt` in 2026-09-02 so this extraction would not start from a 198k-line neighbourhood) | 14 functions + 4 fields taken VERBATIM from **five contiguous spans** of `Checker.kt` — the flag fast path `isSimpleTypeRelatedTo`, the entry `checkTypeRelatedTo` with its `--passTiming` reentrance probe and the recursive `checkTypeRelatedToCore` under it, `structuredTypeRelatedTo` / `objectTypeRelatedTo` / `propertiesRelatedTo`, `signaturesRelatedTo` / `signatureRelatedTo` / `methodSignaturesBivariantlyRelated`, and `isTypeAssignableTo`. Six kept a surviving caller and became one-line private delegations; the other eight had ZERO callers left and got no hop. NOT split, unlike step 4b: this is ONE mutually-recursive algorithm, so a mid-recursion seam would put a `Checker` hop inside the compiler's hottest recursion | **45** — the largest row of the arc, and expected: the relater's ambient IS the type system. ENUM RELATION (16): `enumLiteralApparentPrimitive`, `enumMemberTypeIsStringValued`, `enumMemberTypesAreSameMember`, `enumMemberTypesOf`, `enumMemberValueEqualsLiteral`, `enumOfMemberTypeSymbol`, `enumOwnTypeSymbol`, `enumTargetAdmitsNumericSource`, `enumTargetsAreOwnMembers`, `enumTypesRelation`, `numericLiteralFitsEnum`, `isNumericEnumObjectType`, `isStringEnumObjectType`, `intersectionMergedSatisfiesTarget`, `intersectionMergedContradictsTarget`, `targetIsMemberShaped`; MEMBER RESOLUTION (8): `getTypeOfSymbol`, `resolveStructuredTypeMembers`, `resolveBaseTypesLazy`, `getPropertyTypeForRelation`, `getStaticMembersOfType`, `getApparentType`, `primitiveApparentWrapper`, `isOptionalProperty`; TYPE CONSTRUCTION (4): `getUnionType`, `getIntersectionType`, `getOrInternReference`, `instantiateType`; PREDICATES/WIDENINGS (12): `isArrayLikeReference`, `isRestTupleMember`, `tupleRelationElementTypes`, `readonlyToMutableArrayLike`, `typeContainsUnresolvedTypeParam`, `typeIncludesUndefined`, `propTypeContainsLiteral`, `literalTypeOfExpression`, `isPropPrivateBrandMismatch`, `isLibPhantomMemberOfModuleInterface`, `widenOptionalSourcePropType`, `widenOptionalTargetPropType`; STATE (5): `strictNullChecks`, `freshObjLitRange`, `globalArrayType`, `globalReadonlyArrayType`, `relationDepth`. Plus, by NAME rather than through `checker`: 4 `companion` name sets and the file-private (REL.2) switch `REL2_ENUM_TO_MEMBER`, both widened to `internal` | **5** — `relationDepth` and `genericPropInstantiationBudget` (recursion bookkeeping) and `lastMissingPropertyName` / `lastMissingPropertySymbol` / `lastMissingIndexSigKind`, which are an ELABORATION RETURN CHANNEL and not state the algorithm consults. **No ambient writes to CONTAINERS**: the three stacks it mutates are handed in as the objects and `CheckerState` keeps owning them | ~1,256 (`Checker.kt` 198,022 → **196,797**; `Relater.kt` 1,446) | suite **18,489/0/3** (18,484 + 5 new pins); **488 deterministic lines of `--passTiming` byte-identical against a REBUILT pristine HEAD** — all 420 per-pass rows, the 46 diagnostics, the emissions census, the counter block and the globals-lookup line — with a SAME-BINARY control showing the receipt stable; `cost_gate.py` exit 0 and its counter column identical to pristine's; `huge_methods.py` exit 0, 0 over limit, **836** classes (835 + `Relater`), `Checker.<init>` 5,634 → 5,675; ab-interleaved 6 pairs **−11 ms (−0.04%) B-wins-3/6 NOISE-DOMINATED**, both arms 46 errors; PrintInlining ZERO refusals on every hop and the split REMOVED 344 + 9 `too large` refusals at the two hottest entry points; `new Relater` appears at **exactly one** bytecode in the module (`Checker.<init>`); build warning-clean |
| 8 | `MemberResolver` (`MemberResolver.kt`) — the MEMBER-RESOLUTION seam: tsc's lazy member-table build (design § 6 Stage 0, "member resolution" in the core order). **The seam IS the table builders WITHOUT `getTypeOfSymbol`** — that was the queue item's open question and this row is the answer: § 6 puts `getTypeOfSymbol`/`getTypeOfExpression` in Stage 3 because "their ambient IS the checker", so they stay ambient reads here | 14 declarations taken VERBATIM from ONE contiguous span of `Checker.kt` (117359-118015, 657 lines) — the entry point with its (WARM.3) consumed-side census guard and `--passTiming` probe, the B202.1 in-progress cycle break and (INC.23)'s truncation flag, the kind dispatch and its `LibTypeCensus` twin, B419's JS-expando `this.X =` synthesis, and the interface / reference / anonymous builders — plus `mrProbeDepth`. THREE kept a caller and became delegations; the other eleven had ZERO and got no hop. The member-NAME family (`getMemberName` / `declaredMemberName` and the late-bound key machinery, from 118018) deliberately did NOT move: it is a later row's seam | **21** over 45 call sites, grouped: TYPE RESOLUTION (6) `getTypeFromTypeNode`, `getTypeOfSymbol`, `getTypeOfExpression`, `getWidenedLiteralType`, `getUnionType`, `resolveBaseTypesLazy`; INSTANTIATION CONTEXT (5) `instantiateType`, `instantiateSignature`, `scopeMapper`, `withInstantiationContext`, `currentTypeParamScope`; DECLARATION READING (4) `declaredMemberName`, `getMemberName`, `getParameterSymbols`, `requiredParameterCount`; NAMESPACE SCOPE (2) `pushInferenceNamespaceFor`, `inferenceNamespaceStack` (B280's push/pop pair); LIB/TARGET (4) `builtinLibDecls`, `libFeatureAvailable`, `isLibSymbolForCensus`, `isJsLikeFileName`. Plus `Checker.LIB_MIN_TARGET` by name | **1**, and the columns are DISJOINT: `memberResolutionTruncated`, (INC.23)'s truncation flag — an OUT channel to `Checker.getTypeOfSymbol`, never read here. **No ambient writes to CONTAINERS**: `symbolTypes` and `memberResolutionInProgress` are handed in as the objects and `CheckerState` keeps owning them | ~657 (`Checker.kt` 196,797 → **196,172**; `MemberResolver.kt` 814) | suite **18,493/0/3** (18,489 + 4 new pins); **the SAME 488 deterministic `--passTiming` lines byte-identical across THREE binaries** — pre-step-5 pristine, step 5, and this — i.e. one receipt now covers 1,882 moved lines; `cost_gate.py` exit 0 with its column identical to pristine's; `huge_methods.py` exit 0, 0 over limit, **837** classes, `Checker.<init>` 5,675 → 5,705; ab-interleaved 6 pairs **+38 ms (+0.15%) B-wins-3/6 NOISE-DOMINATED**, both arms 46 errors; PrintInlining ZERO refusals on the hot hop, which REMOVED **189** `too large` refusals at `resolveStructuredTypeMembers`' 244 call sites; `new MemberResolver` at exactly one bytecode in the module; build warning-clean |
| 9 | `MemberNames` (`MemberNames.kt`) — the MEMBER-NAME / LATE-BINDING seam: turning a member DECLARATION into the NAME its table is keyed by. tsc calls the interesting half LATE BINDING (`getLateBoundNameFromType` / `lateBindMember`); here it is resolved SYNTACTICALLY, for the reason round 935 measured | 24 declarations taken VERBATIM from ONE contiguous span of `Checker.kt` (117395-118021, 627 lines) — the plain `Identifier`/string/numeric extractor, the computed-key pair, the late-bound key ladder (a `const` alias chain, an enum member's value, a well-known symbol, a qualified `NS.K` path), the dotted-name and module-path resolution under it, the enum-value readers, and the written-name SPAN the diagnostics use. TWELVE kept a caller and became delegations; the other twelve had ZERO and got no hop. **The family owns NO state** — there is no field in the span, which is the other half of why this row is clean | **5** over 13 call sites, and four of the five belong to seams of their own: ENUM VALUE (3) `canonicalEnumSymbol`, `enumMemberEntries`, `resolveEnumSymbolForDiscriminant`; SYNTAX (2) `unwrapParensExpr` (6 sites), `expressionTrueEnd` (4). Plus the file-private hop limit `LATE_BIND_ALIAS_HOPS`, widened to `internal` and genuinely SHARED (4 sites here, 1 left in `Checker`). `fileResults` is a CONSTRUCTOR INPUT, not a read — it is declared at `Checker.kt:265`, above the 666 boundary — which is what takes the row from 6 to 5 | **none** | ~627 (`Checker.kt` 196,176 → **195,606**; `MemberNames.kt` 765) | suite **18,498/0/3** (18,493 + 5 new pins); **the same 488 deterministic `--passTiming` lines byte-identical across FOUR binaries** — pre-step-5 pristine, step 5, step 6a and this — i.e. one receipt over 2,509 moved lines; `cost_gate.py` exit 0; `huge_methods.py` exit 0, 0 over limit, **838** classes, `Checker.<init>` 5,705 → 5,721; ab-interleaved 6 pairs **−182 ms (−0.70%) B-wins-3/6 NOISE-DOMINATED**, both arms 46 errors; PrintInlining removed **61** `too large` refusals across five hops and added none; `new MemberNames` at exactly one site; build warning-clean |
| 10 | `EnumSemantics` (`EnumSemantics.kt`) — the ENUM seam: everything this checker knows about an enum's MEMBERS, their constant VALUES, the relation between two enums, and how an enum or a member of one is DISPLAYED in a diagnostic (design § 6 Stage 0; the family the row-9 correction censused). tsc models a literal enum AS the union of its members, so most of this is ordinary union machinery there; here (REL.1)(a) round 741 mints ONE member-less `Type.Object` for the whole enum, which is why the decomposition, the value domain and the cross-enum identity question all have to be answered explicitly — and why answering them in one place is a seam at all | 40 declarations taken VERBATIM from ONE contiguous span of `Checker.kt` (115064-116076, 1,013 lines) — the decomposition (`enumMemberTypesOf` / `enumMinusMembers` / `enumComparisonAtoms` …), the value domain (`enumMemberEntries` / `enumKnownDomainValues` / `enumValueDomainIsComplete` / `enumDeclIsAmbient` / `numericLiteralFitsEnum`), the relation (`enumTypesRelation` and its nested `EnumRelFailure`, the flavour predicates, the symbol readers) and the DISPLAY block (the TS2367 no-overlap pair, the relation-error collapse, the qualified-name family, the elaboration). TWENTY-FOUR kept a caller in `Checker.kt` and became one-line delegations; the other sixteen had none and got no hop | **13** over 50 sites, and the split is the row-9 one: the family owns its four memos and its display flag, so what remains is the type system it displays and unions with (`typeToString`, `getUnionType`, `getDeclaredTypeOfSymbol`, `getDeclaredTypeOfEnumMember`, `baseTypeOfLiteralType`, `literalTypeOfExpression`, `isLiteralAssignableToMember`, `fmtEnumAsgVal`), the canonicalizer `canonicalEnumSymbol`, the binder-side `enumValues` table, and `moduleFiles` / `isDtsFile` / `moduleFileBaseNoExt`. Seven `Checker` members were widened `private` → `internal` for it | **none** | ~1,013 (`Checker.kt` 195,606 → **194,631**; `EnumSemantics.kt` 1,137). **Plus the first cross-collaborator wiring: `Relater` 49 → 38 checker reads (−11 over 20 sites) and `MemberNames` 5 → 4** — the first fall in the arc's ambient TOTAL | suite **18,506/0/3** (18,498 + 8 new pins); **the same 488 deterministic `--passTiming` lines byte-identical across FIVE binaries** — pre-step-5 pristine, steps 5, 6a, 6b and this — i.e. one receipt over 3,522 moved lines; `cost_gate.py` exit 0; `huge_methods.py` exit 0, 0 over limit, **839** classes, `Checker.<init>` 5,721 → **5,697** (it FELL: five field initializers left, one collaborator field arrived); ab-interleaved 6 pairs **−120 ms (−0.46%) B-wins-3/6 NOISE-DOMINATED**, both arms 46 errors; PrintInlining **flat, not better** — 182 → 191 `too large` over 20 tracked sites — which is the honest reading for a family of 40 small functions rather than one large one; `new EnumSemantics` at exactly one bytecode; build warning-clean |
| 11 | `CaptureRecorder` (`CaptureRecorder.kt`) — the TYPE-CAPTURE seam: what the language service reads out of a build (design § 6 Stage 0). The step-8 census took it over the queue item's own "obvious candidate", the member-ACCESS family, which measured a SCATTER — `getPropertiesOfType` 116481, `getPropertyAcrossType` 117056, `getStaticMembersOfType` 117400, `getApparentType` 132856, `collectInheritedPropertyNames` 143137, `getPropertyTypeForRelation` 166353, `collectTargetPropertyNames` 166608, `isOptionalProperty` 170841, eight neighbourhoods and no span | 98 declarations taken VERBATIM from ONE contiguous span of `Checker.kt` (5990-9109, 3,120 lines) — the five result tables and their `internal` readers, the recorders `spineEnterNode` calls, the resolvers under them, the renderers, and the nested `SignatureParameterPart`. FIFTEEN kept a caller (10 functions plus the five `captured*` readers the compile driver copies into `CompilationResult`) and became one-line delegations; the other 83 had none. The three `TYPE_CAPTURE_*_MAX_DEPTH` constants moved into the collaborator's own companion, having no reader left | **61 over 170 sites — the arc's largest, and that is the finding rather than a defect.** Design § 2 says this checker cannot serve a post-hoc oracle because "its answers are functions of walk-scoped state"; this row is that claim as a number, and the expensive half is not the type system but the WALK: `ctaFrames`, `ctaM3FlowGraph`, `currentFlowGraph`, `currentClassForThis`, `currentCheckFileName`, `spineCurrentScope`, `spineFileName`, `inAsyncFunctionBody`, `inGeneratorFunctionBody`, `inNonArrowFunctionBody`, `currentTypeParamScope`, `currentTypeParamDecls`, `inferenceNamespaceStack`, `withCtaFrameLocals`. 42 `Checker` members widened `private` → `internal`, and `CtaFrame` with them | **9 over 25 sites**, and every one is a SAVE-AND-RESTORE of walk-scoped ambient in (API.3d)'s pull-based reconstruction (`currentClassForThis`, `currentCheckFileName` ×10, the three body-kind flags, the two type-param fields, `currentFlowGraph`) plus `nodeAnswerComputations++`, whose `private set` became `internal set` | ~3,120 (`Checker.kt` 194,631 → **191,540**; `CaptureRecorder.kt` 3,214) — the arc's largest single move | **THE RECEIPT FOR THIS FAMILY IS THE CAPTURE CHANNEL, and it is far stronger than the pass table**: over tsc's own 78 sources, **381,666 captured types and 360,917 captured definitions with a per-arm DIGEST byte-identical across the move** (`full=-1675305230568277215 narrow=-1216978524918639134` on both arms), and the full-vs-narrow divergence census identical row for row (961 spans in 43 of 76 files — the standing (INC.26) alias figure, not a regression). The same 488 deterministic `--passTiming` lines are byte-identical too, so that receipt now spans SIX binaries. suite **18,514/0/3** (18,506 + 8 new pins, **all eight ablation arms reddening exactly their own pin — the arc's first perfect 8-for-8**); `cost_gate.py` exit 0; `huge_methods.py` exit 0, 0 over limit, **841** classes, `Checker.<init>` 5,697 → **5,621**; ab-interleaved 6 pairs **+52 ms (+0.20%) B-wins-3/6 NOISE-DOMINATED**, both arms 46 errors; **PrintInlining improved the one hop that matters** — `typeCaptureVisit`, called per node from `spineEnterNode`, was `too large ×6` as a 925-byte `Checker` method and its hop reads `inline ×6` (`spineEnterNode`'s own refusals 4 → 3); `new CaptureRecorder` at exactly one bytecode; warning-clean |
| 12 | `LexicalScopeResolver` (`LexicalScopeResolver.kt`) — the INV.2(c) SCOPE-SPACE ascent, i.e. the one walk that answers the B83.5 population from `BinderResult.lexicalScopes`. Not a Stage-0 seam by size but by DUPLICATION: the ascent had been hand-copied FIVE times and the copies had drifted | the five copies — `NameResolver.lexicalTypeSymbolForNode`, `Checker.lexicalTypeAliasArity`, `Checker.owningFileLexicalScopeSymbol`, the inline walk in `Checker.destructuredDiscriminantCarry` and `Checker.lexicalScopeSymbol` — re-pointed at one `symbolAt`, with the four axes they had drifted on (which table, start at the node or its parent, which `SymbolFlags`, hop-capped or not) taken as PARAMETERS so every caller keeps its own behaviour | **none** — `fileResults` is a constructor input and the ascent is a pure function of the tables and the parent chain. **The first `none` row since row 3** | **none** | ~60 removed from `Checker.kt`/`NameResolver.kt`, 124 added (`Checker.kt` 191,540 → **191,506**) | suite **18,519/0/3** (18,514 + 5 new pins, one per axis, asserted as VALUES directly against a `Binder` run); **the 8-profile grid `added=0 removed=0` on all eight** — the gate that matters here, because a wrong ascent resolves a name to an OUTER binding and CLAUDE.md records that as silent in every diagnostic channel; `cost_gate.py` exit 0; `huge_methods.py` exit 0, **842** classes; the 488 deterministic `--passTiming` lines byte-identical, so that receipt now spans SEVEN binaries; warning-clean. Ablation: arms 2-5 redden exactly their own pin; **arm 1 reddens ALL FIVE and is recorded as not being a single-pin arm** — starting the ascent at the file root destroys the ascent, and three narrower arms each redden pin 2 or pin 3 because those two are positional by construction |
| 13 | `NullishReceiverChecks` (`NullishReceiverChecks.kt`) — the (CHK.173) NULLISH-RECEIVER family: tsgo's `checkNonNullExpression` on a member / element access receiver (TS18047/18048/18049, TS2531/2532/2533) — the identifier arm (Round A), the compound / parenthesized arm (Round B3), the element arm (B98.r124), the shared code chooser and entity-name reporter, the type-parameter base-constraint reader (Round B4) and the B6 body-local declared-type reader with its unresolved-RHS veto. Moved while FRESH ((P18.230)): the arc had just grown `Checker.kt` by ~2,500 lines | 21 declarations taken VERBATIM from ONE contiguous span of `Checker.kt` (155313-155893, 581 lines) less B81.1c's `ReceiverInfo` carrier (9 lines), which stayed with its one user; a reverse-transform (strip `checker.`, re-privatize the six entry points) `diff`s byte-identical against the original span. SIX entry points kept callers (9 sites: `reportNullishReceiver` ×2, `emitTs1804xForNullableCompoundReceiver` ×2, the identifier / element arms, `nullishReceiverCode`, `typeParamsToBaseConstraints`) and those CALL SITES were re-pointed at `nullishReceivers.x(…)` directly — no delegation stubs, so no new hop beyond one `getfield` | **28** over 83 sites. TYPE SYSTEM (13): `getTypeOfExpression`, `getTypeOfIdentifier`, `getTypeFromTypeNode`, `getUnionType`, `typeIncludesNull`, `typeIncludesExplicitUndefined`, `isNullishConstituent`, `resolveMemberPropertyType`, `thisReceiverCarrierType`, `bindingElementDeclaredType`, `getNarrowedTypeForReference`, `getNarrowedTypeForReferenceFollowLoopEntry`, `optionalChainGuardsRef`; SYNTAX / FLOW (6): `getReferencePath`, `unwrapParensExpr`, `expressionTrueEnd`, `getFlowAt`, `lexicalScopeSymbol`, `getLineAndCharacterOfPosition`; WALK AMBIENT (5): `currentLocalTypes`, `currentShadowedNames`, `currentParamBindingNames`, `currentClassForThis`, `nonNullChainReceiverReads`; OUTPUT / CARRIERS (4): `diagnostics`, `captureRecorder`, `strictNullChecks`, and the B6 veto pair `bodyLocalVetoPending` / `bodyLocalVetoDeclared` (counted once). 17 `Checker` members widened `private` → `internal` | **5**, all SCOPED: `currentClassForThis` and `nonNullChainReceiverReads` save-and-restore (3 + 1 sites), `currentLocalTypes` CONTENTS install-and-remove around the veto's RHS typing, and the veto pair written as an OUT channel from the declared-type reader to the emitters' `bodyLocalVetoes`. The family OWNS the B6 per-file declaration memo (`bodyLocalDeclMemo` / `bodyLocalDeclMemoFile`) and the destructured-leaf marker `bodyLocalLeafTyped`, which moved in with it | ~570 (`Checker.kt` 203,588 → **203,018**; `NullishReceiverChecks.kt` 620) | **all 418 per-pass `--passTiming` rows, the 46 diagnostics, the emissions census and the counter block (508 normalized lines) byte-identical against the orchestrator's frozen pristine (`Checker.class` 908da6dd)**; corpus screen 8,725 subtests 0 mismatches; 8-profile grid `added=0 removed=0` on all eight with the chain control OK, and cronstrue / marked / rxjs byte-identical; 54 null-check / narrowing pin classes (1,191 tests) green; `huge_methods.py` exit 0, 0 over, 896 classes; PrintInlining: every moved entry point carries the SAME verdict family it had as a `Checker` method (all were already `too large` / `hot method too big` except `typeParamsToBaseConstraints`, `inline (hot)` ×4 in both arms), `checkArgumentsAgainstSignature` row-for-row identical bar one single-run verdict swap; warm A/B ABBA (`BenchMain` 6+8) A 7,539 / 7,369 ms vs B 7,577 / 7,445 ms = +0.76%, per-arm spread 1.8-2.3% — NOISE-DOMINATED, not resolvable on this box |
| 14 | `SignatureArity` (`SignatureArity.kt`) — the (CHK.175)/(CHK.176)(a) SIGNATURE-BASED ARITY reader: tsgo's `hasCorrectArity` for a spread-free argument list (`callArityFails`) and `getArgumentArityError` over the signatures a call resolved to (TS2554 / TS2555 / TS2575 in `reportSignatureArity`, TS2556 in `reportSpreadSignatureArity`), with the declaration-trust, identifier-callee-binding and overload-completeness guards and the span dedup (`arityRowAt`) against the name-based walkers ((P18.232)) | 12 declarations taken VERBATIM from ONE contiguous span of `Checker.kt` (170417-170762, 346 lines) — `callArityFails`, `arityDeclTrusted`, `endsInTupleRest`, `CallArity`, `callArity`, `reportSignatureArity`, `reportSpreadSignatureArity`, `arityIdentifierCalleeTrusted`, `arityOverloadSetComplete`, `arityBindingIn`, `arityBindingOwns`, `arityRowAt`; a reverse transform (strip `checker.`, re-privatize the three entry points) `diff`s byte-identical against the original span. THREE entry points kept callers (7 sites) and those call sites were re-pointed at `signatureArity.x(…)` directly — no stubs. The call-side MINIMUM (`callMinArgumentCount` ×2, `overloadCallMin`, `typeAcceptsVoid`) STAYED on `Checker`: 7 callers in the name-based walkers, the property-access reader and the relation (`relationMinArgumentCount`), so moving it would widen more than it removes | **18** over 27 sites. TYPE SYSTEM / ARITY (4): `signatureDeclaredArity` ×3, `callMinArgumentCount` ×2, `fixedTupleLengthOfRestParam`, `spineExBindingNameShadows` ×4; SPREAD (2): `spreadArityView`, `spreadArityFails`; EMITTERS (4): `emitTS2554TooFew`, `emitTS2554TooMany`, `emitTS2555TooFew`, `emitTS2556`; SYNTAX / FILE (3): `expressionTrueEnd` ×3, `getLineAndCharacterOfPosition`, `isJsLikeFileName`; TABLES (3): `builtinLibDecls`, `builtinLibMemberDecls`, `globals`; OUTPUT / WALK (2): `diagnostics` ×2, `arityCall` ×2 (the per-call anchor's call node, written only by `checkSingleCallExpressionTypes` / `checkSingleNewExpressionTypes`). `options` is a CONSTRUCTOR input (`SignatureArity(this, options)`), not a widening. 15 `Checker` members widened `private` → `internal`: the 12 functions / properties above that were private plus the nested `FuncParamInfo`, `SpreadArityView` and (forced by `FuncParamInfo.overloadSigs`' exposure) `OverloadSig` | **none** | ~344 (`Checker.kt` 203,018 → **202,674**; `SignatureArity.kt` 394) | **all 418 per-pass `--passTiming` rows, the 46 diagnostics and the counter block (493 normalized lines) byte-identical against the orchestrator's frozen parent (`Checker.class` b1157f4c → e324d30b)**; corpus screen 8,725 subtests 0 mismatches; 8-profile grid `added=0 removed=0` on all eight with the chain control OK, cronstrue (at `…/cronstrue/src`, the shared TS5108 row) / marked / rxjs byte-identical; the 31 arity pin classes (596 tests) green, ablation 4 arms 35 / 66 / 2 / 23 RED; a 7-cell TS2554/2555/2556/2575 matrix identical on both arms and to tsgo 7.0.2; `huge_methods.py` exit 0, 0 over, 897 classes; PrintInlining: `checkArgumentsAgainstSignature` row-for-row identical, `callArityFails` the same verdict family (4 too large + 2 too big + 1 `inline (hot)`, 169 → 184 bytes for the `checker` field reads), the new accessor hops `getArityCall` / `getBuiltinLibMemberDecls` `inline` |
| 15 | `SignatureArity` (`SignatureArity.kt`), second extraction — the REST of the ARITY family: the arity message and emitters (`formatExpectedArgs`, `emitTS2554TooMany` / `emitTS2554TooFew` / `emitTS2555TooFew`), the (CHK.98)(d) spread-arity view (`fixedTupleLengthOfRestParam`, `argumentExpansionCount`, `SpreadArityView`, `SpreadOperandShape`, `spreadArityView`, `spreadArityFails`, `firstExcessArgIndex`, `emitTS2556`, `classifySpreadOperand` / `spreadOperandDeclaration` / `classifySpreadAnnotation` / `spreadShapeOfType`), (CHK.33)'s `signatureDeclaredArity`, and the call-side MINIMUM (`callMinArgumentCount` ×2, `overloadCallMin`, `typeAcceptsVoid`) ((P18.237)) | 23 declarations taken VERBATIM from FIVE spans of `Checker.kt` (67987-67992, 68040-68217, 128341-128593, 164753-164815, 170522-170565; 544 lines) — the family is not one run, but each span is self-contained; the unrelated `countAssignmentLikeOccurrences` between the first two and the two orphan KDocs above the third stayed. A reverse transform (strip `checker.`, re-privatize six members) `diff`s byte-identical against the concatenated spans, and the Checker residue (strip `signatureArity.`, drop the import, re-privatize the two new widenings) equals the original minus the spans. 48 call sites in the name-based walkers, the template-tag reader, the property-access reader, the rest-tuple expansion and `relationMinArgumentCount` re-pointed at `signatureArity.x(…)` directly — no stubs; `SpreadArityView` is reached through one `import …SignatureArity.SpreadArityView` so the Checker text naming it is unchanged | **14** over the whole collaborator: row 14's `spineExBindingNameShadows`, `arityCall`, `builtinLibDecls`, `builtinLibMemberDecls`, `globals`, `isJsLikeFileName`, `expressionTrueEnd`, `getLineAndCharacterOfPosition`, `diagnostics`, plus the moved spans' TYPE reads `getTypeOfExpression` ×2 (spread operand, argument expansion), `getTypeFromTypeNodeSafe`, `getTypeOfSymbol`, `getTypeFromTypeNode` (the void trim) and `paramInfo` (`signatureDeclaredArity`). Row 14's nine ARITY / SPREAD / EMITTER reads are now INTERNAL to the collaborator. `Checker` widenings for it fall **15 → 7**: ten un-widened (the four emitters, `fixedTupleLengthOfRestParam`, `spreadArityView`, `spreadArityFails`, `signatureDeclaredArity`, `callMinArgumentCount` left `Checker`, `SpreadArityView` moved in), two new (`paramInfo`, `getTypeFromTypeNodeSafe` `private` → `internal`), five kept (`arityCall`, `builtinLibMemberDecls`, `spineExBindingNameShadows`, `FuncParamInfo`, `OverloadSig`) | **none** (`diagnostics.add` is the output sink, as in row 14) | ~548 (`Checker.kt` 202,811 → **202,263**; `SignatureArity.kt` 394 → 951) | **all 418 per-pass `--passTiming` rows, the 46 diagnostics and the counter block (506 normalized lines) byte-identical against the orchestrator's frozen parent (`Checker.class` 3409e404 → a1953110, `SignatureArity.class` b207bb6d → 882e7d8d)**; corpus screen 8,725 subtests 0 mismatches; 8-profile grid `added=0 removed=0` on all eight with the chain control OK, rxjs / marked (0 rows) / cronstrue (at `…/cronstrue/src`, the shared TS5108) identical; 35 arity pin classes (648 tests) green incl. the new `ArityFamilyCollaboratorTest`, ablation 4 arms 22 / 24 / 27 / 16 RED; an 8-cell matrix identical on both arms and to tsgo 7.0.2 but for one pre-existing cell; `huge_methods.py` exit 0, 0 over, 900 classes; PrintInlining: `checkArgumentsAgainstSignature` rows identical (its `$default` bridge 6 → 7 `too large`, the known A-vs-A instability), every moved method keeps its verdict family, sizes +7 bytes per non-null parameter of a newly non-private method (`checkNotNullParameter`: `paramInfo` 189 → 203, still `inline (hot)`; `getTypeFromTypeNodeSafe` 6 → 13, still `inline`) |
| 16 | `ClassInstanceMembers` (`ClassInstanceMembers.kt`) — the CLASS-INSTANCE MISSING-MEMBER family: the conservative TS2339 for a member absent from a class's whole resolvable instance side (`tryEmitClassInstanceMissingTs2339`), its TS2551 chain suggestion (`emitClassChainTs2551Suggestion`), the TS2576 static-member emitter (`tryEmitStaticAccessTs2576`), and the chain walks they share — `lookupInstanceMemberInResolvableChain` with (CHK.182)'s `mergedInterfaceHasMember`, `hasInstanceMemberNamed`, `isStaticMemberOfClass`, `classMemberNameText` ((P18.245); the family (P18.241) last touched) | 8 declarations taken VERBATIM from ONE contiguous span of `Checker.kt` (161122-161500, 379 lines, the span's three orphan KDocs included). A reverse transform (strip `checker.` and `Checker.` off `RUNTIME_PROPERTIES`, re-privatize the six entry points) `diff`s byte-identical against the span, and the Checker residue (drop the field, strip `classInstanceMembers.`, re-privatize the five new widenings) equals the original minus the span. SIX entry points kept callers (18 sites in the property-access readers, the `this`-member reader and the element-access reader) and those call sites were re-pointed at `classInstanceMembers.x(…)` directly — no stubs; `mergedInterfaceHasMember` and `classMemberNameText` had no caller outside the span and stay `private` | **9** over 19 sites: TABLES `globals` ×4, `builtinLibDecls` ×2; MEMBER NAMES `computedLiteralKey`, `lateBoundComputedKeyName` (both delegations into `MemberNames`); FLOW `getNarrowedTypeForReference` (the instanceof-narrowing bail); DISPLAY `getSpellingSuggestionFromNames`, `resolveDeclarationSourceFile`, `getLineAndCharacterOfPosition` ×4; and the companion constant `RUNTIME_PROPERTIES` ×2. `Checker` widenings: **5 new** (`computedLiteralKey`, `lateBoundComputedKeyName`, `getSpellingSuggestionFromNames`, `resolveDeclarationSourceFile` `private` → `internal`; `RUNTIME_PROPERTIES` companion `private val` → `internal val`), the rest were already `internal` | **none** (`diagnostics.add` ×3 is the output sink, as in rows 13-15) | ~377 (`Checker.kt` 202,482 → **202,105**; `ClassInstanceMembers.kt` new, 422) | **all 417 per-pass `--passTiming` rows, the 46 diagnostics and the counter block (504 normalized lines, node-kind histogram dropped) byte-identical against the orchestrator's frozen parent (`Checker.class` 8b103e5a → 28b024af, `ClassInstanceMembers.class` 9db79087)**; corpus screen 8,725 subtests 0 mismatches; 8-profile grid `added=0 removed=0` on all eight with the chain control OK, rxjs / marked (0 rows) / cronstrue (at `…/cronstrue/src`, the shared TS5108) identical; 9 pin classes (161 tests) green incl. the new `ClassInstanceMembersCollaboratorTest` (15 cells, all identical on both arms and to tsgo 7.0.2), ablation 7 arms 6 / 5 / 4 / 1 / 7 / 1 / 1 RED; `huge_methods.py` exit 0; `spine_closure_audit.py` clean (no spine handler moved); PrintInlining: `checkArgumentsAgainstSignature` rows identical in 3 of 4 runs (the before arm's FIRST run read `size > DesiredMethodLimit` ×3 at its `$default` @4220 site where the other three read `inline (hot)` ×2 + `too large` ×2 — the known A-vs-A instability), every moved method keeps its verdict family (sizes +16 bytes per non-null parameter of a newly non-private method, `checkNotNullParameter`), and the four widened callees are found under their `$module`-MANGLED names in the after arm with unchanged verdicts |
| 17 | `NamedImportExistence` (`NamedImportExistence.kt`) — the NAMED / DEFAULT IMPORT EXISTENCE family: the default-import pass `checkDefaultImports` (TS1192 / TS2613 / TS2614 / TS2616 / TS2595 / TS2597, tsgo's `canHaveSyntheticDefault` with its `__esModule` marker `declaresEsModuleMarker`), the named-import / `export { … } from` existence pass `checkNamedImportExistence`, tsgo's `errorNoModuleMemberSymbol` order `emitAbsentNamedMember` (TS2724 / TS2614 / TS2460 / TS2459 / TS2305) with `emitMissingMemberSuggestion`, the TS2305 emitter `emitTs2305`, and the local-declaration readers the TS2459 / TS2460 / TS2728 / TS6204 rows need (`getModuleLocalNames`, `getModuleExportAlias`, `getLocalDeclarationPos`, `getAllLocalDeclarationPositions`) ((P18.255); the family (P18.249) / (P18.252) grew) | 10 declarations taken VERBATIM from FOUR spans of `Checker.kt` (53063-53423, 53684-54012, 54344-54468, 54977-54988; 828 lines incl. trailing blanks) — each span self-contained; the unrelated passes between the first two (`checkNamespaceImportSyntheticDefaultCall` .. `checkConflictingNamespaceImportSelfConst`) stayed, and lifting `declaresEsModuleMarker` re-attaches `moduleHasDefaultExport`'s orphaned KDoc to it. A reverse transform (strip `checker.`, re-privatize three members) `diff`s byte-identical against the concatenated spans, and the Checker residue (drop the field, strip `namedImportExistence.`, re-privatize the twelve widenings) equals the original minus the spans. THREE entry points kept callers: the two `pass("…")` lambdas (registered names unchanged) and `emitTs2305` (3 sites in `checkNamedImportFromExportEqualsInDts`, `checkBareAtTypesExportEqualsMissingNamedImport`, `checkNamedImportFromAmbientExportEqualsValueIn`), re-pointed at `namedImportExistence.x(…)` directly — no stubs | **16** over 55 sites: OUTPUT `diagnostics` ×11; PARTITION `checkedResults` ×2 (read through `checker` so its `PassTiming.notePartitionRead` getter still counts); MODULE RESOLUTION `resolveModuleSpecifier` ×2, `resolveModuleSpecifierRelative` ×6, `resolveRelativeIncludingIndex`, `resolveBareNodeModulesAnyPrefix`, `nodeModulesPackageTypeIsModule`; EXPORT SETS `getModuleExportsFollowingStars` ×4, `getModuleNamedExports`, `moduleHasDefaultExport` ×3, `getExportEqualsMemberNames` ×2, `augmentationDeclaredExportNames` ×2, `jsDocTypedefExportNames` ×2; DISPLAY / FILE `getLineAndCharacterOfPosition` ×13, `getSpellingSuggestionFromNames` ×2, `isDtsFile` ×2. `options`, `binderResults`, `isMultiFileSource` and `fileResults` are CONSTRUCTOR inputs (NameResolver's precedent), not widenings. `Checker` widenings: **12 new** (`checkedResults` `private val` → `internal val`; `augmentationDeclaredExportNames`, `getExportEqualsMemberNames`, `getModuleExportsFollowingStars`, `getModuleNamedExports`, `jsDocTypedefExportNames`, `moduleHasDefaultExport`, `nodeModulesPackageTypeIsModule`, `resolveBareNodeModulesAnyPrefix`, `resolveModuleSpecifier`, `resolveModuleSpecifierRelative`, `resolveRelativeIncludingIndex` `private fun` → `internal fun`); the other three were already `internal` | **none** (`diagnostics.add` ×11 is the output sink, as in rows 13-16) | ~828 (`Checker.kt` 202,337 → **201,509**; `NamedImportExistence.kt` new, 879) | **all 419 per-pass `--passTiming` rows, the 46 diagnostics and the counter block (491 normalized lines, node-kind histogram dropped) byte-identical against the orchestrator's frozen parent (`Checker.class` 6477c82d → 1efcf4b5, `NamedImportExistence.class` 9b75553f)**; corpus screen 8,725 subtests 0 mismatches; 8-profile grid `added=0 removed=0` on all eight with the chain control OK, rxjs (0 rows) / marked (0 rows) / cronstrue (at `…/cronstrue/src`, the shared TS5108) identical; 48 import/export pin classes (466 tests) green incl. the new `NamedImportExistenceCollaboratorTest` (14 pins over a 17-cell matrix, every cell identical on both arms; 11 byte-identical to tsgo 7.0.2, three identical but for the TS1192 / TS2613 module-path display, three pre-existing `export =` divergences), ablation 6 arms 2 / 5 / 2 / 16 / 1 / 3 RED; `huge_methods.py` exit 0, 911 classes; `spine_closure_audit.py` clean (no spine handler moved); PrintInlining: `checkArgumentsAgainstSignature` rows identical (7 + 2 `$default`, 1 + 2 main), the widened callees found under their `$module`-MANGLED names with unchanged verdict families (`resolveModuleSpecifier` the 10-byte `NameResolver` delegator, `inline` / `inline (hot)` 25 rows in each arm, split 21+4 → 19+5) |
| 18 | `NewExpressionChecks` (`NewExpressionChecks.kt`) — the `new`-EXPRESSION CHECK family: the per-`new` checker `checkSingleNewExpressionTypes` / `checkSingleNewExpressionTypesCore` (TS2347 / TS2351 / TS2673 / TS2674 / TS2511 / TS7009, B171's DataView typed-array TS2345, the argument checks against the construct signature(s) the callee resolves to, the `arityCall` anchor), its helpers `newCalleeVarHoldsInstance` (with its hop budget `NEW_CALLEE_CLASS_VALUE_HOPS`), `emitPrivateConstructorTs2673`, `newExprAbstractConstructorTs2511`, `newCalleeNonNullType`, `typeofClassValueDisplay`, `classExtendsOrIs`, and B264's pass `checkInheritedOverloadedCtorArgs` ((P18.261); the family (CHK.137) / (CHK.196) / (P18.256) / (P18.258) grew) | 11 declarations taken VERBATIM from FIVE spans of `Checker.kt` (7224-7231, 164745-165501, 166602-166627, 166837-166855, 183589-183774; 996 lines + 5 trailing blanks) — the hop-budget field, the contiguous family run (with its orphan "Check argument types for a NewExpression" KDoc), two single-caller helpers lifted to save a widening each (`typeofClassValueDisplay`, `classExtendsOrIs`), and the B264 pass. A reverse transform (strip `checker.` from the 42 member names, re-privatize two members) equals the concatenated spans byte for byte, and the Checker residue (drop the field, strip `newExpressionChecks.`, re-privatize the fifteen widenings) equals the original minus the spans. THREE call sites stayed on `Checker` and were re-pointed directly (no stubs): the spine's `NodeKind.NEW_EXPRESSION` arm (the HANDLER did not move — `spine_closure_audit.py` clean), `checkCallTypesInExpr`, and the `pass("checkInheritedOverloadedCtorArgs")` lambda (registered name unchanged) | **42** over 128 sites: OUTPUT `diagnostics` ×22; DISPLAY / SYNTAX `getLineAndCharacterOfPosition` ×19, `expressionTrueEnd` ×11, `typeToString` ×5; TABLES `globals` ×11, `builtinLibDecls` ×2, `TYPED_ARRAY_NAMES`; WALK AMBIENT (4) `currentFileLocals` ×5, `callWalkerClassStack` ×2 (the call walker's enclosing-class stack, read for TS2673/TS2674 accessibility), `spineNaRunActive`, `arityCall` ×3; COLLABORATORS `classConstructorTypes` ×7, `nameResolver` ×2, `nullishReceivers`; TYPE SYSTEM `getConstructSignaturesOfType` ×5, `isNullishConstituent` ×3, `getCalleeType` ×2, `getTypeOfSymbol` ×2, `getCallSignaturesOfType`, `combineUnionSignatures`, `resolveStructuredTypeMembers`, `getTypeFromTypeNode`, `getUnionType`, `instantiateSignature`, `getNarrowedTypeForReference`, `getReturnTypeOfNewExpression`, `reresolveSigParamsUnderClassScope`, `strictNullChecks`; CALL CHECKING `checkArgumentsAgainstSignature`, `checkArgumentsAgainstOverloads`, `lastOverloadDeclaredHereAt`, `findEffectiveConstructorVisibility`, `emitNewExprImplicitAny`, `newCalleeTypeSymbolDeclaresClass`, `isImplicitAnyVarChain`, `isImplicitAnyThisMember`, `propertyAccessChainIsNamespaceQualified`, `resolvePropertyAccessToSymbol`, `lookupPerFileForNode`; PARTITION / FILE `checkedResults` (through `checker`, so the partition probe still counts), `isDtsFile`, `isJsLikeFileName`. `options` is a CONSTRUCTOR input. `Checker` widenings: **15 new** — five fields (`currentFileLocals` `private var` → `internal var`, `spineNaRunActive` `private var` → `internal var`, `callWalkerClassStack`, `TYPED_ARRAY_NAMES`, `nameResolver` `private val` → `internal val`; in-class access stays a direct `getfield`, 249 sites checked in `javap`) and ten funs (`checkArgumentsAgainstOverloads`, `checkArgumentsAgainstSignature`, `emitNewExprImplicitAny`, `findEffectiveConstructorVisibility`, `isImplicitAnyThisMember`, `isImplicitAnyVarChain`, `lastOverloadDeclaredHereAt`, `newCalleeTypeSymbolDeclaresClass`, `propertyAccessChainIsNamespaceQualified`, `resolvePropertyAccessToSymbol`); the rest were already `internal` | **1, SCOPED**: `arityCall` save-and-restore around the core (the per-call anchor rows 14-15 read); `diagnostics.add` ×22 is the output sink | ~998 (`Checker.kt` 201,635 → **200,637**; `NewExpressionChecks.kt` new, 1,045) | **all 419 per-pass `--passTiming` rows, the 46 diagnostics and the counter block (501 normalized lines; node-kind histogram, wall figures and the time-bucketed narrowWalk rows dropped) byte-identical against the orchestrator's frozen parent (`Checker.class` 7b551473 → fc27c80d, `NewExpressionChecks.class` b35e5d7c)**; corpus screen 8,725 subtests 0 mismatches; 8-profile grid `added=0 removed=0` on all eight with the chain control OK, rxjs (0) / marked (0) / cronstrue (at `…/cronstrue/src`, the shared TS5108) identical; 42 `new`/constructor pin classes (781 tests) green incl. the new `NewExpressionChecksCollaboratorTest` (16 pins over a 23-cell matrix, every cell identical on both arms), ablation 5 arms 3 / 2 / 1 / 2 / 7 RED; `huge_methods.py` exit 0 (914 classes) — the moved core is **7,191** bytecodes against 6,918 in `Checker` (+273: a `checker.` `getfield` per member access), 809 under the limit; `spine_closure_audit.py` clean; PrintInlining: `checkArgumentsAgainstSignature` is now `$module`-mangled and **839 B against 810** (the widening's four `checkNotNullParameter` intrinsics), every row refused in both arms; its two `$default` `inline (hot)` → `size > DesiredMethodLimit` rows are PROCESS NOISE — a second run of the BEFORE binary reads exactly the after arm |
| 19 | `CommentDirectives` (`CommentDirectives.kt`) — the COMMENT-DIRECTIVE family: tsc's `// @ts-ignore` / `// @ts-expect-error` directives as its scanner records them (`TsCommentDirective`, the per-file memo `tsCommentDirectiveCache` / `tsCommentDirectivesOf`, the hand-scanned regexes `scanTsCommentDirectives` / `classifyTsCommentDirectiveAt` / (CHK.205)'s `insideOpenBlockComment` / `isTsDirectiveCommentPrefix` / `commentOpenOnLineBefore`), program.ts's walk-up `markPrecedingTsCommentDirective`, and the funnel filter `applyTsCommentDirectives` (suppression, then TS2578; the (CHK.202) unchecked-JS skip) ((P18.270); the family (CHK.31) / (CHK.202) / (CHK.205) grew) | 10 declarations taken VERBATIM from TWO spans of `Checker.kt` (8580-8588, the cache field, and 11344-11614, one contiguous run; 280 lines + 2 trailing blanks). The collaborator field `commentDirectives` takes the cache's place before `init`. A reverse transform (strip `checker.` from the six member names, re-privatize `applyTsCommentDirectives`) equals the concatenated spans byte for byte, and the Checker residue (drop the field, strip `commentDirectives.`, re-privatize the five widenings) equals the original minus the spans. ONE entry point kept ONE caller — `getDiagnostics`' funnel line, re-pointed at `commentDirectives.applyTsCommentDirectives(…)` directly; the funnel itself (`getDiagnostics`, `filterUncheckedJs`, `keptUnderNoCheck`, `uncheckedJsModeOf`) STAYED | **6** over 10 sites: TEXT SCAN `srcHas` ×2, `srcIndexOf` ×2 (the 3-arg overload; the round-895 n-gram filter); DISPLAY / LINES `getLineAndCharacterOfPosition` ×3, `lineStartsFor`; PARTITION `checkedResultsAll` (the FIELD, deliberately not the (INC.17) census getter — the moved comment says why); FILE MODE `uncheckedJsModeOf`. No walk ambient, no `diagnostics` sink (the filter is pure over its input list). `Checker` widenings: **5 new** — one field (`checkedResultsAll` `private var` → `internal var`) and four funs (`srcHas`, `srcIndexOf(text, needle, startIndex)`, `lineStartsFor`, `uncheckedJsModeOf`); `getLineAndCharacterOfPosition` was already `internal` | **0** — the per-file memo moved WITH its only reader, so the collaborator owns it outright | ~279 (`Checker.kt` 200,907 → **200,628**; `CommentDirectives.kt` new, 324) | **all 417 per-pass `--passTiming` rows, the 46 diagnostics and the counter block byte-identical (sorted, wall figures / node-kind histogram / time-bucketed rows dropped) against the orchestrator's frozen parent (`Checker.class` 5e84ce05 → 8e6e7cf3, `CommentDirectives.class` 2ad63ce6)**; corpus screen 8,725 subtests 0 mismatches; 8-profile grid `added=0 removed=0` on all eight with the chain control OK, rxjs (0) / marked (0) / cronstrue (at `…/cronstrue/src`, the shared TS5108) identical; the (P18.265) library grid (mitt / superstruct / immer / ky / hono / date-fns / zod / type-fest) row SETS identical to `r269`; 8 directive pin classes (144 tests) green incl. the new `CommentDirectivesCollaboratorTest` (9 pins over a 9-cell matrix, every cell identical on both arms and to tsgo), ablation 4 arms 3 / 2 / 1 / 9 RED; `huge_methods.py` exit 0 (923 classes); `spine_closure_audit.py` clean (no handler touched); PrintInlining: `checkArgumentsAgainstSignature` rows (mangled; the unmangled grep reads 0 in both arms) are 1 `callee is too large` + 1-2 `hot method too big` in BOTH arms over two runs each — the 1-vs-2 count moves within one binary across processes; the widened `srcHas` grew 16 → 30 bytes and `srcIndexOf` 8 → 22 (`checkNotNullParameter`), `lineStartsFor` / `uncheckedJsModeOf` +7 each, their PrintInlining rows unchanged |
| 20 | `UnusedDeclarations` (`UnusedDeclarations.kt`) — the UNUSED-DECLARATION family: the three passes `checkUnusedDeclarations` (TS6133 / TS6192 / TS6196 / TS6198 / TS6199 / TS6205), `checkUnusedParameterProperties` (TS6138) and `checkUnusedInferParameters`, the statement-list walk and the declaration / reference collectors under it (`collectUnusedDeclarations`, `collectUnusedReferences`, `collectRefsFromExpr` / `Type` / `ClassElement`, `collectTypeRefs*`, `collectTypeQueryValueRefs`, the JSDoc and `this.`-access collectors), the class / interface / alias / signature type-parameter checkers, the private-member check with its per-class computed-key memo `unusedComputedKeys`, the TS6198 / TS6199 grouping and span helpers, and the scope model `UnusedScope` / `UnusedDecl` ((P18.275); the family (LEGACY.0b) step 13, (CHK.204) and (P18.271) grew) | 58 declarations + 1 field taken VERBATIM from FOUR spans of `Checker.kt` (803-807, the memo field; 16801-17339; 17438-20118; 20180-20321 — the family is one run with two holes, `isModuleFile` / `hasCommonJsExportAssignment` / `isEsModuleFile` (17341-17436, 62 outside callers) and the shared line / pin helpers `lineStartsFor` .. `pinRel` (20120-20178), which STAYED). A reverse transform (strip `checker.` from the nine member names, re-privatize five functions) equals the concatenated spans byte for byte, and the Checker residue (drop the field, strip `unusedDeclarations.`, re-privatize the two widenings) equals the original minus the spans. FIVE entry points kept a caller and were re-pointed: the three `pass(…)` lambdas in `initCheckPasses1` (pass names unchanged), `computeBindingPatternSpan` (TS1182's squiggle, `emitTs1182IfMissingInit`) and `collectTypeReferenceNames` (`annotatedCallReturnTypeForFlow`). The collaborator field `unusedDeclarations` sits beside `newExpressionChecks`, before `init`; `options` is a constructor input | **9 members over 36 sites**: SINK `diagnostics` ×12; LINES `getLineAndCharacterOfPosition` ×12; PARTITION `checkedResults` ×3; FILE KIND `isDtsFile` ×3, `isJsLikeFileName`, `isModuleFile`, `hasCommonJsExportAssignment`; SCOPE `globals` ×2 (lib-global reads in the reference collector); SYNTAX `isParameterPropertyModifier`. NO walk ambient — no `currentLocalTypes`, no spine state, no type queries: the family is purely SYNTACTIC over the AST, which is why it moved whole. `Checker` widenings: **2 new** (`isParameterPropertyModifier`, `hasCommonJsExportAssignment`, `private fun` → `internal fun`, +7 bytes each for `checkNotNullParameter`, JVM names now `$module`-mangled) | the `diagnostics` sink only (12 `add` sites); `unusedComputedKeys` (the one piece of family state) moved WITH its only writer and readers, so the collaborator owns it outright | ~3,368 (`Checker.kt` 200,831 → **197,463**; `UnusedDeclarations.kt` new, 3,416) | **compiler profile: all 417 per-pass `--passTiming` rows, the counter block and the diagnostics byte-identical (sorted; wall figures / node-kind histogram / time-bucketed rows dropped) against the orchestrator's frozen parent (`Checker.class` 282843fa → 255ce969, `UnusedDeclarations.class` 9e5ddf40) — and because the profile sets no `noUnused*` the three passes are not REGISTERED there, the same receipt was taken on a copy of the profile with `noUnusedLocals` + `noUnusedParameters` on (`build/bench/p18275-agent/unused-prof`): 536 normalized lines identical, `checkUnusedDeclarations` 829 ms / 11 rows and `checkUnusedParameterProperties` 6 rows, 63 diagnostics identical**; corpus screen 8,725 subtests 0 mismatches; 8-profile grid `added=0 removed=0` on all eight, chain control OK, rxjs (0) / marked (0) / cronstrue at `…/src` (the shared TS5108) identical; the (P18.265) library grid row SETS identical to `r274` on all eight; 13 unused-family pin classes (200 tests) green incl. the new `UnusedDeclarationsCollaboratorTest` (9 pins over a 9-cell matrix, every pinned row identical on both arms and to tsgo), ablation 4 arms 3 / 3 / 16 / 1 RED; `huge_methods.py` exit 0 (926 classes); `spine_closure_audit.py` clean (no handler touched); PrintInlining `checkArgumentsAgainstSignature` (mangled; unmangled reads 0 in both) 1 `callee is too large` + 2 `hot method too big` in BOTH arms |
| 21 | `ModuleSyntaxChecks` (`ModuleSyntaxChecks.kt`) + `TsSyntaxInJsFiles` (`TsSyntaxInJsFiles.kt`) — the MODULE-SYNTAX family: the TS2440 barrel memo `barrelTypeOnlyMemo` and ten passes (`checkIsolatedModulesGlobalValueShadow` TS2866, `checkIsolatedModulesScriptNamespaces` TS1280, `checkIsolatedModulesReExportType` TS1205, `checkIsolatedModulesExportImportIsType` TS1269, `checkIsolatedModulesExportDefaultIsType` TS1292, `checkImportNotAtTopLevel` TS1147 / TS1232, `checkNamespaceImportVarConflict`, `checkImportConflictsWithLocal` TS2440, `checkVerbatimModuleSyntax` TS1484, `checkExportSpecifierLocality` TS2661) with their helpers; and the TS8xxx family `checkTsSyntaxInJsFiles` ((P18.279)) | two contiguous spans of `Checker.kt` taken VERBATIM (195115-195378 and 195393-197218 in the parent, plus the memo field 7534-7538). A reverse transform (strip `checker.` from the 21 member names, `Checker.KNOWN_GLOBALS` -> `KNOWN_GLOBALS`, re-privatize eleven entry points) equals each moved block byte for byte, and the Checker residue (drop the two fields, strip the collaborator prefixes, re-privatize the five widenings) equals the original minus the spans (`build/bench/p18279-agent/proof.py`). ELEVEN entry points kept a caller — their `pass(…)` lambdas, names unchanged. The fields `moduleSyntaxChecks` / `tsSyntaxInJsFiles` sit before `init`; `options` is a constructor input | **21 members**: SINK `diagnostics`; LINES `getLineAndCharacterOfPosition`; PARTITION `checkedResults`; FILE KIND `isDtsFile`, `isModuleFile`, `moduleFiles`; RESOLUTION `resolveModuleSpecifier`, `resolveModuleSpecifierRelative`, `resolveBarrelStarTarget`, `fileResults`, `nameResolver`; SCOPE `globals`; NAMESPACE STATE `isNamespaceInstantiated`, `getModuleInstanceState`, `moduleInstanceStateOf`; `isTypeOnlyExportName`; TEXT `srcIndexOf`, `srcLastIndexOf`, `expressionTrueEnd`; `getSpellingSuggestionFromNames`; SYNTAX `isParameterPropertyModifier`. NO walk ambient, no spine state. `Checker` widenings: **5 new** (`fileResults`, `srcLastIndexOf`, `moduleInstanceStateOf`, `isNamespaceInstantiated`, `resolveBarrelStarTarget`) | the `diagnostics` sink only; `barrelTypeOnlyMemo` moved WITH its only reader | ~2,089 (`Checker.kt` 197,864 → **195,775**; `ModuleSyntaxChecks.kt` 1,544, `TsSyntaxInJsFiles.kt` 637, both new) | **per-pass `--passTiming` tables identical against the frozen parent (`Checker.class` 3bf810c8 → 844112b5) on the compiler profile (514 normalized lines) AND on an `isolatedModules` profile copy where the moved passes are registered (11,520 lines)**; corpus screen 8,725 / 0; 8-profile grid `added=0 removed=0`, chain OK, rxjs / marked / cronstrue identical; library grid row SETS identical to `p18278d` on all eight; `ModuleSyntaxChecksCollaboratorTest` 5 pins (every row tsgo's), ablation 2 arms 1 / 1 RED; 11-cell matrix identical on both arms; `huge_methods.py` 0; `spine_closure_audit.py` clean; PrintInlining `checkArgumentsAgainstSignature` (mangled) run 2 `1 callee is too large + 2 too big` in BOTH arms, run 1 off by one row (cross-process instability) |
| 22 | `LabelChecks` (`LabelChecks.kt`) + `TslibHelperChecks` (`TslibHelperChecks.kt`) — the LABEL family (`checkDuplicateLabels` TS1114, `checkUnusedLabels` TS7028 and their statement walkers) and the TSLIB emit-helper family (`checkImportHelpersWithoutTslib` TS2354, `checkMissingTslibHelpers` TS2343, the decorator-helper check, `emitTS2354`, the `reportedMissingTslibHelpers` set) ((P18.289)) | spans of `Checker.kt` taken VERBATIM (838-850, the set; 91300-91382 and 92296-92486, the label family; 91397-92294, the tslib family). A reverse transform (strip `checker.` from the five member names, re-privatize the four entry points) equals each moved block byte for byte, the Checker residue (drop the two fields, strip the collaborator prefixes) equals the original minus the spans, and the original spans concatenate to the moved originals (`build/bench/p18289-agent/proof.py`). FOUR entry points kept a caller — their `pass(…)` lambdas, names unchanged. The fields `labelChecks` / `tslibHelperChecks` sit before `init`; `options` and `binderResults` are constructor inputs | **5 members**: SINK `diagnostics`; LINES `getLineAndCharacterOfPosition`; PARTITION `checkedResults`; FILE KIND `isDtsFile`, `isModuleFile`. NO walk ambient, no spine state. `Checker` widenings: **0** | the `diagnostics` sink only; `reportedMissingTslibHelpers` moved WITH its only users | ~1,180 (`Checker.kt` 195,978 → **194,798**; `LabelChecks.kt` 313, `TslibHelperChecks.kt` 955, both new) | 8-cell matrix identical before / after against the frozen parent (`Checker.class` d0596b4f → 6da84d19); corpus screen 8,725 / 0; `cost_gate.py` 0; 8-profile grid `added=0 removed=0`, chain OK, rxjs / marked / cronstrue / mitt identical; library grid row SETS identical to `r288` on all eight; `LabelAndTslibChecksCollaboratorTest` 5 pins (every row tsgo's), ablation label passes 3 RED, `checkImportHelpersWithoutTslib` 1 RED (`checkMissingTslibHelpers` unpinnable through `diagnose()`, matrix-covered); `huge_methods.py` 0; `spine_closure_audit.py` clean. The per-pass `--passTiming` table was NOT taken (builder stalled before it) |
| 23 | `ModuleResolutionChecks` (`ModuleResolutionChecks.kt`) — the MODULE-RESOLUTION family: six passes (`checkUnresolvedModules` TS2307 / TS2792 / TS2882 / TS2732 / TS5097 / TS2834 / TS2846 / TS7016 with its `paths` / `moduleSuffixes` / `node_modules` probes, `checkRelativeImportsInAmbientModules` TS2439 / TS2666, `checkNestedAmbientModules` TS2435 / TS2668, `checkImportEqualsRequireOfNonModule` and `checkNamespaceImportOfNonModule` TS2306, `checkJsxImportResolutions` TS6142), their helpers (`emitTS2307` / `emitTS2882` / `emitTS2732` / `emitTS6142`, the dynamic-import collectors, `flattenImportLikeStatements`, the bare-package probes) and the per-file memo `fileAmbientModuleInfoCache` ((P18.294)) | two spans of `Checker.kt` taken VERBATIM (6484-6486, the memo field; 47767-49218, one contiguous run). A reverse transform (strip `checker.` from the 17 member names, `Checker.ES_MODULE_KINDS` / `Checker.NODE_BUILTIN_MODULES` -> bare, re-privatize ten functions) equals the moved block byte for byte, the Checker residue (drop the field, strip `moduleResolutionChecks.`, re-privatize the seven widenings) equals the original minus the spans, and the original spans concatenate to the moved original (`build/bench/p18294-agent/proof.py`). TEN functions kept a caller: the six `pass(…)` lambdas (names unchanged) and four helpers re-pointed through `moduleResolutionChecks.` — `emitTS2307` (5 sites: `walkRequireImportInNamespace`, `visitBareImportType`), `hasNodeModulesPackage` (2), `resolveBareSpecifierViaNodeModules` (2: `checkBareAtTypesExportEqualsMissingNamedImport`, `checkModuleAugmentationOfNonModuleEntity`), `fileAmbientModuleInfo` (1). The field `moduleResolutionChecks` sits before `init`; `options`, `binderResults`, `isMultiFileSource`, `allInputFileNames`, `jsonModuleContents`, `untypedModuleResolutions` are constructor inputs, passed in | **19 members**: SINK `diagnostics`; LINES `getLineAndCharacterOfPosition`; PARTITION `checkedResults`; PROGRAM `fileResults`; FILE KIND `isDtsFile`, `isModuleFile`, `isJsLikeFileName`; RESOLUTION `nameResolver`, `normalizePath`, `resolveModuleSpecifier`, `resolveRelativeIncludingIndex`, `resolveImportTargetFallback`, `resolveModuleSpecifierStrictRelative`, `resolveRelativeJsSibling`, `resolveSpecifierAnywhere`; TEXT `srcIndexOf`, `emitStatementLineDiagnostic`; COMPANION `ES_MODULE_KINDS`, `NODE_BUILTIN_MODULES`. NO walk ambient, no spine state, no mutable Checker field read. `Checker` widenings: **7** (five functions `resolveImportTargetFallback`, `resolveModuleSpecifierStrictRelative`, `resolveRelativeJsSibling`, `resolveSpecifierAnywhere`, `emitStatementLineDiagnostic`; two companion sets) — none on the relation / spine path | the `diagnostics` sink only; `fileAmbientModuleInfoCache` moved WITH its only user | ~1,452 (`Checker.kt` 195,063 → **193,611**; `ModuleResolutionChecks.kt` 1,505, new) | 9-cell tsgo matrix byte-identical before / after (`build/bench/p18294-agent/matrix/`); `--passTiming` table on the compiler profile identical after normalisation (416 pass rows; ms column, wall figures and the racy node-kind histogram dropped); `-XX:+PrintInlining` on `checkArgumentsAgainstSignature` identical (1 callee too large + 2 hot method too big, mangled 12 / unmangled 0 on both arms); corpus screen 8,725 / 0; 8-profile grid byte-identical (both arms run per profile); rxjs / marked / cronstrue / mitt / date-fns identical; library grid row SETS identical to `b294` on all eight; `ModuleResolutionChecksCollaboratorTest` 9 pins (every row tsgo's), each of the six entry points ablated alone reddens its pins (3 / 1 / 1 / 1 / 1 / 1); `huge_methods.py` 0; `spine_closure_audit.py` clean |
| 24 | `CrossFileConflictChecks` (`CrossFileConflictChecks.kt`) — the CROSS-FILE DUPLICATE / CONFLICT family: twelve passes (`checkCrossFileIdentifierConflicts` TS2300 / TS2451 B93 hub model, `checkCrossFileBlockScopedDuplicates` TS2451, `checkCrossFileEnumConflicts` TS2567, `checkCrossFileInterfaceMemberConflicts` and `checkCrossFileClassConflicts` TS2300, `checkUmdGlobalVsDeclareGlobalConst` TS2451, `checkCrossFileModuleAugmentationDuplicates` TS2451, `checkModuleAugmentationReexportDuplicates` TS2300 / TS2451, `checkCjsExportAugmentationConflict` TS2551 + TS2728, `checkModuleAugmentationEnumMerge` TS2567, `checkGlobalNamespaceMemberConflicts` TS2300, `checkCrossFileTypeAliasNamespaceConflict` TS2649), the TS6203 / TS6204 related-row builder `duplicateCallRelatedInfos` with its `DuplicateRelatedTarget`, the hub / amalgamation emitters, `resolveAugmentationTargetFile`, and the B93 hand-off set `crossFileIdentifierHandledBlockNames` ((P18.298)) | two spans of `Checker.kt` taken VERBATIM (4911-4915, the set; 190932-192311, one contiguous run). A reverse transform (strip `checker.` from the 18 member names, `Checker.RUNTIME_PROPERTIES` / `Checker.DEPRECATED_STRING_HTML_HELPERS` -> bare, re-privatize fourteen functions and one class) equals the moved block byte for byte, the Checker residue (drop the field, strip `crossFileConflictChecks.` / `CrossFileConflictChecks.`, re-privatize the five widenings) equals the original minus the spans, and the original spans concatenate to the moved original (`build/bench/p18298-agent/proof.py`). FOURTEEN functions and one class kept a caller: the twelve `pass(…)` lambdas (names unchanged) and three members re-pointed — `duplicateCallRelatedInfos` + `CrossFileConflictChecks.DuplicateRelatedTarget` (`checkClassShadowsLibType`), `resolveAugmentationTargetFile` (`augmentationTargetFileJsAware`). The field `crossFileConflictChecks` sits before `init`; `options`, `binderResults` are constructor inputs, passed in | **20 members**: SINK `diagnostics`; LINES `getLineAndCharacterOfPosition`; PARTITION `checkedResults`; PROGRAM `fileResults`; FILE KIND `isDtsFile`, `isModuleFile`, `isJsLikeFileName`, `isLibFileName`, `libFileOfDecl`, `resolveDeclarationSourceFile`; RESOLUTION `resolveModuleSpecifier`, `resolveModuleSpecifierRelative`, `resolveRelativeIncludingIndex`; TYPES `getApparentType`, `resolveStructuredTypeMembers`, `getSpellingSuggestionFromNames`; NAMES `getMemberNameText`; TEXT `umdExportAsNamespaceOccurrences`; COMPANION `RUNTIME_PROPERTIES`, `DEPRECATED_STRING_HTML_HELPERS`. NO walk ambient, no spine state, no mutable Checker field read. `Checker` widenings: **5** (four functions `getMemberNameText`, `isLibFileName`, `libFileOfDecl`, `umdExportAsNamespaceOccurrences`; one companion set) — none on the relation / spine path; `getMemberNameText` 55 -> 62 and `isLibFileName` 50 -> 57 bytes (`checkNotNullParameter`), both already refused `callee is too large` at every call site before and after | the `diagnostics` sink only; `crossFileIdentifierHandledBlockNames` moved WITH its only two users | ~1,384 (`Checker.kt` 194,032 → **192,648**; `CrossFileConflictChecks.kt` 1,437, new) | 17-cell tsgo matrix byte-identical before / after (`build/bench/p18298-agent/matrix/`); `--passTiming` table on the compiler profile identical after normalisation (416 pass rows + 33 counter lines; ms column, wall figures and the node-kind histogram dropped); `-XX:+PrintInlining` on `checkArgumentsAgainstSignature` identical (1 callee too large + 2 hot method too big, mangled 12 / unmangled 0 on both arms); corpus screen 8,725 / 0; 8-profile grid byte-identical (chain control OK); rxjs / marked / cronstrue / mitt / date-fns identical; library grid row SETS identical to `b298` on all eight; `CrossFileConflictChecksCollaboratorTest` 16 pins (every row tsgo's but one `residue -`), each of the twelve entry points and the two re-pointed helpers ablated alone reddens its pins (1 / 1 / 1 / 1 / 2 / 1 / 1 / 2 / 1 / 1 / 1 / 1; helpers 1 / 2); `huge_methods.py` 0; `spine_closure_audit.py` clean |
| 25 | `CircularityChecks` (`CircularityChecks.kt`) — the CIRCULARITY family: nine passes (`checkCircularImportAlias` TS2303 import-equals chains, `checkCircularExportEqualsImportAlias` TS2303 `export =` self-export cycles, `checkExportAsNamespaceSelfCycle` TS2303 UMD self-alias, `checkCircularBaseClasses` TS2506 with its ambient dry run `populateAmbientCyclicBaseClasses` (feeds the TS2449 suppression), `checkCircularInterfaceBases` TS2310, `checkCircularBaseTypeReferences` TS2310 / TS2456 / TS2313 + TS2751, `checkCircularTypeAlias` TS2456 / TS2577 / TS4109 / TS4110, `checkCircularClassBaseViaDefaultTypeArg` TS2310), their statement walkers, the private `SelfExportModule`, and the helpers `classHasCircularBase`, `aliasStatementSpanEnd`, `emitTS2303At` ((P18.303)) | four spans of `Checker.kt` taken VERBATIM (89245-89615, the import-alias run; 134843-135471, 135496-135542 and 135573-135852, the base / type-alias run — the two generic helpers between them, `matchClosingBracket` (4 outside callers) and `typeNodeContainsName` (1 outside caller), STAYED as holes). A reverse transform (strip `checker.` from the 11 member names, re-privatize twelve functions) equals the concatenated spans byte for byte, the Checker residue (drop the field, strip `circularityChecks.`, re-privatize the three widenings) equals the original minus the spans, and the original spans concatenate to the moved original (`build/bench/p18303-agent/proof.py`). TWELVE functions kept a caller: the nine `pass(…)` lambdas (names unchanged) and three helpers re-pointed through `circularityChecks.` — `classHasCircularBase` (2: `collectFuncDecls`' implicit-constructor arity, `cmamCheckLiteralAndNewReceiver`), `aliasStatementSpanEnd` (`importAliasReExportSites`), `emitTS2303At` (`checkUnresolvedInImportEquals`). The field `circularityChecks` sits before `init` beside `crossFileConflictChecks`; `binderResults` is a constructor input, passed in | **11 members**: SINK `diagnostics`; LINES `getLineAndCharacterOfPosition`; PARTITION `checkedResults`; FILE KIND `isDtsFile`, `isJsLikeFileName`; SCOPE `globals`; RESOLUTION `resolveModuleSpecifier`, `resolveModuleSpecifierRelative`; TEXT / SYNTAX `matchClosingBracket`, `typeNodeContainsName`; STATE `ambientCyclicBaseClassNamesByFile` (the one Checker field written here — a program-wide map populated by the `init` dry run and read by `checkUseBeforeDeclaration`'s TS2449 suppression, so it stays on `Checker`). NO walk ambient, no spine state, no mutable Checker field read. `Checker` widenings: **3** (`matchClosingBracket` 157 -> 165, `typeNodeContainsName` 760 -> 767 bytes for `checkNotNullParameter`; the field gains an `internal` getter) — none on the relation / spine path | the `diagnostics` sink; `ambientCyclicBaseClassNamesByFile` (`getOrPut(…).add`) in `populateAmbientCyclicBaseClasses` only | ~1,328 (`Checker.kt` 192,857 → **191,529**; `CircularityChecks.kt` 1,380, new) | 17-cell tsgo matrix byte-identical before / after (`build/bench/p18303-agent/matrix/`); `--passTiming` table on the compiler profile identical after normalisation (416 pass rows + 32 counter lines; ms column, wall figures and the node-kind histogram dropped); `-XX:+PrintInlining` on `checkArgumentsAgainstSignature` identical (1 callee too large + 1 hot method too big, mangled 2 / unmangled 0 on both arms); corpus screen 8,725 / 0; 8-profile grid byte-identical (both arms run per profile, raw outputs identical bar `time:`); rxjs / marked / cronstrue / mitt / date-fns identical; library grid row SETS identical to `b303` on all eight; `CircularityChecksCollaboratorTest` 13 pins (every row tsgo's; one filters an ours-only TS2315), each of the nine entry points and the three re-pointed helpers ablated alone reddens its pins (1 / 1 / 1 / 3 / 1 / 1 / 2 / 1 / 1; helpers 1 / 3 / 1 — `aliasStatementSpanEnd` only through the squiggle-length pin); 19 at-risk classes (373 tests) green; `huge_methods.py` 0; `spine_closure_audit.py` clean |
| 26 | `StaticTypeParamRefChecks` (`StaticTypeParamRefChecks.kt`) — the STATIC-MEMBER TYPE-PARAMETER REFERENCE family: the pass `checkStaticMembersReferenceTypeParams` (TS2302) with its statement / class-member / type / statement-body / expression walkers, `emitTS2302` and the nested-generic shadow helper `shadowedTypeParamNames`; and `PropertyInitOrderChecks` (`PropertyInitOrderChecks.kt`) — the PROPERTY-USED-BEFORE-INITIALIZATION family: the pass `checkPropertyUseBeforeInit` (TS2729 + its TS2728 related row) with its statement walker, `checkClassPropertyUseBeforeInit`, the inherited-name collector `collectInheritedPropertyNames` and the `this.X` / `ClassName.X` collector `collectThisPropertyRefs` ((P18.308)) | one span each of `Checker.kt` taken VERBATIM (134973-135353 and 140130-140451, each one contiguous run with its section header; no holes). A reverse transform (strip `checker.` from the 4 / 5 member names, re-privatize the one entry point each) equals each moved block byte for byte, the Checker residue (drop the two fields, strip `staticTypeParamRefChecks.` / `propertyInitOrderChecks.`) equals the original minus the spans, and each original span equals its moved original (`build/bench/p18308-agent/proof.py`). The ONLY outside callers are the two `pass(…)` lambdas (names unchanged); the two fields sit before `init` after `circularityChecks`; the constructor takes `checker` alone (neither family reads `binderResults` or an option) | **5 members** (union of the two): SINK `diagnostics`; LINES `getLineAndCharacterOfPosition`; PARTITION `checkedResults`; FILE KIND `isDtsFile`; SCOPE `globals` (TS2729's base-class lookup only). NO walk ambient, no spine state, no mutable Checker field read. `Checker` widenings: **0** | the `diagnostics` sink only | ~699 (`Checker.kt` 191,774 → **191,075**; `StaticTypeParamRefChecks.kt` 419, `PropertyInitOrderChecks.kt` 360, new) | 18-cell tsgo matrix byte-identical before / after (`build/bench/p18308-agent/matrix/`); `--passTiming` table on the compiler profile identical after normalisation (416 pass rows + 33 counter lines); `-XX:+PrintInlining` on `checkArgumentsAgainstSignature` identical (mangled 3 / `$default` 9 / `Core` 6 / unmangled 0 on both arms); corpus screen 8,725 / 0; 8-profile grid byte-identical (both arms run per profile, raw outputs identical bar `time:`); rxjs / marked / cronstrue / mitt / date-fns identical; library grid row SETS identical to `b308` on all eight; `StaticAndInitOrderChecksCollaboratorTest` 15 pins (every row tsgo's), each entry point ablated alone reddens its family (6 / 6), the helpers 6 / 6 / 6 / 6 / 1 / 1 / 1 (TS2302) and 6 / 6 / 1 / 6 (TS2729) — `shadowedTypeParamNames` and `collectInheritedPropertyNames` were BLIND to the first pin set and got one pin each; 19 at-risk classes (270 tests) green; `huge_methods.py` 0; `spine_closure_audit.py` clean |
| 27 | `InRhsPrimitiveTypeParamChecks` (`InRhsPrimitiveTypeParamChecks.kt`) — the UNCONSTRAINED-TYPE-PARAMETER `in` family: the pass `checkInRhsPrimitiveTypeParams` (TS2322 on the right of `in` with its TS2208 related row, TS2322 for an `= undefined` default of an own unconstrained type parameter, the hardcoded `Object.keys` TS2769 + TS2208 / TS2771 rows), one 491-line function whose helpers are all LOCAL functions; and `TypeAsNamespaceChecks` (`TypeAsNamespaceChecks.kt`) — the TYPE-USED-AS-NAMESPACE family: the pass `checkTypeUsedAsNamespaceRefs` (TS2702 / TS2713 for a namespace-local type used as a qualifier, TS2339 for an enum member name indexed on the enum type, TS2749 for `Enum.Member.X`) with `scanTypeAsNs`, `typeAsNsTypeNode`, `lookupTypeAsNsName`, `resolveTypeRefToEnum`, `isPureTypeSymbol`, `emitTypeAsNs`, `emitTs2713` ((P18.312)) | one span each of `Checker.kt` taken VERBATIM (180160-180650 and 187473-187715, each one contiguous run; no holes — the second span starts at the B408 KDoc, so the orphaned B393 KDoc above it now sits directly over its own `checkNamespaceAliasTypeRefs`). A reverse transform (strip `checker.` from the 6 / 7 member names, re-privatize the one entry point each) equals each moved block byte for byte, the Checker residue (drop the two fields, strip `inRhsPrimitiveTypeParamChecks.` / `typeAsNamespaceChecks.`, re-privatize `pinRel`) equals the original minus the spans, and each original span equals its moved original (`build/bench/p18312-agent/proof.py`). The ONLY outside callers are the two `pass(…)` lambdas; the local `unconstrainedTpNames` inside the first family SHADOWS the Checker member of that name and was deliberately NOT prefixed (a `checker.` prefix there would have re-bound the call to the member). The two fields sit before `init` after `propertyInitOrderChecks`; the constructor takes `checker` alone | **9 members** (union): SINK `diagnostics` (+ the related-row builder `pinRel`); LINES `getLineAndCharacterOfPosition`; PARTITION `checkedResults`; FILE KIND `isDtsFile`, `isJsLikeFileName`; SCOPE `globals`; TYPES `getDeclaredTypeOfSymbol`, `getPropertyOfType` (TS2713's member probe only). NO walk ambient, no spine state, no mutable Checker field read. `Checker` widenings: **1** (`pinRel`, a cold diagnostic builder with a default argument — 126 in-class call sites, none on the relation path) | the `diagnostics` sink only | ~730 (`Checker.kt` 191,430 → **190,700**; `InRhsPrimitiveTypeParamChecks.kt` 529, `TypeAsNamespaceChecks.kt` 282, new) | 16-cell tsgo matrix byte-identical before / after (`build/bench/p18312-agent/matrix/`); `--passTiming` table on the compiler profile identical after normalisation (415 pass rows + 33 counter lines); `-XX:+PrintInlining` on `checkArgumentsAgainstSignature` NOT stable across processes on ONE binary (before 3/9/6 then 3/11/9, after 3/11/6 then 3/9/6 — mangled / `$default` / `Core`, unmangled 0) and before-run-1 equals after-run-2 byte for byte; corpus screen 8,725 / 0; 8-profile grid byte-identical (both arms run per profile, raw outputs identical bar `time:`); rxjs / marked / cronstrue / mitt / date-fns identical; library grid row SETS identical to `b312` on all eight (OURS-ONLY 0 / 4 / 1 / 5 / 27 / 0 / 14 / 111); `InRhsAndTypeAsNamespaceCollaboratorTest` 11 pins (every row tsgo's bar the lib related row's position), each entry point ablated alone reddens its family (5 / 5 and 4 / 4), the TypeAsNs helpers 4 / 4 / 1 / 1 / 3 / 2 / 3 (`scanTypeAsNs` / `typeAsNsTypeNode` / `lookupTypeAsNsName` / `resolveTypeRefToEnum` / `emitTypeAsNs` / `emitTs2713` / `isPureTypeSymbol`); 5 at-risk classes (69 tests) green; `huge_methods.py` 0; `spine_closure_audit.py` clean |
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

### 7 — Relater, and the ambient row the design predicted

**The census was the round's first job and it changed the shape of the work.** The
queue item named seven entry points spanning 925 lines inside a ~6,390-line region
and said to find out which of the ~5,400 intervening lines are the relater. They
are not: **1,256 lines in five contiguous spans are the algorithm**, and the rest is
ELABORATION — `getPropertyElaborationChain` (546), `getFunctionMismatchElaborationWorker`
(407), `checkExcessProperties` (231), the no-overlap family, the array- and
object-literal checks. Those answer "what do we SAY about the failure", which is a
different seam from "does it relate".

**It was deliberately NOT split, where step 4b was.** 4b had two independent families
(per-file lookup / namespace-heritage) and a natural seam. This is one mutually
recursive algorithm — `checkTypeRelatedTo` → `structuredTypeRelatedTo` →
`objectTypeRelatedTo` → `signaturesRelatedTo` → `signatureRelatedTo` → back — so any
mid-recursion cut puts a `Checker` hop inside the compiler's hottest recursion and
inflates this row temporarily for no verification benefit. The verification cost is
the same at 600 lines as at 1,256.

**The delegation surface is SIX, not 363.** `checkTypeRelatedTo` has 329 surviving
call sites and every one is byte-unchanged behind a one-line private hop. Eight of
the fourteen moved functions have zero callers left and get none, so the public
surface of `Relater` (6) equalled the `relater.` reference count (6) by construction —
7 and 7 once the test seam below was added.

**This row is the largest in the arc and that is the design's own prediction, not a
regression.** § 6 puts `getTypeOfSymbol`/`getTypeOfExpression` in Stage 3 because
"their ambient IS the checker"; the relater sits one step below that. The reads
group cleanly: MEMBER RESOLUTION (8) is the seam § 6 calls "member resolution" and
will be a collaborator of its own; TYPE CONSTRUCTION (4) is half-owned by rows 1 and
3 already. **The cheap next step was MEASURED rather than assumed, and the
measurement REFUTED the plan the round started with**: the brief asserted the enum
group had no other callers, and a caller census says only **seven** of the 48 members
do (`enumLiteralApparentPrimitive`, `enumMemberValueEqualsLiteral`,
`enumTargetAdmitsNumericSource`, `numericLiteralFitsEnum`,
`intersectionMergedSatisfiesTarget`, `intersectionMergedContradictsTarget`,
`targetIsMemberShaped`) — absorbing them is mechanical and takes the reads 45 → 38,
while `enumTypesRelation` (3 other callers) and `enumOfMemberTypeSymbol` (8) belong
to an ENUM seam of their own.

**The three elaboration WRITES are the opposite kind of debt.** `lastMissingPropertyName`
/ `lastMissingPropertySymbol` / `lastMissingIndexSigKind` are a RETURN CHANNEL: they
are how the TS2322/TS2345 path learns WHICH property failed, which a `Boolean` result
has no room to carry. Paying them means giving the relation a richer result (tsc's own
`errorInfo` chain) — a semantic change, and Stage 2 or later work, not Stage 0.

**`relationDepth` is the one counter this class must NOT own, and the reason is
invisible.** `Checker.resolveGenericPropertyType` gates INV.5(d1)'s 2,000-computation
budget on `relationDepth > 0`. Tidying it into a private `Relater` field — the obvious
refactor, since the other four counters ARE owned — leaves that gate reading 0 forever
and re-opens the deep-generic blowup the budget exists to bound, with no diagnostic
and no corpus failure. It is part of `Relater.recursionResidue` for that reason.

**The receipt protocol needed one fix and gained one control.** The `--passTiming` pass
table is printed in DESCENDING WALL-TIME order, so its row ORDER is a timing artefact
even though every column the receipt keeps is deterministic: the first comparison read
804 diff lines on two binaries that are in fact identical. Sorting the pass rows and
dropping every line carrying a millisecond figure or a TIME-BUCKETED count leaves **488
deterministic lines**, byte-identical between pristine HEAD and the split — and a
same-binary control confirms the normalisation is not simply hiding everything.

**A THIRD standing hot site is now known to be unstable across processes.** Row 4
showed `getTypeOfExpression`'s `PrintInlining` row is not stable; this round ran arm A
TWICE and `isTypeAssignableTo` moves as well (`4 inline + 4 inline (hot)` against
`4 inline + 2 inline (hot)` on ONE binary), while `checkArgumentsAgainstSignature` is
byte-identical across A's two runs and is therefore the only one of the three that can
separate arms here. It moved by one row in each direction (`5 inline + 2 too large + 2
hot-too-big` → `4 + 1 + 3`) with the refusal COUNT unchanged, which is what a split
does to a caller's inline tree.

**And the split IMPROVED inlining at the two hottest entry points, for the third row
running.** As a monolith `Checker::checkTypeRelatedTo` was refused `too large` at **344**
sites; the hop reads `294 inline + 38 inline (hot)` with ZERO refusals.
`isSimpleTypeRelatedTo` went from `24 inline + 9 too large + 13 hot-method-too-big` to a
hop with `6 inline + 4 inline (hot)` and no refusal at all. Rows 1 and 5 found the same
thing on `getOrInternReference` and `lookupPerFileForNode`.

**One production member was added deliberately: a NAMED TEST SEAM.**
`Relater.recursionResidue` (and `Checker.relaterRecursionResidue` in front of it) sums
the three stacks, `relProbeDepth` and `checker.relationDepth`, and `RelaterTest` asserts
it is 0 after a whole-program check. B202.3's finally-hygiene is otherwise unpinnable:
a stale `(source.id, target.id)` key does not fail, it makes every LATER comparison of
that pair answer `true` through the cycle break — i.e. it deletes diagnostics in
whatever file is checked next, which no corpus baseline, `cost_gate.py` counter or
`--listAll` diff can see. Three ablation arms (dropping the stack pop, the
`relationDepth--`, or the two target-stack pops) each redden it and nothing else.

**Two pins were measured UNDISCRIMINATED and renamed rather than claimed** (round 813).
A leak DETECTOR cannot work for an identical pair, because the `Relation` cache is
probed ABOVE the comparison stack and answers the second ask before a stale key is
consulted; and the `isDeeplyNested` bail is not the only bound on an infinitely
expanding generic pair — disabling it entirely (arm a4) still terminates, because
`maxRelationDepth` at 100 is a second, sufficient ceiling. The two guards are a
round-927 pair.

### 8 — MemberResolver, and the question the queue item asked

**THE OPEN QUESTION IS ANSWERED: the seam is the member TABLE builders WITHOUT
`getTypeOfSymbol`.** § 6 already says why — `getTypeOfSymbol` / `getTypeOfExpression`
are Stage-3 shaped because "their ambient IS the checker" — and the census confirms it
costs nothing to leave them out: this family reads `getTypeOfSymbol` at one site and
`getTypeOfExpression` at one, and a seam that tried to take them would have had to take
the whole checker.

**IT IS A MUCH BETTER-SHAPED SEAM THAN THE RELATER, AND THE CENSUS SAID SO BEFORE ANY
CODE MOVED**: one contiguous span against five, 657 lines against 1,256, **21 ambient
reads against 45**, **1 write against 5**, and **three** surviving entry points against
six. Row 7 is the outlier of this arc, not the trend.

**THE COLUMNS ARE DISJOINT HERE, WHICH ROW 7's ARE NOT.** The single write —
`memberResolutionTruncated`, (INC.23)'s flag — is never read back by this class; it is a
pure OUT channel to `Checker.getTypeOfSymbol`, the same shape as row 7's three
`lastMissing*` elaboration fields. That is the cheapest kind of debt to describe and the
most expensive to pay: paying it means giving these builders a result richer than `Unit`.

**AND THERE IS NO CHEAP ABSORPTION HERE — MEASURED, AND THE OPPOSITE OF ROW 7.** Row 7
had seven members with no other caller in `Checker.kt`, so absorbing them was mechanical.
Here it is **ZERO of 22**: the scarcest are `isLibSymbolForCensus` (2 other callers),
`memberResolutionTruncated` (4) and `resolveBaseTypesLazy` (5); the densest are
`getTypeOfExpression` (372), `getTypeFromTypeNode` (347) and `getTypeOfSymbol` (243).
This seam's ambient surface is shared all the way down, so shrinking it means extracting
the NEIGHBOURS — the member-NAME family that starts at `Checker.getMemberName`, and Stage
3's symbol typing — not tidying this row.

**THE SPLIT IMPROVED INLINING AT THE HOT LOOKUP FOR THE FOURTH ROW RUNNING.**
`resolveStructuredTypeMembers` has 244 call-site lines. As a `Checker` method C2 refused
it `too large` at **189** sites (`239 inline + 61 inline (hot) + 189 too large`); the hop
reads `233 inline + 55 inline (hot)` with **ZERO refusals**, the body carrying the
refusals instead. Rows 1, 5 and 7 found the same on `getOrInternReference`,
`lookupPerFileForNode` and `checkTypeRelatedTo`.

**A NEW AND SHARPER FORM OF THE JVM-NAME-MANGLING TRAP, HIT TWICE IN ONE RUN.** Rows 5
and 6 record that `internal` adds a `$<module>` suffix and a value-class parameter a
`-<hash>` one, so a receipt grep for the SOURCE name reads zero rows. This round shows
the worse case: **widening a member to `internal` as part of the split MANGLES a site
that a previous round's receipt was reading**. `getTypeOfExpression` — one of § 10's
three standing hot sites — went `private` → `internal` here, so the unmangled grep reads
**574 rows in the before-arm and 0 in the after-arm**, which looks exactly like a site
that stopped being compiled. Any § 10 receipt taken across a round that widens
visibility must grep BOTH forms, and must say which one it used.

**`checkArgumentsAgainstSignature` — (P18.56)'s only stable standing site — DID move**,
from `4 inline + 1 too large + 3 hot-too-big` to `6 + 1 + 5`. With 19 members widened to
`internal` in one commit, C2's inline tree at a caller genuinely differs; the counters are
byte-identical, the wall is noise-dominated, and the row is recorded rather than explained
away.

**The pins and their ablation.** `MemberResolver.resolutionResidue` is row 7's
`recursionResidue` one seam over: B202.1's cycle break `add`s a type id and removes it in
a `finally`, and a dropped `finally` makes every LATER resolution of that type return
member-LESS through the break — a type that silently answers as if it declared nothing.
Arm b1 (delete the remove) reddens both residue pins; arm b3 (make the break never
refuse) reddens the heritage pin and its message is the mechanism verbatim — the compile
answers ONE diagnostic, `TS2589 … at (0,0)`, `reportCheckerStackOverflow`'s signature,
and the real circular-base row is gone. **Arm b2 is DEAD BY CONSTRUCTION and recorded as
such**: deleting the `mrProbeDepth--` changes nothing, because that counter only moves
under `PassTiming.detailed`, which no test enables — so that term of the residue is
unpinned by any test and is carried by the `--passTiming` receipt instead.

### 9 — MemberNames, and what a CLEAN seam looks like

**The smallest ambient row of the arc — FIVE reads, ZERO writes — and the reason is
that the family owns no state and answers a SYNTACTIC question.** It is handed a name
node and returns a string; everything it needs beyond the AST is an enum's constant
value (3 reads) and two AST helpers (2). Rows 7 and 8 read the type system because
they ARE the type system; this one does not.

**`fileResults` as a CONSTRUCTOR INPUT is what took the row from 6 to 5**, and it is
the rule rows 5 and 6 established paying off rather than a judgement call: it is
declared at `Checker.kt:265`, above the 666 boundary, so it cannot capture null.
Every other candidate in this arc has been below it.

**`MemberResolver` was deliberately NOT rewired.** Step 6a's collaborator calls
`getMemberName` (×4) and `declaredMemberName` (×4); after this move those are one
more hop away, through `Checker`'s delegation. That is the right answer, not a
shortcut: the delegations exist anyway for the other 30 call sites, and routing
through `Checker` keeps the two collaborators free of a construction-ORDER
dependency, which is a real hazard on a class whose field initializers run ~9,300
lines deep. A later stage that wants the direct edge can add it when both are
constructed from an explicit graph.

**THE SPLIT REMOVED 61 `too large` REFUSALS AND ADDED NONE — the fifth row running.**
`getMemberName` was `16 inline + 16 too large` as a `Checker` method and its hop is
`7 inline` with zero refusals; `computedLiteralKey` `20 + 20` → `6 inline`;
`lateBoundComputedKeyName` `17 + 17` → `3 inline`; `computedSymbolKey` `6 + 6` → `2`;
`objLitElementMemberName` `2 + 2` → `2`. `declaredMemberName` was already
refusal-free and stayed so. Recorded honestly: `checkArgumentsAgainstSignature` — the
only standing site (P18.56) proved stable across processes — moved again, `5
hot-too-big + 6 inline + 1 too large` → `4 + 5 + 1`.

**The pins are AGREEMENT pins, and that is what this family needs.** A member's name
is asked at REGISTRATION and again at RESOLUTION, and BOTH known failures of this
code produce a correct diagnostic beside a false one: round 935's drift emitted a
correct TS2322 and a false TS2339 for the same member in one compile, and (CHK.40)(c)
registered a string-named METHOD correctly and typed it `any`. A pin asserting "it
compiles" passes on both. So each pin reads the member back through a deliberately
WRONG target type and asserts the TS2322 that names the resolved type AND the absence
of TS2339 beside it.

**Every pin discriminates — the first of the session's three extraction rounds where
that is true.** Disabling late binding reddens the three late-binding pins and
nothing else; raising `LATE_BIND_ALIAS_HOPS` from 8 to 100 reddens ONLY the
past-the-limit pin (and it edits `Checker.kt`, so `MemberNames.kt` is byte-unchanged
under that arm — its own control); dropping the `StringLiteralNode` arm reddens ONLY
the string-named-method pin. **The hop-limit PAIR is the interesting one**: a 2-hop
alias chain late-binds and a 11-hop one does not, so the two pins bracket the limit
and neither alone is evidence.

**And the absorption census is ZERO again**, as in row 8: the scarcest ambient member
still has 3 other callers in `Checker.kt` and `expressionTrueEnd` has 274. Row 7's
seven absorbable members remain the arc's only cheap win.

### An arc-level correction, measured 2026-09-09 after row 9

**ROW 7's "absorbing the seven zero-caller members takes the reads 45 → 38" IS WRONG,
and the error is one-sided counting.** It counted what the move REMOVES and never asked
what the moved functions themselves READ. Measured: the seven (138 lines) read eleven
members, of which `checkTypeRelatedTo` is `Relater`'s own and six are already on row 7 —
but **four are NEW** (`canonicalEnumSymbol`, `enumKnownDomainValues`, `enumMemberEntries`,
`enumValues`). So the absorption is **45 − 7 + 4 = 42**, not 38, and it drags `Relater`
into the enum-VALUE machinery that rows 7 and 9 both already reach for. That is the same
shape as CLAUDE.md's population-vs-frequency law: a count of one side of a move is not a
measurement of the move.

**WHAT IT REVEALS IS THE REAL SEAM: an ENUM family, censused here so the next round does
not have to.** `Checker.kt` 115080-115870 is **791 lines / 33 declarations**, essentially
contiguous, with **16 ambient references of which FOUR are its own caches**
(`enumDomainCompleteCache`, `enumMemberEntriesCache`, `enumMemberTypesCache`,
`enumTypesRelationCache` — they move in as owned fields), leaving ~12: `baseTypeOfLiteralType`,
`canonicalEnumSymbol`, `enumModuleImportPrefix`, `enumUnionTargetDisplay`, `fmtEnumAsgVal`,
`getDeclaredTypeOfEnumMember`, `getUnionType`, `isDtsFile`, `isLiteralAssignableToMember`,
`literalTypeOfExpression`, `typeToString`, `enumValues`. **24 of the 33 need a hop**, and
the cross-file half of that is the point: **10 are called from `Relater.kt` and 1 from
`MemberNames.kt`**. A SEMANTICS-ONLY cut at 115080-115520 (441 lines, 16 declarations)
drops the ambient to 11 and is the smaller first bite; the DISPLAY block above it
(`relationErrorTargetDisplay`, `oneMemberEnumCollapsedDisplay`, `enumOperandDisplay`,
`enumTypeQualifiedDisplay`) is (CHK.92)/(P18.48) territory and is a separate question.

**AND IT SURFACES A DESIGN QUESTION THE LEDGER'S OWN COLUMN HAS BEEN HIDING.** Extracting
the enum family reduces `Checker.kt` by ~790 lines and reduces **no** ambient row, because
`Relater`'s ten enum calls would still route through `Checker`'s delegations — and a read
through a delegation counts exactly as the original did. To actually pay row 7 down, the
collaborators have to be wired to EACH OTHER rather than all through `Checker`, which is
the construction-ORDER dependency row 9 deliberately declined to create for
`MemberResolver` → `MemberNames`. **That is a Stage-0-exit decision, not a per-row one**,
and it should be taken deliberately (an explicit construction graph in `Checker.<init>`,
in dependency order, with each collaborator's inputs stated) rather than drifted into.
Until it is taken, expect the ambient TOTAL to plateau while the line count keeps falling —
and read that plateau as the signal that Stage 0 has done what it can, not as a stall.

### 10 — EnumSemantics, the first cross-collaborator edge, and a masking defect the whole arc shared

**The queue item asked for a census of three candidates and the census decided it.**
(a) SIGNATURES is a SCATTER — `requiredParameterCount`/`getParameterSymbols` at 117338,
the call/construct signature readers at 161778, `instantiateSignature` at 172002 and ~50
more over six unrelated neighbourhoods. (b) FLOW is a SCATTER — 64 declarations, 22 of
them singletons or pairs, spanning lines 1091 to 125482. (c) ENUM is ONE contiguous span
of 1,013 lines and 40 declarations. Only (c) is a family; the other two are Stage-3 work
that needs a different instrument.

**THE STAGE-0-EXIT DECISION THE ROW-9 CORRECTION ASKED FOR IS TAKEN, AND IT IS CHEAPER
THAN IT LOOKED.** That correction predicted this extraction would cut ~790 lines and
reduce NO ambient row, because `Relater`'s ten enum calls would still route through
`Checker`'s delegations — and that paying row 7 down needs the collaborators wired to
EACH OTHER, "an explicit construction graph in `Checker.<init>`, in dependency order,
… rather than drifted into". `Checker`'s construction block ALREADY IS that graph
(`instantiator`, `nameResolver`, `relater`, `memberResolver`, `memberNamer`, in order,
each with its inputs stated), so the decision cost one line placed before the two
consumers. Measured with one uniform script across both arms: **`Relater` 49 → 38
checker reads, `MemberNames` 5 → 4**, and the new row is 13 / 0. That is the first time
the arc's ambient TOTAL has fallen.

**A COUNTING-CONVENTION NOTE, so the columns can be compared.** Row 7 records 45 reads;
a uniform `checker\.<name>` scan of the same file reads **49**. The four extra are
exactly the members row 7 lists under WRITES (`genericPropInstantiationBudget`,
`lastMissingIndexSigKind`, `lastMissingPropertyName`, `lastMissingPropertySymbol`) —
read-modify-write, so a scan counts them in both columns while the ledger assigns each
to one. Both conventions give the same **−11** for this round.

**AND A MASKING DEFECT EVERY EARLIER ROUND OF THIS ARC SHARED, found by the compiler
rather than by any instrument here.** `spanmask`/`strip` blank whole string literals, so
a `${ … }` INTERPOLATION — which is code — is invisible to the ambient census and
unrewritten by the forward transform. One such site existed in this span
(`"import(\"${moduleFileBaseNoExt(f)}\")"`); it failed LOUDLY, as an unresolved
reference, because a `Checker` member is not accessible from a collaborator, so the
compiler is a complete detector for the ambient case. It is NOT a complete detector for
the census: a TOP-LEVEL function called from inside a template resolves fine and would
simply be missing from a ledger row. `codemask.py` keeps interpolations visible and
comments blanked, and both of this round's verbatim proofs are taken with it.

**PrintInlining is FLAT here, and that is the honest reading rather than a
disappointment.** Rows 4-9 each removed dozens of `too large` refusals because each moved
ONE large body out of a caller's inline tree; this family is 40 small functions
(largest 1,033 bytecodes), so there was no monolith to remove — 182 → 191 refusals over
the 20 tracked sites. The one real gain is `isEnumFlavoredObjectType`, which had **zero**
inlines at 21 call sites and now has 37. **A trap that manufactured a fake +137 first:**
the twelve members that were `internal` on `Checker` are name-MANGLED there
(`$xemantic_typescript_compiler_core`) and are plain members of an `internal class`
afterwards, so a per-site matcher requiring a space after the name reads ZERO rows in the
PRISTINE arm. Match `::NAME(\$suffix|-hash)?\s` on BOTH arms.

### 11 — CaptureRecorder, and an ambient row that is a MEASUREMENT rather than a debt

**Read this row's 61/9 the other way round from rows 4-10.** Everywhere else a
non-none ambient column is "a debt the next stage must either pay or justify". Here
it is the arc's first quantification of the claim `docs/INVERSION-DESIGN.md` § 2
makes about this compiler — that its capture answers are functions of walk-scoped
state and therefore cannot be served post hoc. Fourteen of the 61 reads and all nine
writes are the WALK, not the type system, and the writes are a save-and-restore
sandwich reconstructing the ambient a node was reached under. Nothing here becomes
"explicit" by being moved; what the move does is COUNT it. The
`postHocTypeAtSpanForTesting` probe deliberately left behind in `Checker` is the
other half of the same instrument.

**The OUT surface, by contrast, is the cleanest of the arc**: 98 declarations move
and 15 keep a caller — ten functions plus the five `captured*` readers
`ProjectCompiler`, `Recheck`, `TypeScriptCompiler` and `CaptureEquivalenceMain`
copy into `CompilationResult`.

**THE RECEIPT MUST BE THE CAPTURE CHANNEL, AND A ROUND THAT TOOK ONLY THE PASS TABLE
WOULD HAVE PROVED ALMOST NOTHING ABOUT THIS FAMILY.** `--passTiming` is a claim about
diagnostics; the capture is a separate resolver ((INC.2): "do NOT infer a capture's
correctness from a green diagnostics sweep"). `scripts/capture-equivalence.sh` prints
a per-arm DIGEST over every captured answer, and the two arms' digests are equal over
**381,666 types and 360,917 definitions**. Note the trap that cost this round a
10-minute run: the driver's summary tail is what a `| tail -4` keeps, and **the digest
line is above it** — redirect the whole output.

**Four things the compiler forced, none of them worked around.** `CtaFrame` becomes
`internal` (a widened `ctaFrames` exposes it, and `withCtaFrameLocals` is an `internal
inline` that touches its members). `nodeAnswerComputations`' `private set` becomes
`internal set` — the recorder increments it, and round 900's point (the count is taken
INSIDE the guarded function) is untouched. The three `TYPE_CAPTURE_*_MAX_DEPTH`
constants move into the collaborator's companion, having no reader left in `Checker`.
And 42 ambient members widen `private` → `internal`, which is what a 61-read row costs.

**PrintInlining says something real for the first time since row 6**, because this
family has exactly one HOT entry point: `typeCaptureVisit` is called per node from
`spineEnterNode`, was a 925-byte body refused six times as `too large`, and its hop is
now `inline ×6` — `spineEnterNode`'s own refusals fall 4 → 3. Everything else in the
family is cold by construction (the bench passes no `TypeCaptureRequest`).
`getTypeOfExpression` moved 379 → 390 refusals and is NOT quoted: row 4 established it
is unstable across processes on one binary.

### 12 — LexicalScopeResolver, and two texts that were WRONG rather than unclear

**This row exists because five copies of one loop is how the next defect gets made,
not because 60 lines matter.** The copies had drifted on four axes and every one of
those differences turned out to be deliberate — which is why they became parameters
rather than being unified. The corpus cannot grade that: three of the five callers are
name-GATED, so a wrong axis surfaces as a name resolving to an outer binding, which is
silent. The 8-profile grid is the gate, and it reads `added=0 removed=0` on all eight.

**WHAT B83.5 IS, measured here and not what its name says.** `Binder` recurses into
statements from exactly two places — a `SourceFile`'s own list and a `ModuleBlock`'s —
so it is not "declarations nested in a `Block`" that go unbound but *everything that is
not a direct statement of one of those two*. A `class` at the very top of a function
body, with no block nesting at all, is equally unbound. Measured against tsgo 7.0.2 on
that shape: **1 ours-only TS2353 and 0 of 4 true rows** for interface/class/type/function,
plus an ours-only TS2339 for the enum VALUE position (round 748 closed the TYPE half only).

**TWO TEXTS CORRECTED, BOTH FALSE RATHER THAN VAGUE.** `LexicalScope`'s KDoc said the
tables are "UNCONSUMED until INV.4 — nothing in the checker reads these tables yet";
five consults have read them since round 748, and the same KDoc proposed
"`symbols` → `existing` → parent" as the order for a future consumer, which is exactly
the read round 748 refused. And `TypeOracle`'s two refusals both said "the retained
scope tables leave block-scoped declarations unbound (B83.5)" — the retained tables HAVE
that population. What is actually missing is a COMPOSED resolver (round 918 measured
that this ascent's rules do not transplant onto another chain), a `meaning` parameter
(`symbols` is one table where tsc's `resolveName` is meaning-split), and an `OracleLens`
row; and `symbolsInScope` opens LATER than `resolveName`, because an enumeration must
also read `existing`. A refusal that misstates its own blocker is worse than no refusal:
it makes the row look like a binder problem when it is a composition problem.

**AND THE ONE QUESTION STAGE 3 WAS UNSIZED ON IS ANSWERED HERE.** `getTypeOfSymbol` /
`getDeclaredTypeOfSymbol` answer correctly for a scope-space `Interface`, `Class`,
`Function` and `Variable` symbol — not only for the `Enum` round 748 built a
transient-symbol route for, and the `TypeAlias` read from its declaration. Measured
2026-09-10 and pinned. That is worth a row of its own because of what it REMOVES from the
next arc's estimate: had the answer been no, every consumer of a scope-space symbol would
have needed the transient route and that would be Stage 3's first commit. It does not, so
a resolution-order change can hand these symbols straight to the type system and the only
thing left to size is the ~357-reader blast radius.

**THE DECISION THIS ROW RECORDS.** Step 9 asked which of two directions to take. Option
(a), the check passes, is where the LINES are — 96,830 of 191,499 attributed lines,
50.6% — and where the seams are not: `cmam*` 83 ambient reads, `caas*` 59, the type-node
builders 68, and `cae*` **97 reads for 8 declarations**. Against rows 1-11's
0/0/4/26/22/4/13/45/21/5/61, a collaborator with twelve times as many inputs as members
is a file move. Option (b) was taken and this is its inert first sub-step; dissolving
B83.5 proper is a resolution-ORDER change with ~357 `globals[` readers downstream, i.e.
a multi-round arc and not a sub-step.

### 13 — NullishReceiverChecks, and a defect the move surfaced rather than fixed

**The family was moved while it was one session old**, which is the cheapest moment:
21 declarations in ONE span, every caller in three neighbourhoods, no reader outside
`Checker.kt`. Call sites were re-pointed directly rather than through one-line private
stubs (the rows 3-11 idiom), because the six entry points have only nine call sites and
a stub would add a hop for no text saving.

**The widening changed no JIT shape, measured.** Widening `currentLocalTypes` /
`currentShadowedNames` / `currentParamBindingNames` from `private` to `internal` looks
as if it should turn every in-class read into a getter call; it does not — Kotlin keeps
`getfield` for same-class reads of a final property, and what the widening actually
replaced was the 42 + 34 + 25 static `access$get…$p` trampolines (and 303 `access$set…`)
that lambdas inside `Checker` had used, one-for-one, with the mangled accessors. The
PrintInlining verdict counts for those accessors are the same families in both arms.

**THE (P18.230) FINDING — B6 DETACHED `currentClassForThis`'s SETTER.** `7f7d3068`
inserted the veto fields between `internal var currentClassForThis` and its
epoch-bumping `set(v)`, so since that commit the setter belongs to
`bodyLocalVetoDeclared`: assigning `currentClassForThis` bumps NO expression epoch, and
assigning the veto's declared type bumps the `currentClassForThis` epoch. The move
PRESERVES this verbatim (the pair stays on `Checker` beside a note) because fixing it is
a behaviour change — the `--passTiming` `epochBumps` counters would move — and belongs in
its own round with its own receipt. **FIXED at (P18.231)**: the setter is back on `currentClassForThis`.

**What did not move, and why.** The (P18.223) arity reader (`callArityFails`,
`reportSignatureArity`, `arityRowAt`, `callMinArgumentCount`) is not one span and
`callMinArgumentCount` has 14 callers across the call-checking machinery — it is a shared
utility of `checkArgumentsAgainstSignature`'s family, not of this one. B81.1c's
`emitTs18048ForOptionalPropertyAccessReceiver` and its `ReceiverInfo` carrier stay too:
they predate (CHK.173) and read the round-412 receiver-of-receiver narrowing inline.

### 14 — SignatureArity, and a "not contiguous" that was

**Row 13's note said the arity reader "is not one span"; it is.** The twelve declarations
from `callArityFails` to `arityRowAt` sit in one run of `Checker.kt` directly below
`checkArgumentsAgainstSignature`; what is NOT in that run is only the call-side MINIMUM
(`callMinArgumentCount` and its two siblings), which lies immediately after it and is the
piece with the many callers. The seam is therefore the reader (emission + guards) versus
the minimum (a shared utility of the whole call-checking family), and the move took the
first and left the second.

**Two ambient facts worth recording.** `arityCall` is WRITTEN only by the per-call
anchors in `Checker` (save / set / restore around the call's checking) and only READ here,
so it is walk-scoped state the collaborator consumes through a trivial getter; and
`options` is passed as a constructor argument rather than widened, which keeps
`options.useRealLibs` verbatim in the moved text.



### 15 — SignatureArity again, and the widenings it gave back

**Row 14 kept the call-side minimum on `Checker` because it has callers in the name-based walkers, the property-access reader and the relation; the
count that decides is WIDENINGS, not callers.** A caller that stays on `Checker` costs nothing once the callee is a member of an internal collaborator
(`signatureArity.x(…)` needs no widening), while a callee left behind costs a widening per member the collaborator reads. Moving the minimum together
with the emitters, the spread view and `signatureDeclaredArity` took the collaborator's `Checker` widenings from 15 to 7.

**The moved spread classifier brought two TYPE reads (`getTypeOfExpression`, `getTypeFromTypeNodeSafe`) and the void trim two more (`getTypeOfSymbol`,
`getTypeFromTypeNode`)** — the collaborator now depends on the checker's type resolution, not only on its syntax helpers and tables. That is the
honest ambient: tsgo's `getArgumentArityError` and `hasCorrectArity` resolve parameter types too.

**Found by the matrix, NOT fixed (the round is behaviour-preserving): a fixed tuple rest with an optional element called with too MANY arguments**
(`function tup(...a: [string, number?])`, `tup("a", 1, 2)`) reads `Expected 2 arguments, but got 3.` on both arms where tsgo 7.0.2 reads
`Expected 1-2 arguments, but got 3.` The too-few call on the same callee is right. The name-walker's rest-tuple branch uses
`fixedTupleLengthOfRestParam` (the element COUNT) for both ends of the range.

### 16 — ClassInstanceMembers, and a family that was one span

**The class-instance missing-member family was contiguous** (161122-161500): unlike rows 14-15 it needed no multi-span stitching, and it moved
with its three orphan KDocs (one above `mergedInterfaceHasMember`'s, two left over from the `hasInstanceMemberNamed` / TS2576 rewrites) so the span
stays verbatim. The type-only-value / TS2693 / TS2708 family (the brief's other candidate) was NOT taken: it lies in three spans and reads the
INV.4 spine's tav and const-assignment state (`spineScopeLookup`, `spineCaStatus`, `spineCaFrames`, `spineSource`, `spineFileName`,
`spineTavGlobalValueless`), so moving it would widen spine internals rather than shrink an ambient.

**Count as in row 15: callers that stay cost nothing, callees left behind cost one widening each.** Every one of the five new widenings is a
display or member-name helper the walks read; `RUNTIME_PROPERTIES` is the second companion set widened for a collaborator after
`FUNCTION_RUNTIME_PROPERTIES` (`Relater`).

### 17 — NamedImportExistence, and the family row 16 refused

**The named-import existence family moved as FOUR spans, not one** — the two passes sit ~260 lines apart with five unrelated import passes
between them, and the TS2459 / TS2460 local-declaration readers plus `emitTs2305` live in a third run further down; moving those helpers WITH the
family is what kept the widenings at twelve (each of `emitTs2305`, `getModuleLocalNames`, `getModuleExportAlias`, `getLocalDeclarationPos`,
`getAllLocalDeclarationPositions`, `declaresEsModuleMarker` would otherwise have been one). The type-only-value / TS2693 / TS2708 family was refused a
second time for row 16's reason (spine tav / const-assignment state).

**Constructor inputs are not widenings.** `options`, `binderResults`, `isMultiFileSource` and `fileResults` are immutable `Checker` construction
facts and come in through the constructor as they do for `NameResolver`; `checkedResults` does NOT, because its getter is a `PassTiming` partition
probe and a copied list would silently stop counting.

**Residues the matrix exposed (pre-existing, both arms):** a named import of a `.ts` module whose surface is `export = <value>` reads TS2616
(plain variable or class) where tsgo 7.0.2 reads TS2305, and under `module: esnext` the same import reads BOTH TS2595 and TS2616 — the
"mutually exclusive" comment in `checkDefaultImports` is false for the plain-value TS2616 branch — while tsgo reads TS2305 plus TS1203 at the
`export =`. And a default import of a relative module names the RESOLVED file by its absolute path in tsgo's TS1192 / TS2613 text, where this
compiler prints the specifier without `./`.

### 18 — NewExpressionChecks, and the walk ambient it carries

**The `new`-expression family moved as FIVE spans and was taken despite four walk-ambient reads**, because each is a READ of a field another
walk owns (`currentFileLocals`, `callWalkerClassStack`, `spineNaRunActive`) or a scoped save-and-restore (`arityCall`) — row 13's precedent, not
spine internals (row 16's refusal). Two single-caller helpers (`typeofClassValueDisplay`, `classExtendsOrIs`) moved WITH the family to cut two
widenings; `newCalleeTypeSymbolDeclaresClass` stayed because lifting it would have traded one widening for two (`resolveQualifiedValueSymbol`,
`findNamespaceMemberClassDecl`).

**A widened private fun gains Kotlin's parameter null-check intrinsics** — `checkArgumentsAgainstSignature` grew 810 → 839 bytes for four
`checkNotNullParameter` calls. C2 already refused it in every row, so the verdicts do not move; but it is a real cost of widening a HOT callee,
and the receipt must quote the bytecode size, not only the verdict family.

**Residues the matrix exposed (pre-existing, both arms):** the instance-callee TS2351 (`const i = new C(); new i()`) is absent from a MODULE file
— `newCalleeVarHoldsInstance` reads `globals`, which INV.3(d) keeps module locals out of — while tsgo reports it; B264's pass does not reach a
plain two-level generic chain (`declare class BaseBase<T, U>` with overloads, `Base extends BaseBase<string, number>`, `Derived extends Base`,
`new Derived(1)`, cells c20 / c22) where the corpus shape (rest-parameter overloads over a concrete base, c23) fires — which ingredient
the AST-only model needs is unmeasured; and `new G<number>(1)` against `T extends string` misses tsgo's TS2344.


### 19 — CommentDirectives, a pure filter with no walk ambient

**The cleanest of the three candidates (P18.270) censused.** The type-argument ARITY family (`getTypeParamInfo` / `checkTypeArgCount` /
`ownScopeTypeParamInfo`) was refused for this round: its spans are split by the unrelated `libProvides*` block, it reads walk ambient
(`currentFileLocals`) and ~15 tables/resolvers (`globals`, `fileResults`, `binderResults`, `resolveAlias`, `lookupPerFileForNode`, …), its
memo fields sit inside the pre-`init` field block, and `TypeParamInfo` / `isUnresolvedGenericType` / `checkHeritageTypeArgCount` keep 9 callers
outside the family. The implicit-any ASSIGNMENT-TARGET family was refused outright: it reads the spineIany walk stacks (`implicitAnyScopes`,
`implicitAnyScopeCtxParams`, `implicitAnyScopeInits`, `implicitAnyScopeDestructures`, `implicitAnyEnclosingClassMembers`).

**The widened `srcHas` is a hot shared helper** (round 895: ~3,800 calls per rebuild through 149 sites) — widening adds two
`checkNotNullParameter` intrinsics to its 16-byte body; its in-class callers are unchanged and no PrintInlining row moved.
