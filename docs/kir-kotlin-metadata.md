# TypeScript public API → Kotlin metadata

Module `xemantic-typescript-compiler-kir`, package `…kir.api`; entry points
`exportTypeScriptApi` / `exportTypeScriptProjectApi`.

## 0. What this produces, and for whom

A **metadata klib**: the artifact a Kotlin Multiplatform project's `commonMain`
compiles against. Given a checked TypeScript library it writes one `.klib`
holding that library's exported declarations as Kotlin declarations — no bodies,
no platform — so a Kotlin developer can `import` a TypeScript library and have
the compiler type their calls.

This is the DECLARATION half of using a TypeScript library from Kotlin. The
other half — a platform artifact whose signatures match — is what the KIR JVM
and Native backends already produce for whole programs; making the two agree is
§6, and it is deliberately not claimed here.

```
.ts ──▶ Parser/Binder/Checker ──▶ CheckedProject ──▶ export surface walk
                                                            │
                                                   KotlinApiModule
                                                            │
                                                   generated Kotlin source
                                                            │
                                        kotlinc's metadata compiler, in-process
                                                            ▼
                                                     library.klib
```

## 1. Why it goes through generated source

Kotlin's metadata is a versioned protobuf whose only writer lives in the
compiler. Writing it directly would be a second implementation of a format that
moves every release — the exact shape of defect this repository keeps recording
— so instead the exported surface is rendered as ordinary Kotlin source and
handed to `KotlinMetadataCompiler`, the third kotlinc entry point this module
drives (beside the JVM pipeline `KotlinIrEmitter` runs and the
`IrGenerationExtension` the Native backend rides in as).

The artifact is then BY CONSTRUCTION what kotlinc would have written, and the
intermediate is readable: `KotlinMetadataExport.source` is the whole exported
API as text, which is reviewable in a way a binary is not.

Three mechanical facts, each measured, each silent when wrong:

- **`metadataKlib = true` is load-bearing.** Left false the compiler writes the
  legacy layout — a `.kotlin_module` under `META-INF` beside per-package
  `.kotlin_metadata` files — under the same `.klib` name, with no diagnostic,
  and a multiplatform consumer resolves nothing from it.
  `KotlinMetadataKlibTest.the artifact has the klib layout` is the pin.
- **No classpath is needed**, because the exported surface names only Kotlin's
  BUILT-IN types (§3). The standard library's common metadata is a separate
  artifact this project does not ship, and not needing it is why the export is
  self-contained.
- **The compiler writes a directory klib**; what a build publishes is a zipped
  one. Both resolve on a consumer's classpath (measured); the export zips,
  because a single file is what a Maven artifact can be.

Generated bodies are `null as T` — legal for every Kotlin type including the
primitives, needing no library, and never shipped: a metadata klib carries
declarations, and only an `inline` function would carry a body.

## 2. What "public API" means

The surface is **the entry module's exports**, followed through re-exports, and
not the union of everything every file in the project marks `export`. A
package's `index.ts` is its statement of what it offers, and the difference is
not cosmetic — the union publishes names a library deliberately keeps internal.
`KotlinMetadataKlibTest.a project's public API is its entry module's exports`
pins both directions, including that a non-re-exported module stays off.

Followed today: `export` on a declaration, `export { a, b }`, `export { a } from
"./m"`, `export * from "./m"`, `export default <name>`, and a name imported into
the entry module and re-exported from it. Re-export cycles terminate; on a
duplicate name the first wins, which is what a local export shadowing an
`export *` means.

Module specifiers are resolved RELATIVELY, against the program's own file list,
and a bare specifier (a `node_modules` package) is refused. That is deliberately
not a second copy of `-core`'s `ModuleResolver`: the file is already in the
checked program, so the question is which of a known list it is.

## 3. The type mapping

The codomain is small on purpose, and every position outside it erases to `Any?`.

| TypeScript | Kotlin |
|---|---|
| `number`, numeric literal types | `Double` |
| `string`, string literal types | `String` |
| `boolean` | `Boolean` |
| `void` | `Unit` |
| `never` | `Nothing` |
| `undefined`, `null` | `Nothing?` |
| `any`, `unknown` | `Any?` |
| `T \| undefined`, `T \| null` | `T?` |
| a union whose members disagree | `Any` (nullable iff a member is nullish) |
| a function type of arity *n* | `(Any?, …) -> Any?` |
| an exported `class` | that class, by name |
| an `enum` | an `object` of `val`s typed as the members' VALUES |
| a type parameter | `Any?` |
| `bigint` | `Any?` — see §3.1 |
| an array, a tuple, an object type, an interface | `Any?` — see §3.1 |

Function types are UNIFORM in `Any?` for the reason `ErasedTypes.function` gives:
TypeScript's function assignability is bivariant and Kotlin's `FunctionN` is not,
so giving the parameters their own erased types would reject handlers the
library's own type system accepts.

### 3.1 What erases to `Any?`, and why that is a stage rather than a decision

An array is a `JsArray` at run time and an object type is a `JsObject` — classes
in this module's runtime, which exist as **JVM** Kotlin and have no common
metadata artifact. A metadata klib naming them would only resolve for a consumer
who also had that artifact, and there is none yet, so today those positions are
`Any?`. `bigint` is the same story with `java.math.BigInteger`.

The consequence is visible and is stated rather than hidden: `mitt` exports as
`mitt(all: Any?): Any?` and `smol-toml` as `parse(toml: String, options: Any?):
Any?`. Both are pinned, so the day a runtime metadata artifact lands, the pins
say so.

### 3.2 Why this is a second mapper

`ErasedTypes` maps the same TypeScript types to Kotlin IR for the JVM backend,
and CLAUDE.md's standing warning is that a second copy of a rule diverges by
widening. Two things make these genuinely different questions:

- the **codomains** differ — an `IrType` needs a live `IrBuiltIns` and a
  `SymbolTable` from a running kotlinc frontend, and metadata is produced with
  neither;
- the **failure modes** differ, and this is the load-bearing half. Inside a
  function body an unmappable type means an operation the backend cannot lower,
  so `ErasedTypes` answers null and the lowering refuses the program. In a
  SIGNATURE there is no operation — the position merely carries a value — so an
  unmappable type erases to `Any?`, which is what TypeScript's own erasure does
  to it.

## 4. Refusals are per declaration

The IR lowering refuses a whole program when it meets a construct it cannot
lower, because a program missing a statement is not that program. An API export
instead OMITS the declaration and reports it (`KotlinMetadataExport.refusals`,
each with file, line and column).

The asymmetry is the point: **an absent declaration is a compile error at the
consumer's use site, and a wrongly-typed one is silent.** Omission is the
failure that announces itself.

Refused today, each with its position: a rest parameter, a destructuring
top-level declaration, an anonymous exported declaration, a default export that
is not a declared name, an unresolvable module specifier or exported name, an
enum whose members' values disagree, and any declaration the checker gave no
type for.

Skipped without a diagnostic, because they are not refusals but erasures:
`interface` and `type` declarations (no runtime witness — their USES erase per
§3), and private, protected and static class members.

## 5. How the artifact is verified

By a **consumer**, and only by a consumer. A metadata klib is a binary nobody
reads by eye and every failure mode it has is silent, so each end-to-end pin
compiles Kotlin source against the artifact through the same metadata compiler —
which is exactly what a Kotlin Multiplatform `commonMain` does with it.

The negative controls are not optional, because a round trip that passes because
the consumer compiles whatever it is given would pass for an empty klib too:

- a name the library does not export must NOT resolve;
- the erased parameter TYPES must be enforced (`greet(1)` against a `Double`
  parameter must fail — the difference between a typed artifact and a bag of
  names);
- a module the entry does not re-export must not be reachable;
- a program the checker rejects must produce no artifact at all.

## 6. What is not done yet

In rough order of what a user would miss first.

1. **The platform half.** The metadata declares signatures; the JVM classes the
   KIR backend emits must match them (package, name, erased JVM signature) for a
   `jvmMain` compilation to link. Nothing pins that agreement yet, and until it
   does this artifact types a consumer's common code without linking its
   platform code. That is the next slice.
2. **A runtime metadata klib**, which is what turns §3.1's `Any?` positions into
   `JsObject` / `JsArray` with members — the difference between "a TOML parser
   returns something" and "a TOML parser returns something you can read".
3. **Interfaces as shapes.** An interface is a property bag at run time, so a
   Kotlin `interface` would be a claim about a representation that does not
   exist; `docs/kir-structural-typing.md` §7 is where the nominal encoding that
   would change this is priced.
4. **Generics.** Type parameters erase, as they do in TypeScript and on the JVM.
   A generic library therefore loses its relationships (`Emitter<Events>` is
   `Emitter`), which is sound and lossy.
5. **Rest parameters, optional-parameter defaults, static members, overloads.**
   Each is refused or skipped rather than guessed; each needs a decision about
   what the platform artifact does, which is why none is a mapper change alone.
6. **Publication.** Producing the artifact is not publishing it: a KMP consumer
   resolves it through Gradle module metadata, which is build-system work and
   owner-gated by this repository's Guardrails.
