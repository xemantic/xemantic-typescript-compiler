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

**Round 482 (2026-07-12) — M5.1 performance, first post-v1 perf item after the mandatory
fresh JFR pass.** One commit (b72ebcf2). The fresh round-482 harness JFR (45.8 s / 3,620
samples) confirmed the round-481 flat profile with the discriminant `.kind` key-domain
family as the top set-churn source: `--callers-of AbstractCollection.addAll` and
`HashSet.add` both put `kindDomainKeysOfType` at the top (~29 `addAll` + ~24 `HashSet.add`
samples), because a union like `Node` is guard-narrowed at many read sites and each call
re-scanned every member's `.kind` annotation and built fresh mutable sets.
- **Fix (byte-identical, two behavior-preserving moves):**
  - Memoize `kindDomainKeysOfType` by Type.id (new `kindDomainKeysOfTypeCache`, mirroring
    `discriminantKindKeysCache` exactly — empty-set encodes "unreadable", and the same
    `canonicalEnumSymbol` cross-path determinism guarantee its `.kind`-annotation readers
    already carry makes a global Type.id memo safe, per the round-425 canonical-key gotcha).
  - Hoist the target's `.kind` key domain out of the negative type-guard filter loop:
    `kindDomainProvesNotSubtype(member, targetNode)` was re-scanning `targetTypeNode` once
    per union member; new `kindDomainKeysExceed(t, targetKeys)` takes the pre-computed
    domain so the filter computes it once per narrowing call.
- **Verification:** harness diagnostics byte-identical (95, per-position `--listAll` diff
  empty vs HEAD); full corpus suite green 10,155 → 10,157 (+2 local
  KindDomainMemoConsistencyTest — repeated negative guards on the same union with different
  targets narrow independently, no stale cross-site memo contamination; + the negative
  control that a genuine subtype still collapses); clean same-machine A/B (3 runs each,
  daemon up) harness self **44.35 → 41.5 s (−6.4%)**; bench TSV row 41.1 s, 95 errors.
- **NEXT M5 leads (from this JFR):** the node-keyed AST scans `kindDomainKeysFromTypeNode`
  / `enumSwitchKeysFromTypeNode` / `enumMemberKeysOfTypeNode` (3.7% / 3.1% / 2.3% inclusive)
  are the deeper cost but need file + node-identity keying — the round-481 (e) hazard (pos
  collides across files; result depends on `currentFileLocals`), so a pure memo is unsafe;
  `checkMemberAccessMissing` (9.2% inclusive / 4.3% self — the biggest walker);
  `emitTs18048ForClosureCapturedUndefinedReceiver` 1.6% self (audit its per-node work); and
  the broad flow-walk HashMap/HashSet churn (M5.2 allocation discipline).

**Round 481 (2026-07-12) — HARNESS REACHES ZERO REAL FPs: ALL EIGHT PROFILES AT ZERO REAL
FALSE POSITIVES — the v1 FP exit criterion is met.** FIVE fixes in 1 commit (b77b1afc),
harness 100 → 95 (the remaining 95 = TS2591×66 process/require + TS2304×10
BufferEncoding/global + TS2584×6 console + TS2503×6 + TS2593 `it` + harnessGlobals
TS7006×3 chai + `Error.captureStackTrace` TS2339×2 + a BufferEncoding-consequence
TS2322 — ALL env-legit offline artifacts). Zero additions by per-position diff; all
seven other profiles re-verified at their 46 floors. Suite 10,142 → 10,155 (+11 local
tests across 5 new files, 0 regressions).
- **Spread-of-any poisons at the TYPE level:** getTypeOfObjectLiteral returns `anyType`
  when a spread's type is any/error (tsc semantics) — harnessLanguageService:758's
  `typingsInstaller: { ...nullTypingsInstaller, globalTypingsCacheLocation }` FP'd the
  per-property leaf, and suppressing only the leaf UNMASKED the coarse whole-object
  relation at the var decl (same-position masking); the type-level rule makes every
  consumer agree. The round-445/472 per-site bails stay as guards.
- **Chimera structural sibling:** `sourceSatisfiesConflatedTargetPerFileView` (relation
  entry + missing-props arg emitter) — a source with NO heritage link relates to a
  chimera target when it satisfies SOME declaring file's per-file view
  (editorServices:3212 CachedDirectoryStructureHost vs ParseConfigHost, whose fakesHosts
  class merge demanded a required getCurrentDirectory; optional on the interface tsc sees).
- **String-layer union members are display strings (no `@`):** a named member falls to
  the bottom `return false` — `namedUnionMemberCouldAcceptArray` resolves a TYPE-ALIAS
  member's body for array-ish forms (`ArrayOrSingle<T> = T | readonly T[]`) so
  fourslashImpl:1214's `expected = [expected]` relates; Array-EXTENDING interfaces
  deliberately keep firing (their extra members make a bare literal a genuine error —
  the first cut's heritage arm failed its own negative control).
- **Overload contextual selection:** resolveCallOverload treats an un-inferred bare
  TypeParam param as matching (tsc infers it), and the property-access pass's
  multi-overload contextual branch adopts the overload arg-matching SELECTS
  (strictSelect — definitive winners only, and only when ≠ sigs[0], keeping the legacy
  heuristic byte-identical otherwise) — documentsUtil:30's `.reduce((meta, key) =>
  meta.set(…), new Map())` typed `meta` as string via the first overload's callback.
- **As-cast member context:** `castTypeDeclaresFnMember` + `uniqueTypeAliasInclNamespaces`
  — an as-cast receiver whose TYPE declares the assigned member as a method AST-side
  signals ctx-unknowable (round-474 mechanism) when the resolved receiver poisons to any
  (harnessIO:379's `(result as CompileFilesResult).repeat = newOptions => …`; the
  namespace-nested alias intersects a barrel-unresolvable `compiler.CompilationResult`).
- **Emit/crash legs verified same session — the OFFLINE-VERIFIABLE v1 DEFINITION OF DONE
  IS FULLY MET:** all eight profiles emit every program file with exit 0, no
  crashes/hangs/OOMs (compiler 78/78, tsc-cli 80/80, jsTyping 84/84, deprecatedCompat
  81/81, typingsInstallerCore 88/88, services 252/252, server 274/274, harness 312/312
  via the bench row — self 50.5 s, +0.9% noise band, RSS 1.89 GB).
- **M5.1 fresh JFR pass (same session, harness profile, 50.5 s / 4,070 samples) — the
  round-434 "flat profile" verdict still holds (top self = HashMap.getNode 6.4%), with
  these ranked leads:** (a) **HashMap/HashSet churn ~20%+ inclusive aggregate**
  (getNode 9.2%, put 7.8%, HashSet.add 6.9%, putVal 5.4% — the flow-walk memos and
  per-walk set copies; M5.2 territory); (b) **checkMemberAccessMissing 8.6% inclusive
  / 4.1% self** — the single biggest walker; (c) **the barrel-star resolution chain
  resolveBarrelStarTarget → resolveModuleSpecifierRelative → normalizePath ~5%**
  (every star-chain walk re-resolved every hop) — **FIXED same session:
  `barrelStarTargetCache` (Tier-2 pure memo over frozen fileResults), byte-identical
  diagnostics, harness self 50.5 → 46.2 s (−8.5%, bench row)**; (d) **the symbol-lookup
  family `findSymbolInAllNamespaceScopes` → `findSymbolInExports` ~7% inclusive** — the
  Transformer probes `resolveConstEnumMemberAccess` for EVERY dotted expression chain,
  and any head resolving nowhere (a B83.5-unbound function-body local) fell through
  `resolveNamePath` to a full-program recursive namespace scan — **FIXED same session:
  `namespaceScopeSymbolCache` (Tier-2 memo keyed by name; stored null = not found),
  byte-identical diagnostics, harness self 46.2 → 45.0 s (a further −2.6%)**; (e) the
  discriminant key-domain AST scans `kindDomainKeysFromTypeNode` +
  `enumSwitchKeysFromTypeNode` ~6% combined (per-node memo candidates); (f)
  display-string building (typeToString 3.4% + joinTo/split ~3.5%); (g)
  `emitTs18048ForClosureCapturedUndefinedReceiver` 1.3% self (a niche emitter — audit
  its per-node work). `getTypeParamInfo` 1.7% self is a smaller flat-profile entry.
  Caller attribution: normalizePath ← resolveModuleSpecifierRelative (137/188);
  resolveModuleSpecifierRelative ← resolveBarrelStarTarget (82 direct + 117
  deep-recursion truncated); checkMemberAccessMissing ← checkSinglePropertyAccess
  (254/351); findSymbolInExports ← findSymbolInAllNamespaceScopes (143/143);
  resolveConstEnumMemberAccess ← Transformer.transformExpression (118/131). Recording:
  `$SCRATCH/r481-harness.jfr` (session-local; rerun per the docs/parallel-caching.md
  how-to — the profile shifts after every fix). **FOUR Tier-2 memos landed same session
  (all byte-identical diagnostics, full suite green): `barrelStarTargetCache`,
  `namespaceScopeSymbolCache`, `typeParamInfoCache` (getTypeParamInfo — full-program
  binder-table double scan per generic ref), `starExportVarDeclCache`
  (resolveExportedVarDeclThroughStars — the emptyArray conflation path). Net harness
  self 50.5 → 44.8 s (−11.3%). LESSON re-confirmed: a Tier-2 memo field consulted
  during init (getTypeParamInfo runs via collectUninitializedVars) MUST be declared
  BEFORE `init` — the first getTypeParamInfo cut NPE'd on a null cache field; the
  crash surfaced as `COUNT=0` on the whole profile (a run-wide crash, not a diff).**
- **NEXT (post-v1):** M5 continues — the remaining flat-profile leads are HashMap/HashSet
  churn in the flow-walk memos (M5.2 allocation discipline) and the discriminant
  key-domain per-node AST scans (context-sensitive on `currentFileLocals`, so a
  file-keyed memo, not a pure one). byte-correct emit diffing vs real tsc stays
  network-gated (needs node + typescript). Candidate follow-ups: delete superseded pin
  walkers; re-audit the env-legit floors once a node-types story exists.**

**Round 480 (2026-07-12, same session as 479) — SIX fixes in 1 commit (629561bb). Dashboard:
harness 109 → 100 with the 480b heritage batch (ddad6077): an imported conflated heritage base resolves per-file (conflatedPerFileViewForContext) + the derived-vs-chimera bails (conflatedChimeraTargetSourceHasPerFileBase, relation entry + arg emitter — the first cut manufactured 2 ParseConfigFileHost FPs, caught by per-position diff). ~5 real left; every step zero-additions by per-position diff; all seven
other profiles hold their 46 floors. Suite 10,132 → 10,142 (+10 local across 5 new test
files, 0 regressions); bench row +2.1% self (noise band).**
- **Never-inference:** a no-return block body whose every path THROWS infers `never`
  (tsc fall-off-never; gated on blockHasAnyReturn so a bare `return;` keeps void) —
  evaluatorImpl's `import: _id => { throw … }` vs `import(id): Promise<…>`.
- **Contextual literal returns:** allArgumentsMatch accepts an inline arrow arg whose every
  RETURN is a string literal ∈ the param's literal-union return
  (argFnLiteralReturnsSatisfyParam; block bodies must always-return) — vfsUtil `_walk`
  callbacks widened `"retry"`/`"throw"` to string and FP-rejected BOTH overloads (TS2769 ×2).
- **Fresh literals at the per-prop ARG leaf:** the B326 keep-the-literal rule applied where
  an objlit arg's member is drilled per-property (`type: "file"` vs `type: "file"` displayed
  as 'string' ⊄ 'string', fourslash organizeImports/getCombinedCodeFix).
- **tsc's SUBTYPE rule in negative narrowing (the vfsUtil symlink-never family):**
  `missingVsOptionalProvesNotSubtype` — a union member LACKING a property the guard target
  declares OPTIONAL is not a subtype (tsc assumeFalse uses the subtype relation, where
  missing-vs-optional FAILS; assignability passes) → `!isDirectory(node)` keeps
  FileInode/SymlinkInode, whose only differences from DirectoryInode are optional props.
  Wired into BOTH the union filter and the single-type negative return; the
  structurally-identical corpus pin (instanceofWithStructurallyIdenticalTypes — no optional
  distinguishers) is unaffected.
- **Any-element source REST params accept-all:** signatureRelatedTo's B196 expansion
  rejected `(...args: any[]) => void` → `(project: Project) => void` by comparing the ARRAY
  type contravariantly when the element gate returned null (incrementalUtils:656).
- **NEXT (harness @100, 5 real + harnessGlobals×3 likely-env-legit):** documentsUtil:30
  (reduce<U> accumulator contextual typing — both overloads arity-applicable so B476
  bails, yet `meta` typed as T; probe); harnessIO:379 (as-cast member assignment ctx —
  minimal repro passes, whole-program only; probe); harnessLanguageService:758 (spread of
  barrel-unresolvable `nullTypingsInstaller` in a var-decl objlit MEMBER value — the
  emission is emitPerPropertyMismatchesForObjectLiteral per the probe; needs the
  round-445 unresolved-spread bail there); fourslash:1214 ('array' vs ArrayOrSingle<…>
  union — the "array" display suggests an un-typed array literal vs an alias union);
  editorServices:3212 (CachedDirectoryStructureHost vs chimera ParseConfigHost param —
  no heritage link, tsc satisfies STRUCTURALLY; the arg emitter would need to compare
  against the per-file view when the param is a chimera).**

**Round 479 (2026-07-12 — the harness burn-down continues) — SEVENTEEN fixes across 3
commits (0a5668b2 / 982431aa / 08cb0bab). Dashboard: harness 145 → 109 (−36; real ~14 left
excl. env-legit + harnessGlobals×3 reclassified likely-env-legit); every step zero-additions
by per-position listAll diff; all seven other profiles re-verified at 46. Suite 10,098 →
10,132 (+34 local, 13 new/extended test files, 0 regressions); harness self −7.2% (TSV row).**
- **Conflation family (the big one):** `conflatedPerFileInterfaceType`'s QualifiedName arm
  gains (a) an ImportSpecifier branch — a namespace imported by NAME through a barrel
  (`import { protocol } from "./_namespaces/ts.server.js"` → the star chain →
  `export { protocol }` of an `import * as protocol` → its module → the interface's
  declaring leaf; client.ts protocol.TextSpan/Location ×5) — and (b) the NamespaceImport
  branch follows a BARREL target's `export *` chain to the leaf (`ts.ParseConfigHost`
  through harness `_namespaces/ts.js` vs fakesHosts' `class ParseConfigHost` chimera;
  cleared the ParseConfigHost/TS2740/TS2739/TS7053/Classification family ×5).
- **Namespace-import aliases ARE namespaces:** checkTypeNameResolved bails TS2833/TS2702
  for an `import * as X` alias (symbolIsNamespaceImportAlias) — a case-differing sibling
  namespace manufactured "Did you mean 'Compiler'?" ×4; and an import-equals alias to a
  ns-member (`export import parse = ts.getPathComponents`) resolves the CALLEE through its
  own target (importEqualsNamespaceMemberCalleeType), never a same-named merged-globals fn.
- **Module-scope isolation on cross-file merge walkers:** TS2433 (namespace-split) and
  TS2475 (const-enum use) gate on isModuleFile — two module files' same-named decls never
  merge in real tsc (namespace Debug vs class Debug; const enum State vs class State).
- **Narrowing/CFA:** the narrowed-single-Object TS2339 emission bails on index signatures
  (CompilerSettings ×3); a closure that is an ARGUMENT of a call rooted at `root?.` is
  non-nullish inside (incrementalUtils ×2, closureGuardedByOptionalChainRoot); property-
  access `.x!` strips nullish under the round-456 all-concrete gate (8-profile A/B clean —
  the historical deferral's hazard is covered by the M3 machinery landed since).
- **Smaller families:** ctor var-decl-nested `this.x =` assignments count for TS2564 (×3 +
  chains); ANY-optional-decl member truthiness for TS2774 (the System class+interface
  chimera pollutes isOptionalProperty's first-decl read); statement-position `yield x;`
  draws no TS7057 (tsc expressionResultIsUnused); bare specifiers never resolve RELATIVE
  under nodenext (TS1192 'path' → src/compiler/path.ts); for-of loop vars shadow in the
  call-types walker (evaluatorImpl); extends+implements-same-class TS2720 skip (bare-args
  gated — the ungated cut regressed extendAndImplementTheSameBaseType2, caught by the
  suite); `new Function(...)()` is an untyped call (tsc isUntypedFunctionCall); method/ctor
  bodies run applyBodyLocalShadowing in the property-access pass (the round-447 trap —
  fourslash Refactor.actions ×4 via refactorProvider's leaked `const refactors` Map).
- **REVERTED:** TS7006 suppression for arrows assigned to an any-typed receiver's member —
  contradicts the round-464 pin (an any contextual type provides NO contextual signature →
  tsc fires); harnessGlobals ×3 reclassified likely-env-legit (chai unresolvable offline).
- **NEXT (harness @109, ~14 real):** the ParseConfigHost/ServerHost RELATION residuals
  (services:1790 objlit vs ParseConfigFileHost, editorServices:3212, harnessLanguageService
  754/758 — the System/ServerHost chimera on the relation side, not resolution); vfsUtil
  TS2769 ×2 + :860 symlink-on-never; documentsUtil:30 reduce-accumulator overload
  selection; fourslash 636/3411 'string' vs 'string' identity displays; evaluatorImpl:337
  (throw-only arrow infers void, tsc infers never); incrementalUtils:656; harnessIO:379.**

**Round 478 (2026-07-11, same session as 477 — the HARNESS burn-down begins) — FIVE fixes,
harness 217 → 145 (−72; TS2339 66 → 13, TS7006 15 → 4, TS2341 6 → 0; every step
zero-additions by per-position diff). Suite 10,090 → 10,098 (+8 local across 2 new test
files, 0 regressions).**
- **Fix 1+2 (tsc getAssignmentReducedType — the fourslash reassignment idioms, ~37 FPs):**
  `narrowByAssignmentRhs` gains THREE assignment-reduction arms, all placed BEFORE the
  round-416 non-nullish reset (a both-arms-non-nullish ternary would otherwise reset to the
  FULL declared union first): (a) `x = typeof x === "tag" ? { … } : x` (both condition
  orders) drops the tag's members via narrowByTypeOfGuard when the pass-through arm is the
  bare reference and the replacement arm an object literal; (b) a plain OBJECT-LITERAL RHS
  drops the declared union's primitive/nullish members (`if (typeof source === "string")
  source = { files: … };` — evaluatorImpl); (c) an ARRAY-LITERAL RHS keeps only array-like
  members (Array/ReadonlyArray refs, tuples, intersections containing one — `if
  (!ts.isArray(expected)) expected = [expected];` incl. the `readonly T[] & {plus}` brand).
- **Fix 3 (lexical private access, TS2341 ×6):** `checkStaticPrivateMemberAccess` accepts a
  same-file access POSITIONALLY inside the declaring class declaration — a function nested
  in a class method reads the class's static privates legally (fourslash
  `TestState.nLinesContext` inside `textWithContext`); the enclosing-class threading resets
  at nested-function boundaries (this-rebinding), which is right for `this` but wrong for
  lexical accessibility.
- **Fix 4 (`import * as ns` guards, TS2339 ×16):** `resolveNamespaceMemberFnDecl` gains a
  NamespaceImport branch — resolveAlias never resolves namespace-import aliases (round 444)
  and the ImportSpecifier-keyed flow resolvers skip them, so
  `ts.isDocumentRegistryEntry(entry)` through the harness `.js` barrel silently never
  narrowed. Resolve the import's own specifier → target file → locals + `export *` chain;
  memoized (`nsImportMemberFnCache`, declared before `init`). REPRO LESSON: the free-fn
  receiver variant "passed" because `entry` was silently UNTYPED (resolution failure reads
  as success) — the interface-METHOD receiver variant typed it and exposed the guard; when
  a repro "passes", confirm the types actually RESOLVED before believing it.
- **Fix 5 (namespace-callee locals, TS7006 ×11):** the same resolver feeds
  `initializerCtxTypeForImplicitAny`'s namespace-callee arm — `const compilerHost =
  ts.createCompilerHostWorker(…)` types the local from the callee's return annotation, so
  `compilerHost.getSourceFile = (fileName, …) => …` arrow params inherit the CompilerHost
  member context.
- **NEXT (harness @ 145, ~55 real):** harnessIO `CompilerSettings` index-sig ×3 (namespace-
  nested interface with a string index sig — the TS2339 should be suppressed) + TS2833
  `compiler.CompilationResult` ns-import-in-TYPE-position ×4 (the type-position sibling of
  fix 4); client.ts protocol `Location` ×5 (conflation family); compilerImpl TS2564 ×3;
  fourslash 829/839 (`.definitions` on a union), 1946 (`string | Range`), 4045 (`Refactor
  .actions`); incrementalUtils TS18048 ×2; editorServices 1461 TS2774 (`this.host.realpath`
  optional-method truthiness) + 3212.**

**Round 477 (2026-07-11 — SERVER REACHES ZERO REAL FPs, SEVEN of eight profiles) — FIVE
fixes, server 51 → 46 (real FPs 5 → 0; the remaining 46 = TS2591×43 + TS2304×2 `global` +
TS2584 console, all env-legit offline artifacts). All five residuals were CONFLATION-family
(Blocker #3) on server/protocol.ts vs compiler/jsTyping declarations.**
- **Fix A (utilities:7827 TS2366):** a same-named `const enum NewLineKind` in protocol.ts
  (Crlf/Lf) AND compiler types.ts (CarriageReturnLineFeed/LineFeed) merges into a 4-member
  chimera, so the switch covering the complete compiler pair read non-exhaustive. THREE
  coupled pieces: `conflatedEnumFileSubsets` (per-file member-name sets keyed by the merged
  symbol id) relaxes the exhaustiveness comparison (`coveredExhaustsConflatedEnumSubset` —
  covering ONE file's complete member set is exhaustive; real tsc never merges module-scoped
  enums); `unionDiscriminantKeysFromAnnotation` — the AST-side fallback when the union
  members are ALSO alias-shadowed (protocol's `type CompilerOptions = ChangePropertyTypes<…>`
  shadows the interface via last-wins → the resolved member is any/error and the
  resolved-type walk bails), resolving each member via `interfaceDeclsForCurrentFileView`
  (own decl, else the import followed through `.js` barrels); and `checkImplicitReturns` now
  sets `currentCheckFileName` per file — it was STALE from whatever pass ran before, so
  conflation-aware resolution in that pass silently used the wrong file.
- **Fix B (editorServices:1253 TS2353→TS2345):** four pieces: `annotationAgrees` (the
  topLevelConstStringValues builder) accepts a namespace-import-QUALIFIED alias annotation
  (`const CloseFileWatcherEvent: protocol.CloseFileWatcherEventName = "closeFileWatcher"` was
  POISONED out of the index); `enumMemberKeysOfTypeNode`'s QualifiedName arm falls back to
  `resolveNamespaceQualifiedTypeAlias` so `eventName: protocol.XEventName` member annotations
  yield `lit:s:` keys; `checkExcessProperties`' UNION nested descent drills the
  DISCRIMINANT-matched constituent (tsc getMatchingUnionConstituentForObjectLiteral — was
  first-with-the-prop, so `data: { id }` checked against LargeFileReferencedEvent's data);
  the then-morphed missing-props TS2345 (the chimera demands protocol's `event`/`body`)
  needed the round-468 `objectLiteralMatchesSomeConflatedDeclaration` rule in the union-arg
  structural gate.
- **Fix C (session:475 TS2322):** a QUALIFIED `ns.Name` naming an interface SHADOWED by a
  same-named `type Name` alias in a DIFFERENT module file (session's own `type Event` vs
  protocol's `interface Event`) resolves through the namespace import to the target module's
  per-file view (`conflatedPerFileInterfaceType` gains a filesOverride param fed by
  `interfaceDeclFilesAll`; QUALIFIED-only so bare refs keep the round-443/444 ecology).
  PAIRED with the `isConflatedInterfaceRefNode` QualifiedName-arm extension (nodeTypes bypass
  — a null-context first touch would cache the alias resolution) and a precise-verdict early
  return in checkReturnAssignability (`qualifiedAliasShadowedTarget` + engine-confirmed
  fresh-literal pass — the STRING fallback re-resolves "Event" by bare name to the shadowing
  alias and re-FP'd; the round-436c trap).
- **Fix D (session:3994 TS2322):** `objectLiteralSpreadsConflatedInterface` — an objlit
  SPREADING a conflated-interface-typed value is unknowable (the spread source's fn-return
  shell cached the chimera eagerly, B198; `{ ...textSpan, contextStart, contextEnd }` mixed
  compiler's `{start: number, length}` into protocol's `{start: Location, end}`); wired at
  the ternary-arm + direct-return paths. Suppression-only.
- **Fix E (typingInstallerAdapter:233 TS2345):** the round-476 "B516 never-param / callee
  union" theory was WRONG — the Diagnostic-init probe showed the emission is the DEFAULT
  clause's `assertType<never>(response)` (line 233 IS the default clause): the
  default-exhaustiveness never fired because EVERY member's `kind: ActionSet`-style
  annotation read null — the CHECKING file only IMPORTS the merged const+type-alias names,
  so `currentFileLocals["ActionSet"]` is an IMPORT alias whose declarations are
  ImportSpecifiers and the bare-alias arm of enumMemberKeysOfTypeNode bailed.
  Fall to the merged GLOBALS symbol's TypeAliasDeclaration (mergeSymbolTable's addAll keeps
  the declaring file's alias in the polluted list).
- **Process:** the round-472 probe technique earned its keep twice (the XP233B stack
  falsified round 476's theory in one run; XP233C found the null keys in one more); one
  self-inflicted incident — a `--rerun-tasks` warnings check launched DURING the chain's
  MainKt run clobbered its classes (the documented NoClassDefFoundError gotcha), costing one
  re-run.
- **NEXT: harness (@225 last measured) — the LAST profile for v1.**

**Round 476 (2026-07-11, same session as 475) — TWO more server fixes in 2 commits
(b4bbf29c / 2e568f9e). Dashboard: server 54 → 51 (real FPs 8 → 5). Suite 10,075 → 10,078
(+3 local, 0 regressions).**
- **Fix 1 (b4bbf29c, jsTyping SafeList ×2):** the round-474 probe verdict resolved in one
  println probe — `globals["ReadonlyMap"]` is NULL (a KNOWN_GLOBALS name with NO modeled
  interface), so jsTyping's `type SafeList = ReadonlyMap<string, string>` body resolves
  errorType. `returnSourceSatisfiesFileLocalAliasBody` treats an UNRESOLVABLE file-local
  alias body as UNKNOWABLE and suppresses (the resolved target is the known-wrong merged
  chimera; FN-not-FP — only reached in the conflation context, and a resolvable failing
  body still fires per the negative pin). The PROPER fix (model ReadonlyMap in the
  embedded lib) is a lib change with the "and N more" count-shift trap — deferred.
- **Fix 2 (2e568f9e, typingInstallerAdapter:224):** `overloadNarrowedArgType`'s union path
  retries with `getNarrowedTypeForReferenceFollowLoopEntry` when the plain walk washed
  back to the declared union at a FlowLoopLabel (STRUCTURAL wash gate — branch labels
  mint fresh identical unions, the round-424 lesson). The ActionSet case reads `response`
  AFTER its requestQueue while-loop, so the switch-case narrowing was lost and both
  updateTypingsForProject overloads FP-rejected. Un-narrowed union args vs a narrower
  single overload turn out to be a PRE-EXISTING conservative FN (couldn't pin a small
  negative control — the dashboard diff is the both-directions evidence).
- **NEXT (server @ 51, 5 real):** compiler/utilities:7827 TS2366 (minimal union-param
  switch repro is CLEAN — the real site's barrel-imported CompilerOptions/PrinterOptions
  or cross-file NewLineKind differ; probe); editorServices:1253 TS2353 + session:475
  TS2322 (both repro clean minimally — the real sites involve protocol.ts's same-named
  conflated event/Event interfaces; probe the emission with the Diagnostic-init trick);
  session:3994 (TextSpan chimera-spread, known deep); typingInstallerAdapter:233 (the
  callee resolves to a UNION of Project's and ProjectService's watchTypingLocations →
  B516 combined sig intersects params to `never` — probe how `this.projectService`
  resolves). Then harness (@225 — TS2339×66/TS2322×16/TS7006×15).**

**Round 475 (2026-07-11, the SERVER burn-down continues) — FIFTEEN fixes in 3 commits
(f1e2589a / 258aae3d / ccc33547). Dashboard: server 77 → 54 (−23; real FPs 31 → 8 excl.
TS2591×43 + TS2304×2 `global` + TS2584), harness 255 → 225 (−30 riding, TSV rows recorded),
services re-verified UNCHANGED at its 46 env-legit floor. Suite 10,045 → 10,075 (+30 local
across 5 new test files, 0 regressions).**
- **Fix batch 1 (f1e2589a, the completions `Request` family ×8, Blocker #3):** the round-474
  "needs a probe first" verdict DISSOLVED into a minimal 2-file repro (protocol `interface
  Request` + completions-local `type Request = <union of inline type literals>`) — no probe
  needed. Three coupled extensions: `returnSourceSatisfiesFileLocalAliasBody` iterates EVERY
  union member of the return annotation (was sole-non-nullish); TypeLiteral alias-body
  constituents check via the new `objectLiteralExactlySatisfiesTypeLiteralNode`; and
  checkMemberAccessMissing's union branch suppresses when a MULTI-member receiver union
  contains an own-file conflated alias member (the chimera makes discriminant narrowing
  unmodelable) and the property exists on some member/alias constituent.
- **Fix batch 2 (258aae3d, nine families):** arg-path spread-of-any (session:1469);
  `registerBindingPatternParamLocals` — binding-pattern params register element names in the
  assignability pass with annotation member types (optional → `| undefined`), closing the
  destructured-SHORTHAND cross-file fn leak (editorServices:2852 `enable`, session:4063
  `isWriteAccess` — the round-473 residual); `getReturnTypeOfNewExpression`'s
  constructor-interface branch gated to NON-class callees (class instances DO carry
  constructSignatures inherited-first, so `new ConfiguredProject(...)` typed as `Project` —
  project:2764, editorServices:2897/3428); `A && B` = falsy(A) | B via isDefinitelyFalsyMember
  (root-caused from the 2 builder.ts FPs the binding-pattern fix unmasked — `let oldState =
  oldProgram && oldProgram.state` had dropped `| undefined`); TS2391 optional bodyless methods;
  TS2416 mutable literal-override widening; TS2564 ctor switch clauses; property-init
  foreign-TP bail (maybeBind, project:564); rhsIsDefinitelyNonNullish returns true for a
  NonNullExpression RHS outright (project:1694 — the unwrap-and-descend classified by the
  INNER call's nullable annotation).
- **Fix batch 3 (ccc33547, four families):** `<literal-union> || "literal"` keeps the right
  literal when the kept left is all string-literals (editorServices:2848);
  resolveMemberPropertyType UNION-root arm — `(A|B).p = A.p | B.p` (union-annotated param
  member switch; repro clean, the REAL utilities:7827 stays — barrel-imported interfaces need
  a probe); REST-param targets provide unbounded args (server/utilities:30);
  conflatedInterfaceFiles extended to cross-file CLASS X + `interface X` merges (canMerge
  Class+Interface makes it a chimera — scriptVersionCache's `class TextChange`) + the
  round-468 ARG-side objectLiteralMatchesSomeConflatedDeclaration rule wired into the RETURN
  path (services/utilities:2353).
- **Also landed (repro-verified, real site deferred):** const-string discriminant keys in the
  objlit-vs-union member selection (enumMemberKeysOfTypeNode TypeQuery arm +
  bare-Identifier const value arm) — the minimal eventName repro passes; the real
  editorServices:1253 additionally involves protocol.ts's same-named conflated event
  interfaces.
- **Process notes:** (a) the round-474 probe-first verdicts keep dissolving into minimal
  repros — ALWAYS try the 2-file repro before instrumenting; (b) one interim regression
  (2 builder.ts FPs from the binding-pattern registration) was caught by the per-step listAll
  diff and root-caused to the missing `&&` falsy rule IN the same batch — the diff-per-step
  discipline pays; (c) a `java` CLI run during a background gradle compile dies SILENTLY
  (classes clobbered mid-load) — sequence them.
- **NEXT (server @ 54, 8 real):** compiler/utilities:7827 TS2366 (probe — the minimal
  union-param switch repro is clean; barrel-imported CompilerOptions/PrinterOptions or the
  cross-file enum differ); jsTyping:81/88 SafeList ×2 (probe — why the alias body
  `ReadonlyMap<string, string>` resolves errorType at the call site; suspect the structural
  nodeTypes collision); editorServices:1253 (conflated event interfaces + const-string
  discriminant interplay); session:475 (repro clean — real site involves the protocol
  namespace-qualified conflated `Event`... probe) + session:3994 (chimera-spread, known);
  typingInstallerAdapter:224 (case-body read AFTER a while loop — suspect the FlowLoopLabel
  wash) + :233 (callee resolved to a UNION of the two watchTypingLocations methods → B516
  intersected param `never` — probe the callee resolution). Then harness (@225 —
  TS2339×66/TS2322×16/TS7006×15 on harness-only files).**

**Round 474 (2026-07-11, the SERVER burn-down continues) — EIGHT fixes in 4 commits
(8c65858a / dc105f56 / 5134ea7c / + the literal-write commit). Dashboard: server 104 → 77 (−27;
real FPs 58 → 31 excl. TS2591×43 + TS2304×2 `global` + TS2584), harness 299 → 255 (−44 riding
the same fixes, TSV rows recorded), every step strictly-removals by listAll diff at the ~46 s
normal band. Suite 10,024 → 10,045 (+21 local across 8 new test files, 0 regressions).**
- **Fix 1 (extractSymbol.ts ×7 + goToDefinition, Blocker #3):** a type-alias BODY referencing a
  CONFLATED interface name resolves in its DECLARING file's view
  (`resolveTypeAliasBodyWithOwnerContext`, identity-matched via localTypeAliasIndex) + the
  `isConflatedInterfaceRefNode` TypeOperator arm (`readonly Diagnostic[]` cached a
  chimera-element resolution in nodeTypes — plain `Diagnostic[]` worked, the readonly wrapper
  didn't: the missing-arm tell). **MEASURED DEAD-END folded into the gate: UNRESTRICTED owner
  threading regressed +41 server FPs and 3.4× wall (104 → 145, 48 → 164 s) — an owner file that
  itself DECLARES one of the referenced conflated interfaces (importTracker's own
  `interface AmbientModuleDeclaration` inside its leaked `type SourceFileLike` union) must keep
  the merged-chimera status quo; the round-443 display-keyed suppression ecology depends on it.**
- **Fix 2 (executeCommandLine.ts ×4, Blocker #3):** an imported CALLEE colliding with an
  unrelated same-named exported function (`formatMessage` compiler vs server/session) resolves
  through its OWN identity-matched import + `export *` chain (`importedCalleeFunctionType`, the
  fn sibling of round 473's `importedTopLevelVarAnnotationType`); gated to a genuine collision
  (globals valueDeclaration ∉ the import target's own decls) so non-collision paths stay
  byte-identical. Negative pin: a wrong arg against the CORRECT signature still fires.
- **Fix 3 (rules.ts ×5):** `keyof X` where a `type X` SHADOWED the `interface X` via the
  last-wins Interface+TypeAlias merge (protocol.ts's `ChangePropertyTypes<…>` aliases → anyType
  → `keyof any` = `string | number | symbol`) recovers the literal key union AST-side
  (`keyofShadowedInterfaceKeyUnion`, own + extends-inherited names; bails on index signatures /
  unresolvable bases). The invalid-key positive control proves the union is real.
- **Fix 4 (editorServices.ts ×4 TS7006):** a body local initialized from a `this.<method>(…)`
  call types from the ENCLOSING class's own method return annotation (the implicit-any walker's
  this-call arm — `getTypeOfExpression(this)` is anyType per B101), PAIRED with the
  ctx-unknowable rule: a target member ANNOTATION naming a conflated alias-shadowed interface
  (`sourceFileLike?: SourceFileLike`) marks the RHS contextually typed instead of propagating
  the wrong resolution. The union-receiver gate needed explicit member resolution
  (getPropertyOfType has NO Union branch — the round-419 gotcha, found by probe).
- **Fix 5 (completions.ts:1251):** a return annotation naming a conflated `type X` THIS file
  declares checks against the TRUE file-local alias BODY
  (`returnSourceSatisfiesFileLocalAliasBody`). jsTyping:81/88 (the SafeList target) STAY: the
  alias body `ReadonlyMap<string, string>` resolves errorType at this call site while ReadonlyMap
  resolves fine program-wide — a resolution residual needing a probe.
- **Fix 6 (session.ts 1827/1907/2424):** a ternary ARM spreading an any/error-typed value is
  `any` in tsc — the round-445 spread-poison rule extended to checkConditionalReturnBranches.
  session:3994 stays (its spread resolves to the conflated-TextSpan chimera, not any).
- **Fix 7 (server/utilities.ts:83):** a POSITIVE equality against a literal narrows a BARE
  supertype primitive to the literal (tsc narrowTypeByEquality) — narrowUnionByLiteral's
  non-union branch returned the primitive unchanged, so `base === "tsconfig.json" || base ===
  "jsconfig.json" ? base : undefined` FP'd `string` vs the literal-union return.
- **Fix 8 (project.ts:2286):** a LITERAL property write whose literal the target's declared
  union annotation SYNTACTICALLY contains is always legal (`this.autoImportProviderHost =
  false` vs `AutoImportProviderProject | false | undefined`) — BOTH this-prop write paths
  (the varTypes string path and checkPropertyAccessAssignment) widened the literal first;
  both now consult the round-436c syntactic membership proof.
- **MEASURED & REVERTED (the completions `Request` theory):** resolving a conflated name to the
  ctx file's OWN top-level `type` alias inside conflatedPerFileInterfaceType cleared NOTHING
  (the Request FPs come through a different path) and added 3 returnValueCorrect.ts
  `'Info | undefined' ⊄ 'Info | undefined'` identity-mismatch FPs — the round-444/445 Info
  first-touch ecology. The completions Request/CompletionData ×8 family needs a probe first.
- **Session note:** the session was restored mid-flight (`--continue`) after the harness process
  died; the suspected in-flight OOM was actually the perf regression of the then-unbisected
  fix-1 (164 s run) — bisecting the two coupled edits found the TypeOperator arm clean and the
  threading responsible for both the +41 and the slowdown.
- **NEXT (server @ 77, 31 real):** completions Request/CompletionData ×8 (probe the emission
  path first — the reverted theory shows it is NOT the bare-name TypeReference resolution);
  session.ts residual (475 `protocol.Event` qualified conflated-alias, 3994 chimera-spread,
  4063 shorthand leak, 1469); project.ts ×8 (399 TS2564, 470/471 TS2391, 564, 1694 TS18048,
  2764 new-expr base, 2914 TS2416); editorServices residual ×5;
  jsTyping SafeList ReadonlyMap-errorType probe; typingInstallerAdapter ×2; compiler/utilities
  :7827 TS2366; services/utilities:2353. Then harness (last 299).**

**Round 473 (2026-07-11, the SERVER burn-down — the three big conflation families) — THREE fixes
in 2 commits (ad660db5 / ef8107f5). Dashboard: server 227 → 104 (−123; real FPs 181 → 58 excl.
TS2591×43 + TS2304×2 `global` + TS2584), harness 429 → 299 (−130 riding the same fixes);
services and compiler UNCHANGED at their env-legit floors (46 each — the zero-real profiles did
not regress). Suite 10,013 → 10,024 (+11 local across 3 new test files, 0 regressions); server
self-compile 43.6 → 48.6 s (+11% — the conflated-name nodeTypes bypass; acceptable, noted for M5).**
- **Fix 1 (ad660db5a, const-string discriminants — session/typingInstallerAdapter/editorServices
  ~35 FPs):** tsc's jsTyping/shared.ts idiom discriminates unions on CONST-typed strings
  (`switch (response.kind) { case EventTypesRegistry: … }` + `eventName: typeof
  ProjectsUpdatedInBackgroundEvent` members). FOUR coupled pieces: the Binder MERGES
  Variable+TypeAlias (the `type ActionSet = "action::set"` + `const ActionSet: ActionSet`
  same-name pair — the const previously OVERWROTE the alias symbol, so every `kind: ActionSet`
  annotation resolved errorType and the narrowing filters kept every member); the NEW
  program-wide `topLevelConstStringValues` index (unambiguous top-level const strings;
  value-space competitors POISON, type-space aliases/interfaces don't compete) feeds
  `constStringCaseLiteralType` in narrowBySwitchClause + the default-exhaustiveness block +
  narrowByDiscriminantProperty; `typeQueryConstStringLiteral` recovers `typeof <const>` member
  annotations that widened to string/any in both discriminant filters; and
  checkMemberAccessMissing gained the SIBLING-discriminant suppression (`switch
  (event.eventName)` narrows the BASE `event` and projects `.data` — the walked path
  "event.data" is invisible to the FlowSwitchClause).
- **Fix 2 (ad660db5b, per-import barrel VAR resolution — the `emptyArray` family, 29 FPs,
  Blocker #3):** compiler files importing core.ts's `emptyArray: never[]` through
  `./_namespaces/ts.js` resolved server/utilitiesPublic.ts's `emptyArray:
  SortedReadonlyArray<never>` — the merged globals symbol's winner is FILE-ORDER-DEPENDENT.
  `importedTopLevelVarAnnotationType` (getTypeOfSymbolWorker's Alias branch ONLY — the
  round-409 resolveAlias-flood rationale stands) resolves the alias through its OWN
  ImportDeclaration (IDENTITY-matched in the structural index — same-shaped specifiers live in
  files whose barrels resolve DIFFERENTLY) + the new `computeExportedVarDeclThroughStars`
  (FILE-AST star following — the merged symbol's declarations list is polluted, so symbol-side
  resolution can't pick the right file's decl).
- **Fix 3 (ef8107f5, per-file views of CONFLATED interfaces — the protocol.ts family, ~64 FPs,
  Blocker #3):** `interface Diagnostic`/`TextSpan`/`HighlightSpan`/`Request` declared in BOTH
  server/protocol.ts and compiler-or-services types.ts merge into a chimera. References now
  resolve the per-file view their context selects (see the commit message + the CLAUDE.md
  gotcha for the FIVE coupled pieces: conflatedPerFileInterfaceType with the
  defer-to-general-resolver rule pinned by errorWithSameNameType; the transient-symbol
  perFileInterfaceType; heritage context threading; the isConflatedInterfaceRefNode nodeTypes
  bypass incl. COMPOSITE nodes; the conflatedCtxMissing no-cache flag + conflatedOwnerFile
  member-annotation context; the conflatedMergedPairRelated relation/arg-emitter bails).
  The round-468 `&&`-return arm now EMITS the falsy-remainder error directly (tsc types
  `count && obj` as `0 | {…}`) — the chimera-era coarse path had reported it by accident
  (the negative control was pinning an accidental mechanism).
- **Lessons:** (a) the `nodeTypes` cache is keyed by the STRUCTURAL node — same-shaped
  annotation nodes in DIFFERENT files collide, which the per-file resolution exposed (bypass
  for conflated names, including composites: TypeLiteral members resolve EAGERLY in
  getTypeFromTypeLiteral); (b) a Diagnostic-init probe on a 4-file repro beats armchair
  resolution-tracing — three rounds of wrong valueDeclaration theories fell to one
  `XPROBE-ID` print showing `globals=SortedReadonlyArray<never>`; (c) fn types cache their
  shell EAGERLY (B198), so null-context param annotations stay chimeras — that's what the
  relation-level conflatedMergedPairRelated bail is for.
- **Residual (documented):** session.ts:4063 resurfaced with a different display — a
  destructured-param SHORTHAND (`isWriteAccess`) leaking to a same-named cross-file function
  in the return-objlit path (previously masked by spread-of-any); the round-429
  currentParamBindingNames shadowing does not reach this pass's shorthand-value typing.
- **NEXT (server @ 104, 58 real):** session.ts:4063 (the shorthand leak above); the remaining
  session.ts objlit targets (Event/EmitOutput/QuickInfo/RefactorEditInfo — union-of-protocol
  targets); completions.ts Request/CompletionData ×9; editorServices ×9; project.ts ×8;
  rules.ts keyof ×5; executeCommandLine Logger ×4. Then harness (@ 299 — its own files +
  TS2339×69/TS2345×37 tail).**


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

**M5 — Performance (starts at v1 compliance — the 8 tsc-source profiles compile clean)**

- [ ] **M5.1 Profiling grid**: JFR/async-profiler over the project corpus (cold CLI,
  warm in-process via BenchMain, RSS); publish flamegraph findings in a session note
  before optimizing anything. **Partially done early (rounds 432–434, branch
  `perf/flow-import-resolution`, owner-directed): two JFR rounds removed the four
  dominant hotspots — self-compile ~593 → ~20 s, zod 6 → 3.5 s, byte-identical
  diagnostics. Tooling: `scripts/aggregate_jfr.py`; method + remaining flat-profile
  leads + tsc/tsgo comparison: `docs/parallel-caching.md`. A FRESH JFR pass is
  mandatory before the next perf item — the profile shifts after every fix.**
- [ ] **M5.2 Allocation discipline in the relation engine** (type interning /
  canonicalization — replace the documented fresh-mint caps like the
  `getPropertyTypeForRelation` depth bound with proper sharing).
- [ ] **M5.3 Cache effectiveness under scope contexts** (today `nodeTypes` is bypassed
  whenever any resolution context is active = recompute on every generic-heavy path).
- [ ] **M5.4 Parallel per-file checking** via the existing-but-unused `CheckerPool`
  (LinkStore side-tables already keep binder output immutable for this).
  **Design decided (2026-07-07, owner discussion): share-nothing workers à la tsgo —
  NO shared/concurrent maps; cache-tier rules, determinism requirements, the phased
  plan (share-nothing → shared frozen lib slice → single-flight pure computations),
  and the evaluated-and-declined cachemap dependency are all in
  `docs/parallel-caching.md`. Read it BEFORE starting this item.**
- [ ] **M5.5 Incremental compilation** (`.tsbuildinfo`-style reuse; the M2.1 shared
  lib snapshot is the first piece).
- [ ] **M5.6 Native target re-enable + tune** (linuxX64 was disabled in c7e3535f;
  native already wins <10 kLOC — fix the big-input inversion, likely GC/allocation).
- [ ] **M5.7 Numeric targets** (proposed; confirm with owner at M5 start): warm ≥ tsc
  throughput on 500k-LOC real code; cold CLI ≤ 1.5× tsc on medium projects; RSS ≤ tsc;
  stretch: approach tsgo on native.

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
