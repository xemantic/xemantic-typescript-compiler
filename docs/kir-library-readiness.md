# Compiling a bigger library: what actually blocks it

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

## What this changes about the plan

The ordering was wrong. Extending the lowering (object literals, arrays, `var`,
`==`, generics) buys nothing while the checker cannot get a real library to
zero. The sequence that works is:

1. **Pick one library as a second conformance corpus** — `yaml` is the right
   size: 76 files, no dependencies, a real parser/serializer, and only two FP
   families between it and clean.
2. **Close those two families**, both of which are already-known weaknesses
   rather than new discoveries.
3. **Then** extend the lowering, driven by what that library actually contains.

Note the differential itself is cheap and repeatable — `tsgo --noEmit -p <dir>`
against the same directory — so "is library X ready" is a question with a
one-command answer, and a second corpus can be adopted without guessing.
