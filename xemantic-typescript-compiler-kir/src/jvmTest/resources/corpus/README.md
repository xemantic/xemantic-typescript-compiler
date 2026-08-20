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

Note in 09 that an out-of-range read prints `null` where a JS engine prints
`undefined`: design doc §3.1 maps both TypeScript `null` and `undefined` onto
the one JVM `null`, and this is the first corpus program where that shows.

Deliberately absent, and each is its own milestone: `any`, generics, closures,
`async`, modules/imports, getters/setters, `==`.
