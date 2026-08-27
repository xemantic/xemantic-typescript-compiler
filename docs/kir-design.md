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

**MEASURED — `docs/kir-structural-typing.md`.** The closure's size was the open
question and it is answered: on tsc's own compiler sources the program forms
**158** structural (class, interface) pairs, no generated class carries more
than **9** `implements` edges, and the median is **1**; over eight corpora the
worst case is 264 edges and a fan-out of 10. The N x M fear was unfounded
because the closure only has to cover the pairs the program actually FORMS, and
those are the pairs the checker forms while checking.

Two things that measurement changes about the plan above. The 63-of-117 majority
of closure targets are ANONYMOUS inline type literals, so the backend must MINT
an interface per target rather than name a declared one — which is the concrete
form of "fails on separate compilation". And the ordering of (3)'s two halves is
backwards: `any`-typed sources are **2,753** closed obligations onto object
targets against the closure's own **220**, so the fallback half is an order of
magnitude larger than the nominal half it was deferred behind.

### 3.4 The mechanism, decided

Two investigations ran against §3.3 — a measurement of what real TypeScript
demands, and a survey of what shipped systems actually built. They look like
they disagree. They do not, and separating the two questions is what settles the
design.

**"How big is the closure?" is answered, and the alarming number was the wrong
one.** The naive count on tsc's own sources is 583 classes × 896 interfaces =
522,368 pairs, and the literature's measurement of nominalizing inferred
structural types is +313% interfaces on average, +1000% worst case. But the
closure that is actually *needed* is over pairs the program forms, not pairs it
could form: **158 edges, median fan-out 1, maximum 9**. Go's own binary is the
same story from the other side — 695 itabs against 59,598 possible pairs, a
**1.2% fill**. Real programs use a sparse corner of the cross product.

So the size objection does not apply to this workload, and neither does
HotSpot's cliff at 64 transitive interfaces — a maximum fan-out of 9 has two
octaves of headroom.

**But three objections survive, and they are not about size.**

1. **Separate compilation is impossible by construction.** Every class used as
   a structural type anywhere must implement the right interface, so a library's
   compiled class cannot satisfy an interface an application declares later.
   This is stated as a result in the literature, not a matter of degree.
2. **Some of it is not expressible as a JVM interface at all.** Of the closure's
   own targets, 17 of 117 are construct-signature types — a JVM interface cannot
   say "has this constructor" — and 63 of 117 are anonymous inline type literals
   the backend would have to mint names for. Index signatures ("a member of any
   name") have no encoding either.
3. **The load-bearing one: the closure answers a question TypeScript never
   asks.** Enumerate every narrowing form in the language — `typeof` (a value tag
   test), `instanceof` (nominal), `in` (a single-member test), a discriminant
   compare (a field read), a user-defined `x is T` predicate (ordinary user code,
   no shape check emitted), `Array.isArray`. **Not one of them requires the
   runtime to decide structural conformance.** Structural assignability is a
   purely static verdict — made by the checker this repository already has.

That last point is what decides it. The closure's entire purchase is the ability
to spell *one* operation, a member access on a structurally-typed receiver, as
`invokeinterface`. It is a lowering choice for one operation, and it drags
separate compilation and two inexpressible type forms along with it.

Two mature compilers reached the same place from the opposite direction:
Kotlin/JS makes `is` against an external interface a hard compile error, and
Scala.js does not support `isInstanceOf` for a `js.Any` trait at all — both
priced the runtime shape question and removed it from the language. Here it
never needed pricing, because TypeScript does not pose it.

A third system reached it later and from the opposite direction again — see
§10, where a native TypeScript compiler removes structural width from the
source language rather than encoding it.

**So: the mechanism is name-keyed, and the closure is demoted to an
optimization.** Four pieces, the first three taken from Static TypeScript, which
is the closest thing to this project that ever shipped:

- **Intern every member name to a dense integer, program-wide.** This is the
  only whole-program artifact, and it is O(distinct member names) — thousands —
  rather than O(classes × interfaces). Dense ids, never a hash: at ~50k distinct
  names a 31-bit hash space collides with about 44% probability.
- **Give every class a member index** from that id to a field handle or a
  method handle, materialized lazily per class (a `ClassValue`), so foreign and
  separately-compiled classes work identically.
- **Lower a structural member access to `invokedynamic`** whose bootstrap
  installs a receiver-class-guarded inline cache. Monomorphic steady state is a
  class compare plus an inlined `getfield`.
- **Keep the dynamic record for `any` and index signatures only.**

**And invert §3.3's priorities.** It defers `any` "until the nominal half runs";
the measurement says `any`-sourced obligations outnumber object-sourced ones
**2,753 to 220** — an order of magnitude. `any` is not the fallback, it is the
common case.

The nominal `implements` edge stays, as a *bounded fast path* where a class
declares it in source or where a hot pair is worth pinning — cheap and safe at a
measured fan-out of 9, and it degrades to the general path rather than to a
cliff. That inverts the original plan: the closure becomes a cache, not a
totality requirement.

One number to design against, from Static TypeScript's own benchmarks: routing
*methods* through a name-keyed table costs 0–13%, while routing *fields* through
it costs about 2×, and a full dynamic property bag costs ~21× a direct field
read. Fields are the hot case in TypeScript, which is exactly why the member
index must resolve to a real field handle and not to a dictionary probe.

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

## 6. Result

The spike is **through**. All eight acceptance programs compile from TypeScript
to Kotlin IR to JVM `.class` files and run, with stdout compared byte for byte.

The bytecode is not a toy. `07-classes` yields `private double value` with
`dadd` and `dreturn` — no boxing anywhere in the class. `05-control-flow`'s
`for` loop is `dstore / dcmpg / dadd / goto`, boxing only at `consoleLog`'s
vararg boundary. And the union decision is visible directly in the disassembly
of `06`:

```
public static final java.lang.String describe(java.lang.Object);   // union ERASED
    7: instanceof  class java/lang/String                          // the proven narrowing
   17: invokedynamic makeConcatWithConstants                       // Kotlin's own concat lowering
```

That last line is worth dwelling on: nobody asked for it. Generated IR goes
through every lowering and optimization kotlinc applies to its own output, which
is the concrete form of the argument for stopping at IR rather than emitting
bytecode directly.

## 7. What the implementation contradicted

These docs were written before the code. Seven claims did not survive contact,
and they are recorded because each was reasoned and wrong:

1. **`this` types as `any`**, so `this.value` does too and resolves to no member
   at all. §1's `memberOf` is unusable for the `this` case; a class property's
   type must be taken on the CLASS's own type instead.
2. **A declaration's return type has no source but its `Signature`.** A
   `TypeNode` is syntax, and the lens exposes no way to resolve one, so the
   oracle's `declaredTypeOf(declaration)` does not exist as designed.
3. **`+` disambiguation over TypeScript types does not fire where it matters.**
   The checker answers `any` for `x + 1` in a narrowed union's else branch
   (there is no subtractive narrow) and for `this.value + by`. The working rule
   decides `+` on the **erased** operand types — a function of the same answers,
   strictly more available, falling to `jsAdd` where they genuinely disagree.
4. **A `let` in a `for` header types as `any`**, so a variable whose own type
   carries no information falls back to its initializer's type — a *recovery*,
   not a widening: a later assignment of another shape is still refused.
5. **`true`, `false`, `null`, `undefined` and `this` are all `Identifier`
   nodes** in this parser, discriminated by `text` rather than by node kind.
6. **IR has no `goto`**, so `for` with `continue` needs a one-iteration
   `do { } while (false)` trampoline whose `break` *is* the `continue`.
7. Mechanics: `IrFactory.createBlockBody` takes no statements; `IrConstructorCall`
   has a `source` property shadowing a field of that name; `irWhen` wants
   `List<IrBranch>`, not `List<IrBranchImpl>`.

What the docs got **right**, and what paid for itself, is the sink seam: overload
selection is asked at the call site and nowhere else, and `console.log` is
recognised by the checker's own `Console`/`log` resolution rather than by
matching syntax.

### Divergences not hidden

- `06`'s then-branch emits `jsToString(x)` where a `CAST` to `String` would do —
  correct output, one redundant call, a consequence of deciding `+` on erased types.
- A non-null erased union parameter picks up Kotlin's `checkNotNullParameter`
  intrinsic: sound under `strictNullChecks`, not JS-faithful for a hostile caller.
- `var`, `==`/`!=`, `typeof x === "object"`/`"function"`, object literals,
  `extends`, statics, generics and arrays are **refused with a position**, never
  degraded. That is the rule that makes the eight passing programs mean something.
## 8. IR- and bytecode-level levers

Working at IR rather than at Kotlin source is not only a convenience; it makes
several representations available that are unexpressible in Kotlin, and rules
out one that looks attractive from source level. Recording the survey, because
the temptation to reach for each of these recurs.

### 8.1 Rejected: `@JvmInline value class` per union

The obvious source-level trick for "a union with no runtime cost" is an inline
value class over `Any` — it erases to `Any` in bytecode, so it allocates
nothing, while giving a distinct static type per union.

It buys nothing here. The static type safety it provides is safety we already
have, from a conformant TypeScript checker, at a strictly higher fidelity than
any JVM encoding could reproduce. What it *costs* is real: value classes get
mangled method names, and they **box at every nullable and every generic
position** — which is precisely where unions live (`T | undefined` is the
commonest union in real TypeScript). So it would allocate exactly where erasure
does not.

### 8.2 Available and worth taking later: specialized overloads

Erasure means `f(x: string | number)` compiles to `f(x: Any)`, and a call site
that statically knows it holds a `string` still boxes nothing but does pass
through `Any`. The JVM permits same-name/different-descriptor methods for free,
so the backend can additionally emit `f(String)` and `f(Double)` bodies and bind
each call site by its static type — which the checker knows at every one.

This is purely additive to erasure and needs no representation change, which is
why it is deferred rather than designed in: it is an optimization with a
measurable before/after, and taking it early would mean measuring nothing.

### 8.3 The real prize: whole-program monomorphization

TypeScript erases generics and so does the JVM, so `Box<number>` and
`Box<string>` would normally share one erased class with `Any?` fields — boxing
every number.

But this compiler sees the **whole program**, and the checker records every
instantiation. So the backend can specialize: emit `Box$number` with a genuine
`double` field beside `Box$string` with a `String` field. That is not available
to `tsc` (which has no runtime types to specialize), and not available to a
Kotlin *source* generator (Kotlin has no user-visible monomorphization). It is
available at IR because IR is where the Kotlin compiler's own inliner and
specializers operate.

This is the strongest argument that the IR target is worth more than a
"TypeScript to Kotlin source" transpiler would be, and it is worth stating as a
hypothesis to test rather than a claim: it predicts that idiomatic generic
TypeScript can run without boxing on the JVM.

### 8.4 `any`, when it arrives: an inline-cache call site

`any`-typed member access has no static target, so it needs dynamic dispatch.
The JVM answer is `invokedynamic` with a bootstrap that installs a
per-call-site inline cache keyed on the receiver's class — which is how every
fast dynamic language on the JVM works, and roughly what a JS engine's inline
caches do.

Kotlin IR does not expose `invokedynamic` construction directly, so the
practical shape is a runtime function holding a `MethodHandle` cache per call
site, with the site identity passed as a constant. Slower than a true indy site,
much faster than reflection, and it keeps `any` from contaminating the typed
majority of the program.

### 8.5 What IR does NOT give us

Worth being explicit, because it bounds the ambition. IR-level generation
produces declarations the **Kotlin resolver cannot see** from another module:
a generated top-level class is written with `k=3` metadata and is invisible to
Kotlin source in a downstream module, though Java and the JVM see it fine.
Making generated declarations resolvable from Kotlin source requires generating
them in the FRONTEND (a FIR extension) as well, which is what serialization and
Compose do. That is a real constraint on any "call your compiled TypeScript from
Kotlin" story, and it is a separate piece of work from this spike.

## 9. The other direction: exporting an API rather than a program

Everything above lowers a TypeScript program to code that RUNS. A library has a
second question — can a Kotlin developer *call* it — and that one is answered by
declarations rather than by bytecode: `docs/kir-kotlin-metadata.md` describes
the export of a checked library's public API as a **Kotlin metadata klib**, the
artifact a Kotlin Multiplatform `commonMain` compiles against.

It shares this pipeline's front end (the checked program and its facts) and
none of its back end: the surface is rendered as Kotlin source and handed to
kotlinc's METADATA compiler, because metadata is a versioned protobuf whose only
writer lives in the compiler. §8.5's constraint is exactly why the two halves
are still separate — IR-generated declarations are invisible to Kotlin source
downstream, so a metadata artifact is how a Kotlin consumer learns what a
compiled TypeScript library offers.

## 10. Prior art: scriptc, and the same bet with the opposite residue decision

`scriptc` (scriptc.dev, read 2026-08-27: the landing page, `/introduction` and
`/limitations`) compiles standard TypeScript to a native binary that "links
against nothing but libSystem". It is the closest living relative this design has,
and it is worth recording because it made the SAME central bet and then split
from us on the one decision that follows from it.

**Provenance rule for this section.** Everything attributed to scriptc is its
own published claim; no scriptc binary was run on this box and nothing here is a
measurement of it. Everything attributed to this backend is a measurement, with
its home document named. The two must not be quoted as if they were the same
kind of statement.

### 10.1 The shared bet

Four things are common ground, and their agreement is the interesting part:

- **A real type checker's proofs are the input, not a shadow type system.** It
  uses TypeScript's own checker against the real `es2025` lib; we use this
  repository's, through the §4 capture seam.
- **Refuse, never degrade.** Its rejected tier emits "a specific error code, a
  code frame, and usually a rewrite hint"; §7 of `docs/kir-lowering.md` is the
  same rule, and `KirFileLowering` carries ~45 distinct refusal sites that name
  the file, the position and the construct. Both projects state the same reason:
  a construct that quietly widens produces a program that compiles and
  misbehaves, which is the one outcome from which nothing can be learned.
- **Generics are monomorphized rather than erased at runtime.** That is §8.3
  here, still a hypothesis; there it is claimed as shipped.
- **Differential testing against Node is the gate.** Its corpus runs under Node
  and as a native binary with stdout, stderr and exit codes compared byte for
  byte; `scripts/kir-bench.sh` gates every arm's `sink=` against the Node arms
  before any timing is read.

### 10.2 Where it splits: what happens to the dynamic residue

Given a static lowering and a conformant checker, the whole design question is
what to do with the part the checker cannot make static. scriptc **partitions**
it out; this backend **absorbs** it.

| | scriptc (claimed) | this backend (measured) |
|---|---|---|
| residue mechanism | tier 2: an embedded quickjs-ng, ~620 KB, opt-in with `--dynamic` | no second engine — the `JsObject` property bag, always present |
| boundary | values crossing back into static code are validated at run time | there is no crossing: a §2b shape instance IS a bag, so an unshaped reader calls the same virtual `get` |
| `any` | rejected without `--dynamic`; also banned in class fields, array elements and union arms | the dominant case by measurement — 2,753 `any`-sourced closed obligations against the closure's own 220 (`docs/kir-structural-typing.md` §7) |
| structural width | "record shapes are exact structs": passing `{a, b}` where `{a}` is expected FAILS, and width subtyping COPIES the record | total and free: every object type erases to the bag, so width subtyping needs no witness, no coercion and no copy |
| memory | reference counting, cycles uncollectable across boundaries, deterministic collection points replacing concurrent GC | whatever the backend gives — a tracing GC on both JVM and Native, so cycles are JS-faithful without being designed for |
| arrays | dense; out-of-bounds access TRAPS | `JsArray`, chosen over `ArrayList` precisely because JS arrays are sparse, growable, and an out-of-range read is `undefined` (§3) |
| strings | UTF-8 storage; relational comparison in code-point order | UTF-16 `String`, "the one genuinely free mapping" (§3) |
| `==` / `!=` | rejected except between identical types or against `null` | full abstract equality, pinned by `KirEqualitySemanticsTest` (`1 == true`, `null == undefined`, `NaN !== NaN`, `0 === -0`, `'0'` truthy) |

### 10.3 The one finding this contributes to §3.4

§3.4 rejected the whole-program nominal closure on three objections, of which
the second was "some of it is not expressible as a JVM interface at all" and the
first was that separate compilation becomes impossible. **scriptc is the same
objection paid for in a different currency, and it is independent evidence for
it**: a compiler that commits to struct-shaped records cannot express TypeScript
width subtyping, so it does not encode it — it CHANGES THE LANGUAGE and rejects
the assignment, or copies the record and loses aliasing. Kotlin/JS and Scala.js
removed the runtime shape question from the language (§3.4); scriptc removed
structural width from the SOURCE language. Three systems, three removals, none
of them an encoding.

So §3.4's decision stands unchanged, and its reasoning is now corroborated from
a fourth direction: the closure is an optimization over a total dynamic
mechanism, never a totality requirement.

### 10.4 The comparison that does not meet

The two projects publish numbers about different quantities, and it is worth
saying so rather than implying a ranking:

- scriptc quotes **deployment**: a ~320 KB hello-world binary starting in ~4 ms,
  against Node's ~120 MB runtime and ~35 ms. No steady-state throughput figure
  appears on the three pages read.
- this backend quotes **throughput only**, within-round against `tsgo -> node`
  (`docs/perf/kir-backend-levers.md` §§ 2b, 6):

| | node | xtsc → JVM | xtsc → Kotlin/Native |
|---|---:|---:|---:|
| mitt, 4M emits | 82.25 ns/emit | **54.50** (1.54× faster) | 354.75 (4.31× slower) |
| smol-toml, 20k parses | 22.20 µs/parse | 33.65 (1.52× slower) | 126.55 (5.70× slower) |

On startup and binary size this backend is not in the same conversation, and on
a managed runtime it does not need to be.

### 10.5 Where scriptc is ahead, plainly

Coverage, and it is not close. It claims `async`/`await` with JS-exact
scheduling, exceptions, regular expressions, closures with JS capture semantics,
single-inheritance classes with dynamic dispatch, and a large slice of Node's
API surface (`fs`, `path`, `process`, `child_process`, `crypto`, `net`, `http`,
`https`, `tls`), plus npm packages through the dynamic tier and a
statement-level `coverage` command that reports the static/dynamic split. This
lowering has **no `async`/`await` at all**, no `.d.ts`-driven interop, refuses
`var`, and its native dynamic-member fallback throws. What it has instead is two
real npm libraries compiling end to end on four arms with an equivalence gate.

### 10.6 What it changes here

One thing, and it is uncomfortable rather than surprising.

**The Kotlin/Native arm competes on scriptc's home ground with a representation
chosen for the JVM's.** §6 of `docs/perf/kir-backend-levers.md` prices that
directly: Kotlin/Native has no escape analysis, so `jsAdd(Any?, Any?)` is
0.95 ns on the JVM and **28.05 ns** native, and boxing one `Double` is 0.86
against **8.61** — which is the whole 4–7× gap and is a property of the dynamic
representation, not of the backend phase. On the JVM a tracing GC and a JIT make
that representation affordable; on a native target the design that pays is
closer to scriptc's, and the only lever here that moves toward it is the NOMINAL
half, (KIR.PERF.1) — whose first slice already measured mitt at −10.7% on the
JVM and **flat** on Native (§2b), i.e. it has not yet reached the boxing that
dominates there.

The direction scriptc structurally does not have is §9's: a checked TypeScript
library's public API exported as a Kotlin metadata klib, so a Kotlin
Multiplatform consumer can call it. A native binary has no such story to tell.
