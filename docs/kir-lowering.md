# TS → Kotlin IR lowering (spike subset)

Companion to `docs/kir-design.md`, which fixes the *representation*. This fixes
the *translation*.

## 1. The oracle: what the backend asks the checker

The lowering is a syntax-directed walk that consults a type oracle at exactly
six kinds of question. Naming them up front bounds the seam that `-core` must
expose, and keeps the backend from growing its own shadow type system.

```kotlin
public interface TsTypeOracle {

    /** The type of an expression AT THIS POSITION — narrowed, body-local-correct. */
    public fun typeOf(expression: Expression): Type?

    /** The declared type of a declaration site: parameter, variable, property, return. */
    public fun declaredTypeOf(declaration: Node): Type?

    /** What a name refers to. Answers null for a member name — those go through `memberOf`. */
    public fun symbolOf(identifier: Identifier): Symbol?

    /** The signature overload resolution picked for this call. */
    public fun signatureOf(call: CallExpression): Signature?

    /** The member a property access resolved to, on the receiver's type. */
    public fun memberOf(access: PropertyAccessExpression): Symbol?

    /** An enum member's constant. `Checker.getEnumMemberValue` is already public. */
    public fun enumValueOf(member: Node): ConstantValue?

}
```

Everything else the backend needs is syntax, and syntax it already has.

Two of these answers do NOT come from where you would first look, both measured
while pinning the seam:

- **A `Parameter` is not in the binder's `nodeToSymbol`.** `bindLexicalParameters`
  goes through `declareLexical`, which uses the separate scope-symbol id space and
  deliberately "never touches shared binder state". A parameter's symbol comes
  from `resolveName` on the position's own lexical chain — which is precisely what
  a lens is for.
- **`declaredTypeOfSymbol` answers `any` for a parameter.** Its worker has arms
  for Class / Interface / TypeAlias / TypeParameter and an `else -> anyType`, and
  a `FunctionScopedVariable` falls through it. Use `typeOfSymbol` for value
  symbols and keep `declaredTypeOfSymbol` for the type-declaration kinds, where it
  is the right question.

Two properties this interface must have, and they are the whole risk:

- **Position accuracy.** `typeOf` must answer for the *reference site*, not the
  declaration — otherwise narrowing is invisible and every union stays `Any`,
  which turns every member access into a failure. This is what forces capture
  *during* the checker's walk rather than after it.
- **Nullability of the answer.** A `null` from the oracle means "the checker had
  no answer here", which must abort lowering of that construct with a diagnostic,
  never silently degrade to `Any?`. A spike that quietly widens on oracle misses
  reports success while compiling nonsense.

## 2. Program shape

One TypeScript file → one synthetic `IrFile`. Package name derives from the
file's path relative to the project root, sanitized. Top-level functions become
top-level Kotlin functions (so they land in the `<FileName>Kt` facade class);
top-level *statements* are collected into a generated `main`, in source order,
which is what makes a script-shaped `.ts` runnable.

Declaration order is irrelevant in TS (functions hoist, classes do not), so
lowering is **two-pass per file**:

1. **Declare.** Walk the top level and create every IR declaration *empty* —
   symbol, name, parameter list, return type, class shell — and record
   `tsDeclaration → IrSymbol` in a side table. Nothing has a body yet.
2. **Define.** Walk again and fill bodies, resolving every reference through the
   side table.

Mutual recursion and forward references then cost nothing. A one-pass lowering
would need a patch-up phase, which is strictly worse.

## 3. Names

TS identifiers are not Kotlin identifiers. Mangling rules:

- a TS name that is a Kotlin hard keyword (`object`, `fun`, `is`, `when`, `val`,
  `in`, `typealias`, …) gets a `$` suffix;
- `$` in a TS name is legal in Kotlin, left alone;
- non-ASCII identifier characters are legal in both, left alone.

The mangling is a pure function so that separate files agree on it, and it is
applied at declaration and reference sites through one shared helper — two
copies of a name mangler is a defect waiting to happen (the repo has been bitten
by duplicated name-extraction `when`s before).

## 4. Statements

| TS | IR |
|---|---|
| `const`/`let`/`var` with initializer | `IrVariable`, `isVar = (kind != const)` |
| `const`/`let` without initializer | `IrVariable` initialized to `null`/`Undefined` |
| `if (c) a else b` | `irIfThenElse`, condition through §6 truthiness |
| `while (c) s` | `irWhile` |
| `for (init; cond; step) s` | `IrBlock { init; irWhile(cond) { s; step } }` — the `step` must also run on `continue`, so `continue` inside a `for` lowers to a jump to a label placed before `step`, not to the loop head |
| `return e` | `irReturn`, coerced to the function's erased return type |
| `break` / `continue` | `IrBreak` / `IrContinue` with the enclosing loop's label |
| expression statement | the expression, value discarded |
| `throw e` | `IrThrow`; a non-`Throwable` operand is wrapped by the runtime |
| block | `IrBlock` with its own scope |

## 5. Expressions

| TS | IR |
|---|---|
| numeric literal | `IrConst` `Double` |
| string literal | `IrConst` `String` |
| `true`/`false` | `IrConst` `Boolean` |
| `null`, `undefined` | `IrConst.constNull` (§3.1 of the design doc) |
| identifier | `irGet` of the local, or a call/field access resolved via `symbolOf` |
| `a + b` | if both operand types are numeric → `Double` add; if either is `string` → `String.plus` with `jsToString` on the other side; otherwise → `runtime.jsAdd` |
| `-` `*` `/` `%` | `Double` arithmetic, operands coerced by §6 |
| `<` `>` `<=` `>=` | `Double` or `String` comparison per operand types |
| `===` / `!==` | `runtime.jsStrictEquals` — Kotlin's `===` is wrong for `Double`/`String`, which JS compares by value |
| `==` / `!=` | `runtime.jsLooseEquals` (not in the spike subset; emit a diagnostic) |
| `&&` / `\|\|` | short-circuit `IrWhen`; note the JS result is an *operand*, not a boolean, so the result type is the LUB of the two sides |
| `!e` | `!jsTruthy(e)` |
| `typeof e === "…"` | folded to a type test — see design doc §3.2. Recognized as a *pattern* on the whole `BinaryExpression`, before generic `===` lowering |
| ternary | `IrWhen` |
| call to a known function | direct `irCall` of the generated symbol, via `signatureOf` |
| method call | `irCall` with dispatch receiver |
| `new C(...)` | `IrConstructorCall` |
| property access | `IrGetField`/getter call via `memberOf` |
| element access `a[i]` | `JsArray.get` for arrays; otherwise out of subset |
| array literal | `JsArray` construction |
| `this` | `irGet` of the dispatch receiver parameter |

## 6. Coercion, the part that is easy to get silently wrong

Every operand position has an *expected* erased type. The lowering inserts a
coercion whenever the operand's erased type differs:

- `T` → `Any` where `T` is a JVM primitive: **boxing**, which the Kotlin backend
  inserts itself given a correct IR type — so the lowering must set the
  expression's IR type honestly and NOT hand-roll boxing;
- `Any` → `T`: `IrTypeOperatorCall(CAST)`, per design doc §3.2;
- anything → `Boolean` in a condition position: `jsTruthy`, *unless* the static
  type is already `boolean`, in which case the call is skipped. This is the one
  optimization worth doing immediately, because conditions are everywhere and
  the checker gives the answer for free.

A coercion decision is a function of `(fromErasedType, toErasedType)` and lives
in ONE place. If it ends up spread across the expression lowering, narrowing
bugs become unfindable.

## 7. Classes

- TS `class` → `IrClass`, `parent` = the file, `superTypes = [Any]` or the
  generated base class.
- Fields → `IrField` + accessors only where a TS getter/setter exists.
- Constructor: parameter properties (`constructor(private x: number)`) expand to
  a field assignment prologue.
- Methods → `IrSimpleFunction` with a dispatch receiver.
- `implements` → the generated interface types; the structural closure of design
  doc §3.3 adds more.
- Every class needs `createThisReceiverParameter()`, a primary constructor whose
  body is `irDelegatingConstructorCall(anyConstructor)` followed by
  `IrInstanceInitializerCallImpl`, and `addFakeOverrides` before it is complete.

## 8. What must fail loudly

The spike's value depends on it never *pretending*. Unsupported construct,
oracle miss, or unmapped type → a `KirDiagnostic` naming the file, the position
and the construct, and the emission aborts. Anything that degrades to `Any?` and
carries on will produce a program that compiles and misbehaves, which is the one
outcome from which nothing can be learned.
