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

## 1b. (TSGO.2) closure — everything `internal/compiler` needs (2026-10-07)

`goport-extract`'s default closure is now `go list -deps ./internal/compiler` plus `bundled`: **42
packages, 181,376 Go lines** (checker 59,818). `--check`: no holes.

| | |
|---|---|
| Go lines of top-level declarations | 146,763 |
| lowered mechanically | **145,618 (99.2%)** |
| stubbed / omitted | 1,083 / 19 |
| overrides | 4 (`ast.getCombinedFlags`, `checker.hashWrite32`, `checker.hashWrite64`, `tsoptions.floatOrInt32ToFlag`: the four basic-set type-parameter generics, § 4) |
| `-tsgo` `compileKotlinJvm` | compiles, warning-clean, 0 methods over 8,000 bytecodes |
| bound oracle | 7,774 / 7,774 |
| **diagnostics differential** | **6,318 / 6,318** conformance configurations produce tsgo's exact diagnostics (`DiagParityTest`, `TSGO_DIAG=1 TSGO_TEST_HEAP=2g`, then `scripts/tsgo-diag-compare.py build/goport/diag-kotlin`; ~70 s for all) |
| **milestone** | `CheckerSmokeTest`: `compiler.NewProgram` over an in-memory `vfs.FS` + `bundled.WrapFS` → `GetSemanticDiagnostics` reports `TS2322: Type 'string' is not assignable to type 'number'.` for `const x: number = "s"`, and nothing for a well-typed file |

Lowered: 146,556 of 146,763 Go lines (**99.9%**). Open refusals, none reached by the compiler:
`core.BreadthFirstSearchParallelEx` (`go`, 130 lines; used by `project`), `core.LimitedSemaphore` /
`ThrottleGroup` (`chan`, used by `osvfs`/`ata`), `api/encoder.noStructuredData` (a uint32 sentinel
constant). Overrides: 4 (the basic-set type-parameter generics, § 4).

**How the differential found the defects** (each a rule, not a patch): `&s1[0] == &s2[0]` compared
elements instead of slots (unions over type parameters were never instantiated — ~250
configurations); struct-value map keys and `goEquals` through a struct alias of a shim struct
(`CacheHashKey`, the instantiation caches — flow analysis spun forever); a comparable struct handed to
a `comparable` generic (`slices.Contains(stack, RecursionId)`: constraint recursion ran to depth 50 and
a tuple grew past 10,000); Go's typed nil in a type switch; a package function shadowed by a method of
the same name inside an extension method; `packagejson.Fields` and pointer fields under json.

## 1c. (TSGO.2) the compiler test harness, ported (2026-10-08)

The diagnostics differential's driver reads the RAW case through tsgo's own harness
(docs/goport-diag-oracle.md § 4, the preferred route): the closure adds `execute/incremental`,
`vfs/iovfs`, `vfs/internal`, `vfs/vfstest`, `testutil/race` whole and the harness slices
(`testrunner`, `testutil/harnessutil`, `tsoptions/tsoptionstest`, `testutil`) as PARTIAL packages
(docs/goport-ir.md § 1), plus the oracle's own overlay copies of the runner's prepare block and the
harness's option derivation / check-only compile. **All of it lowers mechanically: 0 overrides, 0
stubs in the new packages** (`execute/incremental` 2,743, `testrunner` 351, `harnessutil` 741,
`vfstest` 562, `iovfs` 156, `vfs/internal` 165 Go lines); the existing packages' generated code is
byte-identical. What it took: shims for the `io/fs` FS family, `testing/fstest.MapFS`, `path`,
`testing.T`, `os.DirFS` (the first platform `actual` besides the thread park), `encoding/hex`,
`binary.BigEndian`/`Read`, `utf16.Decode`, `errors.AsType`, `strconv.ParseBool`, `strings.SplitSeq`,
`regexp.FindAllStringSubmatch` (docs/goport-runtime.md § 9); a hand-written `internal/repo`
(`go/internal_repo/Repo.kt`: it locates the checkout through `runtime.Caller`, which a port has not);
and three lowering rules (below). Entry points: `-tsgo/src/commonMain/kotlin/harness/Harness.kt`.

## 1d. (TSGO.4-a) the language service (2026-10-08)

The closure gains `internal/ls` and what it reaches (`lsp/lsproto`, `ls/lsutil`, `ls/lsconv`, `ls/change`,
`ls/autoimport`, `format`, `project/dirty`, `project/logging`, `vfs/wrapvfs`, `jsonrpc`; ~62k Go lines): 67
packages, 99.5% lowered mechanically (`ls` 99.4%, `lsp/lsproto` 100%). The gate is LsParityTest against
`tsc --lsp` (docs/goport-ls.md § 4): **21,614 / 21,614** requests equal. Rules added (each general, listed with
the defect that found it in docs/goport-ls.md § 1): Go 1.26 `new(expr)`; instantiated and generic-over-own-
parameters interface implementation (extractor + `supertypes`); reflect through a parameter
(`reflectThroughParameters`); struct context keys in `structKeys`; a generic shim's type-parameter argument
passed as is; a ported generic `*T` parameter instantiated with a non-struct T (`opaquePointerArgs`); an erased
container in a type test; a qualified `_Ptr` box.

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
`goCopy`/zero constructor are detected from the shim sources (`ShimIndex`, which reads `expect`/`actual`
declarations too — `atomic.Uint64` is an `expect` class). A shim struct WITHOUT `goCopy` is copied nowhere:
that is how an immutable shim value (`xxh3.Uint128`, `val` fields) opts out of Go's value copies.

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

(TSGO.2) rules, added for the compiler/checker closure:

- **Case collisions** (`Program.autoMethodRenames`/`autoFunRenames`): exported `Foo` and unexported `foo`
  on one type (or at one package's top level) — the unexported one becomes `fooImpl`, for EVERY method
  and interface method of that name in the package (unexported names are package-scoped). `renames.txt` wins.
- **Structural interface supers** (`Program.structuralIfaceSupers`): Go assigns an interface value to
  any interface whose method set is a subset, with no conversion in the IR — so a named interface
  extends every named interface of the run with a subset method set (equal sets: by key; never one
  that embeds it, which would be a cycle). Method identity = name (package-qualified when unexported)
  + parameter/result type keys (`Program.methodIdentity`, no parameter names, no receiver).
  Redeclared methods get `override`. Generic types (`implementsSkipped`) are matched the same way
  (`Program.genericImplements`).
- **Named empty interface** (`type TypeSystemEntity any`) → `typealias … = Any?`.
- **Pointers**: `*T` for an opaque type parameter is `T?` (its deref is an unchecked `as T`, no
  assertion: `*new(T)` is legitimately nil when T is a pointer); `*[N]T` is the `GoArray` reference
  (like a struct); `&[]T{}` / `&map…{}` is a `GoBox`.
- **Boxed named non-struct types** (`Program.boxedNamed`): a named slice/map/func type with an
  `implements` list (`glob.group`) gets a `<Name>_Box` class implementing the interfaces; an
  interface conversion wraps, a type assertion/switch unwraps `.value`.
- **Receivers**: a value-receiver extension on a non-nullable alias type takes a non-null receiver;
  a nil-safe (extension) method an implemented interface requires also gets a member that delegates
  through `synth.goNullable(this)`; `(*T)(nil).M()` with M never reading its receiver is called on a
  zero instance.
- **Shim generics with element dictionaries** (`ShimIndex.elemDictCount`): a shim function whose
  leading parameters are `GoElem<…>` (`slices.Collect`) gets `elem(targ)` per type argument.
- **Multi-value arguments** `f(g())` → `run { val ta = g(); f(ta.first, ta.second) }` (variadic
  packing applies). **`return` in a range-over-func body**: the yield function sets a flag/result
  and returns false; the function returns after the iterator call (nested loops share them).
- **Function-local types** are hoisted to the package as `<Name>_<function>` (not when they mention
  the function's type parameters).
- **Big structs** (`Program.bigStruct`, > 120 fields: `checker.Checker` has ~500 and a JVM
  constructor takes 255 slots) keep their fields in the class body; composite literals construct
  then assign.
- **`//go:embed`** (extractor: `ValueSpec.embed`): a string variable's file is read from
  `typescript-go-repo/internal/<pkg>/` and written as `EmbedData<n>.kt` (16,000-char byte-string
  constants, ~240 KB per file) plus `EmbedIndex.kt` (`goEmbed_<var>()` concatenates them); the
  variable is initialized from it. `bundled`'s 108 lib files are 3.9 MB of generated Kotlin.
- Smaller: lowercase (unexported) type names are always qualified (a field of the same name shadows
  them); a Boolean-subject `when` gets `else -> {}`; generic structs get `goEquals`/`goHash`; `math.MaxInt`/
  `MinInt` reaching an `int` are `Int.MAX_VALUE`/`MIN_VALUE`; `unsafe.String(&b[i], n)` is
  `goBytesToString(b.slice(i, i + n))`; `(*T)(nil)` conversions are the zero; extension methods used
  via method expressions are imported; anonymous-struct value-class fields use JvmName accessors.
- **Reflection by codegen** (`Program.reflectStructs`, `TypeMapper.reflectTypeInfo`, docs/goport-runtime.md
  § 12): structs reaching `reflect`/`json` become `GoReflectStruct` + `GoJsonStruct`; value classes are
  `GoBasicValue`s; `reflect.TypeFor`/`TypeAssert` are lowered with the static type; value-class pointer
  methods are extensions on `GoPtr<V>?` with a `<V>_Ptr` box. Every `reflect` user in the closure is mechanical.
- **Interface members for generic types and shim interfaces** (`Program.externalIfaces`): a generic type
  satisfying a shim interface (`OrderedMap` → json `MarshalerTo`/`UnmarshalerFrom`) declares it, so its
  methods are members the json shim can call. Method identities compare alias-free type keys (`canonKey`).
- `recover()` in a deferred func literal → the enclosing function's `GoDeferFrame.recover()`.
- **Slot identity** `&s1[i] == &s2[j]` (`core.Same`) compares `GoSlice.addr` slots — a struct element's `&`
  is its reference, but an element of a type parameter or a basic type is not.
- **Comparable structs** (`Program.structKeys`): a struct used as a map key by value, OR passed as the type
  argument of a `comparable` type parameter (`slices.Contains[RecursionId]`), gets a structural
  equals/hashCode; every other struct keeps identity (it doubles as Go's pointer). Struct fields and `==`
  of a struct alias of a shim struct use the shim's `goEquals`/`goHash`.
- **Typed nil** in a type switch: with no `case nil`, the first single-pointer-type clause whose body tests
  its variable against nil also takes null (`printer.tryGetEnd`).
- **Shadowed package functions**: an extension method's body qualifies package-level functions and
  variables named like a method of the receiver type (`module.mangleScopedPackageName`).
- **`&x.f` of an opaque type parameter flowing into an interface** (`json.Unmarshal(data, &e.Value)`) is a
  real pointer (GoFieldPtr/GoBox); an address-taken local of a type parameter is boxed.
- **json structs**: a struct EMBEDDING a json-tagged struct (`packagejson.Fields`) is a `GoJsonStruct` too.
- Debugging: `GOPORT_TRACE=<reason>` prints the porter stack of every refusal with that reason.

Extractor fixes: an indexed func-typed FIELD call (`m.targets[i]()`) is `call: "dynamic"`; the
underlying interface of `comparable` has key `interface{comparable}` (it collided with `any`).

Performance rules (docs/goport-perf.md § 4 — each is a lowering rule, never a hand edit of `gen/`):

- **Substring elimination** (`Exprs.windowOf`, `Calls.viewCandidates`/`declareView`/`FUSIONS`).
  Go's `s[a:b]` is an O(1) view; Kotlin's `substring` copies, and a suffix of the source text per
  token made the scanner quadratic (134x). A string slice is lowered as a WINDOW `(base, from, to)`
  where it only feeds: (a) the first argument of a fused `strings` call — `HasPrefix`, `HasSuffix`,
  `Index`, `Contains`, `IndexByte`, `IndexRune`, `IndexAny`, `ContainsAny` → the shim's
  `<name>At(s, from, …)` / `<name>In(s, from, to, …)` (an index result relative to `from`;
  rune-decoding ones have only the suffix form); (b) the LEFT operand of `==`/`!=` →
  `goStrEqAt`/`goStrEqIn`; (c) a **view local** — `t := s[a:b]` (or `var t = …`), never reassigned
  or address-taken, used only as `len(t)`, `t[k]`, or a sub-slice that is itself (a)/(b) — lowers
  to three locals `t_b`/`t_o`/`t_n` (`goStrView` checks Go's bounds once, `goViewByte` per index,
  `goViewBound` for a sub-slice's bounds, checked against the VIEW as Go does). Anything else keeps
  `substring`. First run: 19 sites rewritten (14 fused calls, 4 equalities, 1 view local);
  single-bound copies in `gen/` 48 → 34. Pins: `WindowShimTest` (`-tsgo`, values printed by
  go1.27.1), `LoweringRulesTest` (named parse-path functions carry no copy; census does not grow).

- **Single-probe comma-ok map read** (`Stmts.multi`, map `range`). `v, ok := m[k]` lowered to
  `GoMap.lookup` — `containsKey` + `get` (two hash probes) + a `Tuple2` per call — and
  `internIdentifier` does one per identifier. Now `val t = m.probe(k)` (one probe; a second only
  when the stored value is `null`), `v = goProbeValue<V>(t) { zero }` (inline, the zero built only
  when absent), `ok = t !== GoMapAbsent`; a struct value is `goCopy()`d as Go copies it (as the map
  `range` already did). Every generated `GoMap.lookup` call is gone. Pins: `MapProbeTest`,
  `LoweringRulesTest`.

- **Inline func-typed parameters** (`Program.computeInlineFuncs`, `Decls.funcDecl`, `Calls.inlineArgs`,
  `Calls.dynamicCall`). A func-typed parameter is a `Function1` called through `invoke(Object)` —
  boxed `Int`, `!!`, and megamorphic when one call site sees several lambdas (`scanASCIIWhile`:
  six predicates). A ported function is emitted `inline` when its body is at most
  `INLINE_MAX_LINES` = **15** Go lines (the body is copied into every caller, and the JIT's
  8,000-bytecode limit counts the copies — `huge_methods.py` is the gate; 15 admits the
  `core.Map`/`Filter`/`Find` family, `visit`/`visitNodes`, `lookAhead` and `scanASCIIWhile`, and
  keeps `parseList`-sized bodies out); it is top-level or an EXTENSION method (an interface member
  cannot be inline); every func-typed parameter is only ever CALLED (never nil-tested, stored,
  returned, reassigned, address-taken or passed on); the body has no function literal (Kotlin
  refuses a closure over an inline parameter and local functions in an inline body), no `defer`,
  no `go`, no range over a function iterator; it does not reference itself; it has no hand
  override; and no call site in the run passes `nil` for such a parameter. Its func-typed
  parameters are declared NON-null with the UNDERLYING signature (a named func type is a nullable
  typealias — `Visitor`), called without `!!`; a caller passing a func VALUE asserts it (`v!!`), a
  function literal is passed as is (Kotlin inlines anonymous functions, and a `return` in one is
  local, as in Go). A body that hoists private helpers keeps the non-null parameters but not
  `inline` (a public inline function cannot reference a private one). First run: **32**
  functions, every report lists them. Measured effect on the parse bench: within noise (§ perf
  progress table) — the `Function1` dispatch was not the cost of `scanASCIIWhile`'s 15.5%; the
  per-character loop is. Pin: `LoweringRulesTest`.

- **Window parameters** (`Program.computeWindowFuncs`, `Decls.windowOverload`, `Calls.funcCall`).
  A top-level, non-generic, non-variadic function whose ONE string parameter is used only as a
  view (`len`, index, a sub-slice feeding a fused call or `==` — the view-local test, shared as
  `CallLowering.viewUseViolations`), never reassigned or address-taken, and to which some call in
  the run passes a string slice, gets an overload `<name>Win(…, p_b: String, p_o: Int, p_n: Int, …)`
  whose body reads `p` as a view. A call passing `s[lo:hi]` with a side-effect-free base (a name or
  field chain — it is evaluated twice) calls the overload with `goStrView` checking Go's bounds; the
  copying function stays for every other caller. If the overload's body cannot be lowered as a
  view (refusal, hoisted helper), it is a delegation that copies — callers are decided before the
  body is lowered, so the overload always exists. First run: 1 function (`parser.isJSDocLikeText`,
  the per-JSDoc-comment suffix copy). Pin: `LoweringRulesTest`.

- **Window fields** ((TSGO.6-g), `Program.computeWindowFields`, `ExprLowering.viewOf`/`fieldSelect`,
  `Lowering.windowStore`, `Decls.structClass`). A `string` struct field in `WINDOW_FIELD_CANDIDATES`
  (`parser.Parser.sourceText`, `scanner.Scanner.text`) of a struct Go never compares, hashes or reflects, and
  that no `&x.f` takes the address of, is three slots `f`, `f_o`, `f_n` (base, offset, length; the length
  defaults to `f.length`, so a composite literal naming only `f` stays exact). A read in a view position (`len`,
  `x.f[k]`, a sub-slice fed to a window call, a `utf8` decode) reads the window with Go's bounds; a sub-slice as a
  string is one checked `goViewSubstring`; any other read materializes it (`goStrWin`: the base itself when the
  window covers it, so outside a narrowed window nothing is copied); a store of `s[lo:hi]`, of another window or
  of a window parameter stores the window, any other string stores `(s, 0, len(s))`; an op-assignment refuses.
  Window parameters extend to METHODS of non-generic structs for this (`Scanner.SetText` → `setTextWin`, imported
  across packages like any extension). It removes the parser's per-JSDoc-comment copy of the file prefix
  (`p.sourceText = p.sourceText[:end-2]`; docs/goport-perf.md § 8.2). Pins: `LoweringRulesTest`, `WindowShimTest`.
- **Immutable structs** ((TSGO.6-g), `Program.computeImmutableStructs`). A struct in `IMMUTABLE_STRUCT_CANDIDATES`
  (`checker.FlowType`) is lowered with `goCopy() = this` when the whole run proves Go never changes one in place:
  no field of it is assigned (`=`, op-assign, `++`, a range target) and none is address-taken; no `*p = v`
  or element store of the type; no `&v` of a variable, field or element of it (a pointer to STORAGE that a later
  store would have to be visible through); no `==` on `*T`; no `*T` into an interface; and every implicit `&x`
  (`autoAddr`) is the receiver of a CALL to a PURE pointer method — one whose body uses the receiver only to read
  a field, deref, or call another pure method (fixpoint). Such a local is then a plain rebindable reference
  (`var t = …; t = f()`), not an address-stable object assigned by `goSet`. Refusals are printed by the porter.
  Pin: `LoweringRulesTest`.
- **Abstract-class interfaces** ((TSGO.6-i), `Program.computeAbstractIfaces`, `Decls.ifaceDecl`/`superCall`). An
  interface in `ABSTRACT_IFACE_CANDIDATES` (`ast.nodeData`, `checker.TypeData`) is lowered as an `abstract class`
  (`abstract fun` members) when the whole run proves it legal: every named type whose IR `implements` lists it is a
  STRUCT class (never a value class, a `<Name>_Box`, a `<V>_Ptr` or a typealias — Kotlin gives a class one superclass
  and a value class none), no type implements two of them, and no other interface embeds it or extends it
  structurally (an interface cannot extend a class). Implementers then name it with a constructor call
  (`: nodeData()`); promoted-method forwarding overrides are unchanged. The JVM dispatches a call on it through a
  vtable instead of an itable scan (docs/goport-perf.md § 11). Refusals are printed by the porter; the non-struct
  emitters refuse the run if one would extend such a class. Pin: `LoweringRulesTest`.

- **Pooled locals** ((TSGO.6-j), `Program.computePooledLocals`, `lower/PooledLocals.kt` `EscapeAnalysis`, `Stmts.body`,
  `Decls.structClass`). A `var b T` local of a struct in `POOLED_LOCAL_CANDIDATES` (`checker.keyBuilder`) is taken from a
  per-thread LIFO stack (`runtime.GoLocalPool`: `val s = T.POOL.stack(); val b = s.acquire(); try { <rest of the body> }
  finally { s.release() }`) when the whole run PROVES it never outlives its function: declared with no value, alone,
  at the top level of a top-level function's body with no `go` and no `defer`; and every occurrence is the receiver of a
  pointer method whose receiver is NON-RETAINING, a field select that is the receiver of a `NON_RETAINING_SHIM_TYPES`
  method (`b.h.Write`, the shim read by hand) or of a non-retaining method, or `&b.h` / `&b` passed straight to a
  non-retaining parameter; an occurrence inside a function literal only when that literal is assigned to a local that is
  otherwise only declared and called. A pointer parameter/receiver of a candidate or non-retaining shim type is
  non-retaining by the same rules (where passing the pointer straight on also counts) — a greatest fixpoint over the
  run. Everything else (return, store, copy, comparison, interface conversion, a call through a func value or an
  interface, a variadic slot, a callee the run does not declare) refuses the local, which keeps `T()`. The struct gets
  `goReset()` (basic fields zeroed, a shim struct field's own `goReset()`, which keeps its buffers) and a companion
  `POOL`; a candidate with any other field kind is refused. Overrides of a non-retaining function (`hashWrite32`) are
  trusted to keep Go's semantics, retention included. The stack is per THREAD and a goroutine runs on one thread to
  completion, so nesting (the checker re-entering key building) takes the next slot and parallel checkers never share.
  First run: 13 of 13 locals, 14 non-retaining parameters (docs/goport-perf.md § 12.1). Pins: `LoweringRulesTest` (the
  13 sites, and a synthetic IR with a pooled local and three refused ones — `&b` into a retaining parameter, a `go`,
  an escaping closure), `GoLocalPoolTest`.

(TSGO.2) harness rules:

- **A `switch` whose tag is an array or a comparable struct compares by VALUE**: the tag is bound
  once and every case tests `tag.goEquals(case)` (`when { … }`), because Kotlin's `when (x)` uses
  `equals`, which is identity for `GoArray` and struct classes. Found by the harness's `case.json`
  cross-check: `vfs/internal.decodeBytes`'s `switch bom { case [2]byte{0xFF, 0xFE}: … }` never
  matched, so UTF-16 case files read as garbage (no directives, wrong configurations).
- **`unsafe.Slice(unsafe.StringData(s), n)`** is a byte view of a string → `goStringToBytes(s).slice(0, n)`
  (the mirror of the `unsafe.String` rule; `vfstest.MapFS.WriteFile`).
- **A shim method that is a Kotlin EXTENSION** (a method of a shim `typealias`, e.g.
  `fstest.MapFS.open`) is imported at the call site (`ShimIndex.isExtension`), as ported extension
  methods already are.

Check-path performance rules (docs/goport-perf.md § 6; each changes no answer, all pinned in
`LoweringRulesTest`):

- **Slot identity**: `&a[i] == &b[j]` (`core.Same`) → `a.sameSlot(i, b, j)` — the same panics, no
  `GoElemPtr` objects.
- **`strings.LastIndex` / `LastIndexByte`** join the substring-elimination fusions (`lastIndexAt/In`):
  `parseJSDocComment`'s indent copied the file prefix per JSDoc comment.
- **A conversion of a FRESH value is fresh** (`Exprs.freshValue`): `return CacheHashKey(b.h.Sum128())`
  no longer copies the struct it just made. The IR's `copy` treats every conversion as non-fresh.
- **`return x` of an owned struct local** (`Lowering.ownedLocals`): a `var` never captured nor
  address-taken, only ever defined or assigned from ONE value (each such flow copies or is fresh), is
  handed out without a copy. Type-switch bindings, range variables and multi-value / comma-ok targets
  are excluded (their components are not copied in). A pointer-receiver method call takes the
  address (`autoAddr`), so `getTypeAtFlowNode`'s `t` keeps its copy.
- **`for k, v := range m`** → `val it = m.iter(); while (it.next()) { it.key; it.value }`: one copy
  of the table instead of a key list plus a probe per entry (`GoMapIter`, docs/goport-runtime.md § 3).

Override for performance (the fifth): `core.Arena.New` returns a fresh zero `T` (the slab bought
nothing on the JVM, where every struct is its own object; the override's header argues it is
unobservable).

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
