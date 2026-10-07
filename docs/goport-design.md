# goport — the design contract for (TSGO.1)

The plan and the gate are `docs/tsgo-port-plan.md`. This page is the **contract** the porter's
parts are built against: the extractor's output, the Kotlin representation of every Go type,
the runtime the generated code calls, and the naming rules. Change a rule here before changing
the code that implements it; every part of the porter reads this page, not each other's source.

## 1. Layout

| path | what | checked in |
|---|---|---|
| `xemantic-typescript-compiler-goport/goport-extract/` | Go module: `go/packages` + `go/types` → JSON IR | yes |
| `build/goport/ir/<pkg>.json` | the IR, one file per Go package | no (regenerate) |
| `xemantic-typescript-compiler-goport/src/main/kotlin/` | the Kotlin lowering IR → Kotlin source (JVM tool) | yes |
| `xemantic-typescript-compiler-goport/overrides/` | hand/LLM ports, keyed by Go qualified name + body hash | yes |
| `xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/runtime/` | hand-written Go runtime (`GoSlice`, `GoMap`, builtins, …), API in `docs/goport-runtime.md` | yes |
| `xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/go/` | hand shims for the Go stdlib AND the non-tsgo modules (`x/text`, `xxh3`, `go-json-experiment`, `x/sync`) | yes |
| `xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/gen/` | generated Kotlin — never edited by hand | yes |
| `build/goport/oracle/` | tsgo's encoded-AST bytes per corpus file | no |

The tsgo pin is `typescript-go-repo` at `typescript/v7.0.2` (`2bd066d87f5b`). Go is
`GOTOOLCHAIN=local tools/go/bin/go` (1.27.1). Nothing in the Gradle build runs Go.

**Gradle is box-serialized**: every agent and script wraps a Gradle invocation in
`flock $XTSC_GRADLE_LOCK ./gradlew …` (the lock path is given per session). Never two Gradle runs at once.

## 2. Scope of the spike closure

tsgo-internal packages (ported mechanically): `ast`, `diagnostics`, `parser`, `stringutil`,
`scanner`, `api/encoder`, `core`, `tspath`, `collections`, `jsnum`, `json`, `debug`, `locale`
(~58k Go lines). External modules (`golang.org/x/text`, `github.com/zeebo/xxh3`,
`github.com/go-json-experiment/json`, `golang.org/x/sync/errgroup`, `klauspost/cpuid`) are NOT
ported: each symbol the closure reaches is bound to a hand-written shim in `go/`, and those
shims are counted separately from overrides. The Go standard library is bound the same way.

## 3. Type representation (Go → Kotlin)

| Go | Kotlin | notes |
|---|---|---|
| `bool` | `Boolean` | |
| `int`, `int32`, `rune` | `Int` | ASSUMPTION: tsgo's `int` values are offsets/counts < 2^31. Any `int` arithmetic the extractor marks as hash-like is a lowering flag. |
| `int8`, `int16`, `uint8`/`byte`, `uint16` | `Int` | value kept in range: the lowering truncates (`and 0xFF`, sign-extend) on conversion and when storing the result of `+ - * << ^` and unary `-`/`^` into the narrow type |
| `uint32` | `UInt` | |
| `int64` | `Long` | |
| `uint`, `uint64`, `uintptr` | `ULong` | |
| `float64` / `float32` | `Double` / `Float` | |
| `string` | `String` whose every `Char` is one **byte** (0..255) | "byte strings": `len(s)` = `length`, `s[i]` = `s[i].code`, `s[a:b]` = `substring`, `+` concatenates, equality and hashing are correct, JVM compact strings store it at 1 byte/char. UTF-8 is decoded only by the runtime's `utf8` shims and at I/O boundaries (`GoString.fromUtf16(String)` / `GoString.toUtf16(String)`). A Go literal is emitted as its UTF-8 bytes (non-ASCII as `\u00XX`). |
| `[]T` | `GoSlice<T>` (runtime) | backing array + offset + len + cap; `append`, `copy`, sub-slicing with Go aliasing. A nil slice is a non-null `GoSlice` with `isNil == true` (shared per element kind), so `len`, `range` and `append` need no null checks while `s == nil` stays expressible. A specialized `GoByteSlice` may follow if the spike measures it. |
| `[N]T` | `GoArray<T>` / primitive array | value type: copied like a struct |
| `map[K]V` | `GoMap<K, V>` (runtime, wraps `HashMap`) | nil map = `GoMap.nil()` (reads OK, writes panic). Struct-VALUE keys are wrapped by the lowering in a structural key (`structEquals`/`structHash`), because struct classes keep IDENTITY equality (see below) |
| `struct` (named) | `class` (plain, NEVER `data`) with `goCopy()` | identity `equals`/`hashCode` — `*T` keys are identity maps. Value semantics are explicit `goCopy()` at assignment, parameter pass, return and composite-literal element where `go/types` says the expression is struct-valued, unless the source is a fresh composite literal or a call result |
| `*T`, `T` a struct | `T?` | the reference IS the pointer. `&compositeLit` = the new instance |
| `*T`, `T` not a struct; `&x` of a local or field | `GoPtr<T>` (runtime) | a local whose address is taken is boxed for its whole life |
| `interface{…}` (named) | `interface` | `any` / `interface{}` = `Any?`. A type assertion is `as?`/`is`; a type switch is `when` |
| named non-struct type (`type Kind int16`) | `@JvmInline value class Kind(val value: Int)` | methods are members; `iota` constant blocks become companion constants. Flag sets (`Or`-ed) stay value classes with `or`/`and` operators |
| `func(...)` | Kotlin function type | method value = bound reference |
| `error` | `GoError?` (runtime interface with `error(): String`) | |
| multiple results | `GoResultN` classes (runtime: `Tuple2`..`Tuple5`) | destructured at the call site |
| generic type/func | Kotlin generics | type-set constraints erased to their core bound |

Zero values are emitted explicitly from the type (the runtime exposes `zeroOf`-style helpers only
for type parameters).

## 4. Control flow

`defer` → `try`/`finally` with a per-function defer stack when a defer is conditional;
`panic(v)` → `throw GoPanic(v)`; `recover()` → catching `GoPanic` in the deferred closure's frame;
labeled `break`/`continue` → Kotlin labels; `goto` and `fallthrough` → lowering rules or refusal;
`switch` → `when`; `for range` over slice/map/string(UTF-8 runes!)/int/func iterators → loops.
Goroutines, channels, `select`, `sync` primitives, `unsafe`, `reflect` → **refused**: the lowering
emits a stub naming the function and an override must exist.

## 5. Naming

Kotlin package = `com.xemantic.typescript.tsgo.<go package path under internal/, '/'→'.'>`
(`internal/api/encoder` → `com.xemantic.typescript.tsgo.api.encoder`). Any OTHER Go package P
(stdlib or external module) maps to `com.xemantic.typescript.tsgo.go.` + P's segments with `.`
and `-` replaced by `_` (`strings` → `…tsgo.go.strings`, `unicode/utf8` → `…tsgo.go.unicode.utf8`,
`golang.org/x/text/language` → `…tsgo.go.golang_org.x.text.language`), and its shim follows the
same naming rules as ported code — so the lowering treats a shim exactly like a ported package. Types keep their Go name;
functions, methods, fields and vars become lowerCamelCase (`GetTypeOfSymbol` → `getTypeOfSymbol`);
constants keep their Go name. Kotlin hard keywords are escaped with backticks. A rename that collides
inside one Kotlin scope (Go `Foo` and `foo`) REFUSES the build until the rename table
(`-goport/renames.txt`) resolves it.

## 6. Traceability and licence

Every generated file starts with the D2 header (Microsoft copyright, derived from
`typescript-go` `typescript/v7.0.2`, modified; AGPL-3.0-only WITH the output exception).
Every generated declaration is preceded by `// go: <pkgpath>.<[Recv.]Name> <sha256-8>`, the
hash of the Go declaration's source text as the extractor reports it. An override in
`overrides/` carries the same line; a mismatch with the current extraction is STALE and fails
the porter run.
