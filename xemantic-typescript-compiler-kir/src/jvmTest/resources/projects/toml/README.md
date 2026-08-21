# smol-toml, as an acceptance corpus

`src/*.ts` — except `main.ts` and `temporal-shim.d.ts` — is
[smol-toml](https://github.com/squirrelchat/smol-toml)'s own source, **taken
unmodified** from its `mistress` branch. It is redistributed here under its
BSD-3-Clause licence, whose text is in `LICENSE` beside this file and whose
copyright notice each source file carries.

It is here because it is a real library that a real program uses: 1,082 lines
across seven files, a hand-written TOML parser with a class extending `Date`,
another extending `Error`, a `const enum`, regular expressions, destructuring
with defaults, optional chaining, `bigint` literals, and a `void 0`. Nothing
about it was chosen or written for this compiler.

Two files are OURS and are marked as such:

- `main.ts` — the driver: it parses a TOML document and prints the result as
  JSON, so the test has an exact string to compare;
- `temporal-shim.d.ts` — an ambient declaration for the `Temporal` proposal,
  which the library's type aliases mention and its code never constructs. It
  stands in for the `temporal-polyfill` types the package depends on, exactly as
  a `@types/*` package would.

`stringify.ts` is deliberately absent: it needs the `Temporal` API itself rather
than only its types, and the parser is what this corpus is for.
