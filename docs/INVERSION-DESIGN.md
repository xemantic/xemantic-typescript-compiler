# INVERSION-DESIGN — the post-hoc type oracle: what xtsc can answer today, what needs the inversion, and what the inversion costs

Written 2026-09-01 under the Phase 18 owner directive ((INV.D) — an ANALYSIS item; no
code ships with this document). It answers the question the WebStorm evaluation left
behind: **which of tsgo's 142 API queries can xtsc answer, which cannot be answered
without changing the checker's architecture, and what is the smallest change that
closes the gap.** The two consumers being built this session — the Kotlin externals
generator (EXT.\*) and the LSP server (LSP.\*) — are the concrete query inventory this
design must serve, and both are used below as calibration: neither NEEDS the inversion,
which is itself a finding.

Method inventory source: `tsc/internal/api/proto.go` in microsoft/TypeScript at
`253c5e2` (2026-08-31, sparse clone of `tsc/internal/api` + `tsc/internal/checker`) —
**exactly 142 `Method` constants**. The local `typescript-go-repo` clone (tag
`typescript/v7.0.2`) carries 114 of them; the 31 added since v7.0.2 are the config
parsing family, the emit/transpile family, `batchRequests`, and a dozen more checker
accessors; 3 were removed (`getServerTiming`, `resetServerTiming`, `isTupleType`).
The census below is over the 142.

## 1. The model tsgo serves, read from the source

`internal/api/session.go`: a client holds a **snapshot** (`updateSnapshot` /
`updateTemporarySnapshot`), asks for a **project**, and every request then does

```go
c, done := program.GetTypeChecker(core.WithCheckerLifetime(ctx, core.CheckerLifetimeAPI))
...
return setup.newTypeResponse(setup.checker.GetTypeAtLocation(node)), nil
```

— it checks a checker out of the pool and calls the checker's own **lazy,
node-addressed** method. Nothing is precomputed for the API's benefit; the checker's
architecture (pull-based, memoize-everything: `NodeLinks.resolvedType`,
`SymbolLinks.type`, interned instantiations, cached relations) makes *any node at any
time* a cheap question. `Symbol`/`Type`/`Signature` cross the wire as **handles**
(`SymbolID`/`TypeID`/`SignatureID` from the object's own id), pinned per snapshot and
freed by `release` — a handle does not survive a snapshot update.

Three properties of that model matter for us:

1. **Node-addressed, post-hoc, lazy.** The query arrives AFTER checking (or instead of
   it), names an arbitrary node, and is answered from caches or by computing just that
   answer.
2. **Answers are location-correct**: `getTypeAtLocation` on a reference is the
   flow-narrowed type at that use; body locals resolve to the body's binding.
3. **Handles tie object identity to a snapshot generation.**

## 2. What xtsc retains after a check, and the three lanes it can answer from

Our checker is the opposite shape — ~416 eager passes inside `Checker`'s `init`, with
per-node answers computed under **walk-scoped ambient** (`currentLocalTypes`, cta
frames, `currentFlowGraph`, namespace stacks, TP scopes) that is installed and restored
around each dispatch and is GONE when `init` returns. `CheckedProgram.kt` and
`TypeCapture.kt` document this precisely, and the measured failure of ignoring it is
CLAUDE.md's round 911: asked post-hoc, a body local answers the same-named GLOBAL's
type, a parameter answers `any` — silently.

What IS retained, on the live `Checker` instance (which `Project` keeps):

- the **binder output**: per-file `locals`, the INV.2(c) `lexicalScopes` tables,
  `nodeToSymbol` (pos-keyed — carries the (BIND.1) caveat: reachable only through
  `owningBinderResult`), `importEdges`;
- the **merged `globals`** and every `Symbol` (declarations, `valueDeclaration`,
  `members`, `exports`, `parent`, alias `target`);
- the **interned type graph**: `referenceCache` instantiations, unions interned by
  member-id list (INV.5(a)), `symbolTypes` (the round-778 empty-context-gated memo),
  `declaredTypes`, enum values, `aliasDisplayMap`;
- lazily-buildable structures that work post-hoc: `resolveStructuredTypeMembers`,
  `BinderResult.flowGraph` ((INC.9): built on first ask);
- and the checker's own callable machinery: `typeToString`, `checkTypeRelatedTo`,
  `getTypeOfSymbol` / `getDeclaredTypeOfSymbol` (both work post-hoc — the ambient
  instantiation context is EMPTY at rest, which is exactly the round-778 gate's
  cacheable case), `getApparentType`, union/reference construction.

So there are **three lanes** today:

- **Lane G (graph)** — answer post-hoc from the retained graph + live checker methods.
  Symbol-level and declaration-level questions live here.
- **Lane R (record-during-walk)** — the `CheckedNodeSink`/`CheckedLens` route
  (`CheckedProgram.kt`) and the `TypeCapture` request machinery: the walk itself
  records the answer, under the correct ambient, and the consumer reads it afterwards.
  This is how the KIR backend gets every expression type and resolved overload, and
  how every `Project` semantic query works. Location-correct by construction —
  **but the questions must be stated before (or the walk re-run per question)**.
- **Lane N (narrowed rebuild)** — `Project`'s answer to a question nobody pre-stated:
  re-check just the file with a capture request installed. Costs one narrowed build
  (~94-110 ms floor at 2,401 files, ~200 ms on the tsc profile).

**The WebStorm-shaped gap, stated exactly:** tsgo's API is lane-G-priced for
lane-R-quality answers at arbitrary nodes. We can give lane-R quality only by
pre-stating spans or paying lane N per fresh question. An IDE oracle that asks
thousands of small questions in unpredictable order is priced out of lane N and cannot
pre-state lane R — that, and nothing about raw speed, is why the evaluation stalled.

## 3. The census — all 142 methods

Bins:

- **A** — answerable post-hoc TODAY (lane G). `A°` = answerable but our MODEL diverges
  from tsc's (the divergence is named — these are semantic-fidelity items, not
  architecture items).
- **B** — needs walk-scoped state. `B/R` = the record-during-walk store designed in
  § 4 moves it to A. `B/L` = needs genuinely lazy machinery even with the store
  (post-edit or off-walk questions).
- **C** — not a checker question. `C-host` = host/program layer where xtsc has an
  equivalent surface already (`Project`, `TsConfigLoader`, `Transformer`/`Emitter`).

### Transport, profiling, batching (5 — all C)

| method | bin | note |
|---|---|---|
| release | C | handle lifetime; § 5's handle table needs the same verb |
| batchRequests | C | transport |
| startCPUProfile / stopCPUProfile / saveHeapProfile | C ×3 | profiling |

### Session, snapshot, program (8)

| method | bin | note |
|---|---|---|
| initialize | C-host | `Project.open` |
| updateSnapshot | C-host | `Project.updateFile`/`reloadFile` — same overlay model |
| updateTemporarySnapshot | C-host | overlay without commit; `OverlayVfs` expresses it |
| createProgram | C-host | `ProjectCompiler.build` |
| getDefaultProjectForFile | C-host | single-project `Project` today; multi-config is host work, not checker work |
| getSourceFile / getSourceFileNames | C-host ×2 | retained parses / `Result.fileNames` |
| getSourceFileMetadata | C-host | parser flags + module detection, all retained |

### Config file surface (7 — all C-host, `TsConfigLoader`)

parseConfigFile, parseCommandLine, readConfigFile, parseJsonConfigFileContent,
getConfigFileNames, getConfigSourceFile, getConfigFileParsingDiagnostics — the
CLI/`ProjectCompiler` parse the same inputs through `TsConfigLoader`; exposing them is
API plumbing, no checker change.

### Emit surface (10 — C-host on Transformer/Emitter, two partial)

emit, emitToString, getJavaScriptEmit, getDeclarationEmit, transpileModule,
transpileModuleFromFile, transpileDeclaration, transpileDeclarationFromFile — the
emitting pipeline exists and is corpus-pinned byte-for-byte; `skipEmitOutputs` gating
(round 738) is the one wiring caveat. **printNode / formatNodeForInsertion — partial**:
our emitter is file-oriented; single-node printing needs an entry point, not new
architecture.

### Position→node resolution (10)

| method | bin | note |
|---|---|---|
| getSymbolAtPosition / getSymbolsAtPositions | B/R | position → node (SourceIndex does this today, with the round-910 span rules) → symbol AT that node; free-name references need scope-at-node — retained `lexicalScopes` + parent chain answer most, B83.5's unbound block-scoped declarations break the rest. The store records the walk's own resolution instead |
| getSymbolAtLocation / getSymbolsAtLocations | B/R | same, node-addressed |
| getTypeAtPosition / getTypesAtPositions | B/R | the flow-narrowed, body-correct expression type — exactly round 911's counterexample. THE core oracle query; today lane R/N only |
| getTypeAtLocation / getTypeAtLocations | B/R | same |
| getShorthandAssignmentValueSymbol | B/R | the VALUE symbol of `{ x }` — name resolution at the node |
| resolveName | B/L | arbitrary (name, location, meaning) lookup — not tied to an existing node, so nothing the walk recorded answers it; needs tree-derived scope resolution (§ 4 pillar 1, B83.5 dissolution) |

### Symbol accessors (18)

| method | bin | note |
|---|---|---|
| getTypeOfSymbol / getTypesOfSymbols | A | `getTypeOfSymbol` post-hoc; round-778 gate is satisfied at rest |
| getDeclaredTypeOfSymbol | A | `getDeclaredTypeOfSymbol`; (INC.28)'s alias-scope fix applies |
| getParentOfSymbol | A | `Symbol.parent` |
| getMembersOfSymbol | A° | `Symbol.members` — round 928: a merged interface member carries only the LAST block's declaration; reconstruct via owner declarations for declaration lists |
| getExportsOfSymbol / getExportsOfModule | A° ×2 | `Symbol.exports`; (CHK.73): a module symbol has no TYPE, but its exports table exists |
| getExportSymbolOfSymbol | A | local → exported mapping via export clauses ((INC.51)'s propertyName rule) |
| getMemberInModuleExports | A | exports probe |
| getAliasedSymbol / getImmediateAliasedSymbol | A° ×2 | `resolveAlias` — carries the (CHK.30)/(CHK.73) fallback-leg gaps for some import forms; those are checker-fidelity items, not architecture |
| getExportSpecifierLocalTargetSymbol | A | (INC.51) machinery |
| getSymbolOfSourceFile / getSymbolsOfSourceFiles | A° ×2 | a module file's symbol is not minted as one object here; its `locals`/export view exists — facade work |
| getFullyQualifiedName | A | `Symbol.parent` chain |
| getTargetSymbol | A | instantiation target |
| isReadonlySymbol | A | modifier/declaration-derived |
| getSymbolsInScope | B/L | scope enumeration at a location — same prerequisite as resolveName |

### Type accessors (36)

| method | bin | note |
|---|---|---|
| getSymbolOfType | A | `Type.symbol` |
| getTargetOfType | A | `Type.Reference.target` |
| getFreshTypeOfType / getRegularTypeOfType | A° ×2 | we model NO fresh-literal types ((CHK.59)); both answer the identity — a real fidelity divergence to state, not to hide |
| getTypesOfType | A | union/intersection members (un-distributed intersections per round 777 — the facade must distribute where tsc does) |
| getTypeParametersOfType / getOuterTypeParametersOfType / getLocalTypeParametersOfType | A ×3 | declaration-derived; outer/local split from the enclosing chain |
| getAliasTypeArgumentsOfType / getAliasSymbolOfType | A° ×2 | `aliasDisplayMap` is id-keyed FIRST-WINS where tsc keys the union cache by (members + alias) — (INC.27) proves ours structurally cannot give per-reference alias identity. Answerable; divergent |
| getObjectTypeOfType / getIndexTypeOfType | A ×2 | indexed-access / index-type structure |
| getCheckTypeOfType / getExtendsTypeOfType / getTrueTypeOfConditionalType / getFalseTypeOfConditionalType | A ×4 | conditional-type structure |
| getBaseTypeOfType | A | enum-member → enum, etc. |
| getConstraintOfType / getConstraintOfTypeParameter / getBaseConstraintOfType / getDefaultFromTypeParameter | A ×4 | TP structure; (INC.19)'s write-once constraint discipline is the caveat on POST-HOC FORCING (§ 5 hazard 2) |
| getPropertiesOfType / getApparentPropertiesOfType | A ×2 | `resolveStructuredTypeMembers` + collect; use the capture path's union/intersection distribution, not `getPropertyOfType` |
| getPropertyOfType | A° | round 916: ours answers the ASSIGNABILITY question (null unless on every constituent; first-wins) — the facade must use the member-collection path |
| getIndexInfosOfType | A | index signatures are modeled |
| getApparentType | A | exists |
| getBaseTypes | A | heritage resolution |
| getTypeArguments | A | `Reference.args` |
| getWidenedType / getBaseTypeOfLiteralType / getNonNullableType | A ×3 | existing/trivial operations on the graph |
| getReducedType | A° | we perform NO subtype reduction ((CHK.66)); identity answer, divergence stated |
| getNonMissingTypeOfSymbol | A° | no `missingType` model (exactOptionalPropertyTypes) |
| getNonPrimitiveType | A | intrinsic `object` |
| isArrayType / isArrayLikeType | A ×2 | structural predicates |

### Signature accessors (10)

| method | bin | note |
|---|---|---|
| getSignaturesOfType | A | call/construct signatures off the resolved type |
| getSignatureFromDeclaration | A | `buildMethodType` family |
| getTypeParametersOfSignature / getParametersOfSignature / getThisParameterOfSignature / getTargetOfSignature | A ×4 | round 921 caveat: `Signature.parameters` DROPS binding-pattern parameters and zips positionally — the facade must read the declaration's list (`typeCaptureSignatureParameters` is the reference) |
| getReturnTypeOfSignature / getRestTypeOfSignature / getTypePredicateOfSignature | A ×3 | modeled |
| getTypeParameterAtPosition | A | positional accessor |

### Checker computations at a location (8)

| method | bin | note |
|---|---|---|
| getResolvedSignature | B/R | overload resolution needs argument types under ambient; the walk computes it and KIR's `CallFact` already records exactly this — the store generalizes it |
| getContextualType | B/R° | ours is additionally SEMANTICALLY partial ((CHK.30)/(CHK.39): arity, not type, in several positions) — recording helps only as far as the checker computes it |
| getTypeOfSymbolAtLocation | B/R | flow-narrowed symbol type at a use — the store's recorded reference type IS this |
| getTypeFromTypeNode | A | works post-hoc for annotation nodes (INV.5(c) population); ambient-dependent cases inside generic bodies are B/R |
| getParameterType | A | contextual-free declared parameter type |
| isContextSensitive | A | syntax-driven predicate |
| isTypeAssignableTo | A | `checkTypeRelatedTo` post-hoc; relation caches are id-keyed and live |
| getConstantValue | A | `enumValues` retained |

### Display and node building (5)

| method | bin | note |
|---|---|---|
| typeToString | A° | `typeToString` — first-wins alias display ((INC.27)), B58.1 renders errorType as `any` |
| typeToTypeNode / signatureToSignatureDeclaration | C ×2 | a NODE BUILDER does not exist here and is not on any path this design needs; strings only. Honest refusal, revisit only against a consumer that needs synthesized AST |
| getDocumentationComment / getJSDocTags | A° ×2 | leading comments are retained; a JSDoc TAG MODEL is parse-level work, orthogonal to the inversion |

### References and language service (4)

| method | bin | note |
|---|---|---|
| getReferencesToSymbolInFile / getReferencedSymbolsForNode | A° ×2 | `Project.referencesAt` (lane R/N) — (API.5) completeness + (INC.44) default-export-edge caveats |
| getSignatureUsages | B/R | call sites resolving to a signature — the store's recorded `CallFact`s answer it by scan |
| getCompletionsAtPosition | A° | `Project.completionsAt` (lane N) |

### Diagnostics (7, beyond config parsing)

getSyntacticDiagnostics (A — parser), getBindDiagnostics (A — binder), 
getSemanticDiagnostics (A — `getDiagnostics`, per-file filter exists),
getSuggestionDiagnostics (A° — we do not produce a suggestion category),
getDeclarationDiagnostics (A° — declaration-emit checks partial),
getProgramDiagnostics, getGlobalDiagnostics (A ×2 — categorization plumbing).

### Intrinsics and well-known (13)

getAnyType, getStringType, getNumberType, getBooleanType, getVoidType,
getUndefinedType, getNullType, getNeverType, getUnknownType, getBigIntType,
getESSymbolType (A ×11 — the interned singletons), getWellKnownSymbols /
getWellKnownSignatures (A ×2 — id bookkeeping for the wire protocol).

### Codefix (1)

getImportAdderEdits — C (an LS codefix engine; out of scope for the oracle).

## 3a. Totals, and how to read them

| bin | count | reading |
|---|---|---|
| A (incl. A°) | **94** | answerable post-hoc TODAY from the retained graph — a facade, not an architecture change. 21 of them are A°: answerable with a NAMED model divergence (no freshness, first-wins alias identity, no subtype reduction, assignability-flavored getPropertyOfType, …) |
| B | **15** | need walk-scoped state. **13 are B/R** — the record-during-walk store moves them to A. **2 are B/L** (`resolveName`, `getSymbolsInScope`) — they name no existing node, so only tree-derived scope resolution answers them |
| C | **33** | 5 transport/profiling, 1 codefix, 2 node-builder refusals, and **25 C-host** — host/program/config/emit surface xtsc already has behind `Project`/`TsConfigLoader`/`Emitter`, needing API plumbing only |

**The headline: the gap is 15 methods, not 142 — but the 15 include the four an IDE
oracle asks most** (`getTypeAtLocation`, `getSymbolAtLocation`, `getResolvedSignature`,
`getContextualType`). Everything else is facade work over what the checker already
retains, plus honestly-stated fidelity divergences.

## 4. The design for bin B — the smallest change that closes it

ARCHITECTURE-RETHINK § 3 names the target architecture's four pillars. Held against
today's code:

1. **Tree-derived scope resolution** (binder-attached container locals + parent-chain
   `resolveName`) — PARTIAL: `lexicalScopes` (INV.2(c)) exists and is retained, but
   B83.5 still leaves block-scoped declarations unbound, and body-local/parameter
   bindings live only in walk-time `currentLocalTypes`. Required by the 2 B/L methods
   and by nothing else in the census.
2. **A NodeLinks-style per-node cache** (`resolvedType` / `resolvedSymbol` /
   `resolvedSignature`) — ABSENT. This is the store.
3. **On-demand flow typing over `Flow.kt`'s CFG** — EXISTS mechanically (graphs build
   on first ask, the walk machinery is callable) but every flow answer depends on the
   walk ambient. **Not required for the census**: the walk already computes
   flow-narrowed answers at every reference, so RECORDING them (pillar 2) serves every
   B/R row without re-deriving flow post-hoc. On-demand flow is only needed for
   LAZINESS (answering about nodes in files a build skipped) — a Stage-4 concern.
4. **Canonical type identity** — DONE (INV.5(a) union interning, `referenceCache`).

**Therefore the minimal design is pillar 2 alone, fed by the walk we already run:**

### The store

Per checked file, filled at `checkedSinkEmit`-time (the site that already reconstructs
the round-911 ambient for the KIR sink and the captures — the reconstruction EXISTS and
is priced):

- `nodeTypeId: IntArray` indexed by `nodeId` (per-file, dense — nodeId restarts per
  file, which here is the friend it was the enemy of in round 787): the id of the
  walk's answer for every Expression node, first-wins per the capture rule.
- `nodeSymbolId: IntArray` for identifier/member-name nodes: what the name resolved
  to.
- `callSignature: IntKeyMap<Signature>` for call-like nodes: what overload resolution
  picked (KIR's `CallFact`, program-wide).
- `contextualTypeId: IntArray` where the checker computed one.

Sizing, on the biggest program this repo measures (tsc's compiler profile, 856,962
spine nodes): two `IntArray`s ≈ **6.9 MB** plus the signature map — against a type
graph that is retained anyway (ids resolve through the checker's existing id→Type
lookup; interning means no new Type objects). Per-file arrays keep (BIND.1)-class
cross-file collisions structurally impossible.

Recording cost is NOT estimated here — the measure-first law owns it. Two priced
neighbours bound it: the KIR sink already records expression facts program-wide as a
shipping feature, and (INC.13) measured widening a capture request to a whole FILE at
+9-17 ms/median file *including* ambient reconstruction. The (INC.33) refusal does NOT
apply: it priced the SCOPE channel, O(anchors × globals) — this store is O(nodes) ids
with no per-anchor global repetition. The flag stays off by default and INV.0's law
("false must stay behaviour-free", round 900's strict-argument corollary) gates it.

### The facade

A `TypeOracle` (working name) over (store + retained graph + live checker), exposing
the proto.go-shaped surface with per-snapshot HANDLES: `Symbol.id`/`Type.id` are
per-build and per-thread (INV.6(6c0)), so a handle table maps wire ids to the retained
objects of ONE build generation and dies with it ((INC.46): an id-keyed anything must
never cross builds). `release` drops pins; a snapshot update invalidates the table.

### What each B row becomes

getTypeAtLocation/Position → `nodeTypeId` lookup (flow-narrowed, body-correct, because
the walk was). getSymbolAtLocation/Position, getShorthandAssignmentValueSymbol →
`nodeSymbolId`. getResolvedSignature, getSignatureUsages → `callSignature`.
getContextualType → `contextualTypeId` (as far as the checker computes one — the
(CHK.39) semantic gap survives and is a checker-fidelity item).
getTypeOfSymbolAtLocation → `nodeTypeId` at the reference. resolveName,
getSymbolsInScope → REMAIN OPEN until Stage 3 (B83.5 dissolution); until then the
facade refuses them with a reason, per the house rule that a wrong answer is worse
than a refusal.

## 5. The hazards ledger (what post-hoc serving must not break)

1. **Post-hoc forcing writes first-touch state.** `declaredTypes` has no write gate;
   TP constraints are write-once ((INC.19)); `aliasDisplayMap` is first-wins
   ((INC.27)). A facade query that FORCES something the walk never touched can freeze
   an answer the next walk would have computed differently. Rule: facade reads prefer
   recorded/retained state; any forcing entry point is audited against this list.
2. **`getPropertyOfType` is the wrong helper** (round 916) — collection, not
   assignability.
3. **`Signature.parameters` drops binding patterns** (round 921) — read declarations.
4. **Merged members carry the last block's declaration** (round 928) — reconstruct
   from owner declarations.
5. **`nodeToSymbol` is shared per binder and pos-keyed** ((BIND.1)) — reads go through
   `owningBinderResult`, never a cross-file scan.
6. **Handles die with the build** ((INC.46), INV.6(6c0)).
7. **The store is per-file-array-keyed** — round 787's program-wide nodeId collapse is
   unrepresentable by construction.
8. **Captures never serve `diagnostics`** (standing rule, language-service.md § 3) —
   the store inherits it: a build that recorded types for the oracle is still not a
   diagnostics build for files outside its partition.

## 6. The staged, corpus-gated migration plan

Each stage is many small commits; every commit passes the full corpus suite,
`cost_gate.py` (+0.00% with everything off), and `huge_methods.py --fail-over 0`.

- **Stage 0 = (INV.0), the responsibility split of `Checker.kt`.** No semantics, no
  new features — extraction of the future memoized core into classes with explicit
  inputs, tsgo's `internal/checker` decomposition as the reference map (relater,
  inference, mapper, flow, nodebuilder→display, grammarchecks, services). Order: name
  resolution, `getTypeOfSymbol`/`getTypeOfExpression`, relations, instantiation,
  signatures, flow FIRST; check passes LAST. Every extraction adds a row to
  `docs/inversion-ambient-ledger.md` (ambient fields read/written — the ledger IS the
  input to every later stage, because it is the census of what must become explicit).
  Gates per commit: corpus byte-identical AND cost_gate 0.00%. Record core-module
  compile time before/after (the 110k-line single file is why BUILD.1 exists).
  **The cost-neutrality contract (§ 10) governs every extraction — cost_gate 0.00% is a
  control there, not evidence; the receipts are wall time, allocation, and inlining.**
- **Stage 1 = the store, off by default** — the (INV.1) proposal below. FIRST
  SUB-STEP, one commit: the per-file `nodeTypeId` array alone, filled from the
  existing sink route behind a flag, plus the pin that PROVES it captures what
  post-hoc cannot: a body local whose recorded type differs from the post-hoc
  `getTypeOfExpression` answer (the round-911 shape as a positive control), and a
  cost_gate run showing +0.00% with the flag off.
- **Stage 2 = the oracle facade** over store+graph, refusing what it cannot answer,
  with the handle table. The A°-divergences ship DOCUMENTED per row. The EXT and LSP
  consumers migrate onto it only if it beats what they use (they are served today).
- **Stage 3 = tree-derived scope resolution** — dissolve B83.5 (bind block-scoped
  declarations into the retained tables; ARCHITECTURE-RETHINK INV.2's "Bind the
  world", the separate-id-space rule already proven by INV.2(c)); `resolveName` and
  `getSymbolsInScope` open here.
- **Stage 4 = laziness and invalidation** — narrow re-recording after an edit to the
  (INC.91)-surviving importer closure; per-file lazy answering for files a build
  skipped. Explicitly out of scope for any current consumer.

## 7. What the consumers being built actually need (the calibration)

- **(EXT.\*), the externals generator**: declaration-level types — bin A plus lane R
  through the existing sink. **Needs nothing from this design.**
- **(LSP.\*), the LSP server**: `Project`'s lane R/N surface. **Needs nothing from
  this design** for LSP.1/LSP.2; Stage 1-2 would turn its per-fresh-caret narrowed
  build into a lookup, which is a latency nicety, not a blocker ((INC.90): the
  narrowed query is 93-217 ms).
- **A WebStorm-class post-hoc oracle**: Stages 1-2 for the 13 B/R methods (the four
  hot ones included), Stage 3 for the last 2. That is the honest answer to the
  directive's question.

## 8. Numbering note (do not confuse the two INV series)

ARCHITECTURE-RETHINK § 5 defined INV.0–INV.7 (2026-07-13). Several landed in spirit:
its INV.0 (pass instrumentation) = PassTiming; INV.2 partially (the INV.2(c) lexical
tables; B83.5 NOT dissolved); INV.3 partially (`perFileScope`); INV.4 = the spine (46
handlers; ~445 tail passes remain); INV.5 partially (interning yes, explicit mappers
no); INV.6 = `--workers`; INV.7 partially (the (INC.\*) arc). **The Phase 18 items
(INV.D)/(INV.0)/(INV.1) in PLAN-PHASE-5.md are a NEW series under the 2026-09-01
directive** — (INV.0) is the responsibility split (Stage 0 above), not the old
instrumentation item. This document is the bridge between the two.

## 9. The (INV.1) proposal — APPROVED 2026-09-02, LANDED the same day (record below)

> **(INV.1)** Land Stage 1's first sub-step: a per-file `nodeTypeId: IntArray` store
> (working name `NodeAnswers`), OFF by default, filled at `checkedSinkEmit`-time for
> Expression nodes, first-wins; one flag; pins: (i) the round-911 positive control (a
> body local's recorded type ≠ the post-hoc answer), (ii) production-mode counter at 0
> (round 900's law), (iii) cost_gate +0.00% flag-off; then MEASURE the flag-on
> recording cost on the compiler profile and the 2,401-file shape before any further
> stage is priced. One commit. Implementation does NOT start until the owner approves
> this item — (INV.D) is analysis-only by its own terms.
### 9a. What landed, and what it measured (2026-09-02)

**The store is `NodeAnswerStore` (`NodeAnswers.kt`), one per checked file, keyed by
`nodeId`, filled by `Checker.nodeAnswerRecord` at the SAME hook and under the SAME
reconstructed ambient as the capture and the KIR sink (`typeCaptureVisit`), first-wins,
with the refusal decided BEFORE the resolution.** What is recorded is
`typeCaptureReportedType`'s answer — the capture's own rule (BUG.4 / API.11), so the store
is the capture generalised to every `Expression` with no display rule of its own. The flag
is the `Checker` constructor parameter `recordNodeAnswers` (DATA, like `typeCapture` and
`checkedSink`), whose default reads the process-global `NodeAnswers.enabled` ONCE at
construction; the CLI arms it with `--nodeAnswers` through the `ModeLedger` and prints the
recorded count after `time:`; `BenchMain`'s 8th argument arms it warm.

**One deviation from § 4, and it is a correction of § 4, not a choice:** the slot holds the
`Type` object, not an `Int` id. § 4 wrote "ids resolve through the checker's existing
id→Type lookup" and **no such lookup exists** — `Type.id` is minted by a thread-local
counter and registered nowhere. Under compressed references the slot is the same four
bytes, and reading it is one step shorter. The "6.9 MB for two arrays" sizing stands.

**Pins** (`NodeAnswerStoreTest`, 10, on `TypeCaptureMeasurementTest`'s own fixture and six
positions): the round-911 positive control — a body local recorded `number`, post-hoc
`string` (a different DECLARATION), on one instance; the three parameters and the
`typeof`-narrow recorded correctly where post-hoc reads `any`; the store agrees with a
capture at every requested span; **every `Expression` of the file holds an answer** and
the computation count equals the recorded count; production mode — no store allocated and
**`nodeAnswerComputations == 0`** (round 900's law: the count lives inside the guarded
function); the shipped default is off ((INC.16)'s law); a first-wins / unindexed-node unit
pin.

**Receipts, flag OFF** (the contract's control and its evidence): full suite 16,838 / 0 / 3
(+10, the pins); `cost_gate.py` exit 0, all 20 counters +0.00%; `huge_methods.py
--fail-over 0` clean; warm A/B parent vs this commit on the compiler profile, three
rotated pairs, one JVM per arm: **+0.19 / −0.97 / −3.17 %, B wins 2/3, median −0.97 %,
arm sd 0.93 % / 0.83 % — NOISE-DOMINATED**, which is the expected reading for a change
whose production-path cost is ONE field write per FILE (78 on that profile); no store is
allocated and no hot entry point gained a delegation hop, so the JFR-allocation and
`PrintInlining` arms have nothing to show and were not run. (`ab-warm.sh` itself could not
drive this pair: it shares the CURRENT test classes across arms, and this commit changed
`BenchMain` — the arms were driven by hand with the parent's own test classes.)

**Measurement, flag ON** — the number the design asked for before any later stage is priced
(warm `BenchMain`, 6 warm-up / 8 measured, ABBA across four JVMs per shape):

| shape | off (ms) | on (ms) | delta | recorded | per recorded expression |
|---|---|---|---|---|---|
| compiler profile, 78 files | 5,272 / 5,485 | 6,152 / 6,212 | **+14.9 %** (+804 ms) | 598,455 | **1.34 µs** |
| many-small-2400-dom, 2,401 files | 3,378 / 3,307 | 3,638 / 3,737 | **+10.3 %** (+345 ms) | 232,106 | **1.49 µs** |

Diagnostics identical in every arm (46 / 1). Per recorded expression the price is the same
on both shapes to within 11 %, so it is a per-NODE cost and not a per-file or per-program
one; what it buys is one resolution plus one ambient reconstruction per expression. It is
NOT priced against (INC.13)'s +9-17 ms/file capture widening here — that measured a
per-FILE request and this is the per-EXPRESSION population, ~7,700 expressions per file on
the compiler profile. **Whether the 1.3-1.5 µs is the resolution or the reconstruction is
the first question of Stage 2's pricing** ((INV.1b) in the queue), and nothing in this
stage depends on the answer: the flag ships off.

## 10. Cost-neutrality contract (owner additions, 2026-09-02)

The split (Stage 0 / (INV.0)) and every later stage are graded against this contract.
`cost_gate.py` reads 0.00% for a pure split BY CONSTRUCTION — its counters count calls,
and a split moves none — so it is a control, not evidence. The per-commit evidence is:
`scripts/ab-interleaved.sh` wall time with win rate; a JFR allocation profile
before/after (`scripts/aggregate_jfr.py`); `-XX:+UnlockDiagnosticVMOptions
-XX:+PrintInlining` on `checkArgumentsAgainstSignature`, `getTypeOfExpression` and
`isTypeAssignableTo`, confirming every new delegation hop reads `inline (hot)`; and
core-module compile time before/after.

- Collaborators are long-lived and final; interfaces on hot dispatch only with one
  implementation loaded in production (Kotlin/Native interface dispatch is slower than
  class dispatch — keep the corpus-on-native target in view).
- Objects describing an instantiation (a TypeMapper) are created once per DISTINCT
  instantiation and interned as the cache key — tsc's model.
- Forbidden on hot paths: capturing lambdas as inputs, `by lazy`, boxing through
  generic or nullable seams (`Map<Int,_>`, `Pair`, `Int?`, value classes in generic
  position), `open` classes. The existing `IntKeyMap`/`LongKeyMap` stay the seam types.
- Watch inlining depth (~15 levels) and frame size in the relation recursions;
  re-check deep-stack headroom on the pathological corpus cases after each core
  extraction.
- Success metric for the inversion is SHRINKAGE: checker core line count as a
  STATUS.md dashboard row. Reference points: tsc ≈ 50k lines (one file), tsgo 60,479
  across 25 files, `Checker.kt` **191,155** today (`wc -l`, 2026-09-02).
