# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Re-scope (2026-07-03, owner): the Phase 17 target narrowed from "any TypeScript
project" to the TypeScript compiler itself.** Rationale: "any project" is asymptotic,
while the tsc-source profiles are already the dashboard — v1 becomes a measurable
burn-down (compiler 2,726 / services ~7,145 / server ~7,606 / harness ~8,135 FPs,
same ~4 families ≈85% of every profile). Queue consequences: M2.4 (DOM — tsc sources
don't reference it), M3.0 (conformance adoption — optional extra regression net, not
needed for the burn-down), M3.5 (per-file scopes — revisit only if dashboard FPs trace
to cross-file conflation on tsc sources), and all of M4 (nodenext `exports` maps, decl
emitter, JSX, external sourcemaps, project references — none block self-compiling tsc)
moved to the new § "Post-v1 backlog". M3.1–M3.4 stay live but re-scoped from
completeness campaigns to dashboard-driven burn-down: the acceptance bar is the shapes
tsc's source uses, with the corpus suite as the regression net. M5 unchanged —
performance is the directive's second half and starts at v1 compliance.

*(Numbering note: rounds 432–434 below are the `perf/flow-import-resolution` branch's original
rounds 430–432, renumbered at merge — the branch ran in PARALLEL with main's own rounds 429c–431e,
which own those numbers. The perf rounds' FP baselines (1,148 / 1,665) are the branch's pre-merge
numbers; main's concurrent M3.1/M3.2 work independently took the compiler profile to 482.)*

**Re-scope (2026-07-13, owner — round 490): M5 becomes the staged ARCHITECTURE
INVERSION arc (INV.0–INV.7).** The owner reviewed the round-490 architecture
analysis ("question every decision; cross-check tsc/tsgo") and directed: follow the
recommendation, rescope towards the overall goal. Full analysis + phase design:
**`docs/ARCHITECTURE-RETHINK.md`** — read it before ANY M5/INV item. Headline: the
flat-profile micro-opt mode (rounds 482–489, 1–3%/round) is CLOSED; the measured
cost is a multiplier (~512 sequential full-program checker passes × uncached
`getTypeOfExpression` × context-bypassed `nodeTypes` × per-pass scope re-derivation
× non-interned unions), and the fix is the tsc-shaped inversion: bind-everything →
per-file scopes → single-pass demand-driven checking with per-node caches →
canonical type identity/mappers → share-nothing parallel checkers. Owner-approved
same directive: **kotlinx-coroutines-core as a commonMain dependency** (within the
kotlinx.* rule) for the Flow-based concurrent front-end (read+UTF-8→UTF-16 decode on
`Dispatchers.IO`, parse on `Dispatchers.Default`, bounded `flatMapMerge` = the
owner's measured microbench win) and later the parallel checker/emit phases —
streams live at the I/O boundaries; the checker core stays demand-driven
memoization, per the doc's § 4. Old M5.1–M5.7 are superseded/absorbed by the INV
items in the QUEUE below (M5.1 profiling → INV.0; M5.2/M5.3 → INV.5; M5.4 → INV.6;
M5.5/M5.6 → INV.7; M5.7 targets → doc § 6).**

**Round 513 (2026-07-14) — INV.3(d)(v) deletion #1: the `moduleFileLocalVarNames`
value-leak ecology DELETED (the round-442/445/447/448/450/453/460 family).**
Removed: the field, the 1a2 builder, all seven consult bails (mam receiver-chain
walk, arg-loop root walk via `argRootIsLeakedModuleVar`, return-path, assignment
RHS + TARGET, var-decl alias inference, `arithOperandType` operand bail).
Post-retire every consult was hypothesized inert — the gates found exactly TWO
pre-retire accidental masks (the round-512 (iii) lesson class): (1) the suite
caught ModuleVarLeakAssignReturnTest's fixture genuinely erroring in tsc
(`Named ⊄ Target | undefined` — the destructured local now resolves CORRECTLY;
the bail had been suppressing a genuine error; fixture rewritten into a sharp
leak-kill pin). (2) the 8-profile A/B caught harness +2 TS2339 (fakesHosts.ts
`sys.vfs` on `System | FileSystem`) = TWO real narrowing gaps the bail masked —
it over-suppressed EVERY `sys`-rooted member chain program-wide because
compiler/sys.ts exports a module-level `let sys`. Fixed tsc-faithfully:
`resolveInstanceOfRhsType` resolves a ns-import-QUALIFIED class
(`sys instanceof vfs.FileSystem`) via `resolveNamespaceQualifiedSymbol` (both
branches narrow); `narrowByAssignmentRhs` gained the NEW-EXPRESSION
assignment-reduction arm (tsc getAssignmentReducedType — the 4th round-477
sibling); plus the DIR-RELATIVE resolver leg in `namespaceAliasMemberSymbol`'s
three arms (the THIRD instance of the round-511 lesson class, purely additive).
Pins: InstanceofNsQualifiedNarrowingTest (5). VERIFIED: suite 10,346 → 10,351
(0/3); ALL 8 profiles listAll byte-identical vs pre-deletion.
**Deletion #2 (same session, resumed after an OOM kill): the
`conflatedTypeAliasFiles` alias-shadow ecology DELETED** (rounds
443/444/447/464/468b/474/475/476/477): the field, the 1a3 builder, the mam
receiver bail (+ aliasOwnFileHasProp), `paramTypeIsLeakedConflatedAlias`,
`returnSourceSatisfiesFileLocalAliasBody`,
`objectLiteralMatchesConflatedFileLocalTypeAlias`,
`implicitAnyMemberAnnotationConflated`, the round-477 QualifiedName
per-file-view dispatch, `qualifiedAliasShadowedTarget` + its return-path
early-return, the round-474 keyof recovery (2 helpers), the round-464 negative
gate in typeNodeDefinitelyNonNullish, and isConflatedInterfaceRefNode's alias
arms. TWO pieces RE-KEYED onto their true conditions (both are NON-conflation
gaps the alias table happened to gate): (1)
`objectLiteralSatisfiesAugmentationMergedInterface` — fires on
augmentation-block `interface X` presence (the un-modeled cross-file
augmentation MEMBER merge); the gates caught a same-named-CLASS hole
(jsExportMemberMergedWithModuleAugmentation: `class Abcde { x }` merges
invisible-to-the-scan members → bail). (2)
`objectLiteralMatchesFileLocalAliasUnion` — a returned objlit vs a FILE-LOCAL
alias union name-covering some constituent (the round-438 nullish-strip-only
objlit gate makes REFERENCE-valued members' guard narrow-DOWNs invisible —
fixAddMissingMember.ts:410 `{ kind: InfoKind.Enum, token, parentDeclaration }`
vs `Info | undefined` was the sole 3-profile diff), WITH an explicit-value type
firewall (ConflatedAliasBodyOwnerContextTest's negative control caught the
name-only version over-suppressing `{ errors: "not an array" }`). Pins:
FileLocalAliasUnionReturnTest (3). VERIFIED: suite green (10,351/0/3 + the new
pins targeted; 10,354 next full run); ALL 8 profiles listAll byte-identical.
NEXT: deletion #3 — the `conflatedInterfaceFiles` objlit/relation chimera
bails, then #4 (enum subsets + the per-file-view core + heritage threading).

**Round 512 (2026-07-14) — INV.3(d)(ii)+(iii)+(iv) ALL DONE; the retire branch is
MERGED TO MAIN: suite fully green, ALL 8 profiles byte-identical to pre-retire.**
Six commits. (ii) the last 6 corpus multi-file failures, one per-consult-flip family
each (union-discriminant objlit drill node-keyed — indirectDiscriminant; ns-import
static TS2339 + the DIR-RELATIVE legs in resolveAlias's ImportDeclaration AND
ImportSpecifier branches (the round-511 lesson class — the corpus test passed while
the path-shaped MainKt repro still failed, twice) — exportStarFromEmptyModule;
TS2749 file-keyed + the NEW `typeSideImportFallback` gate (a local `function
NodeLinks(this: NodeLinks)` shadowing a TYPE import stays type-eligible — the naive
flip manufactured TS2749×48 on both profiles) — allowImportClauses; the B585
contextual-display hops — allowJscheckJs; JSDoc ImportType own-specifier resolution
— checkJsdocTypeTag2; the TS2415 imported-base heritage flip —
declarationEmitPrivateSymbol2). (iii) the last 4 local pins: 2 resolver gaps (the
`export * as NS` re-publication arm in namespaceAliasMemberSymbol; the ns-member
objlit ctx root/sub-namespace flips) + 2 PRE-RETIRE ACCIDENTAL PASSES — the
conflated receiver used to resolve to a sibling interface and fire through
Interface-gated emitters; post-retire the correct anonymous resolution exposed two
genuine FNs, fixed tsc-faithfully (mam all-missing ALL-ANONYMOUS union TS2339, no
member chain; TS2345 `paramIsPlainObjectBag` gated to all-concrete member types —
the sys.ts B83.5 closure-param mis-binding FP'd until the gate). (iv) all three
profile-residual families: deprecate.ts `compareTo` (an anyType shadow now BAILS
mam — the outer-import fall-through was categorically wrong; pre-retire the merged
symbol's polluted flags dodged the string branch by accident); **server/harness
session.ts Diagnostic — the round-473 Identifier DISPATCH into
`conflatedPerFileInterfaceType` is REMOVED (the first (v) deletion): post-retire
the node-keyed resolution already yields clean per-file interfaces, and the view
minting had turned actively wrong — two instances of protocol.ts's SAME
`Diagnostic` declaration resolved their nested `DiagnosticRelatedInformation`
member in DIFFERENT files' views (probe: srcElem riFile=types.ts cat=
DiagnosticCategory vs tgtElem riFile=protocol.ts cat=string) and failed to relate;
an env-gated experiment proved removal restores the pre-retire baselines exactly**;
fourslashImpl `'array'` (namedUnionMemberCouldAcceptArray hops import aliases —
ImportSpecifier via resolveImportedSymbolGeneral, ImportEquals via resolveAlias).
VERIFIED: full suite green; **all 8 profiles listAll byte-identical vs a
main-worktree pre-retire build** (the (iv) exit gate). Local pins:
UnionDiscriminantModuleAliasTest(3), NamespaceImportStaticMemberTest(2, incl.
path-shaped), PostRetirePerFileConsultsTest(7), PostRetireProfileResidualsTest(4).
NEXT: (v) — delete the remaining conflation ecology walker-by-walker (the
Identifier dispatch removal is the template: measure each against all 8 profiles).

**Round 511 (2026-07-14) — INV.3(d)(i) DONE + (ii) 8/14 + (iii) 4 flips & 2 pin-updates
(all on branch `wip/inv3d-merge-retire`, rebased onto main; 4 commits): branch
failures 42 → 10.** (i) The ambiguous-constrained→foreign TP leg reverted
(declaration-identity kept) — 17 tests flipped incl. the WhileTrue/tsx collateral;
checker.ts:7358 re-solved inference-side (CallExpression args carrying a TypeParam
soft-skip at forReturnType sites, pinned by ForeignTpInferenceSoftSkipTest ×6).
(ii) Per-consult flips per the round-510 method: heritage/implements walkers
node-keyed + the B563 ownership-gate mirror (the double-TS2420 lesson: when a
walker's ownership gate is "the OTHER walker resolves it", both must consult the
SAME primitive), checkConstraintsForTypeArgs keyNode/presetSymbol (ImportType
resolves through its OWN specifier), checkTypeNameResolved's leftSym →
globalsForFile (the pass runs unscoped — currentFileLocals is null there), the mam
type-only-winner + ns-import value-side bail. **Critical find: the import hop
lacked a DIR-RELATIVE resolver leg — path-shaped extensionless imports never
hopped, so post-retire EVERY import-mediated type died on real on-disk projects
(repro: `const x: number = importedString` drew NOTHING); pre-retire the merge
masked it, and the tsc profiles' `.js` specifiers take the separate stripping leg,
so listAll byte-identity NEVER covered this class — the local direct-construction
pins (EnclosingImportIndexTest) were the only net that caught it. Lesson recorded
as a CLAUDE.md gotcha: profile byte-identity is NOT sufficient verification for
resolution changes; scratch-project MainKt repros are cheap and decisive.**
(iii) Two pin-updates to the post-retire contract (unindexed-copy degradation
nulls module-only names; the leak worklist assertion inverted — an emptied
worklist is the victory condition). Every commit gated on compiler AND services
`--listAll` byte-identical vs the round-510 captures. Remaining on the branch:
6 corpus (roots noted per-test in the (ii) item) + 4 local (all suspected REAL
suppressions — r7/r8 scratch repros exist for the ConflatedTypeAliasLeak pair) +
the (iv) profile residuals. Main untouched.

**Round 510 (2026-07-13) — INV.3(d) merge retire: BUILT AND PROFILE-VERIFIED on
branch `wip/inv3d-merge-retire` (033598b6); main untouched pending the
enumerated 36-failure worklist.** The retire (skip a MODULE file's module-only
top-level locals in the step-1 merge) landed with every consult flip the
empirical loop demanded, and reached **compiler AND services `--listAll`
BYTE-IDENTICAL vs pre-retire HEAD**. Method (the session's real product): (1)
naive full retire → 861 compiler FPs, dominated by SHARED names — module
interfaces riding same-named LIB globals (`Symbol`/`Node`/`Performance`),
whose importers free-ride on the merge because the general resolver can't
follow `.js` barrels → **the retire must stage by NAME CLASS: module-only
first, SHARED later** (predicate: lib-keys ∪ script-locals computed pre-merge,
kept as `mergeSharedKeepNames`); (2) module-only cut → 34 FPs; a
classifier-MISS stack-sampling probe (a miss on a retired name was a hit
pre-retire = exactly the at-risk traffic) + the Diagnostic-init emitter probe
traced every family to its consult: the shadow-ecology collision questions
(gain a `currentFileLocals` disjunct — imports no longer sit in globals),
checkMemberAccessMissing's bare-Identifier receiver, checkTypeArgCount's
enum-member gate, aliasUnionContainsNullishKeyword,
calleeDeclaredCtxParams/resolveClassCtorParamsForCtx,
computeImportedCalleeFunctionType's collision gate (globalsForFile),
getTypeFromTypeQuery, resolveTypeOfValueEntityName, resolveQualifiedName +
resolveQualifiedValueSymbol (the QualifiedName-root convention is untenable
post-retire), findVariadicTupleInTarget/arrayElementTypeNode; (3) services
flood (TS2339×5355!) rooted in ONE line: `mergeModuleAugmentations`' else-add
put an AUGMENTATION-ONLY stub into globals when the retired base missed, and
the 1b visibility delta then marked `SourceFile`/`Node` non-module-only so
every fast-path consult returned the stub — fixed by resolving `.js`
augmentation targets (`resolveModuleSpecifierRelativeJsAware`; the
locals-merge now fires, so augmentation members reach the target file's own
symbol) and gating the globals-add to FILELESS ambient targets; (4) the
remaining services families forced real per-file machinery:
`typeSideImportFallback` (import-TYPE shadowed by a same-named local VALUE —
utilities.ts's `SourceMapSource`; recovered via the ImportSpecifier's
nodeToSymbol-recorded alias), `namespaceAliasMemberSymbol` (dotted
`ts.server.X` chains + the round-479 `import * as NS; export { NS }` barrel
re-publication), and `ternaryBranchType` (a ternary condition narrows a
`??`/`||` branch's LEFT reference — deprecatedCompat's Version idiom). The
full suite then enumerated 36 failures (~11 from the foreign-TP
ambiguous-constrained leg — revert it, re-solve checker.ts:7358 on the
inference side; ~13 corpus multi-file; ~10 of the (c)-era local tests pinning
pre-retire semantics) + harness +5 / server +2 residuals — more than the
session budget, so per protocol the working state is preserved on the branch
with the worklist decomposed into queue sub-items (d)(i)–(v). Suite on main
UNCHANGED (10,316 / 0 / 3). NEXT: (d)(i)–(iv) on the branch, then merge, then
the (v) deletions.

**Round 509 (2026-07-13, same session as 508) — INV.3(c)(iv) leg 2 LANDED +
re-measure: the (c) migration is COMPLETE, INV.3(d) unlocked.** The four
remaining sites node-keyed: `getTypeFromBaseTypeExpression`'s Identifier
fallback (the PropertyAccess last-segment fallback stays legacy, mirroring
getTypeFromTypeReference's QualifiedName convention),
`emitTs2345ForBareTpArgToConstrainedTpParam`,
`getOverloadImplementationRelated` (keyed by the overload DECL's own name
node — a top-level decl's file always declares the name so the merged
instance is byte-identical; a nested/B83.5-unbound or foreign-collision name
no longer hands TS2793 a wrong-file impl pointer from the polluted merged
declarations list), and `calleeReturnAnnotationForImplicitAny` (the
`uniqueFunctionDeclByName` fallback still covers program-wide-UNIQUE names —
only ambiguous foreign names change, yielding no annotation instead of the
merged list's first pick). Verified: suite green 10,311 → 10,316 (+5
Inv3TypePositionLeg2NodeKeyTest — 2 leak-kills FAIL on the post-leg-1
checker via stash: an UNIMPORTED foreign heritage base grafting members
manufactured TS2741 on `const d: D = {}` (post-flip only the correct TS2304
on the base name remains), and an unimported foreign `take<T extends
string>` callee manufactured TS2345+TS2208 about an invisible constraint;
3 preservation controls: imported base + cross-file SCRIPT base keep the
real TS2741, own-file constrained callee keeps TS2345); `--listAll`
byte-identical on compiler AND services (46/46 — diffed against the leg-1
captures, which are HEAD's own outputs). RE-MEASURE (`--passTiming`,
compiler profile): **CONFLATED 6,165 → 917** (−85%; cumulative from the
pre-migration 157k → −99.4%), total keyed lookups 2.36M → 2.06M, 97 names
across 9 passes (top: checkCallExpressionTypes 318 /
checkTypeAssignability 284 / checkPropertyAccess 273), and the by-NAME table
(`diag` 140, `clone` 135, `map` 73, `length` 45, `factory` 38, `min`,
`isIdentifier`, …) is exactly the shadow-detection ecology's
"does a merged global collide" consults (`registerNestedGlobalShadow*`/
`applyBodyLocalShadowing`/`shadowNestedFunctionNames`) + tiny tails — the
deliberately-legacy INV.3(d) scope, per the round-507b prediction. Bench row
appended. NEXT: INV.3(d) — retire the merge + delete the conflation ecology,
walker-by-walker, each deletion suite- and listAll-gated.

**Round 508 (2026-07-13) — INV.3(c)(iv) leg 1 LANDED: `resolveTypeNameToSymbol`'s
Identifier branch + `typeNodeDefinitelyNonNullish`'s two fallbacks node-keyed
JOINTLY (the round-507c order constraint).** The general type-resolution flip:
`resolveTypeNameToSymbol`'s Identifier branch consults
`lookupPerFileForNode(node, node.text)` — node-keyed resolution is a fixed
property of the node, so the `nodeTypes` cache stays valid by construction —
and the TWO call sites carrying their own trailing `?: globals[name]`
(`getTypeFromTypeReference`, whose inferenceNamespace middle consult is
untouched, and `checkConstraintsInTypeNode`'s TS2315 emitter) gate that
fallback to QualifiedName: for an Identifier it was byte-redundant pre-flip
(same key as the resolver's own lookup) and would silently RE-LEAK the
node-keyed null post-flip — the trap is now recorded in the CLAUDE.md
INV.3(c) entry. `typeNodeDefinitelyNonNullish`'s two merged-globals fallbacks
consult `lookupPerFileForNode(t.typeName, name)` (currentFileLocals stays the
first consult, the (c)(ii) convention). TWO discoveries: (1) the first full
suite failed exactly ONE test — ThisPredicateNarrowingTest's cross-file
augmentation pin — exposing a REAL visibility-model gap: tsc scopes a
`declare module "<relative-spec>"` AUGMENTATION body under the augmented
module's exports (the round-443 buildNamespaceScope rule), so the naive flip
nulled `UnionType` inside services-style `declare module "./types.js"` blocks
and this-predicate narrowing died; `lookupPerFileForNode` now captures the
innermost string-named ModuleDeclaration during its parent walk and grants
the resolved target's direct named exports (unclassified under --passTiming,
the (c)(ii) discipline). (2) Test-design: the ADDITIVE leak-kill direction is
SHADOWED by any-degradation — an unresolvable callee annotation degrades the
assigned reference itself to `any` (proven with a never-declared `Zorp`
control; also why the basic `return x` on a `T | undefined` local draws no
TS2322 — pre-existing FN), masking every downstream narrowing consumer — so
the flow observable uses the SUPPRESSION direction: a foreign UNIMPORTED
NULLABLE alias return-annotation pre-flip types the assigned reference as the
leaked union and manufactures TS18048 on a closure-captured read; post-flip
the reference degrades to `any` and the leaked TS18048 dies (tsc-faithful:
TS2304 → any). Verified: suite green 10,302 → 10,311 (+9
Inv3TypePositionNodeKeyTest — 3 leak-kills FAIL on the pre-flip checker via
stash: the flow TS18048, an annotation-position TS2322 from a leaked foreign
`type Shape2 = { v: string }`, and a TS2315 manufactured about an unimported
non-generic alias; 6 preservation controls pass both sides: imported/own-file
nullable alias keep the REAL TS18048, imported non-nullish alias + imported
interface keep the nullish-strip, imported alias annotation keeps the real
TS2322, own-file TS2315 keeps firing); `--listAll` byte-identical on compiler
AND services (46/46 env-legit diagnostics); bench row 28,004 ms self, +0.1%
(dead in band), same top codes. NEXT:
(iv) leg 2 — `getTypeFromBaseTypeExpression`'s Identifier fallback + the tiny
value tail (emitTs2345ForBareTpArgToConstrainedTpParam,
getOverloadImplementationRelated, calleeReturnAnnotationForImplicitAny), then
the instrumented re-measure that unlocks INV.3(d).

**Round 507c (2026-07-13, same session as 507/507b) — INV.3(c)(iv) first cut
ATTEMPTED and REVERTED (unpinnable, not regressing): the
`typeNodeDefinitelyNonNullish` fallbacks alone have NO observable.** The flip
compiled, all preservation controls passed, but the leak-kill could not be
made to FAIL pre-flip OR fire post-flip: for every constructible shape the
narrowing survives through `resolvedCallReturnTypeForFlow`'s assignment-reset
arm, which resolves the SAME return annotation via `getTypeFromTypeNode` →
`resolveTypeNameToSymbol`'s still-legacy globals fallback — the syntactic
classifier's leak is fully shadowed by the general-resolution leak (they
consult the same merged map; the classifier's extra power — barrel-imported
alias bodies — is exactly the case the visibility probe PRESERVES, so no
kill shape exists there either). Landing an unpinnable flip would break the
(c)-migration's stash-verified leak-kill discipline, so the code was
reverted and the ORDER CONSTRAINT written into the (iv) queue item:
`resolveTypeNameToSymbol`'s Identifier fallback must flip FIRST (or jointly
in one commit) — it is also the highest-risk site (general type resolution:
nodeTypes caching, first-touch poisoning, the round-473/474 conflation
ecology) and needs fresh context + the full battery. The joint observable
and its verified preservation fixtures are recorded in the queue item. No
tree change; suite state carries over (10,302 / 0 / 3).

**Round 507b (2026-07-13, same session as 507) — INV.3(c)(iii) phase 3
LANDED: `getTypeOfIdentifier`'s globals fallback node-keyed — (iii) is
COMPLETE.** The round-442 measured dead-end (by-NAME nulling of the fallback
broke cross-file initializer inference / redeclare / .d.ts emit — 5 corpus
regressions) does NOT reproduce for the per-FILE flip: the visibility probe
resolves every imported/own/script/lib name to the SAME merged instance
(an import alias probes non-null through `resolveImportedSymbolGeneral`
whatever its kind), so only a name with NO per-file meaning changes — it
types as `any` instead of the foreign leaked local, which is what real tsc
sees (TS2304 → any). Companion perf guard: `lookupPerFileForNode` gained a
fast path — a non-module-only name resolves identically under every file's
visibility (`globalsForFile` ignores the file for it), so it goes straight
to `globals[name]` with NO parent-chain walk; the fallback serves ~2M
identifier typings per self-compile and only ~126k module-only names walk
(the pre-1b2 empty set degrades everything to the legacy consult — the
init-order gotcha handled by construction). MEASURED: conflated 10,034 →
6,165 (cumulative from the round-505 baseline 20,941 → 6,165, −71%);
`factory` (3.3k, the phase-2 residue's dominant name — a function param in
non-importing files typing through nodeFactory.ts's leaked export) is GONE;
by-pass checkImplicitAnyParameters 2,608 → 171, checkUncalledFunctions 968
→ 189, checkConstAssignment 206 → 131. The remaining ~6.2k = the (c)(iv)
type-position tail (types.ts type names via typeNodeDefinitelyNonNullish /
resolveTypeNameToSymbol / getTypeFromBaseTypeExpression) + ~500 value
names in the deliberately-legacy shadow-detection ecology (INV.3(d) scope)
+ tiny tail sites folded into (iv)'s re-measure. Verified: suite green
10,298 → 10,302 (+4 Inv3IdentifierTypingNodeKeyTest — the leak-kill FAILS
on the pre-flip checker via stash: a bogus TS2322 typed a non-importing
file's bare identifier from a foreign module's const; 3 preservation
controls pass both sides, including the import-driven initializer-inference
shape from the round-442 regression family); `--listAll` byte-identical on
compiler AND services; bench row 27,972 ms self (+4.0% single-run = the
documented box-drift band; the walk cost is bounded by the fast path).
CLAUDE.md updated in BOTH places (the INV.3(c) entry + a clarifying line
inside the round-442 dead-end entry so the two records don't read as
contradicting). NEXT: (c)(iv) — the type-position tail, then the
re-measure that unlocks INV.3(d).

**Round 507 (2026-07-13) — INV.3(c)(iii) phase 2 LANDED: the bare-Identifier
VALUE/receiver/callee cluster node-keyed (11 consults, one commit).** The
round opened by re-running the round-503 stack-sampling probe on HEAD (1:20,
937 samples over the remaining ~18.7k conflated lookups — probe reverted):
the measured per-site distribution was checkPrivateMemberAccess ~2.7k /
computeRawTypeOfPropertyAccess's direct ns-fallback ~2.9k / getTypeOfIdentifier
via isCalleeResolvable+receiver typing ~3.2k / typeNodeDefinitelyNonNullish
~2.7k + resolveTypeNameToSymbol ~1.9k (the (iv) type tail) /
resolveFlowCalleeDecl ~1.6k / resolveNamespaceMemberFnDecl ~1.1k, dominant
name `factory` (~38% — a function PARAM in non-importing files like
emitter.ts, resolving through nodeFactory.ts's leaked export). Flipped onto
`lookupPerFileForNode` (keyed by the name's own Identifier node — uniform
with (c)(ii), equals current-file keying for own nodes): the TS2341 receiver
consult (`checkPrivateMemberAccess` — a private-access verdict about an
invisible class is always bogus), `getCalleeType`'s Identifier branch (args
must not check against a foreign leaked signature), `resolveFlowCalleeDecl`
+ `resolveNamespaceMemberFnDecl` (guards/asserts with no per-file meaning
must not narrow — tsc sees TS2304 there; the round-471 per-file predicate
selection is preserved via the extracted `currentFileNestedPredicateDecl`,
now also reachable from the direct==null fallback so an own-file nested
guard keeps narrowing when several files nest same-named guards), the three
ns-fallback receiver resolvers (`computeRawTypeOfPropertyAccess` /
`resolvePropertyAccessToSymbol` / `propertyAccessChainIsNamespaceQualified`
— a leaked root now bails exactly like an unleaked one),
`isCalleeResolvable` (an unresolvable callee provides no contextual
signature → TS7006 legitimately fires; lexical/nested checks below the
consult keep param callees resolvable), `checkPropertyAccessAssignment`'s
ns base, the two mam receiver consults (B589's gate is unreachable for
conflated names by construction — lib-visible ⇒ SHARED; B586's foreign
`{}`-annotation emission), and the two protected-CONSTRUCTOR heritage walks
(`findEffectiveConstructorVisibility`/`classExtendsOrIs` — the round-506
cluster's missed siblings). MEASURED: conflated 20,941 → 10,034 (−52%);
by-pass checkPropertyAccess 7,038 → 3,136, checkCallExpressionTypes 5,743 →
1,430, checkProtectedMemberReadAccess 2,198 → 0; remaining top names are
`factory` 3.3k + the types.ts type-name tail (the (iv) sites) + clone/diag/
toPath (getTypeOfIdentifier fallback + shadow ecology). Verified: suite
green 10,289 → 10,298 (+9 Inv3ValueCalleeNodeKeyTest — 4 leak-kill tests
FAIL on the pre-flip checker via stash: bogus TS2341 from a leaked
private-membered var vs a same-named param, bogus TS2345 from an unimported
foreign callee, and two narrowing-leak kills where killing the foreign
guard/namespace-guard resolution makes the genuine union TS2339/TS2345
fire; 5 preservation controls pass both sides: same-file private, imported
callee, cross-file script callee, imported guard, same-file namespace
guard); `--listAll` byte-identical on compiler AND services; bench row in
band (26,902 ms self, −1.8%, same 46 env-legit diagnostics). Test-design
note: `v.length` on a `string | number` param draws NO union TS2339 from
our checker (pre-existing FN — the leak-kill observables use an
interface-member union TS2339 and a union-arg TS2345 instead). NEXT:
(c)(iii) phase 3 = `getTypeOfIdentifier`'s fallback node-keyed (the ~3.2k
receiver-typing tail — needs its own battery per the round-442 caution),
then (c)(iv) the type-position tail.

**Round 506 (2026-07-13, same session as 500–505) — INV.3(c)(iii) phase 1
LANDED: the protected-member cluster node-keyed.** The pw/pmr/pm walkers
(TS2445/TS2446 protected-access + the assignment-mismatch companion —
`checkProtectedMemberReadAccess` was #4 in the post-505 conflated-by-pass
table at 2,198) key their merged-globals fallbacks by the name IDENTIFIER
node via `lookupPerFileForNode`: the `pwResolveClass`/`pmrResolveClass`
funnels (which every heritage walker, param-annotation resolution, and
derives-from chain feeds) plus the two direct consults (`pmrCheckAccess`'s
static-class receiver, the top-var ctor-init resolution). KEY TECHNIQUE
(the template for the remaining (iii) sites): the heritage walkers all wrap
a REAL indexed Identifier in a synthesized `TypeReference(typeName =
baseName)` — keying by `typeName` (never the wrapper, whose parent is null)
attributes correctly with ZERO signature changes; a fully-synthesized
identifier (pmrLocalClass's from-text `Identifier(it)`) has no owner and
degrades to the legacy merged consult inside the primitive. Suppression-only
by construction: every consumer resolves a CLASS for a protected-visibility
verdict, and a conflated resolution could only manufacture a bogus TS2445
about a class the file never imports (real tsc: TS2304 territory; note the
imported-class case was ALREADY a silent FN here — the locals hit returns
the alias with no ClassDeclaration and the walker bails — so the flip
cannot lose real diagnostics). Verified: suite green 10,284 → 10,289 (+5
Inv3ProtectedNodeKeyTest — BOTH leak-kill tests FAIL on the pre-flip
checker via stash: the leaked resolution manufactured TS2445 for an
unimported foreign class's protected static and for a param annotated with
an unimported foreign class; 3 preservation controls — same-file instance,
same-file static, cross-file script — pass both sides); `--listAll`
byte-identical on compiler AND services vs the pre-505 baseline (46/46);
bench row in band. NEXT: (iii) phase 2 — the typing-path sites, starting
with the per-site consumer classification (identifier.fallback is
round-442 dead-end territory).

**Round 505 (2026-07-13, same session as 500–504) — INV.3(c)(ii) LANDED: the
kind-domain/enum-discriminant family flipped onto the node-keyed primitive.**
The ~82% conflated family (round-503 measurement) now keys its merged-globals
fallbacks by the NODE'S OWNING FILE: `resolveEnumSymbolForDiscriminant` and
`kindDomainTypeDeclSymbol` thread a `keyNode` parameter (all 5 call sites pass
the AST node the name was read from — the annotation TypeReference, the
heritage `base`, the comparison PropertyAccess), and the alias fallbacks
inside `enumSwitchKeysFromTypeNode`/`enumMemberKeysOfTypeNode` (incl. the
round-477 import-alias fallback) consult `lookupPerFileForNode(node, name)`
instead of raw `globals[name]`. `currentFileLocals` stays the FIRST consult at
every site (own-file semantics unchanged — minimal-diff, byte-identity-first);
the recursion into an alias BODY naturally re-keys by the body node = the
declaring file (tsc-faithful alias-body scoping for free). Effect: a types.ts
member annotation resolves under TYPES.TS's visibility whatever file is being
checked — the owning file declares/imports those names, so the probe passes
and the merged INSTANCE returns (resolution PRESERVED, the acceptance bar) —
while a checking-file expression naming a module-only enum its file never
imports nulls (the leak killed: real tsc sees TS2304 there and never narrows;
killing the narrowing is FAITHFUL, and the readers' null contract is
conservative — members kept, structural verdicts kept). Companion
instrumentation-integrity piece: `globalsForFile`'s proven-visible branch now
reads UNCLASSIFIED (`InstrumentedSymbolTable.getUnclassified`) under
`--passTiming` — a node-keyed flip's legitimate foreign-node hit classifies
CONFLATED against the CHECKING file's locals, which would have polluted the
migration tables; the round-502 "tables measure only un-migrated traffic"
contract now holds for node-keyed flips too. MEASURED (instrumented compiler
profile): conflated 157k → 20,941 (−87%), total keyed lookups 2.71M → 2.36M;
remaining top conflated names are the (c)(iii) value/callee sites (`factory`
12k, `toPath` 500, `createDiagnosticForNode` 317, `isIdentifier` 298, `clone`
276, `map`, `diag`) plus a types.ts type-name tail for (c)(iv)
(NoSubstitutionTemplateLiteral 224 / NumericLiteral / BigIntLiteral /
AccessorDeclaration ~100–133 each); by-pass: checkPropertyAccess 7,038 /
checkCallExpressionTypes 5,743 / checkImplicitAnyParameters 2,744 /
checkProtectedMemberReadAccess 2,198 / checkTypeAssignability 1,765.
Verified: suite green 10,279 → 10,284 (+5 Inv3KindDomainNodeKeyTest — the
leak-kill test FAILS on the pre-flip checker via stash: the leaked resolution
narrowed `x.kind === Kind.A` in a file that never imports `Kind`, hiding the
TS2339 on the un-narrowed union member; 4 preservation controls pass on BOTH
sides: foreign-node alias annotation, imported-alias fallback,
same-module-file, cross-file script); `--listAll` byte-identical on compiler
AND services (46/46 env-legit each); bench row in band (27,173 ms self /
927 MB, −0.5% vs previous, same diagnostics). Out of scope, noted:
`canonicalEnumSymbol`'s memoized `globals[sym.name]` consult (self-guarding
shared-decl identity check, tiny traffic — not in the round-503 family list)
and `resolveNamespaceQualifiedTypeAlias` (currentCheckFileName-keyed
fileResults reads, not a globals consult). CLAUDE.md gotcha extended (the
node-keyed rule + the readers' names). NEXT: INV.3(c)(iii) — the
current-file-keyed value/callee sites.

### QUEUE — work top-to-bottom; promote unblockers per protocol

(Restored 2026-07-12, round 481 — the queue/backlog/inventory sections had been
swept into PLAN-PHASE-5-HISTORY.md by an over-eager session-note trim; they are
LIVE structure, not history. v1's offline-verifiable legs LANDED at round 481, so
M5 is now the active arc per the owner directive; the Post-v1 backlog below is the
"any TypeScript project" horizon and stays parked until the owner re-scopes. The
M1–M3 campaign items still unchecked in the history file (M2.2/M2.3/M3.1–M3.4/M1.12)
hit their re-scoped v1 acceptance bar — "the shapes tsc's source uses" — when the
burn-down reached zero real FPs; reviving their full-completeness form is a
backlog-horizon decision, not queue debt.)

**EP — Emit parity (owner-authorized 2026-07-12: "output parity, including reported errors").**
The offline v1 DoD checked emit COMPLETENESS (all files emitted, exit 0) but not
emit-BYTE parity with tsc. The round-483 emit diff (`scripts/emit-diff-tsc.sh`, xtsc
vs npm `tsc@6.0.3` on the `compiler` profile) found 8/78 byte-identical, 70/78
differing — but **none are miscompiles**; xtsc's output is semantically correct and
runnable. Three systematic families explain nearly all changed lines (sequenced
cheap-first to shrink the diff before tackling the hard cross-file one):

- [x] **EP.3 Logical/nullish-assignment downleveling** (`||=`/`&&=`/`??=` below
  ES2021). DONE round 484 (2026-07-12): `Transformer.downlevelLogicalAssignment` —
  `a ||= b` → `a || (a = b)` etc., with side-effecting property/element receivers
  captured into temps (`(_a = obj())[_b = key()] || (_a[_b] = 6)`, tsc-faithful temp
  naming). ~284 sites in the compiler profile. Gated `effectiveTarget < ES2021`;
  corpus has ZERO files exercising these operators so it's pinned by
  `LogicalAssignmentDownlevelTest` only. KNOWN RESIDUAL: a `??=` target BELOW ES2020
  keeps a native `??` (not further downleveled — ES2020 is the tested/dashboard
  target); close when a sub-ES2020 `??=` case appears.
- [ ] **EP.2 Multi-line expression printer formatting.** Match tsc's operator/`:`
  placement (line-end vs line-start) and indentation when wrapping long
  `||`/`&&`/ternary chains. Mechanical Emitter work, no cross-file dependency, but
  HIGHER corpus-regression risk (touches the printer that the green corpus pins) —
  do it with the emit-diff gate in place and verify the full suite after each step.
- [ ] **EP.1 Cross-module const-enum inlining** (highest impact, ~93% of the changed
  lines in files like utilities.js). xtsc inlines SAME-FILE const enums but keeps
  `mod.Enum.Member` for const enums imported across modules; tsc inlines to
  `VALUE /* Enum.Member */` (numeric AND string-valued). Needs the checker to resolve
  imported const-enum values whole-program. Biggest/hardest (cross-file), collapses
  most of the diff. NOTE: xtsc's form still RUNS (preserveConstEnums keeps the enum
  objects) — this is byte-fidelity, not correctness.
- [ ] **EP.0 Wire the emit-diff gate into the dashboard.** `scripts/emit-diff-tsc.sh`
  exists (reports identical/differing + family signals). Ideal reference is a tsc
  BUILT AT THE PINNED COMMIT (npm tsc adds version noise to the small residual tail,
  esp. emitHelpers.js helper bodies); decide whether to build+cache the pinned tsc or
  accept the version-stable family signals. Re-run after EP.2/EP.1 to track the diff
  shrinking.

Session note (round 484) has the full family breakdown + methodology.

**INV — the M5 architecture-inversion arc (re-scoped 2026-07-13, owner; supersedes
M5.1–M5.7 — mapping and full design in `docs/ARCHITECTURE-RETHINK.md`, READ IT FIRST).**
Ground rules for every INV item: corpus suite green + 8-profile FP floors unchanged +
`--listAll` byte-diff empty for behavior-preserving steps + a bench TSV row per landed
item; decompose into the smallest standalone suite-gated commits; micro-opt rounds
against the flat profile are CLOSED (only an INV.0-evidenced ≥5% single lever may
interrupt the arc).

- [x] **INV.0 Instrument the multiplier.** DONE round 491 (2026-07-13):
  `PassTiming.kt` + non-inline `pass(name) {}` around all 514 init dispatch calls +
  the three counters (`getTypeOfExpression` calls/distinct with per-pass attribution,
  `nodeTypes` cacheable/bypassed/hit, depth-0 flow walks at `flowWalkWithTripCheck`),
  behind the `--passTiming` CLI flag; off-mode byte-identical (listAll A/B + wall
  parity) + suite green (+7 local). The table (round-491 session note): checker-init
  = 83% of wall; top-3 passes 38.6% (property-access / assignability / call-types,
  458k of 595k getTypeOfExpression calls, 84k flow walks — 68% from
  checkPropertyAccess); 474 sub-100 ms passes sum 36.5% = the multiplication tail.
  That note's cost-ordered worklist IS the INV.4 migration order.
- [x] **INV.1 Concurrent front-end — the owner's Flow beachhead (owner-approved
  kotlinx-coroutines-core dependency, 2026-07-13).** Sub-steps: (a) DONE round 492 —
  the dep was already in commonMain; landed the `runCompilerPipeline` expect/actual
  seam (JVM `runBlocking`) + the import-graph crawl as a cold sequential Flow
  (`crawlImportGraph`, ProjectCompiler) with the load-bearing emission-order
  contract documented at the seam (suite +3, listAll A/B byte-identical); (b) DONE
  round 493 — read+decode on `Dispatchers.IO` (`pipelineIoDispatcher`
  expect/actual), extraction parse on `Dispatchers.Default`, bounded
  `flatMapMerge(16)` per frontier (`readAndScanBatch`); resolution + emission stay
  sequential per frontier so emission stays first-discovery order (the binder stays
  sequential; parser audited — no shared mutable state); (c) DONE round 493 —
  corpus green (+6 local) + 3× `--listAll` byte-identical vs the (a) binary; (d)
  DONE round 493 — interleaved A/B −0.8 s (~3%) on the compiler profile + bench
  TSV row.
- [x] **INV.1(e) Kill the double parse — reuse the crawl's parses in the core.**
  DONE round 494 (2026-07-13): `computeParserFlags` (the shared single source of
  truth for the option-derived `Parser` flags, used by the core's parse sites AND
  the crawl), `ParsedSource.preParsed` carrying `PreParsedFile(content, flags,
  sourceFile, diagnostics)`, and the core's multi-file site reusing an entry ONLY
  on an exact content+flags match (else re-parse — reuse is a pure optimization).
  Verified: suite +6 (Inv1PreParseReuseTest — sentinel-tree reuse proof + both
  mismatch gates + driver-path counters), `--listAll` byte-identical on compiler
  AND services, reuse fires 78/78 (`--passTiming` counters), interleaved wall A/B
  neutral within noise on both profiles (the parse leg is small next to the
  checker; the point is one canonical tree per file — the INV.2 enabler).
  CLAUDE.md gotcha: a new option-derived Parser argument must extend
  `ParserFlags`, never a parse site inline, or the match reuses a wrong tree.
- [x] **INV.2 Bind the world** — COMPLETE round 499 (all four sub-items landed;
  the tables' mass consumption is INV.4's migration). Decomposed round 494
  (facts verified in-code:
  `Node` is a sealed interface + ~138 data classes with single-interface supertypes
  `) : Expression/Node/TypeNode/Statement/Declaration/ClassElement`; there is NO
  generic child-walk anywhere; nodes have no parent/id fields; `Symbol.id` is a
  GLOBAL companion `nextId++` (Types.kt:116–127, the ~350-test reshuffle anchor);
  `nodeKey` is the cross-file-colliding `(pos<<32)|end`). Work the sub-items in
  order, one commit each:
  - [x] **INV.2(a) AST identity foundations.** DONE round 495 (2026-07-13):
    `NodeBase` (nodeId/parent, NOT implementing Node — preserves sealed-`when`
    exhaustiveness) + 138 supertype edits + `SourceFile.nodeCount`; canonical
    `forEachChild` (exhaustive sealed `when`) + iterative preorder
    `indexSourceFile` hooked into `Parser.parse()`. Pinned by the jvmTest
    reflection oracle (`ForEachChildOracleTest` — componentN diff over fixtures +
    all 78 real tsc sources) + `Inv2NodeIndexTest` (dense preorder / parent
    chains / copy-unindexed / 30k-chain-on-plain-thread). Suite +10 (10,228),
    `--listAll` byte-identical, wall neutral. Gotchas: NodeBase LUB trap +
    power-assert node-toString trap.
  - [x] **INV.2(b) Pilot consumer.** DONE round 496 (2026-07-13):
    `FlowGraph.flowAt` — nodeId arrays pre-computed from the finished map
    (preserves the nodeKey extent-ALIASING) + identity ownership check
    (synthesized/foreign nodes take the legacy path); 5 checker sites migrated;
    suite +3, listAll byte-identical, wall neutral. JFR verdict: getNode ≈6.7%
    of samples but nodeToFlow only ~4% of that slice (~0.3% wall) — mechanism
    validated; the mass-migration targets are the HOT maps (walk memos, INV.4
    per-node type cache), not more cold tables. `nodeTypes` rejected as pilot
    (structural cross-file keying — INV.5 territory).
  - [x] **INV.2(c) Full lexical binding, additive.** Scope symbols from a SEPARATE
    id space (never the global `nextId` sequence — the reshuffle hazard); existing
    `locals`/`globals` byte-unchanged; new tables unconsumed until INV.4.
    - (i) DONE round 497 (2026-07-13): function-like containers —
      `bindLexicalScopes` (Binder.kt) walks the whole tree iteratively after
      conventional binding, building per-nodeId `LexicalScope`s
      (`BinderResult.lexicalScopes`): SourceFile root aliases file locals,
      ModuleDeclaration aliases the merged exports (chained per dotted segment,
      the B512 rule), the 7 function-like kinds + static blocks get fresh tables
      (type params, params minus `this`, fn-expr self-name, body-top-level
      decls, `var`s hoisted from any block depth). `Symbol.scopeSymbol` mints
      ids ≤ −2; a delta-probe test pins zero global-id consumption. Suite +14
      (Inv2LexicalScopeTest), listAll byte-identical, interleaved wall
      position-balanced +0.8% (noise band).
    - (ii) DONE round 498 (2026-07-13, same session): block-scope containers —
      every Block that is not a function-like's immediate body, for/for-in/for-of
      headers, CatchClause (binds the catch variable, destructuring included),
      SwitchStatement standing in for tsc's CaseBlock (our AST has none — the
      switch EXPRESSION routes to the OUTER scope by hand) — plus class scopes
      (type params; named class-expression self-name; class decorators outer),
      interface/type-alias scopes (type params), and enum scopes (aliasing
      main-bound exports; nested enums bind scope-space members also published
      on the scope symbol's exports, gated `id ≤ −2` so main symbols stay
      untouched). Design dividend: the phase-(i) `isDirectBodyChild` gates for
      block-scoped declarations DISSOLVE into `scope.existing == null` (every
      fresh scope IS the correct nearest block-scope container); `var` gains the
      real `varHoistTarget` walk-up. Block-nested function declarations use
      strict/module semantics (bind to the block). Suite +6 (20 total in
      Inv2LexicalScopeTest — the phase-(i) negative controls flipped to
      positive location asserts), listAll byte-identical, interleaved wall ×6
      both orders neutral.
  - [x] **INV.2(d) B83.5 dissolution pilots.** DONE round 499 (2026-07-13): the
    canonical site — `checkPropertyAccessInStatement`'s ClassDeclaration branch —
    now resolves a block-scoped class via `lexicalScopeSymbol` (parent-chain walk
    over `currentLexicalScopes`, set per file in `checkPropertyAccess`; legacy
    transient synthesis kept as the unindexed-tree fallback). Fidelity proven:
    suite green, listAll byte-identical on compiler AND services; and the pilot
    FIXES a real FP — a block-level `interface B` + `class B` merge now
    contributes interface members to `this` (the transient class-only symbol
    could not see them; measured: the pre-pilot checker emitted a false TS2339).
    Candidate analysis: the other two `Symbol(SymbolFlags.Class, …)` syntheses
    are NOT B83.5 scope-binding shapes and stay — the B511 clodule recovery
    (the class symbol is main-bound then OVERWRITTEN by last-wins, so it is in
    neither table) and the classExpressionAssignment display synthesis (a
    ClassExpression is never a scope binding). Mass consumption of the tables
    (the ~59 synthesis sites, `buildNestedFunctionMap`, the per-pass scope
    machinery) is INV.4's migration proper.
- [ ] **INV.3 Per-file scoping** — decomposed round 500 (facts verified in-code:
  `perFileScope` EXISTS and is already consumed at 4 sites — the 17.32b–e flips
  (TS2663-vs-TS2301, TS2552 candidate pool, resolveExpressionToSymbol, file-root
  TS2304) — so the earlier "never consumed" note was stale; the remaining
  migration surface is ~400 keyed `globals` consults; import aliases free-ride on
  the conflation because the general `resolveAlias` cannot follow ESM-`.js`
  specifiers / `export *` barrels / NamespaceImports — the FLOW-ONLY resolvers
  can, and the general-fallback variant measured a TS2315×466 flood at round
  409). End state: module files resolve own-locals + imports + true globals;
  the `mergeSymbolTable` conflation is retired for module files; the conflation
  ecology is deleted. Also lays the cross-file value-resolution groundwork EP.1
  needs. Work the sub-items in order, one commit each:
  - [x] **INV.3(a) Instrument the conflation dependency.** DONE round 500
    (2026-07-13): `globals` constructed as `InstrumentedSymbolTable` under
    `--passTiming` (plain map otherwise — zero added code on the hottest map);
    every keyed lookup classified against the per-file visibility model
    (TRUE_GLOBAL / SHARED / OWN_LOCAL / CONFLATED / UNSCOPED — see
    `GlobalsLookupClass`) by a classifier installed after init step 1b, with
    per-name + per-pass conflated/unscoped tables in the dump. Measured
    (compiler / services profiles): 2.71M / 4.92M keyed lookups — 71% / 79%
    MISSES (globals probed as a maybe-fallback everywhere), ownLocal
    530k/703k (flips to per-file trivially), CONFLATED 157k/217k concentrated
    in 608/845 names (almost all `types.ts` type names reached through barrel
    imports; services adds the round-442 value-space leaks `parent`/`error`)
    and 14–15 passes with the top 3 = 95–96% of conflated traffic = INV.0's
    top-3 wall passes (checkPropertyAccess / checkCallExpressionTypes /
    checkTypeAssignability), SHARED only 2.9k/4.0k (the chimera ecology's
    cost is per-lookup bail checks, not hit volume), unscoped 71.8k/97.1k
    (checkUnresolvedNames + outside-dispatch). Worklist: (b)'s primitive must
    resolve barrel-imported TYPE names; (c) starts at the three hot passes.
    Suite +5 (Inv3GlobalsLookupTest), `--listAll` byte-identical (off-mode),
    bench row in band.
  - [x] **INV.3(b) Per-file resolution primitive.** COMPLETE round 502:
    - (i) DONE round 501 (2026-07-13): `lookupPerFile(fileName, name)`
      (internal, unconsumed by checker paths) — perFileScope lookup with an
      ImportSpecifier-alias local resolved onward through
      `resolveImportedSymbolGeneral` (the kind-AGNOSTIC generalization of the
      flow-only resolver skeleton: ESM-`.js` strip + `export *` barrels +
      renamed re-exports via the star walk's NamedExports arm + re-import
      hops; memoized `importedSymbolGeneralCache`; ADDITIVE — the three
      kind-specific legacy variants stay untouched, their per-decl
      kind-filter-then-continue semantics differ; never wired into
      `resolveAlias` per the round-409 flood gotcha). KEY TRAP hit and
      pinned: mergeSymbolTable FLAG pollution means an Alias flag cannot
      identify an import alias — a barrel-imported name's TARGET symbol
      acquires the Alias bit from the importing file's merge, so the hop
      test must be declaration-based (`isImportBindingDecl` — the
      isValueExport gotcha applied to alias hopping). Degradations
      documented in the KDoc: unresolvable import / default-import /
      `import * as ns` / `import =` aliases return the alias symbol itself
      (callers keep their existing handling — extend when a (c) flip needs
      them); null strictly means "no per-file meaning" (the conflation
      leak). Pinned by Inv3PerFileLookupTest (direct
      `Checker(options, binderResults)` construction — a first for local
      tests — asserting symbol IDENTITY with the declaring file's binder
      locals across direct-`.js`/barrel/renamed-re-export/own-local/
      script-global/lib shapes + the foreign-module-local null and
      alias-degradation negative controls).
    - (ii) DONE round 502 (2026-07-13): pilot consumer — the TS2315/TS2346
      heritage-base "not generic" gate (`checkTypeArgumentConstraints`, the
      smallest nonzero pass in the (a) conflated-by-pass table with DIRECT
      pass-local consults) resolves through the NEW
      `globalsForFile(fileName, name)`, THE (c) flip shape: return the
      merged-globals INSTANCE whenever the name has a per-file meaning (a
      non-module-only name, or a module-only name the file declares/imports
      — probed via `lookupPerFile`; substituting the primitive's return
      directly would change symbol identity for lib/script names), null
      exactly where the legacy consult leaked a foreign module file's local
      (suppression-only at this site: real tsc never emits TS2315 for an
      unresolvable base). Supporting infra always-on: init 1b2 became
      `computePerFileVisibility` — publishes `moduleOnlyGlobalNames`
      (module-file local names minus lib/script/augmentation-visible), the
      INV.3(a) classifier installs on top of the same sets. Both mirrored
      consult sites flipped together (kept-in-sync contract); the conflated
      branch never touches `globals`, so the `--passTiming` conflated
      tables keep measuring only UN-migrated traffic. MEASUREMENT LESSON
      for (c): the post-flip instrumented run shows the pass STILL at 11
      conflated with the total lookup count EXACTLY unchanged (2,711,601)
      — the pass's conflated traffic comes from DEEPER shared machinery
      (`checkConstraintsForTypeArgs` → `getTypeFromTypeNode`), not the
      direct pass-local consults, which measured ZERO conflated hits on
      the compiler profile. Per-PASS attribution ≠ per-SITE: a hot-pass
      (c) flip needs per-site reasoning about which consults inside the
      pass actually carry the conflated traffic. Suite +7
      (Inv3GlobalsForFileTest — both leak-kill tests FAIL on the pre-flip
      checker, verified via stash; five preservation controls pass on
      both); `--listAll` byte-identical on compiler AND services.
  - [x] **INV.3(c) Flip resolution families onto the primitive** — COMPLETE
    round 509 (all four sub-items landed; conflated 157k → 917, the residue
    being the INV.3(d)-scoped shadow ecology). Decomposed
    round 503 from a MEASURED per-site attribution (a temporary 1:200
    stack-sampling probe on the classifier's CONFLATED branch, ~790 samples,
    probe reverted — evidence in the round-503 session note). The guessed
    site list above was WRONG: `getTypeFromTypeReference`'s globals fallback
    measured ZERO conflated hits and `resolveTypeNameToSymbol`'s Identifier
    entry only ~1.2% — the actual distribution:
    **~82% is ONE family, the enum-discriminant/kind-domain narrowing
    machinery** (`kindDomainKeysFromTypeNode` → `enumSwitchKeysFromTypeNode` /
    `enumMemberKeysOfTypeNode` / `kindDomainTypeDeclSymbol` /
    `resolveEnumSymbolForDiscriminant`, reached from `narrowByCallPredicate`
    via `applyConditionNarrowing`, plus smaller entries from
    `filterUnionByEnumDiscriminant`/`resolveCallOverload`), which resolves
    type names read from FOREIGN AST nodes — types.ts's union-member `.kind`
    annotations — while `currentFileLocals` points at the CHECKING file
    (exactly the top conflated names: JSDocFunctionType / FunctionTypeNode /
    ConstructorTypeNode / MappedTypeNode / ConditionalTypeNode). The
    per-file-correct key there is the NODE'S OWNING FILE (tsc semantics: a
    types.ts annotation resolves in types.ts's scope), NOT
    `currentCheckFileName` — a naive `globalsForFile(currentCheckFileName,…)`
    flip would silently kill narrowing in files that don't import the name.
    The rest: `identifier.fallback` ~3.8k + `propAccess.objExpr` ~3k (tagged
    counts), `checkPrivateMemberAccess`, `getTypeOfIdentifier ←
    isCalleeResolvable`, `resolveFlowCalleeDecl ←
    flowCalleeMayHaveAssertEffects`, `computeRawTypeOfPropertyAccess ←
    getCalleeType`, `typeNodeDefinitelyNonNullish`, `pmrCheckAccess`,
    `mam.objectExpr`/`mam.recvSym` (~63 each). Sub-items, one commit each,
    every flip suite+listAll-gated on compiler AND services:
    - (i) DONE round 504 (2026-07-13): the node-keyed resolution primitive —
      `owningSourceFile(node)` (NodeWalk.kt: parent-chain walk to the
      SourceFile, null for unindexed `copy()`/synthesized/detached nodes,
      defensive hop bound) + `lookupPerFileForNode(node, name)` =
      `globalsForFile(owner.fileName, name)` with legacy-merged-consult
      degradation for ownerless nodes. Additive/unconsumed; pinned by
      Inv3NodeKeyedLookupTest (direct construction — a foreign-node
      annotation resolves under its OWNING file's visibility to the same
      merged instance; an owner without the name yields null (the leak);
      an importing owner keeps resolving; an unindexed copy degrades to
      legacy; lib names never nulled).
    - (ii) DONE round 505 (2026-07-13): the kind-domain/enum-discriminant
      family (~82% of conflated traffic) flipped onto the node-keyed
      primitive — `resolveEnumSymbolForDiscriminant`/`kindDomainTypeDeclSymbol`
      thread a `keyNode` (all 5 call sites), and the alias fallbacks in
      `enumSwitchKeysFromTypeNode`/`enumMemberKeysOfTypeNode` (incl. the
      round-477 import-alias fallback) consult `lookupPerFileForNode(node,
      name)`; `currentFileLocals` stays the first consult everywhere.
      Companion: `globalsForFile`'s proven-visible branch reads UNCLASSIFIED
      (`InstrumentedSymbolTable.getUnclassified`) under `--passTiming`, so a
      legitimate foreign-node hit — CONFLATED against the CHECKING file's
      locals — no longer pollutes the migration tables. Suite +5
      (Inv3KindDomainNodeKeyTest — leak-kill FAILS pre-flip via stash;
      4 preservation controls pass both sides); listAll byte-identical on
      compiler AND services.
    - (iii) **Flip the current-file-keyed value/callee sites** — these read
      names from the CURRENT file's own AST; node-keying by the name's
      IDENTIFIER node is the uniform shape (equals current-file keying for
      own nodes); suppression-only where the name classifies conflated.
      Phase 1 DONE round 506 (2026-07-13): the protected-member cluster
      (pw/pmr/pm, TS2445/TS2446 — `pmrCheckAccess`'s static consult, the
      ctor-init consult, and the `pwResolveClass`/`pmrResolveClass` funnels
      every heritage walker feeds) keys by the name Identifier via
      `lookupPerFileForNode` — the heritage walkers wrap a REAL indexed
      Identifier in a synthesized TypeReference, so keying by `typeName`
      (never the wrapper) needs zero signature changes; a fully-synthesized
      identifier (pmrLocalClass's from-text one) degrades to the legacy
      consult inside the primitive. Suite +5 (Inv3ProtectedNodeKeyTest —
      both leak-kill tests FAIL pre-flip via stash: the leaked resolution
      manufactured bogus TS2445 about a class the file never imports);
      listAll byte-identical on compiler AND services. Phase 2 DONE round
      507 (2026-07-13): the bare-Identifier VALUE/receiver/callee cluster —
      checkPrivateMemberAccess, getCalleeType's Identifier branch,
      resolveFlowCalleeDecl (+ the extracted currentFileNestedPredicateDecl
      preserving round-471 narrowing from the direct==null fallback too),
      resolveNamespaceMemberFnDecl, the three ns-fallback receiver
      resolvers (computeRawTypeOfPropertyAccess /
      resolvePropertyAccessToSymbol / propertyAccessChainIsNamespaceQualified),
      isCalleeResolvable, checkPropertyAccessAssignment's ns base, the two
      mam receiver consults, and the protected-ctor heritage walks
      (findEffectiveConstructorVisibility/classExtendsOrIs) — all keyed by
      the name's own Identifier node. Conflated 20,941 → 10,034 (−52%);
      suite +9 (Inv3ValueCalleeNodeKeyTest — 4 leak-kills FAIL pre-flip via
      stash); listAll byte-identical on compiler AND services; bench in
      band. Phase 3 DONE round 507b (2026-07-13) — (iii) COMPLETE:
      `getTypeOfIdentifier`'s globals fallback node-keyed (the round-442
      by-NAME dead-end does NOT reproduce per-FILE — imports resolve
      through the visibility probe to the same merged instance; pinned by
      Inv3IdentifierTypingNodeKeyTest incl. the import-driven
      initializer-inference control from the round-442 regression family),
      plus a fast path in `lookupPerFileForNode` (non-module-only names
      skip the parent walk — the fallback is ~2M calls/compile). Conflated
      10,034 → 6,165 (cumulative 20,941 → 6,165, −71%); `factory` gone;
      checkImplicitAnyParameters 2,608 → 171, checkUncalledFunctions 968 →
      189. Suite green 10,298 → 10,302 (+4); listAll byte-identical on
      compiler AND services; bench +4.0% single-run = the documented
      box-drift band (~126k parent walks ≈ negligible by construction).
      Residue ~6.2k = the (iv) type-position tail (types.ts type names
      reached via typeNodeDefinitelyNonNullish / resolveTypeNameToSymbol /
      getTypeFromBaseTypeExpression) + ~500 value-name lookups in the
      shadow-detection ecology (registerNestedGlobalShadow*/
      applyBodyLocalShadowing/shadowNestedFunctionNames ask "does a merged
      global collide" — they die with INV.3(d), do not flip them) + tiny
      tail sites (emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated, calleeReturnAnnotationForImplicitAny
      — fold into (iv)'s re-measure).
    - (iv) **Flip the type-position tail**. Leg 1 DONE round 508 (2026-07-13):
      `resolveTypeNameToSymbol`'s Identifier branch + `typeNodeDefinitelyNonNullish`'s
      two fallbacks flipped JOINTLY per the round-507c order constraint, with
      the two call-site trailing `?: globals[name]` fallbacks
      (`getTypeFromTypeReference`, `checkConstraintsInTypeNode`'s TS2315
      emitter) gated to QualifiedName — for Identifier names they were
      byte-redundant pre-flip and would silently RE-LEAK the node-keyed null
      post-flip (the trap now in the CLAUDE.md INV.3(c) entry). The full
      suite caught a REAL visibility gap the flip exposed:
      `lookupPerFileForNode` now grants a node inside a `declare module
      "<relative-spec>"` AUGMENTATION block the augmented module's direct
      named exports (the round-443 rule; the innermost string-named
      ModuleDeclaration is captured during the parent walk, unclassified
      under --passTiming) — without it the flip nulled `UnionType` inside
      services-style `declare module "./types.js"` blocks and this-predicate
      narrowing died (ThisPredicateNarrowingTest's augmentation pin).
      Test-design lesson: the ADDITIVE leak-kill direction is SHADOWED by
      any-degradation (an unresolvable callee annotation degrades the
      assigned reference to `any` — proven with a never-declared `Zorp`
      control — masking the TS18048/TS2322 consumers), so the flow
      observable uses the SUPPRESSION direction: a foreign UNIMPORTED
      NULLABLE alias return-annotation pre-flip types the reference as the
      leaked union and manufactures TS18048 on a closure-captured read;
      post-flip it degrades to any and the leaked TS18048 dies (tsc-faithful).
      Suite +9 (Inv3TypePositionNodeKeyTest — 3 leak-kills FAIL pre-flip via
      stash: the flow TS18048, annotation-position TS2322, TS2315; 6
      preservation controls pass both sides); `--listAll` byte-identical on
      compiler AND services. Leg 2 DONE round 509 (2026-07-13) — **(iv) and
      the whole (c) migration COMPLETE**: getTypeFromBaseTypeExpression's
      Identifier fallback (PropertyAccess last-segment fallback kept legacy —
      the QualifiedName convention), emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated (keyed by the overload DECL's own name
      node — a nested/foreign collision no longer hands TS2793 a wrong-file
      impl pointer), calleeReturnAnnotationForImplicitAny (the
      uniqueFunctionDeclByName fallback still covers program-wide-unique
      names). Suite +5 (Inv3TypePositionLeg2NodeKeyTest — 2 leak-kills FAIL
      pre-flip via stash: a leaked foreign heritage base grafting members
      manufactured TS2741 on `const d: D = {}`, a leaked foreign
      constrained-TP callee manufactured TS2345; 3 preservation controls);
      listAll byte-identical on compiler AND services. RE-MEASURE (compiler
      profile): CONFLATED 6,165 → **917** (−85%; from the pre-migration 157k
      → −99.4%), 97 names / 9 passes, top 318/284/273 — the residue is the
      deliberately-legacy shadow-detection ecology (`diag`/`clone`/`map`/
      `factory` collision questions) + tiny tails, i.e. INV.3(d)'s scope.
      INV.3(d) is UNLOCKED.
  - [ ] **INV.3(d) Retire the merge + delete the ecology.** Stop merging
    module-file locals into `globals`; delete `moduleFileLocalVarNames`,
    `conflatedTypeAliasFiles`, `conflatedInterfaceFiles`,
    `conflatedEnumFileSubsets`, the per-file interface views, and the chimera
    bails — walker-by-walker, each deletion suite- and listAll-gated (each
    removes hot-path work from `checkMemberAccessMissing`).
    **THE RETIRE IS MERGED TO MAIN (round 512): sub-items (i)–(iv) all DONE —
    suite fully green (10,346/0/3) and ALL 8 profiles byte-identical to the
    pre-retire baselines. Remaining: (v) the ecology deletions (the round-473
    Identifier dispatch is already deleted as the (iv) residual fix — its
    removal is what restored the server/harness baselines).** What the branch
    proved (measured round 510): the retire
    must be STAGED BY NAME CLASS — retire only MODULE-ONLY names; SHARED names
    (module local colliding with a lib/script global: `Symbol`/`Node`/
    `Performance` riding the lib names) must KEEP merging until every lib-name
    consumer resolves per-file (the naive full retire measured 861 compiler
    FPs, the module-only cut 34, each traced to an unflipped consult by the
    classifier-MISS stack-probe technique). Sub-items to finish it, in order:
    - (i) DONE round 511 (2026-07-14): the ambiguous-constrained→foreign leg
      REVERTED (declaration-IDENTITY leg kept) — flipped the whole TP family
      (17 tests: the 8 corpus TP pins + 3 local negative controls +
      tsxTypeArgumentPartialDefinitionStillErrors ×2 + WhileTrueDefiniteAssignTest
      ×4, the last two collateral of the over-aggressive classification);
      checker.ts:7358 re-solved at the INFERENCE side —
      `tryInferSingleTypeParamFromArgs` soft-skips a CallExpression arg whose
      type still carries a TypeParam at forReturnType sites (tpSawAnyArg →
      anyType, the pre-retire any-degradation behavior; round-468
      CallExpression gate keeps own-TP identifier args anchoring). Pinned by
      ForeignTpInferenceSoftSkipTest (6); compiler+services listAll
      byte-identical.
    - (ii) DONE round 512 — all 14 corpus multi-file failures fixed (the last 6:
      union-discriminant objlit drill node-keyed; ns-import static TS2339 +
      the dir-relative resolveAlias legs; TS2749 file-keyed with the
      typeSideImportFallback gate; the B585 contextual-display hops; the JSDoc
      ImportType own-specifier resolution; the TS2415 imported-base flip).
      Round-511 record follows:
      heritage/implements walkers node-keyed (interfaceDeclaration3,
      interfaceImplementation6 — incl. the B563 ownership-gate mirror that
      killed the double TS2420), checkConstraintsForTypeArgs keyNode +
      ImportType presetSymbol (divergentAccessorsTypes6,
      unmetTypeConstraintInImportCall), checkTypeNameResolved's leftSym →
      globalsForFile (augmentExportEquals1/2 + decoratorMetadataWithImport…7),
      the mam type-only-winner + namespace-import value-side bail
      (noCrashOnImportShadowing), **and the session's critical find: the
      import hop (`resolveImportedSymbolGeneral`) lacked the DIR-RELATIVE
      resolver leg, so path-shaped extensionless imports (`/proj/src/f1.ts` →
      `./lib`) never hopped and EVERY import-mediated type died on real
      on-disk projects — masked pre-retire by the merge, invisible to the
      `.js`-specifier tsc profiles; found via the EnclosingImportIndexTest
      pins + a MainKt scratch-repro matrix.** REMAINING 6 (per-test roots,
      each needs a probe dig): exportStarFromEmptyModule (X.A.r static
      TS2339 through a local-shadowed star chain),
      allowImportClausesToMergeWithTypes (TS2749 default-import-of-value used
      as type), allowJscheckJsTypeParameterNoCrash (display regression:
      `WatchHandler<any>` unfolds to the fn-type — alias display lost),
      checkJsdocTypeTagOnExportAssignment2 (JS `@type import("./a").Foo`
      excess-prop TS2353 — the JSDoc path's cross-file resolution),
      declarationEmitPrivateSymbolCausesVarDeclarationEmit2 (TS2415 with
      cross-file computed `[x]` private members),
      indirectDiscriminantAndExcessProperty (single-file module: TS2322
      member-vs-discriminant `"foo" | "bar"` — the objlit-member drill's
      resolution; NOT tryEmitObjectVsNamedUnionArg, whose anonymous
      constituents defer to the discriminant walker).
    - (iii) DONE round 512 — the last 4 were 2 real resolver gaps (the
      `export * as` arm in namespaceAliasMemberSymbol; the ns-member objlit ctx
      flips) + 2 pre-retire ACCIDENTAL PASSES fixed tsc-faithfully (all-missing
      all-anonymous union TS2339; primitive-vs-plain-object-bag TS2345).
      Round-511 record follows:
      (Inv3NodeKeyedLookupTest's unindexed-copy degradation → null for
      module-only names; Inv3GlobalsLookupTest's leak assertions inverted to
      the emptied-worklist victory condition); 3 more of the original 9
      flipped as REAL code fixes (EnclosingImportIndexTest ×2 +
      Inv3NodeKeyedLookupTest imports-keep-resolving via the dir-relative hop
      leg; ExtendsImplementsSameClassTest + NamespaceImportQualifiedTypeTest
      via the (ii) walker flips). REMAINING 4, all look like REAL
      suppressions to dig (scratch repros r7/r8 reproduce two):
      ConflatedTypeAliasLeakTest ×2 (own-file `type X` union TS2339 /
      own-file TS2345 both silent — receiver/param resolution in the alias's
      own file returns something unexpected post-retire),
      NamespaceQualifiedBaseInheritanceTest (export-star-as barrel base →
      TS2339 FP returned), BuilderChainAndNsMemberCtxTest (ns-member objlit
      contextual params → TS7006 FP returned).
    - (iv) DONE round 512 — all three residual families closed: deprecate.ts
      `compareTo` (an anyType shadow now BAILS mam instead of falling through
      to the outer import); session.ts protocol.Diagnostic (the round-473
      Identifier DISPATCH into conflatedPerFileInterfaceType REMOVED — the
      first (v) deletion, see the session note); fourslashImpl `'array'`
      (namedUnionMemberCouldAcceptArray hops import aliases). **Full 8-profile
      listAll A/B vs pre-retire main: ALL BYTE-IDENTICAL**; suite fully green;
      branch merged to main.
    - (v) THEN the deletions, walker-by-walker, each suite- and listAll-gated:
      `moduleFileLocalVarNames` DONE round 513 (+2 masked narrowing gaps fixed);
      remaining: `conflatedTypeAliasFiles` (re-key the round-468b
      augmentation-merge compensation first), `conflatedInterfaceFiles`,
      `conflatedEnumFileSubsets`, `conflatedPerFileInterfaceType` + chimera
      bails (heritage threading, type-alias-body owner context, QualifiedName
      dispatch, relation-entry bails).
- [ ] **INV.4 Single-pass check spine.** `checkSourceFileOnce` per-node dispatch;
  migrate walker families in INV.0's cost order — every migration deletes a full-tree
  pass and its private scope machinery. Once ONE authoritative walk state exists, land
  the two things that are unsound today: a per-node expression-type cache, and flow
  narrowing folded into reference typing once (collapsing the rounds-408–479
  per-consumer wiring). The long middle — plan as many small items; corpus + listAll
  gate every family move.
- [ ] **INV.5 Canonical types + explicit instantiation** (absorbs M5.2/M5.3). Intern
  unions/intersections by sorted member-id key; literal interning; explicit mapper
  objects replace the ambient `currentTypeAliasArgs`/TP-scope contexts; instantiated
  members cached ON the `Type.Reference` (delete `resolveGenericPropertyType`
  fresh-minting + its depth-4 OOM cap); `nodeTypes` keyed (node, mapper) — always
  valid; then open `canUseTypeEngine`'s generic gate and DELETE superseded pin walkers.
- [ ] **INV.6 Parallelism** (absorbs M5.4). Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic partition +
  merge via the existing diagnostic sort. Structured concurrency from INV.1.
- [ ] **INV.7 Productization** (absorbs M5.5/M5.6). Native re-enable (the big-input
  GC inversion should largely dissolve post INV.4/5); watch mode driven by a
  file-event Flow; `.tsbuildinfo`-style incremental reuse.

Numeric targets (proposed, doc § 6): post INV.4/5 single-threaded compiler profile
≤ 10 s (≈ JS tsc) + harness RSS ≤ 1 GB; post INV.6 compiler ≤ 5 s on 4 cores;
INV.7 stretch: native cold ≤ 2× tsgo.

### Post-v1 backlog — the "any TypeScript project" horizon (parked 2026-07-03)

The top-to-bottom loop SKIPS this section until v1 (the 8 tsc-source profiles at zero
FPs) lands. None of these block self-compiling tsc. Each returns to the live queue
when v1 lands — or earlier if a live item genuinely needs one (promote per protocol,
with a session note saying why). Item IDs are stable; session notes reference them.

- [ ] **M2.4 DOM libs as an opt-in set** (dom.generated.d.ts is 1 MB+ — measure the
  parse/bind cost; ties into the shared-snapshot design). tsc's sources don't
  reference DOM — post-v1.
- [ ] **M3.0 Conformance generator extension.** Extend `generateTypeScriptTests` with
  a per-category allowlist for `tests/cases/conformance/` (5,907 files; keep all tsgo
  set-B filters). Start with the categories matching M3.1 (types/typeParameters,
  types/typeRelationships, expressions/functions). Each category lands only when its
  failures are triaged into queue items — never leave a category half-red without
  notes. Owner approval (2026-07-02) stands; optionally pull in early as an extra
  regression net if an M3 campaign wants the coverage.
- [ ] **M3.5 Per-file scopes** (Blocker #3: stop merging all file locals into
  `globals`; per-file scope construction with explicit import visibility). Revisit
  before v1 ONLY if dashboard FPs trace to cross-file scope conflation on tsc sources.
- [ ] **M4.1 Full nodenext resolution**: package.json `exports`/`imports` maps,
  symlink/realpath (pnpm layouts), `typesVersions`, package self-references. (The tsc
  repo itself uses relative imports + @types — unused for v1.)
- [ ] **M4.2 Real declaration emitter.** `.d.ts` output for arbitrary code (the corpus
  strips most `.d.ts` sections, so almost none exists today; `declaration: true` is
  table stakes for "any project"). Test bed: conformance decl baselines + self-compile
  d.ts diffing. Pull into v1 only if the owner defines "fully compile tsc" to include
  declaration output.
- [ ] **M4.3 JSX end-to-end** (`jsx: react-jsx`/`react`/`preserve` transforms on real
  React-shaped code).
- [ ] **M4.4 External sourcemaps** (`.js.map` files; inline maps exist).
- [ ] **M4.5 Decision point**: project references / composite / incremental scope
  (tsgo supports them; needed for large monorepos — decide build vs defer with owner).
- [ ] **M4.6 `package.json "type": "module"` module-format detection in
  `ProjectCompiler`** (found compiling zod, 2026-07-07): under `module: NodeNext`
  with a `"type": "module"` package.json, real tsc emits ESM but we emit CJS — the
  `collectPackageJsonTypes` machinery exists only for the multi-file TEST-source path
  and is not wired into the on-disk project pipeline. Repro: zod (see M4.7); the
  emitted CJS only runs in a `"type": "commonjs"` context. Unused for v1 (the
  tsc-source bench project has no package.json → CJS default is correct there).
- [ ] **M4.7 zod as a second dashboard profile** (validated 2026-07-07, round 432
  session note): shallow-clone `github.com/colinhacks/zod`, compile
  `packages/zod/src` (107 files, ~31k LOC) via a `tsconfig.xtsc.json` extending zod's
  real `.configs/tsconfig.base.json` (strict, exactOptionalPropertyTypes,
  noUnusedLocals, NodeNext), include `src/**/*.ts`, exclude tests/benchmarks — real
  tsc 6.0.3 reports 0 errors on it, so every xtsc diagnostic is an FP. Baseline
  2026-07-07: 1,665 FPs (top: TS7006×447 contextual params, TS2694×284 namespace
  members via `export *` barrels, TS7029×211 switch-fallthrough, TS2344×182), 0
  crashes, all 107 files emit, output passes a runtime smoke test. Complements the
  tsc-source profiles: stresses generic method chaining + noFallthroughCasesInSwitch,
  which tsc's own source doesn't.

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).
