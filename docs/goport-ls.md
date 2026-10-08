# goport LS — tsgo's language service in process ((TSGO.4-a))

tsgo's language service (`internal/ls`, ~29k Go lines, and what it reaches: `lsp/lsproto`, `ls/lsutil`,
`ls/lsconv`, `ls/change`, `ls/autoimport`, `format`, `project/dirty`, `project/logging`, `vfs/wrapvfs`,
`jsonrpc` — ~62k lines) is PORTED with the rest of the closure (`goport-extract`'s default closure, whole
packages) and served to Kotlin through `com.xemantic.typescript.tsgo.TsgoLanguageService`
(`xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/facade/TsgoLanguageService.kt`). The language
server `xemantic-typescript-compiler-lsp` answers its requests with it. The gate is a differential against
the shipped `tsc --lsp` (§ 4).

## 1. What is ported, and how

Mechanically, like the checker: 99.4% of `ls`, 100% of `lsp/lsproto`, `ls/lsconv`, `ls/change`; the
refusals are `ls.getCallHierarchyItemName` / `resolveCallHierarchyDeclaration` (`defer` with named results),
`ls/autoimport.createCheckerPool` (`chan`), `ls/lsutil.removeDiacritics` (`golang.org/x/text/unicode/norm`),
`jsonrpc`'s reader/writer (`bufio`) and `project/logging`'s test logger (`time.Unix`). None is reached by
hover, definition, references, completion or diagnostics.

The language SERVER (`internal/lsp/server.go`) and the project system (`internal/project`) are not ported. The
overlay `goport-extract/overlay/api/xtsc_ls.go` (added to `internal/api` through go/packages' Overlay, never
written into `typescript-go-repo`) stands in for their two ends:

| overlay | what it replaces |
|---|---|
| `XtscResolveClientCapabilities(initialize params)` | `handleInitialize`: `lsproto.InitializeParams` unmarshalled, `Capabilities.Resolve()` |
| `XtscUserPreferences(configuration)` | `RequestConfiguration`: the `workspace/configuration` answer parsed by `lsutil.ParseUserPreferences` |
| `XtscLanguageService.XtscLSRequest(ctx, requestID, method, params)` | the server's handler table for `hover`, `definition`, `typeDefinition`, `references`, `implementation`, `completion`, `signatureHelp`, `documentHighlight`, `diagnostic` — each handler's body as server.go writes it, the context tagged with the client capabilities and the request id |
| `xtscOrchestrator` | the server's `crossProjectOrchestrator` for a session of ONE configured project |

`XtscCheckerPool` (the API overlay) gained the project pool's REQUEST AFFINITY: a request that already holds
a checker gets it again without blocking (find-all-references acquires one per file while holding the first).

The hand-written `project` shim's `Snapshot` is the language service's `ls.Host` (files from the FS, each
file's LSP line map and ECMAScript line info computed once, converters in the session's position encoding,
the session's preferences), and its `Project` is an `ls.Project`. The API session's six language-service
handlers (`getCompletionsAtPosition`, `getReferencesToSymbolInFile`, `getReferencedSymbolsForNode`,
`getSignatureUsages`, `getJSDocTags`, `getDocumentationComment`) and `setupLanguageService` are no longer
stubs (ported; not yet in the API recording).

**Auto-import completions are not provided**: they need the project system's auto-import registry
(`project.Session.GetLanguageServiceWithAutoImports`). A client must switch them off
(`js/ts.suggest.autoImports: false`); otherwise `ErrNeedsAutoImports` is an error response. Inferred projects
(a file under no tsconfig) are not provided either.

Porter and shim changes this needed (each general; docs/goport-lowering.md):

- **Go 1.26 `new(expr)`** lowered as `new(T)` — a silent ZERO (`Integer: new(diagnostic.Code())` was 0,
  `new("ts")` was ""). Now a box of the value (a struct's copy).
- **Instantiated generic interfaces** are implemented nominally: `class directory : Cloneable<directory?>`, and a
  GENERIC struct implements a generic interface over its own type parameters (`Box<T> : Value<T>`) — the
  extractor considers each constraint's instantiation and tries every tuple of the type's parameters.
- **A struct reaching `reflect` through a parameter** (`lsproto.marshalUnion(v any, …)` → `reflect.ValueOf(v)`)
  is a `GoReflectStruct`, like a direct `reflect.ValueOf(s)`.
- **A struct used as a context key** (`context.WithValue(ctx, clientCapabilitiesKey{}, …)`) has value equality:
  the context compares keys with Go's `==` on interfaces. Without it no request saw the client's capabilities.
- **A generic shim's type-parameter parameter takes its argument as is** (no `!!`): `slices.Contains(syms, sym)`
  with a nil `sym` threw.
- **A ported generic `*T` parameter instantiated with a non-struct T** receives the pointee (`derefOr`).
- **An erased container in a type test** (`result.(json.Value)`, a typealias of `GoSlice<Int>`) is `is GoSlice<*>`.
- A value class's `_Ptr` box is qualified (not imported with its class).
- **One override** (`format.getAllRules`, hash-pinned): the generated body verbatim with its three rule tables moved
  into their own functions — 13,377 bytecodes as one method, over the JIT limit (`huge_methods.py --fail-over 0`).
- Shims: `bytes.Buffer`/`NewBuffer`/`Equal`, `net/url.PathEscape`/`Parse`, `unicode.IsUpper`/`IsLower`/`IsDigit`/`Mn`
  (tables dumped from Go 1.27.1), `runtime.GOMAXPROCS`, `runtime/debug.Stack` (the last recovered panic's
  stack), `slices.Replace`, `jsontext.Value.Kind`, `reflect.Value.Fields`/`Addr`/`Len`/`Index`/`SetBool`/`SetInt`/
  `SetString`, `reflect.MakeSlice`/`Append`. The json shim decodes into a nil `*T` struct field through the
  field's type (`PointeeCell`), and decodes the LSP protocol's named NUMERIC types (`lsproto` only; § 10 #14 of
  goport-runtime.md keeps every other refused).

## 2. The Kotlin API

```kotlin
val ls = TsgoLanguageService.open("/abs/tsconfig.json",            // fs = diskFS(), libDirectory = null (bundled)
    initializeParams = """{"rootUri":null,"capabilities":{…}}""",   // the client, as `initialize` describes it
    configuration = """[{"suggest":{"autoImports":false}},null,null,null]""")
ls.request("textDocument/hover", """{"textDocument":{"uri":"file:///abs/a.ts"},"position":{"line":0,"character":6}}""")
ls.hover(file, offset); ls.definition(file, offset); ls.references(file, offset)
ls.completions(file, offset); ls.diagnostics(file)                  // UTF-16 offsets in, offsets out
```

`request` is the raw protocol: an LSP method and its JSON params in, the JSON result the server sends out.
The typed queries are the same requests at a UTF-16 offset. Every call runs on a goroutine thread
(`onGoStack`). A failed request throws `TsgoApiException` (a handler panic: `panicked = true`).

## 3. The language server

`xemantic-typescript-compiler-lsp` (`XtscLanguageServer`, JSON-RPC 2.0 over stdio, no new dependencies) keeps
its protocol loop and framing and routes every language-service method to `TsgoLanguageService.request`:
the client's buffers are an `OverlayFS` over the disk, a file belongs to the nearest `tsconfig.json` above it
(built on first use, rebuilt after any change), diagnostics are pushed for open documents after every
lifecycle event and served on pull. Not provided: rename (server.go's handler is a cross-project workspace
edit), inferred projects, auto-imports, incremental program updates (a change rebuilds the program).

## 4. The gate

```
scripts/tsgo-ls-oracle.py [--only NAME,…] [--workers N]    # records tsc --lsp -stdio → build/goport/ls-oracle
TSGO_LS=1 TSGO_TEST_HEAP=3g ./gradlew :xemantic-typescript-compiler-tsgo:jvmTest --tests '*LsParityTest*'
```

The recorder drives the SHIPPED binary (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`) over the type-oracle projects
(`build/goport/api-projects`: tsc's 78 sources and 200 conformance cases, made by `scripts/tsgo-api-oracle.py`):
initialize (VS Code-like capabilities: markdown hovers, definition links, snippet / label-details completions,
pull diagnostics), the configuration answer above, `didOpen` of every file; then per file one pull
`textDocument/diagnostic`, and at identifier-start carets spread evenly through the file (comments and
strings skipped) `hover` + `definition` (60), `references` (6) and `completion` (2 at identifiers, 2 after a
`.`). `LsParityTest` replays every request through `TsgoLanguageService.request` with the recorded client and
compares the result JSON: objects as maps, a completion list's items as a MULTISET (tsgo's own order is a Go
map's iteration order: two recordings of the binary differ), everything else exactly. Positive control:
`TSGO_LS_INJECT=<project>:<line>`.
