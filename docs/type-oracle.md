# The type oracle — post-hoc, node-addressed answers over one finished check

**(INV.2), Stage 2 of `INVERSION-DESIGN.md`, landed 2026-09-02.** This page is the
consumer-facing contract of `TypeOracle` (core module, `TypeOracle.kt`): how to get one,
what each row answers from, and — row by row — where its answer diverges from tsc's
model. The design rationale (why the walk-scoped rows had to be RECORDED rather than
computed post-hoc, the census of tsgo's 142 methods, the hazards) is in
`INVERSION-DESIGN.md` §§ 2-5 and is not repeated here.

## 0. In one paragraph

A `TypeOracle` is the query shape of tsgo's `tsc/internal/api/proto.go` — `getTypeAtLocation`,
`getSymbolAtLocation`, `getResolvedSignature`, `getContextualType`, and the symbol / type /
signature accessors once one is in hand — over ONE build of ONE program text. The four
walk-scoped rows are served from the (INV.1) per-file store, which the check spine filled
under the correct ambient as it walked past each node; every other row is answered from the
retained type graph through the live checker. Two rows are REFUSED with a stated reason
until Stage 3. An oracle is closed by its owner on any edit, after which every question is
refused rather than answered stale.

## 1. Getting one

In-memory program (sources in hand; path-shaped names so relative imports resolve):

```kotlin
val built = typeOracleOf(
    mapOf("/proj/lib.ts" to libSource, "/proj/main.ts" to mainSource),
    CompilerOptions(useRealLibs = true),
)
val oracle = built.oracle          // valid until oracle.close()
val diagnostics = built.diagnostics
```

A project on disk (tsconfig, crawl, module resolution):

```kotlin
val holder = OracleHolder()
val result = ProjectCompiler(SystemVfs).build("/path/to/project", noEmit = true, oracleHolder = holder)
val oracle = holder.oracle!!
```

An open `Project` (the `-project` module) hands one out directly, which is the shape an
editor-style host wants — it shares this project's parses, so `nodeAt` and `typeAt` address
the same objects, and it is CLOSED by every edit:

```kotlin
val project = Project.open("/path/to/project")
val oracle = project.typeOracle()!!           // valid until the next edit
val type = oracle.typeAt(project.nodeAt(file, offset) as Expression)
```

All three run the SEQUENTIAL checker (a `--workers` partition rebases ids per worker, so two
workers' stores would silently conflate) and all three refuse `recheckOnly` (a partition walks a
subset, so the store would be a subset with nothing to say so).

**Ask about the trees the oracle itself hands out** (`oracle.files`, or — through `Project` —
that project's own parses, which are the same objects). A store is keyed by `nodeId` WITHIN
its file and read behind a BOUNDS CHECK ALONE: node identity is never compared, so a node
of another parse of the same file name does not "answer nothing", it answers **whatever the
previous build recorded at that index**. Measured (INV.2b) on an eight-line file, three
shapes of the same program:

| what changed | what the stale store does |
|---|---|
| nothing (text re-set unchanged) | answers CORRECTLY everywhere — the parse cache is content-keyed, so the "new" tree is the same tree |
| an annotation retyped in place (ids unmoved) | answers EVERY identifier, and reads `number` for each name a file now declaring `string` moved |
| a statement inserted above (ids moved) | two confidently WRONG types, three nulls past the old array's end, the rest right by coincidence |

So an oracle must be closed on ANY edit and re-obtained — which `Project` does for you.

## 2. Validity and closing

- One build, one program text. `Type.id` / `Symbol.id` are per-build and per-thread
  (INV.6(6c0)); nothing an oracle hands out may be compared with another build's objects.
- `close()` on ANY edit. There is no finer invalidation, deliberately ((INC.46)): an oracle
  cannot tell a stale answer from a fresh one, so the owner of the program must not ask.
  After `close()` every query throws `OracleRefusal`, and the handle table is emptied.
- `handles` is the per-build handle table of the design's § 4: `pin(type|symbol|signature)`
  → a `(generation, id)` handle, `type(handle)` etc. to resolve, `release(handle)`. A handle
  of another generation, a released one, or one asked after `close()` is refused.
- **One id space, therefore one thread.** Ask an oracle from the thread you obtained it on.
  `Type.id` / `Symbol.id` come from THREAD-LOCAL counters (INV.6(6c0)) and most rows here can
  MINT — `typeOfSymbol` resolving a symbol the walk never needed, `propertiesOfType`
  resolving a member table, `isAssignableTo` instantiating a generic's members. A thread
  that has never compiled starts at 1, so its mints land INSIDE this build's own ids and the
  checker's id-keyed tables then confuse two distinct objects. A query from such a thread is
  REFUSED rather than answered.

  Measured (INV.2b), on a program whose build minted 612 types: seven representative rows
  mint nothing on either thread (everything they ask about is already interned — which is
  why a naive probe reads a reassuring zero), while resolving a lib type the program never
  mentions mints 7-82 types per row. On the building thread those landed at ids 612-806; on
  a fresh thread the same queries minted ids **1-70**, and `anyType.id` is **10** — a fresh
  type carrying the intrinsic `any`'s id, which is round 825's `--workers` race reached
  through a retained oracle.

  The guard is the COUNTER, not the thread: the thread that RAN the checker is
  `runWithDeepStack`'s `xtsc-deep-stack` thread and is already dead, and what the handoff
  does is write the advanced counters back to the CALLER. So a thread whose sequences
  dominate the build's is admitted — which includes the caller, a thread that has built
  something else since, and a parallel worker — and only a thread that could mint into the
  build is refused.

## 3. The rows

Bins are the census's: **A** answerable from the retained graph, **A°** answerable with a
named model divergence, **B/R** recorded during the walk (the store), **B/L** refused until
Stage 3. "Divergence" names what a consumer expecting tsc's answer will see instead.

### 3a. Recorded during the walk (B/R — the rows a post-hoc query cannot compute)

| proto.go | oracle | answered from | divergence |
|---|---|---|---|
| `getTypeAtLocation` | `typeAt(node: Expression): Type?` | store: the walk's own type at the node, flow-narrowed and body-local-correct; a member NAME answers the type of its access (BUG.4), a member declaration name its declared type ((API.11)) | none in the recorded rows; `null` for an unwalked / synthesized / `copy()`-ed node |
| `getSymbolAtLocation` | `symbolAt(node): Symbol?` / `symbolsAt(node): List<Symbol>` | store: a free name through the walk's scope chain, a member name through the receiver's type (union-distributed, round 916's collection rule), a declaration name as its own symbol, an object-literal key as the literal's own property | an import is answered as its ALIAS (ask `aliasedSymbol`); `this`/`super`, an import/export specifier's `propertyName` and a computed member name answer nothing; a shorthand property `{ p }` answers the LOCAL (round 922) |
| `getResolvedSignature` | `resolvedCallAt(node): ResolvedCall?` / `resolvedSignatureAt(node): Signature?` | store: overload resolution at a `CallExpression` / `NewExpression`, with the candidate count (0 = not callable; null signature beside a positive count = no overload accepted the arguments) | tagged templates and decorators are not recorded; the checker's own selection rules apply ((CHK.54)'s specialised-first order, (CHK.55)'s weak-type refusal) |
| `getContextualType` | `contextualTypeAt(node: Expression): Type?` | store: (API.10)'s syntactic walk — annotated initializer, call / `new` argument through the callee's signatures, `return`, arrow expression body, assertion, enclosing literal member, array element | `null` where the checker types a position by ARITY only ((CHK.30)/(CHK.39)); `null` for an inferred generic argument (tsc's answer too); no `satisfies`-through-contextual-return chains beyond the listed positions |
| `getTypeOfSymbolAtLocation` | `typeOfSymbolAt(symbol, node): Type` | store where `node` resolves to `symbol` (the narrowed type); `typeOfSymbol` otherwise | as `typeAt` |

### 3b. The composed resolver, and the one row still refused

| proto.go | oracle | note |
|---|---|---|
| `resolveName` | `resolveName(name, location, meaning): Symbol?` | **ANSWERED since (INV.0) step 10d.** Names no existing node, so neither the store nor the retained graph alone can serve it; `Checker.oracleResolveName` composes the checker's own three legs — the INV.2(c) scope-space ascent, `lookupInEnclosingNamespaces` and `lookupPerFileForNode` — in the order the checker uses them, with a `meaning` mask on all three. Reaches the whole B83.5 population (a `class`/`interface`/`type`/`enum`/`function`/`namespace`/`const`/`let`/`var` declared in a function body or a block, and a PARAMETER) and answers the INNER of two same-named declarations. **Divergence**: two MEANINGS of a name declared in the SAME block collide — `LexicalScope.symbols` is one table and `Binder.canMerge` has no Interface+Variable rule — so across scopes the mask is exact and within one it is not. **And the SYMBOL is the answer, never a type read off it at rest**: `typeOfSymbol` on an un-annotated local re-infers with no walk ambient, so an un-annotated `const`'s type is `typeAt`'s answer and not this row's (measured while pinning it: `const useLocal = collide` renders the file-level `collide`'s type at rest) |
| `getSymbolsInScope` | `symbolsInScope(location)` throws `OracleRefusal` | **STRICTLY harder than the lookup and it does NOT open with it**: an ENUMERATION must also offer every conventionally-bound name, i.e. read `LexicalScope.existing`, which is the INV.3 question round 748's `symbols`-only rule exists to keep out |

### 3c. From the retained graph (A / A°)

| proto.go | oracle | divergence |
|---|---|---|
| `getTypeOfSymbol` | `typeOfSymbol(symbol)` | none (round 778's empty-context gate is satisfied at rest) |
| `getDeclaredTypeOfSymbol` | `declaredTypeOfSymbol(symbol)` | (INC.28): a reference to a generic alias answers the instantiated form, never the parametric one |
| `getAliasedSymbol` | `aliasedSymbol(symbol): Symbol?` | A°: an ambient-module namespace import (`import * as fs from "fs"`) answers nothing ((CHK.73)); some import forms lack the (CHK.30) fallback leg |
| `getPropertyOfType` | `propertyOfType(type, name): List<Symbol>` | deliberately the COLLECTION question: every constituent's member, never the checker's assignability-flavoured first-wins (round 916) |
| `getPropertiesOfType` | `propertiesOfType(type): List<Symbol>` | A°: for a union, the names common to every constituent, each answered by the FIRST constituent's symbol (tsc synthesizes a union property); an intersection answers every constituent's members by name, first-wins |
| `getApparentType` | `apparentType(type)` | none |
| `getBaseTypes` | `baseTypes(type)` | none for a class or interface; empty for anything else |
| `getTypeArguments` | `typeArguments(type)` | none |
| `getTypesOfType` | `typesOfType(type)` | A°: an intersection is UN-distributed (round 777) — `X & (A \| B)` answers two constituents where tsc answers the distributed union's |
| `getSignaturesOfType` | `callSignaturesOfType(type)` / `constructSignaturesOfType(type)` | none for objects; a union's call signatures are the concatenation (tsc resolves a union signature) |
| `getReturnTypeOfSignature` | `returnTypeOfSignature(signature)` | `any` where the signature carries no resolved return |
| `getParametersOfSignature` | `parametersOfSignature(signature): List<Symbol>` | A° (round 921): binding-pattern parameters are DROPPED and the rest zipped positionally — read `parameterDeclarationsOfSignature` for names, arity and annotations |
| — | `parameterDeclarationsOfSignature(signature): List<Parameter>?` | the declaration's own list, `this` included; `null` for a synthesized signature |
| `isTypeAssignableTo` | `isAssignableTo(source, target)` | the relation engine's verdict — the weak-type rule lives in the walkers and NOT here ((CHK.54)), so a weak target accepts a disjoint non-nullish source |
| `typeToString` | `typeToString(type)` | A°: first-wins alias display per interned type ((INC.27)); `errorType` renders `any` (B58.1); no fresh-literal display ((CHK.59)); no subtype reduction ((CHK.66)) |
| `getTypeFromTypeNode` | `typeFromTypeNode(node: TypeNode)` | sound for the INV.5(c) cacheable population; inside a generic body a bare `T` has no scope at rest and answers `any` — prefer `typeAt` on the typed expression |
| `getConstantValue` | `constantValue(enumMember)` | answers a value for an ambient non-const member with no initializer (auto-numbered for the emitter) where tsc has none |
| `getAnyType` … `getESSymbolType` | `intrinsicType(name)` | none |
| `getSymbolOfType`, `getTargetOfType`, `getConstraintOfTypeParameter`, `getDefaultFromTypeParameter` | the fields: `Type.Object.symbol`, `Type.Reference.target`, `Type.TypeParam.constraint` / `.default` | (INC.19): a constraint is WRITE-ONCE and may be unset for a parameter no walk resolved |
| `getMembersOfSymbol`, `getExportsOfSymbol`, `getParentOfSymbol` | the fields: `Symbol.members`, `.exports`, `.parent` | round 928: a merged interface member carries the LAST block's declaration only |

Not on the oracle (bin C, or host surface elsewhere): `typeToTypeNode` /
`signatureToSignatureDeclaration` (no node builder here), the LS rows (`Project`),
diagnostics (`ProjectCompiler.Result` / `TypeOracleBuild.diagnostics`), emit and config
(`ProjectCompiler`, `TsConfigLoader`).

## 4. Cost

The store is recorded ONLY for a build that asked for an oracle (or the CLI's
`--nodeAnswers` mode). Production compiles allocate no store and compute nothing for it —
`NodeAnswerStoreTest` pins the computation count at zero. The flag-on recording cost of
this store, measured warm and rotated across JVMs (`INVERSION-DESIGN.md` § 9b, beside
Stage 1's type-only § 9a):

| shape | plain check | with the store | per recorded expression |
|---|---|---|---|
| tsc's compiler profile, 78 files, 598,455 expressions | 5,270 ms | 6,404 ms (**+21.5 %**) | 1.90 µs |
| 2,401 small files, 232,106 expressions | 3,457 / 3,439 ms | 3,654 / 3,685 ms (**+6-7 %**) | 0.95 µs |

So an oracle over a program costs roughly one fifth of the check that produces it on a
large-file codebase and under a tenth on an application-shaped one — paid once per build,
and then every question is a lookup.

## 5. What a position-addressed consumer adds

The oracle is NODE-addressed, as tsgo's checker API is. A `(file, offset)` question is the
`-project` module's `SourceIndex` (round 910's span rules: `Node.end` overshoots to the
following token, `Node.pos` is tsc's `getStart()`), and an editor-shaped host wires the two
together.

**`Project.typeOracle()` (INV.2b commit 1) is that wiring's first half.** It builds
whole-program with an `OracleHolder`, retains the oracle so a second call with no edit costs
no build, and CLOSES it in `updateFile`, `deleteFile`, `reloadFile` and `close` — the four
sites that drop the project's cached build. Closing rather than merely dropping the field is
the point: the HOST holds its own reference, and a stale store answers a wrong type rather
than nothing (§ 1).

**`Project.nodeAt(fileName, offset)` (INV.2b commit 2) is the second half**, and it is public
for a reason that is not convenience: every `TypeOracle` row is addressed BY NODE, so a host
that cannot obtain a node cannot ask an oracle anything. `Project.nodeInfoAt` deliberately
answers a descriptor and is the right member for a host that only wants to know what the text
is; it is useless as an oracle address. Publishing `nodeAt` adds no type the API did not
already publish — `TypeOracle.files` hands out `SourceFile` and `NodeBase.parent` walks
upward — what was missing was the one correct way IN.

*Correct* is measurable here. The obvious hand-rolled descent (recurse into the child whose
`[pos, end)` contains the offset) is wrong because `Node.end` is the end of the token AFTER
the node, so sibling spans overlap. Over **669,350 offsets** in twelve of tsc's own compiler
sources (`Inv2bBridgeProbeMain`):

| | |
|---|---|
| naive descent names a DIFFERENT node | 190,820 (28.5 %) |
| …and the oracle's answer differs there | 42,507 (6.4 % of all offsets) |
| restricted to offsets that BEGIN an identifier | 591 of 25,533 (2.3 %), 27 changing the type |

**Tree identity is structural, not a coincidence.** While an oracle is live, `nodeAt` descends
THAT ORACLE'S OWN tree (`Project.oracleTreeOf`), for every file it walked and in either call
order — the miss path prefers it and `buildOracle` drops indexes built before the build, which
is the hit path. The same probe measured the pre-existing behaviour and found the property
already held at all 669,350 offsets, so this is not a repair; it is worth doing because the
violation is silent (`TypeOracle.storeOf` is keyed by file NAME and reads by `nodeId` behind a
bounds check alone, so a node of any equally-named tree is ANSWERED).

What it deliberately does NOT do: serve any shipped query. `quickInfoAt`, `definitionsAt`,
`completionsAt`, `signatureHelpAt` and the rest still answer from the capture machinery, and
whether the oracle may serve them is a separate decision that needs an oracle-vs-capture
equivalence sweep (`scripts/capture-equivalence.sh` varies the PARTITION at a fixed request
and structurally cannot see it). The oracle build is also kept out of `Project`'s `cached`:
a store build types nodes an ordinary build never types, so its diagnostics are not a plain
build's ((INC.14)), and the separation is enforced by the return type of the private
`buildOracle()` rather than by documentation.
