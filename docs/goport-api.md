# goport API — tsgo's type oracle in process ((TSGO.3-b))

tsgo's API (`internal/api`: proto.go, session.go — the server behind `tsc --api`) is PORTED with the rest
of the closure and exposed to Kotlin through `com.xemantic.typescript.tsgo.TsgoProject`
(`xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/facade/TsgoProject.kt`). Every query is the
session's own handler, so an answer is tsgo's answer; the gate is a differential against the shipped
binary (§ 4).

## 1. What is ported, and how

`internal/api` is a PARTIAL package of the extraction (`goport-extract/partial.go`): its roots are the
three functions of the overlay `goport-extract/overlay/api/xtsc_api.go` (added through go/packages'
Overlay, never written into `typescript-go-repo`):

| overlay function | what it replaces |
|---|---|
| `XtscNewSession(projectSession, snapshot)` | `NewSession` + the snapshot bookkeeping of `handleUpdateSnapshot`, for ONE caller-built snapshot |
| `XtscMarshal(result)` | `MessagePackProtocol.WriteResponse`'s JSON payload (the session speaks JSON: no `RawBinary`) |
| `XtscOpenProgram(config, fs)` | `project.NewConfiguredProject` + `Project.CreateProgram`: cwd = the config's directory, `UseSourceOfProjectReference`, the checker pool below, `BindSourceFiles` |
| `XtscCheckerPool` | `project/checkerpool.go` reduced to one checker per category: the persistent API checker (every API query), the diagnostics checker, the query checker |

The roots keep `Session`, and a kept type keeps all its methods: `HandleRequest`, every handler,
`snapshotData`'s registries, proto.go's request/response types and `unmarshalers` are ported verbatim.
**Stubs** (`partialStubs`, signature only, body not extracted; the porter emits
`TODO("goport: refused partial-stub …")` and counts them under `partial-stub`, 17 declarations):

- the project-session lifecycle — `handleInitialize`, `handleUpdateSnapshot`, `handleRelease`,
  `handleGetDefaultProjectForFile`, `Close`, `releaseOpenRefs`, `toFileChangeSummary`,
  `computeSnapshotChanges`, `setupLanguageService` (replaced by `XtscNewSession`'s caller-built snapshot);
- `runtime/pprof` profiles — `handleStartCPUProfile`, `handleStopCPUProfile`, `handleSaveHeapProfile`;
- `internal/ls` (the language service, ~29k lines, not ported yet) — `handleGetCompletionsAtPosition`,
  `handleGetReferencesToSymbolInFile`, `handleGetReferencedSymbolsForNode`, `handleGetSignatureUsages`,
  `handleGetJSDocTags`, `handleGetDocumentationComment`.

Not reached at all: the transport (`conn_*.go`, `protocol_*.go`, `transport*.go`, `server.go`,
`callbackfs.go`, timing) — the in-process caller is the transport.

**Hand-written shims** (`-tsgo/src/commonMain/kotlin/go/internal_*`), the members the ported code names:

| package | shim |
|---|---|
| `internal/project` | `Session` (FS + cwd, a `tsoptions.ParseConfigHost`), `Snapshot` (id, `ProjectCollection`), `ProjectCollection.GetProjectByPath`, `Project` (`ID`, `GetProgram`, `GetProjectDiagnostics` = config + program + the pool's global diagnostics, sorted/deduplicated), `FileChangeSummary` (a stub's signature) |
| `internal/lsp/lsproto` | `DocumentUri` + `FileName` (bundled names, `file://` with percent-decoding and the drive-letter fix, other schemes as `^/scheme/authority/path`) |
| `internal/ls/lsconv` | `FileNameToDocumentURI` (`url.PathEscape` + tsgo's extra escapes) |
| `internal/ls` | `LanguageService` (an empty class: a stub's signature) |
| `internal/pprof` | `CPUProfiler` (an empty class: a `Session` field) |

Porter rules added for the API (each general, docs/goport-lowering.md):

- **`f[T]` as a value** (an explicitly instantiated generic function used as a function value,
  `unmarshallerFor[P]`) is the instantiated function, like an implicitly instantiated one (`funcValue`)
  — it was a `generic-func-value` refusal.
- **`&x` of an opaque type parameter flowing into an interface** goes through `goOpaqueAddr(goElem_T, ptr)`:
  for a struct or array T (whose `GoElem` copies) Go's `*T` is the reference itself, so a later
  `.(*S)` assertion sees the struct (`unmarshallerFor`'s `return &v`); for any other T the real pointer.
  Changes two `packagejson` call sites to the same behaviour (the JSON shim decodes a struct in place
  either way).
- **A composite literal's anonymous-function element is parenthesized** in its fill statement: K2's
  raw-FIR builder treats `it[a] = fun(…) = x; it[b] = fun(…) = y; …` as nested assignments and is
  EXPONENTIAL in their number (22 entries 32 s, 26 > 200 s; `api.unmarshalers` has 112 and held a
  whole-module compile for 43 minutes). Parenthesized, 130 entries compile in 5.6 s.

## 2. The Kotlin API

```kotlin
val p = TsgoProject.open("/abs/path/tsconfig.json")         // fs = TsgoProject.diskFS() by default
val t = p.typeAtPosition("/abs/path/src/a.ts", offset)       // UTF-16 offset
p.typeToString(t!!); p.propertiesOfType(t); p.signaturesOfType(t)
val node = p.nodeAt(file, offset)!!                         // the touching name, as a NodeHandle
p.resolvedSignature(node); p.contextualType(node); p.symbolAtLocation(node)
p.isTypeAssignableTo(a, b); p.typeOfSymbol(sym); p.returnTypeOfSignature(sig)
p.request("getBaseTypeOfLiteralType", """{"snapshot":${p.snapshotId},"project":"…","type":12}""") // the raw protocol
```

Typed: files (`sourceFileNames`, `semanticDiagnostics`), positions/nodes (`nodeAt`, `typeAtPosition`,
`symbolAtPosition`, `typeAtLocation`, `symbolAtLocation`, `contextualType`, `resolvedSignature`), types
(`typeToString`, `propertiesOfType`, `propertyOfType`, `signaturesOfType`, `isTypeAssignableTo`,
`typesOfType`, `typeArguments`, `baseTypes`, `apparentType`), symbols and signatures (`typeOfSymbol`,
`declaredTypeOfSymbol`, `parametersOfSignature`, `returnTypeOfSignature`). `TsgoType`/`TsgoSymbol`/
`TsgoSignature` are handles (identity = the API handle within the project); `TsgoNode` is an API
`NodeHandle` (`index.kind.path`). `request` reaches every other ported proto.go method.

Every call runs on a goroutine thread (`onGoStack`: tsgo's checker recurses as deep as a 1 GB Go stack).
A refused query (unknown handle, file not in the program) throws `TsgoApiException` with tsgo's message.

Differences from `tsc --api`: one snapshot per project (no `updateSnapshot`); the disk FS is
`iovfs.From(os.DirFS("/"))`, i.e. without `osvfs`'s symlink resolution (`Realpath` is the identity).

## 3. Not ported yet (the remaining surface)

The 10 language-service and lifecycle methods stubbed in § 1 (completions, references, signature
usages, JSDoc tags, documentation comments; initialize/updateSnapshot/release/getDefaultProjectForFile;
profiles), which need `internal/ls` (+ `ls/lsutil`, `format`, `ls/autoimport`, `ls/change`, the
generated `lsp/lsproto`, ~60k lines) or the project system (`internal/project`, file watching, ATA).

## 4. The gate

```
xemantic-typescript-compiler-goport/oracle-go/build.sh       # → build/goport/bin/tsgo-oracle
scripts/tsgo-api-oracle.py [--tsc-src DIR] [--cases DIR] [--conformance 200]
TSGO_API=1 TSGO_TEST_HEAP=3g ./gradlew :xemantic-typescript-compiler-tsgo:jvmTest --tests '*ApiParityTest*'
```

`tsgo-oracle api` (oracle-go/api.go) drives the SHIPPED binary (`tools/tsgo-7.0.2/lib/tsc --api`, msgpack)
over one project and records every request with its response. The request stream is generated from
the binary's own answers: for every non-library file, every node of the encoder's node index table
(built from this process's parse of the same text, so handles agree): Identifiers →
`getSymbolsAtLocations` + `getTypeAtLocations` (batched); call-likes → `getResolvedSignature`; call/new
arguments → `getContextualType`, `getTypeAtLocation`, `isTypeAssignableTo(argument, contextual)`; every
returned symbol → `getTypeOfSymbol`; every returned type → `typeToString`, and a type met at a
location also `getPropertiesOfType` (its properties' types) and `getSignaturesOfType`; every signature
→ `getReturnTypeOfSignature`. The corpus: tsc's 78 sources (a copy of the bench profile) and 200
single-file conformance cases (every k-th of the sorted list, default options).

`ApiParityTest` replays each recording through `TsgoProject.request` (= `HandleRequest` + `XtscMarshal`).
Handles are per-process counters, so type/symbol/signature ids are compared as a BIJECTION (bound on
first meeting, enforced after); params are translated through it; everything else — names, flags,
node handles, type strings with their raw JSON escapes, field order, omitted fields — must be equal.
An error must be an error on both sides (digits masked). Positive control:
`TSGO_API_INJECT=<project>:<line>`.
