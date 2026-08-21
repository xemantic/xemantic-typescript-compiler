# KIR acceptance corpus

Each `NN-name.ts` compiles to bytecode and runs; its stdout must equal
`NN-name.expected` byte for byte. Ordered by the oracle question each first
forces — a failure at N tells you which capability is missing, without a
debugger.

| # | first forces |
|---|---|
| 01 | literal types; `console.log`; ECMAScript number formatting (`1/2` is `0.5`, and `42` must not print `42.0`) |
| 02 | locals, mutation, `typeOf` on an identifier reference |
| 03 | `+` DISAMBIGUATION — the same operator is concatenation on line 2 and addition on line 3, decided only by operand types |
| 04 | declared functions: two-pass declare/define, parameter and return coercion, direct calls |
| 05 | control flow, `for` desugaring, comparison operators, recursion-free iteration |
| 06 | UNION ERASURE plus `typeof` narrowing: the parameter is `Any`, and both branches need a cast the checker proved |
| 07 | classes: fields, `this`, constructor, methods, `new` |
| 08 | `T \| undefined` erasing to `T?` rather than `Any`, and `=== undefined` |
| 09 | ARRAYS: `T[]` erasing to the runtime `JsArray`, array literals, index read and write, an out-of-range read, and members reached by the receiver's ERASED type (`push`/`pop`/`join`/`indexOf`/`length`) |
| 10 | CLOSURES: an arrow as a value, a function-typed parameter, a captured parameter, a captured mutable local, and `Array.map` with a callback |
| 11 | OBJECT LITERALS and interfaces: the property-bag erasure, shorthand and method properties, a member write |
| 12 | **mitt 3.0.1, a real published library**, source unmodified plus a driver — see `CorpusTest` |
| 13 | OPERATORS: compound assignment, `++`/`--` in both positions and both value meanings, the bitwise family with its `ToInt32`/`ToUint32` coercions, and `==` as ECMAScript ABSTRACT equality (`1 == "1"`, `null == undefined`) |
| 14 | STRINGS: every `string` member goes through a runtime function, never Kotlin's same-named one — `length` is a NUMBER, `charAt` out of range is `""`, `slice` counts a negative index from the end and `substring` swaps a reversed pair — plus template literals |

Note in 09 that an out-of-range read prints `null` where a JS engine prints
`undefined`: design doc §3.1 maps both TypeScript `null` and `undefined` onto
the one JVM `null`, and this is the first corpus program where that shows.

Every function value erases to `kotlin.FunctionN<Any?, …, Any?>` — UNIFORMLY,
whatever its TypeScript signature says. TypeScript's function assignability is
bivariant and `Function1<in P, out R>` is not, so typing the parameters
honestly would reject programs the checker accepted; the cast is paid at the
use site instead, where union erasure already pays it.

Deliberately absent, and each is its own milestone: `any`, generics,
`async`, modules/imports, getters/setters, `==`.
