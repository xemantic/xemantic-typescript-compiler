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

### 0.1 Running it

From Kotlin:

```kotlin
exportTypeScriptProjectApi(
    projectPath = "…/my-library",
    entryFileName = "index.ts",
    outputKlib = out / "my-library.klib",
    runtimeKlib = out / "xtsc-runtime.klib",   // optional — see §3.1
)
```

From a command line, off this module's classpath — the same shape as
`…kir.census.StructuralCensusMain`, and for the same reason it is not in the
shipped CLI: that one is a GraalVM native image and this pipeline embeds the
Kotlin compiler.

```
java -Xmx4g -cp <kir + core + deps> \
  com.xemantic.typescript.compiler.kir.api.KirApiExportMainKt \
  <project-dir> <entry-file> <out.klib> [package] [runtime.klib]
```

It writes the generated Kotlin source beside the klib as a `.kt` file, which is
the reviewable form of what the artifact contains.

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

An OVERLOADED function reaches the surface as its FIRST declaration — the one
its own sources state first — because the erasure collapses the differences
between overloads anyway (`smol-toml`'s two `parse` overloads erase to the same
Kotlin signature). The implementation signature, which TypeScript deliberately
makes uncallable, is not the one exported.

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
| an array, a tuple | `JsArray`, or `Any?` — see §3.1 |
| an object type, an interface, an intersection of them | `JsObject`, or `Any?` — see §3.1 |
| `Map`, `Set`, `RegExp`, `Date`, `Error` | their runtime class, or `Any?` — see §3.1 |

Function types are UNIFORM in `Any?` for the reason `ErasedTypes.function` gives:
TypeScript's function assignability is bivariant and Kotlin's `FunctionN` is not,
so giving the parameters their own erased types would reject handlers the
library's own type system accepts.

### 3.1 The runtime surface, and what still erases to `Any?`

An array is a `JsArray` at run time and an object type is a `JsObject` — classes
in this module's runtime, which exist as **JVM** Kotlin. A metadata klib is
common code and cannot see a JVM class, so naming them takes a SECOND metadata
klib declaring those types under their real fully qualified names, produced by
the same machinery and put on the exported library's compile classpath. That is
the pairing a Kotlin Multiplatform library already is, and it is opt-in:

```kotlin
exportTypeScriptProjectApi(project, "index.ts", out / "lib.klib",
                           runtimeKlib = out / "xtsc-runtime.klib")
```

With it, `mitt` exports as `mitt(all: JsMap?): JsObject` and `smol-toml` as
`parse(toml: String, options: JsObject?): JsObject` — signatures a Kotlin caller
can use, `document.get("title")` and all. A LIBRARY type is named by the table
`KirIntrinsics.libraryClass` mirrors, so `mitt`'s `EventHandlerMap` — an alias of
a `Map` — arrives as the `JsMap` the compiled program actually holds there. Without it the artifact is
self-contained and those positions are `Any?`. Both are pinned, in both
directions.

**What is a bag needs POSITIVE evidence, and that gate is the load-bearing part.**
An interface the program declares is a property bag; a LIBRARY type is not — a
`Date` is a `JsDate` at run time, and typing one as `JsObject` would offer a
consumer members the value does not have, silently. So the mapping asks
`KirFileLowering`'s own question (`isOwnStructuralDeclaration`: a structural kind,
declared in a program file that is not a `.d.ts`), an anonymous object type is a
bag by construction, and everything else stays `Any?`.

An INTERSECTION — `ParseOptions & { integersAsBigInt: … }`, the branded-options
shape every library writes — is one bag, but only when EVERY member is positively
a bag. That is stricter than `ErasedTypes.mapIntersection`, and the difference is
forced: there, an unmappable member can be read as a pure type-level constraint
because a nominal one would have mapped to a runtime class and been refused;
here there is no library-type table, so `Date` maps to `Any?` — the same answer a
constraint gives — and the permissive reading would type `Date & Tag` as a bag.

**An absent declaration is not evidence of an anonymous shape**, and that is the
trap this gate was measured falling into: a `Promise<string>` reaches the object
mapping with no declaration to walk and reads as a bag. Two things separate the
cases — the instantiation's TARGET carries the declaration where the reference's
own symbol does not (which is how `Emitter<Events>` is recognised as the
program's own interface), and a type with a NAME but no reachable declaration is
a library type this backend does not know, so it stays `Any?`.

Still `Any?`, and each for its own reason: `bigint` (`java.math.BigInteger` is a
JVM class), and every library type outside both tables — `Promise`, `Symbol`,
`WeakRef` — because a runtime class is what an entry names, and there is none.

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

### 5.1 The runtime facade, and how it is kept honest

`KirRuntimeApi` states `JsObject`'s and `JsArray`'s common surface BY HAND, and
that is a second copy of a public API — the thing this repository's CLAUDE.md
warns about at length. Both mechanical alternatives are worse: Java reflection
cannot see nullability, which is the one thing this surface must state, and
`kotlin-reflect` on this module's classpath is older than the metadata the
runtime is compiled with.

So the drift is caught rather than prevented: `KirRuntimeApiTest` reflects over
the REAL classes — `JsObject`, `JsArray`, `JsMap`, `JsSet`, `JsDate`, `JsRegExp`,
`JsError` — and fails when a declared member or constructor is absent or its JVM
signature disagrees, with three negative controls proving the check can fail. The direction
that matters is "declared here, absent there" — that one types a consumer's call
against a method nothing implements, and the consumer's compiler cannot see it,
because the consumer compiles against the metadata.

## 6. What is not done yet

In rough order of what a user would miss first.

1. **The platform half.** The metadata declares signatures; the JVM classes the
   KIR backend emits must match them (package, name, erased JVM signature) for a
   `jvmMain` compilation to link. Nothing pins that agreement yet, and until it
   does this artifact types a consumer's common code without linking its
   platform code. That is the next slice.
2. **Interfaces as shapes.** An interface is a property bag at run time, so a
   Kotlin `interface` would be a claim about a representation that does not
   exist; `docs/kir-structural-typing.md` §7 is where the nominal encoding that
   would change this is priced.
3. **Generics.** Type parameters erase, as they do in TypeScript and on the JVM.
   A generic library therefore loses its relationships (`Emitter<Events>` is
   `Emitter`), which is sound and lossy.
4. **Rest parameters, optional-parameter defaults, static members, overloads.**
   Each is refused or skipped rather than guessed; each needs a decision about
   what the platform artifact does, which is why none is a mapper change alone.
5. **Publication.** Producing the artifact is not publishing it: a KMP consumer
   resolves it through Gradle module metadata, which is build-system work and
   owner-gated by this repository's Guardrails.
