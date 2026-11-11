# goport — the TypeScript 7.1 upgrade (branch `tsgo-7.1`)

The first upgrade of the port past tsgo 7.0.2: what moved upstream, what the mechanical updater
needed, and what is still open. Pin: `typescript@7.1.0-beta` (npm `beta` dist-tag, 2026-11).

## 1. Where the source is

- npm's `gitHead` for the beta names nothing in typescript-go. The beta binary's Go build info does:
  `tools/go/bin/go version -m tools/tsgo-7.1.0-beta/lib/tsc` → module `github.com/microsoft/TypeScript/tsc`,
  `vcs.revision=3e9236361fa9` in **microsoft/TypeScript** (branch `release-7.1`, 2026-10-19).
- tsgo moved into the TypeScript monorepo under `tsc/` (module `github.com/microsoft/TypeScript/tsc`). The
  `_submodules/TypeScript` submodule is gone: TypeScript's test cases live in-tree at `tsc/testdata/tests/cases`.
  The monorepo's `go.work` `use`s an absent `./tools` in a sparse checkout — every `go` command runs with
  `GOWORK=off` (the extractor sets it).
- Layout on this branch: `tools/typescript-src-7.1.0-beta` is a sparse (`tsc/`) worktree of `typescript-repo`
  at 3e9236361fa9; `typescript-go-repo` (gitignored) is a symlink to its `tsc/`; the reference binary is
  `tools/tsgo-7.1.0-beta/lib/tsc` (the `@typescript/typescript-linux-x64@7.1.0-beta` package).

## 2. Control

At 7.0.2 the updater regenerates the checked-in `gen/` byte-identically (2026-11-11), so every difference on
this branch comes from the beta.

## 3. What 7.1 changed that the porter had to learn

| upstream change | porter answer (docs/goport-lowering.md § 3) |
|---|---|
| module path `github.com/microsoft/TypeScript/tsc` (~100 hard-coded uses) | renamed across porter, overrides, rename/refuse tables, overlays |
| #64798 "Recover concrete AST nodes from their embedded headers": no `nodeData`/`TypeData`; `(*T)(unsafe.Pointer(h))` (23 → 1488 lines) | **Header chains** over `ast.Node`, `checker.Type`, `checker.TypeMapper` |
| `diagnostics.allMessages = [...]**Message{&X, …}` | **Addressed globals** |
| Go 1.27: promoted fields in composite literals | regrouped into the embedded field's literal |
| Go 1.27: generic methods (`p.doInContext[T]`) | method dictionaries |
| `ipc` defers rewriting named results | `withDefersNamed` (runtime already had it) |
| typed paths (`tspath.RootedFilePath`, … , `func f[T ~string]`) | `string(T)` / `T(string)` for string-core type params |
| `core.Arena[[]*Type]` | generic `*P` result with a non-struct P is a `GoPtr` |
| `encoding/json/v2` (Go 1.27 std) instead of go-json-experiment | delegating shims `encoding/json/{v2,jsontext,internal/jsonopts}` |
| new packages `spanmap`, `contentmapper` (content mappers: tsconfig plugins mapping e.g. `.vue` to virtual TS, over JSON-RPC), `ipc`, `osutil` | added to the closure; shims `bufio`, `net`, `io`, `sync.Cond`, `context.AfterFunc/WithTimeout`, `sort.Slice` (Go's pdqsort by index), … |

Mechanical share: 99.6 % of Go lines (7.0.2: 99.3 %). Remaining refusals are the 7.0.2 set
(`BreadthFirstSearchParallelEx`'s local type, `NewTestLogger`'s `time.Unix`, `removeDiacritics`' `norm.Form`,
two untyped constants whose uses are folded) plus three pinned `ipc` Unix-socket transport functions
(`refuse.txt`: commonMain has no socket API; content mappers use the stdio transport).

## 4. Overrides

Re-ported: `checker.getRelationKey` (7.1 writes the intersection state with `writeUint32`, builds a fresh
`keyBuilder`), `core.Arena.New` (non-struct T: a `GoBox`), `compiler.compilerHost.GetSourceFile`
(`EnsureScriptKindFromFileName`), `vfs/internal.Common.ReadFile` (typed path). New: `core.nextArenaSize`
(`unsafe.Sizeof` has no JVM meaning). Removed (functions gone upstream): `checker.hashWrite32/64`,
`ast.getCombinedFlags`.

**Trap found:** `vfsmatch.globPattern.matchPathParts` kept its body hash, but its override inlines a read of a
field 7.1 renamed (`caseSensitive bool` → `caseSensitivity tspath.CaseSensitivity`). The source-hash pin covers
an override's own Go body, not what the hand port inlined from elsewhere; only the compile caught it. After an
upgrade, compile — a green "no stale overrides" is not enough.

## 5. Open (follow-ups)

- **keyBuilder pooling lost** (performance): 7.1's `keyBuilder` keeps a `[192]byte` inline buffer and its
  writers return sub-slices of it, which the escape proof (`PooledLocals.kt`) correctly calls retaining. Every
  relation/union/instantiation key now allocates a `keyBuilder` (at 7.0.2 pooled, ~8 % of a check's allocation,
  docs/goport-perf.md § 12). Pinned in `LoweringRulesTest`; re-pooling needs the proof to follow a view to its
  last use.
- **Link stores**: 7.1's checker link stores key by `uint64` node/symbol ids from a block id generator; the
  `core.LinkStore` override's dense `GoLinkKey` path no longer applies to them (correct, slower).
