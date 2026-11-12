# goport — the TypeScript 7.1 upgrade (branch `tsgo-7.1`)

The first upgrade of the port past tsgo 7.0.2: what moved upstream, what the mechanical updater
needed, and the gates it passes. Pin: `typescript@7.1.0-beta` (npm `beta` dist-tag, 2026-11).

## 1. Where the source is

- npm's `gitHead` for the beta names nothing in typescript-go. The beta binary's Go build info does:
  `tools/go/bin/go version -m tools/tsgo-7.1.0-beta/lib/tsc` → module `github.com/microsoft/TypeScript/tsc`,
  `vcs.revision=3e9236361fa9` in **microsoft/TypeScript** (branch `release-7.1`, 2026-10-19).
- tsgo moved into the TypeScript monorepo under `tsc/` (module `github.com/microsoft/TypeScript/tsc`). The
  `_submodules/TypeScript` submodule is gone: TypeScript's test cases and `/.lib` test libraries live in-tree at
  `tsc/testdata/tests/{cases,lib}`, one suite (compiler/ + conformance/; no `local/`), baselines under
  `testdata/baselines/reference/<suite>/` with no `.diff` layers. The monorepo's `go.work` `use`s an absent
  `./tools` in a sparse checkout — every `go` command runs with `GOWORK=off` (the extractor and
  `oracle-go/build.sh` set it).
- Layout on this branch: `tools/typescript-src-7.1.0-beta` is a sparse (`tsc/`) worktree of `typescript-repo`
  at 3e9236361fa9; `typescript-go-repo` (gitignored) is a symlink to its `tsc/`; the reference binary is
  `tools/tsgo-7.1.0-beta/lib/tsc` (the `@typescript/typescript-linux-x64@7.1.0-beta` package).

## 2. Result

| gate | 7.1.0-beta |
|---|---|
| porter | 99.5 % of Go lines mechanical (7.0.2: 99.3 %), no stale/orphan overrides, warning-clean |
| diagnostics differential (`DiagParityTest`) | 13,728 / 13,728 configurations; the oracle matches tsgo's own baselines 13,728 / 13,728 |
| emit differential (`EmitParityTest`) | 13,728 / 13,728 |
| API (`ApiParityTest`, `tsc --api`) | 594,730 / 594,730 requests, 201 projects |
| language service (`LsParityTest`, `tsc --lsp`) | 22,088 / 22,088 requests — auto-import completions included |
| CLI (`CliParityTest`) | 121 / 121 cases |
| full test suite | 1,000 / 1,000 (all modules) |

Control: at 7.0.2 the updater regenerates the checked-in `gen/` byte-identically (2026-11-11).

## 3. What 7.1 changed and what the porter learned (docs/goport-lowering.md § 3)

| upstream change | answer |
|---|---|
| module path `github.com/microsoft/TypeScript/tsc` (~100 hard-coded uses) | renamed across porter, overrides, tables, overlays |
| #64798 nodes recovered from embedded headers: no `nodeData`/`TypeData`, `(*T)(unsafe.Pointer(h))` (23 → 1,488 lines) | **Header chains** over `ast.Node`, `checker.Type`, `checker.TypeMapper` |
| `diagnostics.allMessages = [...]**Message{&X, …}` | **Addressed globals** |
| Go 1.27 promoted fields in composite literals; generic methods | regrouped literals; method dictionaries |
| `ipc` defers rewriting named results | `withDefersNamed` |
| typed paths (`tspath.RootedFilePath`, `func f[T ~string]`) | string-core type-parameter conversions |
| `core.Arena[[]*Type]` | generic `*P` with a non-struct P is a `GoPtr` |
| `dirty.Map[K, V Cloneable[V]]` instantiated with `dirty.CloneableMap` (a named map: a typealias) | **Constraint dispatch** (bound dropped, run-time dispatch) |
| `tsoptions` package vars initialized across files in a cycle | **Package variable order** (interleaving packages emit `PackageVars*.kt`) |
| `packagejson.Parse` decodes into an anonymous struct | anonymous structs carry json/reflect members |
| `defer close(ch)`; `ata.TypingsInfo.Equals` next to a comparable struct's `goEquals` | lowered; `goEqualsMethod` JVM name |
| `locale.Locale` (alias of shim `language.Tag`) declares `String()` | the shadowed-alias check covers shim structs; renamed `localeString` |
| `encoding/json/v2` (Go 1.27 std) instead of go-json-experiment | delegating shims `encoding/json/{v2,jsontext,internal/jsonopts}` |

New closure packages: `spanmap`, `contentmapper` (content mappers: tsconfig plugins turning e.g. `.vue` into
virtual TypeScript, run as child processes over JSON-RPC), `ipc`, `osutil`, **`project` + `project/ata` +
`project/background`** (the session system the reworked `api.Session` runs on — it replaces the hand-written
`go/internal_project` fake), `transpile`, `api/requestfilesystem`; `testutil/contentmappertest` partial.
New/extended shims: `bufio`, `net` (`Listen` unsupported; `Pipe` in memory), `io`, `sort.Slice` (Go's pdqsort by
index), `sync.Cond`, `context.AfterFunc/WithTimeout`, `time.Timer/AfterFunc/Until/Time.Add`, `slices.Backward`,
`strings.CutLast/LastIndexAny`, `bytes.Cut/TrimSpace`, `hex.DecodeString`, `utf8.Valid`, `nativepath.IsNetworkPath`,
`os.Args`, `repo.TestDataPath`. The json shim learned raw `jsontext.Value` fields (and `[]jsontext.Value`, and
`GoElem.JSON_VALUE` behind a type parameter), interface `omitzero` (zero only when nil), and content-mapper named
numbers. `TsgoMain` passes a JVM `ProcessBuilder` spawner, so the CLI runs real content mappers.

Remaining refusals: the 7.0.2 set (`NewTestLogger`'s `time.Unix`, `removeDiacritics`' `norm.Form`, two untyped
constants whose uses are folded) plus pinned `ipc` Unix-socket transport (3) and LSP telemetry (2), and the
pprof session handlers (partial stubs).

## 4. Overrides

Re-ported: `checker.getRelationKey`, `core.Arena.New` (non-struct T: a `GoBox`), `compiler.compilerHost.GetSourceFile`,
`vfs/internal.Common.ReadFile`, `vfsmatch.globPattern.matchPathParts` (field rename, see the trap below). New:
`core.nextArenaSize` (`unsafe.Sizeof`), `core.BreadthFirstSearchParallelEx` (its local generic `result` type cannot
be hoisted; a sequential port with Go's index-decided answer). Overrides may declare `// import <fqn>` lines.
Removed: `checker.hashWrite32/64`, `ast.getCombinedFlags` (gone upstream).

**Trap:** an override's source-hash pin covers its own Go body only. `matchPathParts` kept its hash while inlining
a field 7.1 renamed; only the compile caught it. After an upgrade, compile — "no stale overrides" is not enough.

## 5. Facade and downstream

`TsgoProject`/`TsgoLanguageService` open a project as `tsc --api` does (project.SessionInit →
`api.NewStandaloneSession` → `createSnapshot`) and speak 7.1's protocol (`project.ID`, SymbolReferences,
`GetTypePropertyParams`…). The LS retries a completion on `ErrNeedsAutoImports` with an auto-import snapshot,
as tsgo's server does — 7.0.2's port had to tell clients to switch auto-imports off. Gates: the API gate binds
7.1's SymbolReferences (`{id, file node id}`, lazily assigned tsgo ids) as handles. Behaviour changes 7.1 brings:
`tsc --noEmit` with errors exits **2** (7.0 exited 1); 7.1 parses `extends`/`implements` elements of interfaces
and classes as TypeReferences (kir and externals convert them back); `KindSourceKeyword` shifted SyntaxKind
numbers from 167 up (externals uses named constants now).

## 6. Open

- **keyBuilder pooling lost** (performance): 7.1's `keyBuilder` hands out views of its inline buffer, which the
  escape proof (`PooledLocals.kt`) calls retaining; every relation/union/instantiation key allocates one (pooled
  at 7.0.2, ~8 % of a check's allocation, docs/goport-perf.md § 12). Pinned in `LoweringRulesTest`.
- **Link stores**: 7.1's checker link stores key by `uint64` ids from a block id generator; the `core.LinkStore`
  override's dense `GoLinkKey` path no longer applies to them (correct, unmeasured).
- `ipc.PipeTransport` (Unix socket) is unsupported in commonMain; `--api` over pipes is not ported.
- `NativeCheckMain.kt` (Kotlin/Native) is not updated to the 7.1 host/program API.
