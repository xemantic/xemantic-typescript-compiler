# goport oracle — tsgo's encoded AST per corpus file

The (TSGO.1) gate (`docs/tsgo-port-plan.md` § 4.1) is **byte equality** between the Kotlin port's
`parse → bind-flags → EncodeSourceFile` and the shipped tsgo binary's `--api` `getSourceFile`.
This page is the oracle side: how the bytes are obtained, what they depend on, and the tools.

| what | where |
|---|---|
| oracle fetcher | `scripts/tsgo-oracle.py` |
| oracle bytes + manifest | `build/goport/oracle/<source>/<relpath>.bin`, `build/goport/oracle/manifest.json` (not checked in) |
| in-process encoder / cross-check / dump tool | `xemantic-typescript-compiler-goport/oracle-go/` (`main.go`, `build.sh`) → `build/goport/bin/tsgo-oracle` |

## 1. Protocol

`tools/tsgo-7.0.2/lib/tsc --api [-cwd DIR] [-async]` serves the API on stdio
(`cmd/tsgo/api.go`, `internal/api/`).

- **Default (no `-async`) = MessagePack tuples, synchronous** (`protocol_msgpack.go`). A request is
  `0x93, 0x01 (Request), bin(method), bin(JSON params)`; the response is `0x93, 0x04 (Response),
  bin(method), bin(payload)` or type `0x05` (Error, payload = message). `bin` is msgpack bin8/16/32
  (`0xc4`/`0xc5`/`0xc6`, big-endian length). Payloads are JSON, **except `getSourceFile`, whose
  payload is the raw encoded bytes** (`RawBinary`). One request at a time per process.
- `-async` = JSON-RPC 2.0 with LSP `Content-Length` framing; `getSourceFile` then returns
  `{"data": "<base64>"}`. The oracle uses msgpack (no base64, no framing) and parallelises with
  several processes instead.

Sequence used by the oracle, per batch of files:

1. `initialize` (no params) once per process.
2. Write a `tsconfig.json` listing the batch in `"files"` (absolute paths), then
   `updateSnapshot {"openProjects": [<tsconfig>]}` → `{snapshot, projects:[{id, rootFiles, …}]}`.
   The project id is the config path.
3. `getSourceFile {"snapshot", "project", "file": <absolute path>}` per file. Empty payload = the
   file is not in the program (the oracle refuses).
4. `release {"snapshot"}`, `updateSnapshot {"closeProjects": [<tsconfig>]}`, `release` that snapshot.

Oracle config: `{"allowJs": true, "noResolve": true, "noLib": true, "types": [], "skipLibCheck": true}`
— exactly the listed files, nothing else parsed.

## 2. What the bytes depend on (measured)

`handleGetSourceFile` (`session.go:1045`) encodes `program.GetSourceFile(name)`. That file comes
from `project/compilerhost.go` → `parsecache.go`: `parser.ParseSourceFile(opts, content, scriptKind)`,
then `file.Hash = xxh3.HashString128(content)`, then **`project.go:409` binds every program file
(`newProgram.BindSourceFiles()`) before any request is served**. So the bytes are a pure function of:

| input | how to reproduce it |
|---|---|
| text | file bytes through `vfs` `decodeBytes`: UTF-8 BOM stripped; `FF FE`/`FE FF` → UTF-16 decoded to UTF-8; otherwise raw bytes (invalid UTF-8 kept as-is) |
| `fileName` | the normalized **absolute** path (`tspath.NormalizePath`), stored as a string in the SourceFile extended data |
| `path` | `tspath.ToPath(fileName, …)`; on Linux (case-sensitive) identical to `fileName` |
| ScriptKind / LanguageVariant | `core.GetScriptKindFromFileName`: `.js/.cjs/.mjs`→JS(1), `.jsx`→JSX(2), `.ts/.cts/.mts`→TS(3), `.tsx`→TSX(4); variant JSX for JSX/TSX |
| `ExternalModuleIndicatorOptions {JSX, Force}` | **stored in the header, bytes 20–23 (bit 0 JSX, bit 1 Force)** and copied into the manifest (`parseOptions`, `jsx`, `force`). Derived by `ast.GetExternalModuleIndicatorOptions` from `jsx`, `moduleDetection`, module kind, extension and package.json `type` scope |
| content hash, header bytes 4–19 | `xxh3` 128-bit of the decoded text (the port needs an xxh3 shim) |
| **binder node flags** | `binder.BindSourceFile` sets ExportContext(6), ContainsThis(7), HasImplicitReturn(8), HasExplicitReturn(9), ThisNodeOrAnySubNodesHasError(17), HasAsyncFunctions(18, SourceFile), Unreachable(27) |

There is **no languageVersion and no JSDoc parsing mode** in tsgo's parse options: tsgo always parses
at the latest syntax and always parses JSDoc. Measured: with the same file in projects configured
`{}`, `{"module":"esnext"}`, `{"target":"es5","strict":true,"alwaysStrict":false}` the bytes are
identical; `{"jsx":"react-jsx"}` flips JSX (and can move `externalModuleIndicator`),
`{"moduleDetection":"force"}` flips Force. Nothing else of the compilerOptions reaches the bytes.
In the default corpus Force is set only for `.cjs`/`.mjs`/`.mts`/`.cts`; JSX is never set.

Positions in node records are **UTF-16** offsets (`PositionMap.UTF8ToUTF16`); string-table offsets
are **UTF-8 byte** offsets into the string data.

## 3. Tools

```
scripts/tsgo-oracle.py [--inputs list] [--out dir] [--workers 4] [--batch 400] [--force] [--determinism 50]
scripts/tsgo-oracle.py --list-sources
xemantic-typescript-compiler-goport/oracle-go/build.sh          # → build/goport/bin/tsgo-oracle
build/goport/bin/tsgo-oracle encode [-parse-only] [-jsx] [-force] [-like oracle.bin] [-o out.bin] FILE
build/goport/bin/tsgo-oracle crosscheck [-parse-only] [-limit N] [-v] build/goport/oracle/manifest.json
build/goport/bin/tsgo-oracle dump [-indices] [-no-strings] [-max-string N] FILE.bin
```

- The fetcher caches by input sha256 + output sha256 + a config key (tsc binary sha256, compiler
  options, config version); a re-run fetches only changed inputs. It exits non-zero and marks the
  manifest `"complete": false` on any failed file, dead worker, or determinism mismatch (a sample
  re-fetched in a fresh process with reversed order and a different batch size).
- **Why the Go tool has no `go.mod` of its own:** it imports `internal/` packages, which Go only
  allows inside the `github.com/microsoft/typescript-go` module. `build.sh` compiles `main.go` as
  the *virtual* package `typescript-go-repo/cmd/xtsc-oracle` through `go build -overlay` — the build
  runs inside the tsgo module, but no file is written into `typescript-go-repo`.
- `encode` reproduces the binary in-process (parse → hash → bind → encode); `-parse-only` skips the
  binder, `-like` copies the JSX/Force bits from an oracle file's header.
- `dump` is a hand-written decoder (it does not trust the bytes, so it renders a broken Kotlin
  output too): one line per node, indentation = depth, kind name, UTF-16 `pos..end`, decoded data
  (child mask / string / extended fields / NodeList length), named flags; then the strings appended
  after the file text. Without `-indices` it prints no absolute indices, so
  `diff <(tsgo-oracle dump a.bin) <(tsgo-oracle dump b.bin)` points at the first differing node and
  resynchronises after an insertion.

## 4. Measured (2026-10-07)

| source | root | files | source MB | encoded MB |
|---|---|---|---|---|
| conformance | `typescript-repo/tests/cases` (sparse clone: 6,455 `.ts` + 118 `.tsx`, no `.js`) | 6,573 | 4.68 | 38.32 |
| tsc | `build/bench/tsc-project-637d5746/src` | 78 | 9.98 | 43.93 |
| cronstrue | `build/scratch-p18171/libs/cronstrue/src` | 54 | 0.22 | 1.15 |
| marked | `build/scratch-p18171/libs/marked/{src,test,bin,docs}` | 53 (15 ts, 20 js, 18 cjs) | 0.33 | 2.06 |
| type-fest | `build/scratch-p18265-census/type-fest` | 453 | 1.27 | 8.35 |
| hono | `build/scratch-p18265-census/hono/src` | 311 (22 tsx) | 2.55 | 18.70 |
| rxjs | `build/scratch-p18208-census/lib/rxjs/src` | 252 | 0.81 | 3.49 |
| **total** | | **7,774** | **19.8** | **116.0** |

knip is not on this box. Failures 0; fetch 1.6 s wall with 4 workers (~4,800 files/s); determinism
probe 50/50 identical; re-run is fully cached.

Cross-check, in-process `encode` vs the binary: **7,774 / 7,774 byte-identical** (`.ts` 7,359,
`.d.ts` 223, `.tsx` 140, `.js` 34, `.cjs` 18), plus a synthetic set of 84 (40 `.jsx`, 10 each
`.mts`/`.cts`/`.mjs`/`.js`, a UTF-8 BOM file, a UTF-16LE BOM file, CRLF + non-BMP text, invalid
UTF-8 and a lone-surrogate escape): 84 / 84. Parse-only (no binder): 4,024 of 7,774 files differ,
all only in node-flag words — bits 6/7/8/9/17/18/27 above.

## 5. What threatens the gate

- **The binder is part of the gate.** Half the files differ without its seven node flags. It is
  ported whole (2026-10-07) and `OracleParityTest` with `TSGO_ORACLE=bound` matches all 7,774 files.
- **Absolute paths are inside the bytes** (fileName and path strings). The Kotlin side must use the
  manifest's `fileName` verbatim; moving the checkout invalidates the oracle (re-run it).
- **xxh3-128** of the decoded text is in the header; the port needs a byte-exact xxh3 shim.
- UTF-16 positions vs UTF-8 string offsets, WTF-8 for lone surrogates in cooked strings, and the
  BOM/UTF-16 file decoding are all exercised and must be reproduced exactly.
- No non-determinism was observed (fresh process, different batching, repeated runs).
