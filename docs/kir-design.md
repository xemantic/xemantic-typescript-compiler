# TypeScript → Kotlin IR (spike)

Branch `spike/ts-to-kotlin-ir`. Module `xemantic-typescript-compiler-kir`.

## 0. Why IR, and why this project can do it

The existing backend (`Transformer` → `Emitter`) lowers the checked AST to
JavaScript **text**, and it does so with essentially no type information:
`Emitter` takes no `Checker` at all, and `Transformer` takes a *nullable* one it
consults for exactly two erasure questions — import elision and const-enum
inlining. That is all TypeScript-to-JavaScript needs, because JS is untyped.

Targeting Kotlin IR inverts that. IR is *typed*, nominal and JVM-shaped, so the
backend is the first consumer of xtsc's `Type` graph — and the reason this is
worth attempting here rather than anywhere else is that xtsc already has a
**whole-program checker**. Every hard question below ("is this `+` concatenation
or addition", "does this object literal satisfy that interface", "is this
reference narrowed here") is a question the checker has already answered.

Stopping at IR rather than at bytecode is the point: IR is the fork point for
every Kotlin backend. Getting to JVM `.class` files proves the pipeline; JS,
Native and Wasm are then a change of backend phase, not a new compiler.

## 1. Pipeline

```
.ts ──Parser──▶ SourceFile ──Binder──▶ BinderResult ──Checker──▶ Type graph
                                                                     │
                              ┌──────────────────────────────────────┘
                              ▼
                    KIR lowering (this module)
                              │
                              ▼
                    Kotlin IrModuleFragment
                              │
              kotlinc JVM pipeline phases, in-process
                              ▼
                          .class files
```

The last stage is not ours: we hand synthesized `IrFile`s to an
`IrGenerationExtension` and let kotlinc's own `JvmFir2Ir → JvmBackend →
JvmWriteOutputs` phases run every lowering, inlining, metadata and class-writing
step. A one-line **seed** source file is compiled purely to obtain a wired
`IrBuiltIns` / `SymbolTable` / `IrPluginContext`; our generated files are
appended to that module. Verified working (a synthesized program printed to
stdout with no frontend involvement).

Two mechanical constraints found the hard way, recorded so they are not
rediscovered:

- Build synthetic files as `IrFileImpl(entry, EmptyPackageFragmentDescriptor(module.descriptor, fqName))`.
  The no-descriptor `IrFileImpl(entry, IrFileSymbolImpl(), fqName)` works until
  the generated code calls an **inline** function (`println` is one), then dies
  with `IrFileImpl cannot be cast to IrDeclaration` inside `IrInlineCodegen`.
- Kotlin 2.2+ removed `putValueArgument`; arguments go through the flat
  `call.arguments[i] = …` list, and parameters through `IrFunction.parameters`
  discriminated by `IrParameterKind`. Anything written against older tutorials
  will not compile.

Develop with `-Xverify-ir=error`: it is `none` by default on JVM, and it is the
only thing between a malformed tree and an opaque codegen `ClassCastException`.

## 2. The north star

**Faithful TS/JS semantics.** A compiled program must behave as it does on a JS
engine — `5/2 === 2.5`, `1` prints as `1` and not `1.0`, an out-of-range array
read yields `undefined` rather than throwing. The long-range target is compiling
tsc's own sources and running them on the JVM, which is a program that will
notice every divergence.

Idiomatic-JVM output is explicitly *not* the goal, and where the two conflict,
faithfulness wins. A Kotlin/Java-callable facade over exported declarations is a
later, additive layer.

Consequence: there is a small **runtime support library**, and there has to be.
JS semantics that have no JVM equivalent (number formatting, `==` coercion,
truthiness, sparse arrays, property deletion) live there as ordinary Kotlin
functions which the generated IR calls by symbol. It ships in this module under
`…kir.runtime`, and the emitter puts this module's own classes on the generated
program's classpath — the same mechanism by which the generated code reaches
`kotlin.io.println`.

## 3. Type mapping

| TypeScript | Kotlin IR | Notes |
|---|---|---|
| `number` | `Double` | JS numbers are IEEE-754 doubles. Int narrowing is a later, provable optimization, never a default. |
| `string` | `String` | JS and JVM strings are both UTF-16. The one genuinely free mapping. |
| `boolean` | `Boolean` | |
| `bigint` | `java.math.BigInteger` | Deferred. |
| `void` | `Unit` | |
| `never` | `Nothing` | |
| `undefined`, `null` | `null` | See §3.1. |
| `any`, `unknown` | `Any?` | |
| literal types (`"a"`, `42`) | their base type | The literal-ness is static; it has no runtime witness. |
| `T \| undefined`, `T \| null` | `T?` | The common case, and it costs nothing. |
| other unions | erased LUB — see §3.2 | |
| `T[]`, `Array<T>` | `JsArray<T>` (runtime) | Not `ArrayList`: JS arrays are sparse, growable, and index-out-of-range reads are `undefined`, not exceptions. |
| tuple types | `JsArray<Any?>` | The element types are static. |
| `interface` | generated JVM interface | §3.3 |
| `class` | generated JVM class | |
| object literal | generated final class | §3.3 |
| function types | `kotlin.FunctionN` | |
| enums | class with static fields + reverse map | Member constants come from the checker (`getEnumMemberValue` is already public). |
| generics | erased to `Any?` | TS erases them too; so does the JVM. |
| conditional / mapped / indexed-access / `keyof` | whatever the checker resolved them to | These have no `Type` subclass in xtsc — the checker materializes them into ordinary object types, so the backend never sees them. This is a gift. |

### 3.1 `null` vs `undefined`

TypeScript distinguishes them *statically*; JS distinguishes them at runtime;
the JVM has one `null`. The spike maps **both to `null`** and accepts the
divergence, because under `strictNullChecks` the type system already separates
them and correct programs rarely branch on which one they hold.

The known-wrong cases are `x === undefined` vs `x === null` on an
`unknown`-typed value, and distinguishing "absent property" from "property
present and null". If those bite, the fix is a singleton `Undefined` object in
the runtime and `undefined` mapping to it — a change confined to the type mapper
and the equality lowering, which is why it is worth deferring rather than
pre-paying.

### 3.2 Unions — erase, and let narrowing pay for it

**Decision: a union has no runtime representation.** It erases to the least
upper bound of its members, and every place the program relies on knowing *which*
member it holds is a place the checker has already proven a narrowing.

The rule:

- all non-nullish members erase to the same type `T` → `T`, nullable iff the
  union contains `null`/`undefined`;
- otherwise → `Any`, nullable on the same condition.

So `string | undefined` → `String?`, `Cat | Dog` → `Any`, `number | null` →
`Double?`.

The alternative — a generated `sealed class` per distinct union, one subclass per
member — was rejected. It allocates at every boundary crossing, destroys object
identity (`x` passed as `string` to anything expecting a string is no longer a
string), and would make interop with real JS values impossible. It also buys
nothing the checker has not already given us: tsc itself erases unions
completely, and the *entire* purpose of having a conformant checker in this
repo is that we inherit its proofs rather than re-litigating them at runtime.

Narrowing then lowers to type tests and casts:

```ts
function f(x: string | number): string {
  return typeof x === "string" ? x : x.toFixed()
}
```
```kotlin
fun f(x: Any): String =
  if (x is String) x else (x as Double).toFixed()
```

`typeof` comparisons map to IR type operators:

| `typeof x === …` | lowering |
|---|---|
| `"string"` | `x is String` |
| `"number"` | `x is Double` |
| `"boolean"` | `x is Boolean` |
| `"undefined"` | `x == null` |
| `"function"` | `x is Function<*>` |
| `"object"` | `x == null \|\| (x !is String && x !is Double && x !is Boolean && x !is Function<*>)` — note `typeof null === "object"` in JS, which is why `null` is on the *true* side |

Casts use `IrTypeOperator.CAST` (a real `checkcast`, plus unboxing where the
target is primitive) rather than `IMPLICIT_CAST`, even though the checker has
proven the value. `Any → Double` requires an unbox regardless, and a wrong
`IMPLICIT_CAST` produces silently invalid bytecode instead of a loud failure.
Demoting proven casts to implicit ones is an optimization to make later, with a
differential test, not a starting assumption.

**Specialized overloads** (emitting `f(String)` and `f(Double)` beside the erased
`f(Any)` so statically-known call sites skip boxing) are a natural extension of
erasure and stay compatible with it. Deferred; erasure alone must work first.

### 3.3 Structural typing — the real hard problem

Unions are the question that gets asked; structural typing is the one that
decides whether this approach survives. TS types are structural, JVM types are
nominal, and no encoding makes that difference disappear.

Three candidate representations:

1. **Nominal, with whole-program structural closure.** Each `interface` becomes a
   JVM interface; each class and each object literal becomes a class that
   `implements` *every* generated interface it is structurally assignable to.
   Sound only because we compile the whole program at once and the checker can
   answer the assignability questions. Fast at runtime — plain `invokeinterface`.
   Fails on separate compilation and on types that cross a library boundary.
2. **Dynamic records.** Every object is a property map; every access is a
   lookup (`invokedynamic` with an inline-cache bootstrap). Exactly JS
   semantics, including `delete`, index signatures and expando properties.
   Slower, and throws away the type information we went to such trouble to get.
3. **Hybrid.** (1) wherever the checker gives a definite object type, (2) as the
   fallback for `any`, index-signature-dominated types and computed access.

**The spike takes (3), starting from the (1) half**, with the narrowest version
of the closure: classes and interfaces declared in the program, object literals
that have a contextual type. `any` is out of scope until the nominal half runs.

## 4. Where the type information comes from

The backend needs the real `Type` object for every expression node. Today it
cannot have one: every type query on `Checker` is `private`, and the existing
`TypeCapture` facility deliberately returns value types only (`typeText: String`)
so that `Type`/`Symbol`/`Node` are not frozen as public API.

Post-hoc querying is *not* an option either, and this is the subtlest constraint
in the whole spike: the checker's answers depend on walk-scoped state
(`currentLocalTypes`, the cta frame stack, `currentFlowGraph`) that is empty once
the check is over. Asking "what is the type here" after the fact reads a
function-body local as a same-named global, and a parameter as `any` — silently.

So the type information must be **captured during the walk**, at the points
where the existing capture facility already records `typeText`. The seam is
therefore a structured sibling of `TypeCaptureRequest`, off by default and
zero-cost when off. Its design is the subject of the next work item.

## 5. Spike scope

The judgement criterion is a **running program**, not a design. In scope:

- top-level functions, parameters, locals, `return`
- `number` / `string` / `boolean` literals and arithmetic, string concatenation
- `if` / `while` / `for`, comparison operators
- calls to declared functions
- classes: fields, constructor, methods, `this`
- one union parameter narrowed by `typeof`
- `console.log` → the runtime's JS-faithful `println`

Out of scope for now: `any`, generics, closures capturing mutable state,
`async`/generators, modules and imports, prototypes, `delete`, getters/setters.

Success looks like:

```
$ xtsc-kir sample.ts -o out/
$ java -cp out:kir-runtime.jar sample.MainKt
hello 42 true
```
