# goport lowering — rules, pins, open refusals

The porter (`xemantic-typescript-compiler-goport`, JVM) lowers the IR of `docs/goport-ir.md` into
`xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/gen/` per the contract
`docs/goport-design.md`, against the runtime of `docs/goport-runtime.md`. This page is the
running record: what each rule does, where it lives, what pins it, and what is still refused.

```
cd xemantic-typescript-compiler-goport/goport-extract && GOTOOLCHAIN=local ../../tools/go/bin/go run . --tsgo ../../typescript-go-repo --out ../../build/goport/ir
flock $XTSC_GRADLE_LOCK ./gradlew :xemantic-typescript-compiler-goport:port          # wipes and rewrites gen/, prints the report
flock $XTSC_GRADLE_LOCK ./gradlew :xemantic-typescript-compiler-tsgo:compileKotlinJvm
TSGO_ORACLE=parse-only flock $XTSC_GRADLE_LOCK ./gradlew :xemantic-typescript-compiler-tsgo:jvmTest --tests '*OracleParityTest*' -i
```

The port task writes `build/goport/port/{report.txt,refused.tsv,lowered.txt}` and exits non-zero on
a STALE or ORPHAN override or a NAME COLLISION.

## 1. Measured (2026-10-07, first full run)

| | |
|---|---|
| Go lines (top-level decls of the 13-package closure) | 41,205 |
| lowered mechanically | **40,711 (98.8%)** |
| stubbed (refused, `TODO` stub with the real signature) | 474 |
| omitted (refused, no stub) | 19 |
| overrides | **1** (`diagnostics.placeholderRegexp`) |
| pinned refusals (`refuse.txt`, gaps outside the porter) | 5 |
| generated | 107 files, ~74k Kotlin lines; `compileKotlinJvm` warning-clean, ~20 s incremental |
| **parse-only encoded-AST byte equality** | **7,774 / 7,774** files (conformance 6,573, tsc 78, cronstrue 54, marked 53, type-fest 453, hono 311, rxjs 252), 0 crashes |

Caveats of that equality (each is a follow-up, § 5): it is against tsgo's parser+encoder WITHOUT
the binder (`build/goport/oracle-parse-only/`, `tsgo-oracle encode -parse-only -like`); the header's
xxh3-128 is copied from the oracle (the xxh3 shim has no hash function); the run is on a 1 GB-stack
thread (`binderBinaryExpressionStress` recurses past the default JVM stack, as in Go).

Per package (mechanical share): api/encoder 99.1, ast 99.6, collections 77.9, core 87.1, debug 100,
diagnostics 100, jsnum 93.6, json 93.0, locale 33.3, parser 100, scanner 99.6, stringutil 100,
tspath 87.3.

## 2. Architecture (who owns what)

| file (under `src/jvmMain/kotlin/com/xemantic/typescript/goport/`) | concern |
|---|---|
| `ir/Ir.kt` | raw JSON tree + accessors, package loader (schema check) |
| `types/GoTypes.kt` | the IR type table as a sealed model + `under`/`unalias`/method sets |
| `naming/Naming.kt` | package mapping, lowerCamelCase, keyword escaping, `renames.txt`/`refuse.txt` reader |
| `lower/Program.kt` | whole-run indices (extension methods, alias/struct sets, struct map keys, interface method names), `FileCtx` imports |
| `lower/TypeMapper.kt` | Go type → Kotlin type, zero value, `GoElem` element kind, type-parameter bounds, anonymous structs/interfaces |
| `lower/Literals.kt` | constants from the IR's exact values, byte-string literals |
| `lower/FnCtx.kt` | per-declaration local naming (unique across the whole function), frames, branch targets |
| `lower/Exprs.kt` | expressions: identifiers, operators, selectors/promotion paths, index/slice, composite literals |
| `lower/Calls.kt` | calls, argument packing, conversions, builtins, shim-call special cases |
| `lower/Stmts.kt` | statements: assignment forms, control flow, switch/type switch, range, defer, func literals |
| `lower/Decls.kt` | declarations: struct classes, value classes, typealiases, interfaces, functions/methods, consts, vars, overrides, stubs |
| `report/Report.kt`, `Main.kt` | accounting, file assembly (header, chunking, imports), synth/anon files |

The lowering classes are a chain `ExprLowering ← CallLowering ← Lowering`; a concern can be
worked on in its own file without touching the others.

## 3. Rules (and the pin or gate that sees them fail)

Representation (design § 3):

- **Struct** → plain class, all fields constructor `var`s with zero defaults, `goCopy()`, `goSet(o)`,
  `goEquals`/`goHash` when comparable, companion `ELEM`. Fields are `@JvmField` (a Go `SetText`
  method would otherwise clash with `text`'s setter); value-class-typed fields (incl. `UInt`/`ULong`)
  get `@get:/@set:JvmName("goGet_x")` instead. A struct used as a map KEY by value gets a structural
  `equals`/`hashCode` (`Program.structKeys`).
- **Embedding** → composition: the embedded value is a field named after its type; promoted
  fields/methods follow the IR's selection `path`; a promoted method an implemented interface
  requires becomes a delegating `override`.
- **Interface satisfaction** → nominal supertypes from the IR's `implements` (incl. `error` →
  `GoError`, `Unwrap` → `GoUnwrapper`, shim interfaces present in the shim index). Anonymous method
  interfaces become `gen/synth/Ifaces.kt` (`Iface_<methods>_<hash>`).
- **Named basic type** → `@JvmInline value class X(val value: T)`, `Comparable<X>` when ordered;
  arithmetic is lowered on the raw `.value` and re-wrapped (`ExprLowering.raw/wrap`).
- **Named slice/map/func type, and `type A B` over a named struct** (`MutableNode`, `Locale`) →
  `typealias` (Go assigns unnamed↔named without a conversion and the IR records none); their
  methods are extensions.
- **Pointer-receiver methods** are EXTENSIONS on `T?` unless an implemented interface needs them as
  members: Go calls them on a nil pointer and panics only where the body dereferences
  (`NodeFactory.UpdateSourceFile` with a nil factory). Found by the oracle run (146 crashes → 0).
- **Generics**: Kotlin generics; a type parameter with a composite core (`S ~[]E`) is replaced by
  its core and dropped from parameter/argument lists (the shims do the same). **Element-kind
  dictionaries**: generic struct classes carry `goElem_T: GoElem<T>` fields and generic functions
  take `goElem_T` parameters, so `var zero T`, `make([]T)` and arena slots are Go's zero, not
  `null` (`getSpellingSuggestion[string]` answering null instead of "" was the last byte diff).
- **Anonymous struct types** → one synthetic class per type per package (`AnonStructs.kt`).
- `int` → `Int`; narrow ints truncated after `+ - * / <<`, `^x`, conversions (`goInt8` …); shifts
  with a non-constant count use `goShl/goShr`; division uses Kotlin's (it throws like Go panics).
- Constants: literals from the IR's exact value; a named constant of the expression's own type is
  referenced by name; out-of-range for 32-bit `int` → refused (`int-overflow`).

Control flow (design § 4): every loop carries a label; `continue` in a 3-clause `for` with a post
statement uses a `first` flag so the post runs; a `switch` that a `break` targets is wrapped in
`run label@{}`; `fallthrough` appends the next clause's body; type switches are `when` with `is`
(erased generics as star projections); ranges over slice/array/int/string (UTF-8 decode via
`goDecodeRune`)/map (key snapshot + lookup)/func iterators (body becomes the yield anonymous
function); `defer` → `withDefers` (named results + defer still refused).

Shim interop: shim variadics are Kotlin `vararg` (packed args passed directly, spreads via
`toArray`/`toTypedArray`); shim function/struct-pointer parameters are non-null (`!!` at the call);
`utf8.DecodeRuneInString(s[i:])` → `decodeRuneInStringAt(s, i)`; external struct types without a
`goCopy`/zero constructor are detected from the shim sources (`ShimIndex`).

Files: one Kotlin file per Go file, split above 3,000 Go lines or 150 package variables (a JVM
`<clinit>` holds 64 KB); package variables sorted by the IR's `initOrder` (Kotlin rejects a forward
reference); literal tables over 150 elements are filled by hoisted private helpers; `func init()`
→ `init`, `init_1`, … called by a generated `goInitPackage()` (the caller runs it:
`parser.goInitPackage()` installs `ast.SetParseJSDocForNode`).

Pins: `-goport/src/jvmTest/.../LoweringRulesTest.kt` (naming, byte-string literals, constant edges);
the end-to-end gate is `-tsgo/src/jvmTest/kotlin/OracleParityTest.kt`.

## 4. Open refusals (Go lines)

| reason | lines | what removes it |
|---|---|---|
| local-type | 130 | `core.BreadthFirstSearchParallelEx` (also goroutines → override) |
| shim-missing | 90 | `jsontext` streaming, `reflect` (collections/core JSON + options reflection) — override or shim |
| constraint-as-type | 64 | `collections.SyncMap` methods: a type-set interface as a value type (generic `comparable` handling) |
| unsafe | 48 | `tspath.ToFileNameLowerCase` → override (byte view) |
| range-func-return | 40 | `return` inside a range-over-func body (needs a non-local exit flag) |
| pinned:shim-signature-big.Float.SetPrec | 31 | shim `Float.setPrec(UInt)` should take `ULong` (Go `uint`) |
| int-overflow | 16 | constants beyond 32-bit `int` (the design's `int` = `Int` assumption) |
| arith-non-basic, new-typeparam, chan, make(chan), pointer-method-on-value-type, shim-zero(language.Tag) | ~60 | individual rules/overrides |

## 5. Known semantic gaps (not visible in the parse-only gate)

- Binder flags: the full oracle needs `binder` ported (orchestrator: added to the closure).
- xxh3-128: shim lacks `HashString128`; the header hash is copied in the test.
- `goZeroTP` remains only where no dictionary is in scope (method values of generic functions).
- Struct copies are conservative (every IR `copy`): no escape analysis yet (perf).
- `huge_methods.py --classes xemantic-typescript-compiler-tsgo/build/classes/kotlin/jvm/main`:
  20 methods over the 8,000-bytecode JIT limit at the first census; chunking `<clinit>` (150 vars)
  and literal fillers (150 elements) took it to **3**, all giant `switch` statements:
  `diagnostics.keyToMessage` (54,743), `encoder` decoder switch (12,506),
  `encoder.getChildrenPropertyMask` (11,322 — on the encode hot path). A contiguous-range
  switch-splitting rule (CLAUDE.md (JIT.1)) is open. `scanner.scan` is 5,289.
