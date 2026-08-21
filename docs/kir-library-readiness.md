# Compiling a real library: what it took

**Two real libraries now compile to JVM bytecode through this backend and RUN**
(2026-08-21). The page below is kept in the order it was written, because the
first half is a prediction that the second half corrects.

| library | what it is | result |
|---|---|---|
| `mitt` 3.0.1 | the event emitter, 123 lines, one file | compiles and runs — twice: as a concatenated corpus program, and as a real MODULE a second file imports |
| `smol-toml` | a hand-written TOML parser, 1,082 lines across seven files | compiles and PARSES a 40-line TOML document, checked against **Python's `tomllib`** |

Both are their own published source, unmodified. The TOML acceptance is
differential — the expectation comes from a second, independent TOML
implementation — so it checks the compiled library against another parser
rather than against itself.

The checker reports **zero errors** on both, which is what tsgo 7.0.2 reports.
Getting there closed six checker defects, every one of them a false positive or
a silent false negative that a corpus of one codebase's style could not have
shown (see § "What this changes about the plan" and the round notes).

---

Measured 2026-08-20 on branch `spike/ts-to-kotlin-ir`. Two real, dependency-free
TypeScript libraries fetched from source and checked with xtsc, with **tsgo
7.0.2 as the oracle** (it is on the box and runs).

## The result

| library | files | tsgo errors | xtsc errors | xtsc excess |
|---|---|---|---|---|
| `yaml` 2.7.0 (library proper) | 76 | 5 | 71 | **~66** |
| `zod` 3.24.1 | 84 | 19 | 146 | **~127** |

The remaining tsgo errors are environmental in both cases — `@types/node` is
absent offline (TS2591/TS2307), which is a known local condition, not a defect.

**The blocker to compiling a bigger library is the FRONT END, not the backend.**
That is worth stating plainly, because it is the opposite of what the spike's
own risk list predicted: the lowering refuses constructs loudly and could be
extended incrementally, but a checker that reports ~0.9 false positives per file
cannot be lowered from at all — the backend's own rule is that it must refuse to
emit a program the checker rejected.

## Why this is not a contradiction of the dashboard

xtsc is at **zero false positives on all eight tsc-source profiles**, and has
been since round 481. That is a real achievement and it is also the exact shape
of the problem: the corpus that drove conformance is *one codebase's style*.
`tsc`'s own sources are interface-heavy, factory-function-heavy and written by
people who avoid the parts of TypeScript that are hard to check. A different
real library exercises different parts, and the generalization gap shows up
immediately.

This is the already-parked "any TypeScript project" scope, now with a number
attached to it.

## The two families that dominate

Both are single, well-defined causes rather than a long tail, which is the good
news — roughly 37 of `yaml`'s 66 are the first family alone.

**1. Contextual typing does not reach into literal members.** A fresh literal
widens where tsc keeps it:

```
Type 'string' is not assignable to type '"PLAIN" | "QUOTE_DOUBLE" | "QUOTE_SINGLE"'
Type '{ value: string; type: null; comment: string; range: number[] }'
  is not assignable to
     '{ value: string; type: BLOCK_FOLDED | BLOCK_LITERAL | null; comment: string; range: Range }'
```

The second one shows the array half of the same cause: `range: number[]` against
a tuple-typed `Range`. An array literal in a tuple-typed position is not
contextually typed as a tuple, so it widens to an array.

**2. Union member access where narrowing did not apply.**

```
Property 'contents' does not exist on type
  'Alias | Scalar<any> | YAMLMap<unknown, any> | YAMLSeq<any> | Document | null'
```

The code has narrowed with a type guard; xtsc has not. This is the same weakness
CLAUDE.md already records from the other side — `getPropertyOfType`'s union arm
demands the property on *every* constituent and then returns the first — and it
is directly relevant to the backend too, since a member access on a union
receiver is exactly what union erasure must lower.

## Both families, root-caused to a minimal repro

Neither needed a debugger — pointing the compiler at unfamiliar code and
differencing against tsgo localised both in minutes.

### Family 1 — a `readonly` property with a literal initializer widens

```ts
class C { static readonly A = 'A'; readonly b = 'B' }
const viaConst = 'A'
let fromConst: 'A' = viaConst       // clean: the `const` rule works
let fromStatic: 'A' = C.A           // TS2322: Type 'string' is not assignable to type '"A"'
let fromInstance: 'B' = new C().b   // TS2322, same
```

tsgo: clean. xtsc: two errors. The literal-retention rule exists for `const`
(that control passes) and is simply absent for `readonly` class properties.

**FIXED and RE-MEASURED (WIDEN.1)(b).** The rule landed as tsc's
`isDeclarationReadonly` half of `getWidenedLiteralTypeForInitializer`, and `yaml`
went **71 -> 66** errors with none added.

**The "roughly 37 of 66" estimate above was wrong for THIS rule and is corrected
here — measured, it is worth 5.** The ~37 belongs to the broader literal-retention
FAMILY, of which the readonly property is only one member; its dominant member is a
different site entirely — **24** of the remaining rows are RETURN-POSITION literal-
union retention in `parse/cst.ts`:

```ts
export function tokenType(source: string): TokenType | null {
  switch (source) {
    case BOM: return 'byte-order-mark'   // TS2322: 'string' not assignable to 'TokenType | null'
```

So the next unit of work in this family is return-position contextual literal
retention, not anything further about properties. The lesson for this page: an
error-count attributed to a family by inspection is a hypothesis until one member
of it is fixed and the corpus re-run.

### Family 2 — `instanceof` does not narrow a class imported from another file

```ts
// nodes.ts
export class Doc { contents: string | null = null }
export class Alias { alias = 'a' }

// use.ts
import { Doc, Alias } from './nodes'
export function f(x: Alias | Doc) {
  if (x instanceof Doc) console.log(x.contents)   // TS2339: Property 'contents' does not exist
}
```

tsgo: clean. xtsc: one error per site. The **same code in a single file narrows
correctly** — a five-arm ladder over union shapes (nullable, aliased, three
constituents) is entirely clean when the classes are declared locally. So the
defect is cross-file class identity in the narrow, not the union shape and not
the guard form: an imported user-defined type guard and a bare `instanceof`
fail identically.

A cosmetic sibling shows up in the same output: with `type N = Alias` in scope,
the union `Alias | Doc` *renders* as `N | Doc`, so the alias display table is
naming a constituent after an alias that was not written at that position.

## UPDATE 2026-08-21 — a complete library now compiles AND RUNS on the JVM

**mitt 3.0.1 — a real, published, dependency-free TypeScript library — compiles
to JVM bytecode through this backend and its event emitter works.** Twice over,
and the second is the one that counts:

| | what it proves |
|---|---|
| corpus `12-mitt.ts` | the library's own `src/index.ts`, unmodified, plus a driver appended — compiles, runs, stdout matches byte for byte |
| `ProjectCorpusTest` | the same library as a MODULE: `tsconfig.json`, `src/mitt.ts`, and a `src/main.ts` that says `import mitt from './mitt'` — one program, nothing concatenated |

The checker reported **zero errors** on mitt. For this library the front end was
never the obstacle; the backend was, and the ordering below is what closed it.

### The capability ladder, in the order the libraries forced it

Each rung was found the same way — point `LibraryProbe` at the source, read the
first refusal, which names a file, a line and a column — and each is a corpus
program that compiles to bytecode and runs:

| # | capability |
|---|---|
| 09 | **arrays**: `T[]`, `Array<T>`, `ReadonlyArray<T>` and every tuple erase to one runtime `JsArray`; members are found by the receiver's ERASED type, not by the checker's rendering of its TypeScript one |
| 10 | **closures**: an arrow is a Kotlin lambda; every function type erases UNIFORMLY to `FunctionN<Any?, …, Any?>`, because TypeScript's function assignability is bivariant and the JVM's is not |
| 11 | **object literals and interfaces**: the property-bag erasure — the DYNAMIC half of the hybrid, taken first because §7 of `kir-structural-typing.md` measured it as 12× the nominal half |
| 12 | **mitt**: `Map`/`Set` as runtime classes, `jsCall`'s arity adaptation, optional parameters and defaults, parameter assignment, `>>>` |
| — | **modules**: a project directory compiled as one program |
| — | **module bodies that RUN**: a `moduleInit` per file, called in dependency order, and module variables as JVM statics reached across files through accessors |
| — | **classes 2**: `extends` (a generated class OR a runtime one), `super(…)` and `super.m()`, overrides through a base-typed reference, statics, accessors, `instanceof` |
| — | **the dynamic operations**: `jsGet`/`jsSet`/`jsInvoke`/`jsIndexGet`/`jsIndexSet`, for a receiver the checker typed `any` |
| — | **the rest smol-toml needed**: `RegExp`, `Date`, `Error`, `enum` (inlined constants), `bigint` literals, destructuring with defaults at both levels, optional chaining, overloads, `typeof` as an operator, the comma operator, `void`, statements before `super(…)`, and `Math`/`Number`/`Object`/`JSON`/`String` globals |

Two decisions inside that are worth carrying forward.

**A lib type never erases to a property bag.** `Map` is declared in a lib
`.d.ts` as an interface structurally indistinguishable from one the program
could have written, and erasing it to a bag would make `m.size` read `undefined`
and every method call fail inside the runtime — silently, in a program that
compiled. Only a declaration whose root file is one of the PROGRAM's own may
become a bag; a lib type reaches a runtime class through a short explicit table
or is refused.

**A union of function types has no single erasure.** mitt's own
`Handler | WildcardHandler` is a `Function1` on one side and a `Function2` on
the other, and JavaScript calls either with whatever the site supplies. So a
call whose callee is a union of callables, an `any`, or a property-bag member
goes through the runtime's `jsCall`, which pads missing arguments with
`undefined` and drops extra ones. This is a deliberate widening of the
"refuse rather than pretend" rule, and it is confined to calls the checker
itself treats dynamically.

### And it found a checker defect of the silent kind

`export default function f() {}` is neither an `ExportAssignment` nor bound
under the name `default` — it is bound under its OWN — so the default-import
alias resolved to nothing, i.e. to `any`, and every misuse of a default-imported
function went unreported. Measured against tsgo 7.0.2 on two files:

```ts
// d.ts
export default function twice(x: number): number { return x * 2 }
// main.ts
import twice from './d'
const probe: string = twice(1)   // tsgo: TS2322.  xtsc, before: SILENT.
```

Fixed, with the whole suite green (15,448 tests). Named imports were never
affected, which is what makes it a property of the default import rather than of
imports — and it is exactly the shape this page predicted: a corpus that drove
conformance is one codebase's style, and `export default` is not tsc's.

## What this changes about the plan

**Amended by the update above, and the amendment is a correction of THIS
section.** The ordering below was derived from `yaml` and `zod` alone, and it
generalized one library's obstacle into a rule: it says extending the lowering
"buys nothing while the checker cannot get a real library to zero". Measured on
a third library, that is false in both halves — mitt reached zero checker errors
untouched, and every step between "type-checks" and "runs" was backend work. So
the rule is really per-library, and the cheap way to know which obstacle a
candidate has is to ask both compilers before planning anything:
`tsgo --noEmit -p <dir>` for the front-end half, `LibraryProbe` for the backend
half.

The `yaml` measurement moved a long way under that method, without anyone
working on `yaml`: **80 -> 24 errors** (4 of them the absent `@types/node`, i.e.
environmental) purely from the defects the other libraries exposed —
cross-module `instanceof`, imported type guards, const-arrow guards, the
default import, return-position literals, module-level `const` literals, object
literals typed under their target's shape, and assignment narrowing over a
computed primitive. TS2339 on a union, 21 rows at the start, is now zero.

For `yaml` and `zod` specifically, the ordering below still holds:

1. **Pick one library as a second conformance corpus** — `yaml` is the right
   size: 76 files, no dependencies, a real parser/serializer, and only two FP
   families between it and clean.
2. **Close those two families**, both of which are already-known weaknesses
   rather than new discoveries.
3. **Then** extend the lowering, driven by what that library actually contains.

Note the differential itself is cheap and repeatable — `tsgo --noEmit -p <dir>`
against the same directory — so "is library X ready" is a question with a
one-command answer, and a second corpus can be adopted without guessing.
