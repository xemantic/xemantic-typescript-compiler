# goport IR — schema of `goport-extract`'s output

The IR is the ONLY input of the Kotlin lowering (`xemantic-typescript-compiler-goport`). It is a
typed dump of tsgo's `go/ast`, annotated with everything `go/types` decided: the type of every
expression, the object behind every identifier, every selection path, every implicit conversion
and copy, method sets, structural interface satisfaction, and per-variable aliasing facts. A
lowering author should never need to read Go source or reason about Go typing rules; where the
IR does not settle a question, that is an extractor bug — report it (see § 9, *Limits*).

The contract the IR serves is `docs/goport-design.md`; the plan is `docs/tsgo-port-plan.md`.
The closure census produced from the same run is `docs/goport-closure-stats.md`.

## 1. Running it

```
GOTOOLCHAIN=local tools/go/bin/go build -C xemantic-typescript-compiler-goport/goport-extract -o /tmp/goport-extract .
/tmp/goport-extract [--tsgo DIR] [--out DIR] [--stats FILE] [--check] [pkg ...]
```

- `pkg` is a path under tsgo's `internal/` (`ast`, `api/encoder`) or a full import path. With
  none, the spike closure: `ast diagnostics parser stringutil scanner api/encoder core tspath
  collections jsnum json debug locale`.
- `--tsgo` defaults to `<repo>/typescript-go-repo`, `--out` to `<repo>/build/goport/ir` (gitignored).
  The repo root is found by walking up from the working directory to the directory holding
  `typescript-go-repo/`; the extractor prepends `<repo>/tools/go/bin` to `PATH` so `go/packages`
  runs the pinned toolchain.
- `--stats FILE` writes the closure census (markdown). `--check` lists IR holes and exits 1 if
  there are any (§ 8).
- The extractor refuses (exit 2) if tsgo does not type-check.
- Output is **byte-deterministic**: two runs produce identical files (verified by sha256; the
  sums are in `index.json`). The IR of a package does depend on the SET of packages in the run
  (the `implements` candidates of § 5.3, the `at` positions of § 6 and the closure/external
  split), so always extract the porter's whole set in one run.

## 2. Files

| file | content |
|---|---|
| `<pkg>.json` | one Go package; name = import path minus the module prefix, `/` → `_` (`internal_api_encoder.json`) |
| `index.json` | `{schema, module, packages: [...]}`; per package `path, name, file, sha256, bytes, lines, generatedLines, decls, types, objects, scopes, files: [{name, generated, lines, decls}]` |

All JSON is UTF-8. Top-level tables are written one element per line (diffable); nodes are
compact. **Byte-exact data is base64** (`b64`): Go strings are byte strings, and JSON text would
mangle invalid UTF-8. Only names, type keys, doc comments and raw literal spellings are JSON text.

## 3. Package object

```
{ "schema": 1, "path": "github.com/microsoft/typescript-go/internal/core", "name": "core",
  "files": [File...], "initOrder": [Init...],
  "types": [Type...], "objects": [Object...], "scopes": [Scope...] }
```

- `schema` — bumped on every incompatible change. A lowering must refuse an unknown schema.
- **Ids are dense integers LOCAL to the file**: `types[i].id == i`, `objects[i].id == i`,
  `scopes[i].id == i`. They are assigned in first-encounter order of a deterministic walk
  (files sorted by name, source order). The **cross-package join key** is `key` (types,
  package-level objects, methods, fields of named structs) — ids never cross a file.
- `initOrder` — `go/types`' package-variable initialization ORDER (Go initializes package
  variables in dependency order, not source order): `[{lhs: [objectId...], file, o, e}]`, where
  `file/o/e` locate the initializer expression (the `values` entry of a `ValueSpec`). Package
  variables NOT listed have no initializer (zero value). `init` functions run after all of them,
  in file order then source order (they appear as `FuncDecl` named `init`; § 4.2).

### 3.1 File

```
{ "name": "core.go", "generated": false, "package": "core", "goVersion": "go1.26",
  "imports": [{"path": "iter", "name"?: "alias", "obj"?: objectId}],
  "lines": 512, "size": 13500, "lineOffsets": [0, 13, 14, ...],
  "decls": [Decl...] }
```

- `generated` — the file carries `// Code generated ... DO NOT EDIT.` (`ast.IsGenerated`).
- `goVersion` — language version in effect. Go ≥ 1.22: a `for` loop variable is a FRESH variable
  per iteration (matters when a closure captures it, § 6 `captured`).
- `lineOffsets[i]` — byte offset of line `i+1`. Every node carries byte offsets `o` (start,
  inclusive) and `e` (end, exclusive) into the file; statements and declarations also carry `line`.
- `imports[].obj` — the `pkgname` object (§ 6) the file's identifiers refer to.

## 4. Declarations

### 4.1 Common fields

Every top-level declaration has `k`, `qname`, `line`, `lines` (line count), `o`, `e`,
`hash`, optional `doc` (comment text), and `facts` (§ 4.4).

- **`qname`** — the Go qualified name: `pkgpath.Name` or `pkgpath.Recv.Name` (Recv = base type
  name, no `*`, no type arguments). Unique within a package: a repeat (`init`, `_`) gets `#2`,
  `#3`… in source order. A `GenDecl` has `pkgpath.<tok>@<line>` (`…core.var@2255`); each of its
  specs carries the per-name qnames (`ValueSpec.qnames`, `TypeSpec.qname`).
- **`hash`** — full hex sha256 of the exact source bytes `[o, e)` of the declaration, EXCLUDING
  its doc comment (a `FuncDecl` from `func` to the closing brace; a `GenDecl` from the keyword;
  each top-level spec also has its own `hash`). This is the `// go: <qname> <sha256-8>` value of
  `docs/goport-design.md` § 6 and the override staleness key.

### 4.2 `FuncDecl`

```
{ "k": "FuncDecl", "qname", "name", "obj": objectId, "recv"?: FieldList, "type": FuncType,
  "body": BlockStmt, "external"?: true, "facts": {...}, ... }
```

- `obj` — the `func`/`method` object; its `t` is the full signature (with receiver).
- `type` — a `FuncType` node; its `t` is the signature (go/types records no TypeAndValue for a
  declaration's FuncType; the extractor supplies it from the object).
- `external: true` — no Go body (assembly/linkname); does not occur in the spike closure.
- Methods: `recv` is the receiver field list; generic receivers (`func (s *Set[T])`) declare
  their OWN type parameters (`signature.recvTparams`), distinct from the type's.

### 4.3 `GenDecl` and specs

```
{ "k": "GenDecl", "tok": "import"|"const"|"type"|"var", "grouped"?: true, "specs": [...] }
ImportSpec { "k", "path", "name"? }
ValueSpec  { "k", "line", "names": [Ident...], "qnames"?: [...], "type"?: Expr, "values"?: [Expr...],
             "iota"?: n, "implicitRepeat"?: true, "hash"?, "doc"? }
TypeSpec   { "k", "line", "name", "qname"?, "nameNode": Ident, "tparams"?: FieldList,
             "alias"?: true, "type": Expr, "hash"?, "doc"? }
```

- `qnames`/`qname`/`hash` are present only on TOP-LEVEL specs (a `DeclStmt` inside a function
  carries a `GenDecl` without them).
- **Constants**: `iota` is the spec's index in its group. `implicitRepeat: true` marks a spec with
  no values (`KindB` after `KindA Kind = iota`): Go repeats the previous spec's type and
  expression. The lowering never re-evaluates: every constant OBJECT carries its exact value
  (`c`) and type (`t`).
- `values` of a `var` spec carry the conversion/copy annotations of § 7 against the declared
  type. A single multi-value initializer (`var a, b = f()`) appears as ONE value with
  `implTuple` (§ 7.3).
- Type-declaration entries in the TYPE table (method sets, `implements`) are always full for
  types declared in this package.

### 4.4 `facts` (per top-level declaration; closures nested inside count toward it)

All optional; absent = 0/false.

| field | meaning |
|---|---|
| `defer`, `panic`, `recover`, `goto`, `fallthrough`, `labels` | occurrence counts |
| `go`, `chanOps`, `select` | `go` statements; sends, receives, `close`, `make(chan)`, chan types, ranges over channels; `select` statements — **refused** by the lowering (override) |
| `unsafe`, `reflect` | uses of package `unsafe` / `reflect` objects — **refused** |
| `closures` | func literals |
| `locals`, `addrLocals`, `capturedLocals` | local variables defined; how many are address-taken / closure-captured |
| `structCopies` | `copy`/`valueCopy`/`recvCopy` annotations (§ 7) |
| `typeParams` | the declaration is generic (own or receiver type parameters) |
| `instantiates` | it instantiates a generic function or type |
| `multiResult`, `namedResults`, `bareReturn` | result shape; `bareReturn` counts `return` with no operands in a function with results (returns the named results) |

## 5. Statements

Every statement: `k` (the `go/ast` type name), `o`, `e`, `line`, plus:

| `k` | fields |
|---|---|
| `BlockStmt` | `list: [Stmt]` |
| `ExprStmt` | `x` |
| `DeclStmt` | `decl: GenDecl` (local const/type/var) |
| `EmptyStmt` | `implicit?` |
| `LabeledStmt` | `label: Ident` (object kind `label`), `stmt` |
| `BranchStmt` | `tok`: `break`/`continue`/`goto`/`fallthrough`, `label?: Ident` |
| `AssignStmt` | `tok`: `=`, `:=`, or an op-assign (`+=`, `<<=`, `&^=` …); `lhs: [Expr]`, `rhs: [Expr]` |
| `IncDecStmt` | `x`, `tok` (`++`/`--`) |
| `ReturnStmt` | `results?: [Expr]`, `bare?: true` (no operands; returns named results) |
| `IfStmt` | `init?`, `cond`, `body`, `else?` (`IfStmt` or `BlockStmt`) |
| `ForStmt` | `init?`, `cond?`, `post?`, `body` |
| `RangeStmt` | `tok` (`:=`, `=`, or `ILLEGAL` when there is no key/value), `x`, `key?`, `value?`, `body`, `rk`, `kt?`, `vt?`, `valueCopy?` |
| `SwitchStmt` | `init?`, `tag?`, `clauses: [CaseClause]` |
| `CaseClause` | `list?: [Expr]` (each annotated against the tag type, § 7), `default?: true`, `body: [Stmt]` |
| `TypeSwitchStmt` | `init?`, `bind?` (name in `x := y.(type)`), `x` (the operand `y`), `clauses: [TypeCaseClause]` |
| `TypeCaseClause` | `types?: [Expr]` (type expressions, or `nil`), `default?`, `implicit?: objectId`, `body` |
| `SelectStmt` | `clauses: [CommClause]` — refused |
| `CommClause` | `comm?: Stmt`, `default?`, `body` |
| `SendStmt` | `chan`, `value` — refused |
| `GoStmt`, `DeferStmt` | `call: CallExpr` |

Notes:

- **`:=`**: a LHS identifier with `def: true` is a new variable (its type is on its object). An
  identifier WITHOUT `def` in a `:=` re-assigns an existing variable (Go allows this when at least
  one name is new). `_` has `blank: true` and no object.
- **Op-assign** (`x += y`) is not decomposed; `lhs[0]`'s type is the operation's type.
- **`rk`** (range kind) with key type `kt` and value type `vt`:
  `slice`, `array`, `ptrarray` (pointer to array) → `kt` int, `vt` element;
  `string` → `kt` int (BYTE offset), `vt` rune — **iteration decodes UTF-8** and steps by
  rune length; an invalid byte yields U+FFFD with width 1;
  `map` → key/elem (iteration order is unspecified in Go; tsgo does not depend on it);
  `int` (`for i := range n`) → `kt` = the type of `n`;
  `chan` → `kt` = element (refused);
  `func` (range-over-func iterator `func(yield func(K[, V]) bool)`) → yield's parameter types.
  A type-parameter operand is resolved through its core type (§ 5.2 of the type table).
- **`valueCopy: true`** on a `RangeStmt`: the value variable receives a COPY of a struct/array
  element each iteration.
- **Type switch**: Go gives each clause its OWN variable named `bind`, typed as the clause's type
  if the clause lists exactly one type, else as the operand's type. That object is the clause's
  `implicit`; identifiers in the clause body reference it. A clause type `nil` is an `Ident`
  with `m: "nil"`.
- Labels: a labeled `for`/`switch`/`select` is `LabeledStmt{label, stmt}`; `break L`/`continue L`
  name the label object. Labels live in a per-function `labels` scope.
- `fallthrough` is the last statement of a `CaseClause` body.

## 6. Expressions

Every expression node: `k` (go/ast type name), `o`, `e`, and — when `go/types` recorded a
TypeAndValue for it (every expression except the cases in § 8) —

| field | meaning |
|---|---|
| `t` | type id (the expression's FINAL type: an untyped constant used as `int8` has `t` = int8) |
| `m` | mode: `value`, `variable` (addressable), `const`, `type` (a type expression), `builtin` (a builtin function name), `nil` (the untyped nil), `void` (call with no result), `mapindex` (`m[k]`: assignable, not addressable), `commaok` (type assertion or receive usable in comma-ok form) |
| `c` | constant value (when `m == "const"`, and on constant-valued sub-expressions): `{kind:"int", v:"<exact decimal>"}`, `{kind:"float", v:"<exact rational or decimal>", f64:"<shortest float64>"}`, `{kind:"string", b64}`, `{kind:"bool", v}`, `{kind:"complex", re, im}` |

Untyped constants: when the final type is still untyped (e.g. a constant only used in a constant
context) `t` is an untyped basic type whose entry carries `default` (the type Go assigns when
the constant is used as a value). Everywhere a constant flows into a typed context, `t` is
already that type. Constant folding is done: the lowering emits `c` and never re-evaluates
constant arithmetic (including overflowing intermediate `untyped int` math).

### 6.1 Nodes

| `k` | fields |
|---|---|
| `Ident` | `name`; `obj?`; `def?: true` (a definition); `blank?: true` (`_`); `inst?: {targs: [typeId], t: typeId}` (an instantiated generic function/type: type arguments, explicit or INFERRED, and the instantiated type) |
| `BasicLit` | `kind` (`INT`/`FLOAT`/`IMAG`/`CHAR`/`STRING`), `raw` (source spelling); the value is `c` |
| `CompositeLit` | `type?` (absent when elided inside another literal), `elts` (§ 6.3) |
| `FuncLit` | `type: FuncType`, `body`, `captures?: [objectId]` (variables of enclosing functions it references, first-use order) |
| `ParenExpr` | `x` |
| `SelectorExpr` | `x`, `sel: Ident` — see § 6.2 |
| `IndexExpr` | `x`, `index`, `ik`: `slice`, `array`, `ptrarray` (auto-deref), `string` (yields a BYTE), `map`, `instantiate` (generic `F[T]` / `T[X]`) |
| `IndexListExpr` | `x`, `indices`, `ik: "instantiate"` |
| `SliceExpr` | `x`, `low?`, `high?`, `max?`, `slice3?`, `sk`: `slice`, `array` (slicing an addressable array var: § 6 `addr`), `ptrarray`, `string` (substring, byte offsets) |
| `TypeAssertExpr` | `x`, `type?` (absent for `.(type)`); `t` = the asserted type |
| `CallExpr` | see § 6.4 |
| `StarExpr` | `x`, `star`: `deref` (value `*p`) or `pointerType` (type `*T`) |
| `UnaryExpr` | `op` (`-`, `+`, `!`, `^`, `&`, `<-`), `x` |
| `BinaryExpr` | `op`, `x`, `y`; `cmp: true` for `==`/`!=` (operands then carry § 7 conversions). `&&`/`||` short-circuit; `&^` is AND NOT; shifts take an unsigned or signed count of any integer type |
| `KeyValueExpr` | `key`, `value`; inside a composite literal also `field: true` (struct field key) or `index: true` (array/slice index key). No `t` |
| `ArrayType` | `len?` or `lenEllipsis?: true` (`[...]T`, length = the array type's `len`), `elt` |
| `StructType` | `fields: FieldList` |
| `FuncType` | `tparams?`, `params`, `results?` (FieldLists) |
| `InterfaceType` | `methods: FieldList` (methods have names; embedded types / unions have none) |
| `MapType` | `key`, `value` |
| `ChanType` | `dir` (`both`/`send`/`recv`), `value` |
| `Ellipsis` | `elt` — the `...T` of a variadic parameter |

`FieldList = {k: "FieldList", list: [Field]}`,
`Field = {k: "Field", o, e, names?: [Ident], type: Expr, tag?: {raw, b64}, doc?}`.
A tag is not an expression: `raw` is its source spelling, `b64` its unquoted bytes.

### 6.2 Selectors

A `SelectorExpr` is EITHER a qualified identifier (`qual: true`; `x` is an `Ident` whose object
is a `pkgname`, `sel` is the package member) OR a selection with:

| field | meaning |
|---|---|
| `selk` | `field`, `method` (method value / call), `methodexpr` (`T.M`, `(*T).M`: a function whose first parameter is the receiver) |
| `path` | index path: every entry but the last is a FIELD index through an embedded field (implicit promotion); the last is the field index in the struct, or the method index in the declaring named type's `methods` list (interface: its `allMethods` list) |
| `indirect` | a pointer indirection happens somewhere along the path (including the receiver itself being a pointer for a field) |
| `recv` | type id of `x` (the receiver expression) |
| `selt` | type of the selection (a method's signature WITHOUT receiver) |
| `ptrRecv` | (methods) the method is declared on `*T` |
| `ifaceMethod` | (methods) the method is an interface method — dynamic dispatch |
| `autoAddr` | `x.M()` with `M` on `*T` and `x` an addressable `T`: Go takes `&x` implicitly. With struct-as-class Kotlin this is the same reference; the variable is marked `addr` (§ 6 objects) |
| `autoDeref` | `p.M()` with `M` on `T` and `p` a `*T` (or an indirect path): `(*p).M()` |
| `recvCopy` | `M` has a VALUE receiver of struct/array type: the receiver is COPIED into the method |
| `callee` | this node is the `fun` of a `CallExpr`. A `selk: "method"` WITHOUT `callee` is a **method value** (a bound closure over the receiver; with `recvCopy`, over a COPY taken at evaluation time) |

`sel` (an `Ident`) references the field or method object (for a promoted member: the object of
the type that declares it).

### 6.3 Composite literal elements

`elts` are annotated against the literal's (possibly pointer-elided) underlying type:
struct — positional elements carry `fieldIndex`; keyed ones are `KeyValueExpr{field: true}`
whose `key` Ident references the field object; omitted fields are ZERO. Slice/array — elements
(or `KeyValueExpr{index: true}` with a constant index key; gaps are zero). Map — key and value
annotated against the map's key/elem types. Every element value carries § 7's conversions and copies.

### 6.4 Calls

```
{ "k": "CallExpr", "fun": Expr (with "callee": true), "args": [Expr], "call": ..., "sig"?: typeId,
  "spread"?: true, "variadicFrom"?: n, "tupleArg"?: true, "to"?: typeId, "builtin"?: name }
```

| `call` | meaning |
|---|---|
| `conv` | type conversion `T(x)`; `to` = T. Notable cases by operand/target type: `string(rune)`, `string([]byte)`, `[]byte(string)`, `[]rune(string)`, numeric (truncation / sign extension / float→int toward zero), named↔underlying |
| `builtin` | `builtin`: `append`, `cap`, `clear`, `close`, `complex`, `copy`, `delete`, `imag`, `len`, `make`, `max`, `min`, `new`, `panic`, `print`, `println`, `real`, `recover`, or `unsafe.*`. `sig` = the instantiated signature go/types recorded. `append`'s element args are annotated against the element type |
| `func` | static call of a package-level function (possibly generic: the `fun` Ident has `inst`) |
| `method` | method call (static, or dynamic if the selector has `ifaceMethod`) |
| `methodexpr` | call through a method expression |
| `dynamic` | call of a function VALUE (variable, field, parameter, result of an expression) |

- `sig` is the callee's signature (instantiated when generic).
- `spread: true` — `f(xs...)`: the slice is passed AS the variadic parameter (aliasing it).
- `variadicFrom: n` — the callee is variadic and the call is not spread: arguments at index ≥ n
  are packed into a NEW slice of the variadic element type (none → a nil slice).
- `tupleArg: true` — `f(g())` with a multi-value `g`: the single argument is a tuple spread over
  the parameters; its node carries `implTuple` (§ 7.3).

## 7. Implicit operations the lowering must materialize

Wherever a value flows INTO a location — assignment and `:=`, var initializer, return operand,
call argument, composite-literal element, map index key, send value, switch case value
(against the tag), `==`/`!=` operand, `append` element — the flowing expression node may carry:

### 7.1 `impl` — implicit conversion

- `{"k": "iface", "from": typeId, "to": typeId, "fromTypeParam"?: true}` — a concrete value
  becomes an interface value (`to` is an interface type). In Kotlin usually a no-op upcast, but
  the class of `from` must implement `to` (§ 5.3 `implements`), and a non-pointer struct/array
  `from` is also COPIED (`copy`).
- `{"k": "nil", "to": typeId}` — the untyped `nil` becomes a nil slice / map / pointer / func /
  chan / interface of type `to` (every `nil` in the IR carries one; `--check` enforces it).

An interface value flowing into another interface type needs no `impl` (the dynamic value is
unchanged); comparisons of two interfaces compare dynamic type and value.

### 7.2 `copy: true` — value-semantics copy

The flowing expression has a struct or array type (with state: not `struct{}`/`[0]T`), is not a
constant, and is not FRESH (a composite literal or the result of a non-conversion call). Go copies;
Kotlin would alias. The lowering emits `goCopy()` (`docs/goport-design.md` § 3). Related flags:
`valueCopy` (§ 5, range value), `recvCopy` (§ 6.2, value receiver). Note that `x := y` and
`return y` are value flows too. A value-receiver struct ASSIGNED into a field or slice element
is covered the same way.

### 7.3 `implTuple` — conversions of a multi-value source

On a call (or comma-ok form) that is assigned / returned / passed element-wise:
`implTuple: [impl | null, ...]`, one per tuple element. A comma-ok form (`v, ok := m[k]`,
`x.(T)`, `<-ch`) additionally carries `commaOk: true`, and its `t` is the TUPLE `(V, bool)` —
go/types re-records the expression's type for a comma-ok use.

## 8. The tables

### 8.1 Types — `types[id]`

Every entry: `id`, `k`, `key` (canonical string; equal keys ⇔ identical Go types, with parameter
NAMES included and type parameters / function-local types disambiguated by declaring position —
treat it as an opaque join key). Kinds:

| `k` | fields |
|---|---|
| `basic` | `name` (`int`, `uint8`, `untyped int`, `unsafe.Pointer` …), `kind` (`Int`, `Uint8`, `UntypedInt`, `UntypedNil` …), `untyped?`, `default?` (untyped → its default typed type) |
| `named` | `name`, `pkg?` (absent for universe `error`/`comparable`), `localAt?` (function-local type, "file:line:col"), `tparams?` (generic origin) **or** `origin` + `targs` (instantiation), `u` (underlying), `isStruct`, `isIface`, `comparable` (`types.Comparable`: usable as a map key / with `==`), `methods?` (declared methods: `name`, `fn`, `ptrRecv`, `sig` without receiver), `msetT?`, `msetPtr?`, `implements?` / `implementsSkipped?` |
| `alias` | `name`, `pkg?`, `tparams?`, `targs?`, `rhs` (the aliased type as written), `actual` (fully unaliased) |
| `pointer`, `slice` | `elem` |
| `array` | `len`, `elem` |
| `map` | `key`, `elem` |
| `chan` | `dir` (`both`/`send`/`recv`), `elem` |
| `signature` | `params`, `results` (`[{name, t}]`; names may be `""`), `variadic?` (the last param's type is the slice `[]E`), `recv?: {name, t, ptr}` (method objects only), `tparams?`, `recvTparams?` |
| `struct` | `fields: [{name, t, embedded?, exported, pkg? (unexported: the declaring package), tag?}]` |
| `interface` | `methods` (explicit: `name`, `sig`), `embedded?` (type ids: interfaces, unions, plain types in constraints), `allMethods?` (complete method set incl. embedded, sorted by Go's method Id; `fn` = owning interface's method key), `comparable`, `isMethodSet?` (no type terms), `implicit?` (`~int` written directly in a constraint) |
| `typeparam` | `name`, `index`, `at`, `constraint`, `core?` (the core type: the single underlying type of every term in the constraint's type set — what `range`/index/slice/`len` on the parameter operate on) |
| `tuple` | `elems: [{name, t}]` (multi-value call results) |
| `union` | `terms: [{tilde, t}]` (constraint unions) |

**Method sets** (`msetT` for `T`, `msetPtr` for `*T`; interfaces have only `msetT`), from
`types.NewMethodSet`, entries `{name, fn, path, indirect, ptrRecv, sig}`. `fn` is the
declaring method's key (`pkg.Type.Method`); `path` is the embedding path as in § 6.2 (length > 1
= PROMOTED through embedded fields — the lowering emits a delegating member). Method sets are
always present for named types declared in the package; for types declared elsewhere they are
present when the type is reached structurally (an expression/object type, a field, an element,
an underlying type) and may be absent when it is reached only through a signature — find the
entry by `key` in the declaring package's file. A struct's `fields` list itself shows embedding
(`embedded: true`); promoted FIELDS are resolved per use by `path`.

**`implements`** (named non-interface types declared in the package): every candidate
interface the type satisfies — `[{iface: typeId, key, via: "value"|"pointer"}]` (`pointer`: only
`*T` has all methods). Go satisfies interfaces STRUCTURALLY; this list is what the lowering
writes as Kotlin supertypes. Candidates are run-wide: every non-empty, method-only, non-generic
interface that ANY extracted package mentions (named or anonymous), `error`, and every exported
interface of every EXTERNAL package an extracted package imports (so `fmt.Stringer`,
`encoding/json/v2.Marshaler` appear even though tsgo never names them — the shim calls those
methods). Generic named types get `implementsSkipped: "generic"` (decide per instantiation).

### 8.2 Objects — `objects[id]`

Common: `id`, `k`, `name`, `pkg?`, `key?`, `exported`, `t?` (type id; absent for `label`,
`pkgname`, `builtin`), `at?` ("file:line:col", for objects declared in an extracted package).

| `k` | meaning / extra fields |
|---|---|
| `var` | package-level (`key` = `pkg.Name`) or local variable |
| `param`, `result`, `recv` | function parameter, (named or unnamed) result, method receiver |
| `field` | struct field: `owner` (type id of the STRUCT type), `embedded?`, `key` = `pkg.Struct.Field` when the struct is the underlying of a named type; `origin?` for a field of an instantiated generic struct |
| `const` | `c` (exact value, § 6) |
| `typename` | `alias?`; `t` = the declared type (for a type parameter's name: the typeparam) |
| `func` | package-level function; `key` = `pkg.Name` (`builtin.<name>` for universe) |
| `method` | `recv` (type id), `ptrRecv`, `abstract?` (interface method), `origin?` (instantiated method → its generic origin), `key` = `pkg.Type.Method` |
| `pkgname` | an import in a file: `imported` = imported package path |
| `label` | a statement label |
| `builtin` | `append`, `len`, … (`key` = `builtin.<name>`) |
| `nil` | the universe `nil` |

Locals (declared inside a function) additionally have `local: true`, `scope` (scope id),
`fn` (the qname of the top-level declaration containing it), and the facts that decide their
Kotlin representation:

| fact | meaning | lowering consequence |
|---|---|---|
| `captured` | referenced from a func literal declared inside its scope | captured `var` (Kotlin boxes it); per-iteration loop variables (Go ≥ 1.22) must be fresh per iteration |
| `addr` | address taken: `&x`, `&x.f`, `&x[i]` on an array, `x.ptrMethod()` (§ 6.2 `autoAddr`), `x[:]` on an array var. On a FIELD: `&s.f` or one of the above through it | a non-struct variable must be a `GoPtr` box for its whole life; a struct-typed one IS its own reference, but if it is also `mut` the lowering must assign by copying INTO the existing object so outstanding pointers stay valid |
| `mut` | assigned after declaration (`=`, op-assign, `++/--`, range `=`, `:=` re-assignment) | `var` instead of `val` |

The facts are computed for every variable-like object, package-level ones included (a
package-level `var` with `addr` exists, e.g. `sync.Pool`s).

### 8.3 Scopes — `scopes[id]`

`{id, k, parent?, file?, o?, e?, fn?}`. `k` is the go/ast node owning the scope: `File`,
`FuncType` (a function's parameters and body), `BlockStmt`, `IfStmt`, `ForStmt`, `RangeStmt`,
`SwitchStmt`, `TypeSwitchStmt`, `CaseClause`, `CommClause`, `TypeSpec` (a generic type's
parameters), plus `package`, `universe`, and `labels` (a function's label scope). The lowering
uses scopes to place declarations and to resolve shadowing (`x := x` in an inner block).

## 9. Holes, `--check`, and limits

`--check` reports, by category, every place the IR fails its completeness contract:
`expr-without-type:<Kind>`, `ident-without-object`, `def-without-object` (other than `_`),
`nil-without-target`, `field-without-owner`, `call-without-signature`,
`range-kind-unknown`, `index-kind-unknown`, `slice-kind-unknown`, `scope-without-node`,
`funcdecl-without-object`, `bad-*`. On the spike closure — and on `binder checker compiler
evaluator printer module packagejson` added — it reports **no holes**.

Legitimately untyped (not holes, by construction): a defining `Ident` (its type is the object's),
`KeyValueExpr`, a struct `tag`, `[...]` (`lenEllipsis`), the package clause name and the
type-switch symbol (encoded as `bind`), the `_` in definitions (`blank`).

**Limits — what the IR does not tell the lowering:**

1. **Callbacks from the shims.** An external package may call a closure method through an
   interface (`fmt` → `String()`, `encoding/json/v2` → `MarshalJSONTo`); no identifier use shows
   it. `implements` (§ 8.1) lists every such satisfaction for non-generic types; the census lists
   them under *Closure types satisfying EXTERNAL interfaces*. Generic types are not checked.
2. **Escape / liveness** is not computed: `copy` is conservative (every non-fresh struct flow);
   the lowering may elide a copy only where it can prove the source dead.
3. **Slice aliasing** (whether a sub-slice outlives a mutation) is not analysed; slices are
   `GoSlice` views by contract.
4. **Integer width semantics** are carried only as types: the lowering applies the narrowing
   and wrap-around of `docs/goport-design.md` § 3 for every non-constant arithmetic result.
5. **Type-parameter values that may be structs** (`T` instantiated with a struct) carry no
   `copy` (go/types cannot know); `fromTypeParam` marks such interface conversions.
6. **Map iteration order, goroutine scheduling, `unsafe`/`reflect` semantics** are out of scope
   (refused or order-independent by contract).

## 10. Worked examples

Offsets (`o`, `e`) are removed below for brevity; ids are those of the actual output.

### 10.1 `collections.(*Set[T]).Add`

```go
func (s *Set[T]) Add(key T) {
	if s.M == nil {
		s.M = make(map[T]struct{})
	}
	s.M[key] = struct{}{}
}
```

```json
{"k":"BlockStmt","line":24,"list":[
 {"k":"IfStmt","line":25,
  "cond":{"k":"BinaryExpr","t":78,"m":"value","op":"==","cmp":true,
   "x":{"k":"SelectorExpr","t":2610,"m":"variable",
        "x":{"k":"Ident","t":2607,"m":"variable","name":"s","obj":493},
        "sel":{"k":"Ident","name":"M","obj":496},
        "selk":"field","path":[0],"indirect":true,"recv":2607,"selt":2610},
   "y":{"k":"Ident","t":79,"m":"nil","name":"nil","obj":38,"impl":{"k":"nil","to":2610}}},
  "body":{"k":"BlockStmt","line":25,"list":[
   {"k":"AssignStmt","line":26,"tok":"=",
    "lhs":[{"k":"SelectorExpr","t":2610,"m":"variable","x":{"k":"Ident","name":"s","obj":493,"t":2607,"m":"variable"},
            "sel":{"k":"Ident","name":"M","obj":496},"selk":"field","path":[0],"indirect":true,"recv":2607,"selt":2610}],
    "rhs":[{"k":"CallExpr","t":2610,"m":"value","call":"builtin","builtin":"make","sig":2626,
            "fun":{"k":"Ident","t":2626,"m":"builtin","name":"make","obj":39,"callee":true},
            "args":[{"k":"MapType","t":2610,"m":"type","key":{"k":"Ident","t":2469,"m":"type","name":"T","obj":494},
                     "value":{"k":"StructType","t":97,"m":"type","fields":{"k":"FieldList","list":[]}}}]}]}]}},
 {"k":"AssignStmt","line":28,"tok":"=",
  "lhs":[{"k":"IndexExpr","t":97,"m":"mapindex","ik":"map",
          "x":{"k":"SelectorExpr","…":"as above"},
          "index":{"k":"Ident","t":2469,"m":"variable","name":"key","obj":495}}],
  "rhs":[{"k":"CompositeLit","t":97,"m":"value","type":{"k":"StructType","t":97,"m":"type","fields":{"k":"FieldList","list":[]}},"elts":[]}]}]}
```

Tables it references (trimmed):

```json
{"id":78,"k":"basic","name":"untyped bool","kind":"UntypedBool","untyped":true,"default":7,"key":"untyped bool"}
{"id":97,"k":"struct","fields":[],"key":"struct{}"}
{"id":2469,"k":"typeparam","name":"T","index":0,"at":"set.go:24:14","constraint":0,"key":"typeparam:T@set.go:413"}
{"id":2607,"k":"pointer","elem":2608,"key":"*…/collections.Set[typeparam:T@set.go:413]"}
{"id":2608,"k":"named","name":"Set","pkg":"…/collections","origin":2465,"targs":[2469],"u":2609,"isStruct":true,"comparable":false,…}
{"id":2610,"k":"map","key":2469,"elem":97,"key":"map[typeparam:T@set.go:413]struct{}"}

{"id":493,"k":"recv","name":"s","t":2607,"at":"set.go:24:7","local":true,"scope":110,"fn":"…/collections.Set.Add"}
{"id":494,"k":"typename","name":"T","t":2469,"local":true,…}
{"id":496,"k":"field","name":"M","key":"…/collections.Set.M","t":2610,"owner":2609,"origin":480}
```

Reading it: `T` (2469) is the RECEIVER's own type parameter (declared at `set.go:24:14`, the
`T` in `(s *Set[T])`), so `s` is `*Set[T']` — an instance (`origin` 2465) of the generic `Set`.
`s.M` is a field selection with `indirect: true` (through the pointer `s`); its object is the
instantiated field whose `origin` (480) is `Set.M`. `nil` converts to the map type 2610.

### 10.2 `core.Map` (range, make, dynamic call, generic)

```go
func Map[T, U any](slice []T, f func(T) U) []U {
	if slice == nil { return nil }
	result := make([]U, len(slice))
	for i, value := range slice { result[i] = f(value) }
	return result
}
```

```json
{"k":"AssignStmt","line":84,"tok":":=",
 "lhs":[{"k":"Ident","name":"result","def":true,"obj":583}],
 "rhs":[{"k":"CallExpr","t":2219,"m":"value","call":"builtin","builtin":"make","sig":2221,
  "fun":{"k":"Ident","t":2221,"m":"builtin","name":"make","obj":23,"callee":true},
  "args":[{"k":"ArrayType","t":2219,"m":"type","elt":{"k":"Ident","t":2218,"m":"type","name":"U","obj":580}},
          {"k":"CallExpr","t":10,"m":"value","call":"builtin","builtin":"len","sig":2222,
           "fun":{"k":"Ident","t":2222,"m":"builtin","name":"len","obj":8,"callee":true},
           "args":[{"k":"Ident","t":2215,"m":"variable","name":"slice","obj":581}]}]}]},
{"k":"RangeStmt","line":85,"tok":":=","rk":"slice","kt":10,"vt":2216,
 "x":{"k":"Ident","t":2215,"m":"variable","name":"slice","obj":581},
 "key":{"k":"Ident","name":"i","def":true,"obj":584},
 "value":{"k":"Ident","name":"value","def":true,"obj":585},
 "body":{"k":"BlockStmt","line":85,"list":[
  {"k":"AssignStmt","line":86,"tok":"=",
   "lhs":[{"k":"IndexExpr","t":2218,"m":"variable","ik":"slice",
           "x":{"k":"Ident","t":2219,"m":"variable","name":"result","obj":583},
           "index":{"k":"Ident","t":10,"m":"variable","name":"i","obj":584}}],
   "rhs":[{"k":"CallExpr","t":2218,"m":"value","call":"dynamic","sig":2217,
           "fun":{"k":"Ident","t":2217,"m":"variable","name":"f","obj":582,"callee":true},
           "args":[{"k":"Ident","t":2216,"m":"variable","name":"value","obj":585}]}]}]}}
```

`call: "dynamic"` — `f` is a parameter (a function value), not a declared function. The
declaration's `facts` are `{"locals":3,"typeParams":true}`.

### 10.3 A struct with embedding and its promoted method set

```go
type Identifier struct {
	PrimaryExpressionBase
	FlowNodeBase
	Text string
}
```

```json
{"id":347,"k":"named","name":"Identifier","pkg":"…/ast","u":348,"isStruct":true,"isIface":false,"comparable":true,
 "msetT":[],"msetPtr":[
   {"name":"ArgumentList","fn":"…/ast.Node.ArgumentList","path":[0,0,0,0,0,0,0,0,0,32],"indirect":true,"ptrRecv":true,"sig":11344}, …270 entries],
 "implements":[{"iface":42,"key":"…/ast.nodeData","via":"pointer"}, …]}
{"id":348,"k":"struct","fields":[
   {"name":"PrimaryExpressionBase","t":349,"embedded":true,"exported":true},
   {"name":"FlowNodeBase","t":205,"embedded":true,"exported":true},
   {"name":"Text","t":13,"exported":true}]}
```

`*Identifier` has 270 methods, all promoted: `ArgumentList` is reached through nine embedded
fields (each `0`: the first field) to the type declaring it, `ast.Node`, whose method #32 it is;
`indirect` is set because the path dereferences a pointer (here the `*Identifier` receiver). `Identifier` (the value type) has an empty method set because every
method is on a pointer receiver. `implements` says only `*Identifier` satisfies `nodeData`.
