# goport lowering — rules, pins, open refusals

The porter (`xemantic-typescript-compiler-goport`, JVM) lowers the IR of `docs/goport-ir.md` into
`xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/gen/` per the contract
`docs/goport-design.md`, against the runtime of `docs/goport-runtime.md`. This page is the
running record: what each rule does, where it lives, what pins it, and what is still refused.

```
cd xemantic-typescript-compiler-goport/goport-extract && GOTOOLCHAIN=local ../../tools/go/bin/go run . --tsgo ../../typescript-go-repo --out ../../build/goport/ir
flock $XTSC_GRADLE_LOCK ./gradlew :xemantic-typescript-compiler-goport:port          # wipes and rewrites gen/, prints the report
flock $XTSC_GRADLE_LOCK ./gradlew :xemantic-typescript-compiler-tsgo:compileKotlinJvm
TSGO_ORACLE=bound flock $XTSC_GRADLE_LOCK ./gradlew :xemantic-typescript-compiler-tsgo:jvmTest --tests '*OracleParityTest*' -i
```

`TSGO_ORACLE=bound` is THE gate (parse → `binder.bindSourceFile` → encode, against the binary's
bytes in `build/goport/oracle/`); `TSGO_ORACLE=parse-only` is the earlier stage (no binder, against
`build/goport/oracle-parse-only/`). Either mode FAILS on any differing or crashing file;
`TSGO_ORACLE_REAL_HASH=0` copies the header's xxh3-128 from the oracle instead of computing it
(debug only). The env vars are Gradle test inputs, so a mode change re-runs the task. The
always-on half is `TsgoPinTest`: five tiny committed fixtures (`src/jvmTest/resources/tsgo-pins/`,
every binder-set flag, JSX, JSDoc-in-`.js`, non-ASCII) with expected bytes from
`tsgo-oracle encode -as /tsgo-pins/X` (the `-as` flag records a stable virtual fileName), plus a
negative control that unbound bytes differ.

The port task writes `build/goport/port/{report.txt,refused.tsv,lowered.txt}` and exits non-zero on
a STALE or ORPHAN override or a NAME COLLISION.

## 1. Measured (2026-10-07, binder added to the closure)

| | |
|---|---|
| Go lines (top-level decls of the 14-package closure, incl. `binder`) | 44,395 |
| lowered mechanically | **43,948 (99.0%)** (`binder` 3,190 / 3,190 = 100%) |
| stubbed (refused, `TODO` stub with the real signature) | 413 |
| omitted (refused, no stub) | 19 |
| overrides | **1** (`ast.getCombinedFlags`, 15 lines) |
| pinned refusals (`refuse.txt`) | 0 |
| switch-split functions (JIT.1) | 3 (`keyToMessage` 40 parts, `astDecoder.createChildrenNode` 6, `getChildrenPropertyMask` 4) |
| generated | 117 files; `compileKotlinJvm` warning-clean |
| **bound encoded-AST byte equality (THE gate), real xxh3-128** | **7,774 / 7,774** files (conformance 6,573, tsc 78, cronstrue 54, marked 53, type-fest 453, hono 311, rxjs 252), 0 crashes |
| parse-only equality | 7,774 / 7,774 |

The run is on a 1 GB-stack thread (`binderBinaryExpressionStress` recurses past the default JVM
stack, as in Go). The binder needed no new lowering rule: one rename (`binder.bindSourceFile` →
`bindSourceFileImpl`, colliding with the exported `BindSourceFile`) and one override
(`ast.getCombinedFlags`, previously an unreached stub: `flags |= …` on a `T ~uint32` type
parameter — arithmetic Kotlin generics cannot express; it goes when the lowering monomorphizes
generic functions over basic-core type parameters).

Per package (mechanical share): api/encoder 100.0, ast 99.9, binder 100.0, collections 77.9,
core 87.1, debug 100, diagnostics 100, jsnum 100, json 98.6, locale 100, parser 100, scanner 99.6,
stringutil 100, tspath 95.9.

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

- **Package-level name collisions**: two top-level Go functions whose lowerCamel names coincide
  (`BindSourceFile` / `bindSourceFile`) with identical parameter types are Kotlin conflicting
  overloads; the port REFUSES the run (`NAME COLLISIONS`) until `renames.txt` separates them
  (`PackageEmitter.checkPackageCollisions`). Different parameter types are legal overloads and pass.
- **Switch splitting (JIT.1)** (`lower/SwitchSplit.kt`): a function whose largest top-level
  `switch` spans > 20,000 source bytes becomes a dispatcher plus parts of <= 8,000 source bytes of
  clauses. Each part is the WHOLE body lowered again with the switch restricted to its clauses
  (default, pre-switch statements and tail in every part), so a part is the original function for
  every value it is handed; the dispatcher evaluates the tag once and calls one part. Preconditions:
  tag = an unreassigned parameter (or a field chain on one, with nothing before the switch), no
  init, no `fallthrough`, constant cases, no own `defer`, not variadic, no hoisted helpers in a part.
  An `Int` tag dispatches with a `when` over the constants (clauses grouped in source order, the
  first-written cases in part 0 — tsgo's tables give no hotness profile); a byte-string tag
  dispatches by `<` over contiguous ranges of the sorted constants (one constant per clause
  required; `String.compareTo` is the same on every target). The report lists every split.

Pins: `-goport/src/jvmTest/.../LoweringRulesTest.kt` (naming, byte-string literals, constant edges);
the end-to-end gates are `-tsgo/src/jvmTest/kotlin/OracleParityTest.kt` (corpus, opt-in) and
`TsgoPinTest.kt` (always on).

## 4. Open refusals (Go lines)

| reason | lines | what removes it |
|---|---|---|
| local-type | 130 | `core.BreadthFirstSearchParallelEx` (also goroutines → override) |
| shim-missing | 90 | `jsontext` streaming, `reflect`, `go-json-experiment/json/jsontext.Null` (collections/core JSON + options reflection) — override or shim |
| constraint-as-type | 64 | `collections.SyncMap` methods: a type-set interface as a value type (generic `comparable` handling) |
| unsafe | 48 | `tspath.ToFileNameLowerCase` → override (byte view) |
| range-func-return | 40 | `return` inside a range-over-func body (needs a non-local exit flag) |
| int-overflow | 16 | constants beyond 32-bit `int` (the design's `int` = `Int` assumption) |
| receiver-type-refused, pointer-method-on-value-type, make(chan), chan | ~44 | individual rules/overrides |

Cleared 2026-10-07 by the shim round (d5b67557d): the pinned `big.Float.SetPrec` (now `ULong`)
and `jsontext` Begin/End token refusals, `shim-zero(language.Tag)` (locale 33% → 100%), and the
`diagnostics.placeholderRegexp` override (the regexp shim reads RE2 syntax).

## 5. Known gaps

- `goZeroTP` remains only where no dictionary is in scope (method values of generic functions).
- Struct copies are conservative (every IR `copy`): no escape analysis yet (perf).
- `huge_methods.py --classes xemantic-typescript-compiler-tsgo/build/classes/kotlin/jvm/main
  --fail-over 0`: 20 methods over the 8,000-bytecode JIT limit at the first census; chunking
  `<clinit>` (150 vars) and literal fillers (150 elements) took it to 3 giant switches, and the
  switch-splitting rule (§ 3) to **0**. `scanner.scan` (5,289) is the largest remaining method.
