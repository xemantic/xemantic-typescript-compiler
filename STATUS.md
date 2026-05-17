# Status

**Phase 4 — Checker buildout.** 8,765 / 10,078 tests passing (~87.0%).

Only the most recent ~5 B-entries are kept here. Older session notes live in
`STATUS-HISTORY.md` (and in `git log`, where every B-entry has a matching commit).

**B42.2 (2026-05-17, +1 — flips `isolatedModulesAmbientConstEnum_ts` errors-baseline)** —
TS2748 "Cannot access ambient const enums when 'isolatedModules' is enabled" now fires
for `E.X` where `E` is a `declare const enum E { ... }` in a non-.d.ts file under
`@isolatedModules: true` (without `@preserveConstEnums`). Per-file check in
`checkSinglePropertyAccess` (Checker.kt:50001): resolves the receiver identifier,
walks `declarations` for `EnumDeclaration` with both Const + Declare modifiers, and
emits TS2748 at the receiver position with squiggle length = identifier text length.
Skip when `preserveConstEnums` is set (TypeScript still allows the access — the const
enum is preserved at runtime as an object). Per-file scope: uses `binderResults` lookup
matching the file's `sourceFile.fileName`, not a global enum cache, so cross-file
const enums declared via `declare const enum` in OTHER files are still flagged.
Full-suite 10078/1310/3 (was 10078/1311/3, +1 net). Zero regressions.

**B42.1 (2026-05-17, +1 — flips `isolatedModulesExportDeclarationType_ts` JS-emit)** —
For multi-file `@isolatedModules` with `import { T } from "./type"` where T resolves to
a type-only export, `isValueExport` was returning true (treating T as runtime) because
the symbol's `flags` had been polluted by `mergeSymbolTable` — same-name symbols from
importing files merge their flags into the target file's locals (CLAUDE.md gotcha:
"ALL file locals merged into globals at Checker init"). The polluted T had
BlockScopedVariable|Alias|TypeAlias|ExportValue flags from cross-file merging.

`isValueExport` now scans the target file's source statements DIRECTLY to classify
declarations of `name` as value or type, avoiding the polluted symbol flags. For names
not found as direct declarations (ambient/aliased cases), falls back to the
flag-based logic. Companion change: `ExportAssignment` for `export default expr` now
captures and propagates `trailingComments` through `makeExportAssignment` so the
`// Ok` comment on `export default T;` survives erasure-vs-emission. Restricted the
parser change to `export default` only (NOT `export =`): under ES-module emission,
`export = X` is silently dropped by `Emitter.emitExportAssignment`, and
`emitTrailingCommentsBeforeNewline` would otherwise back up past the prior statement's
newline and attach the comment there (`es6ExportAssignment2_ts` regression). Full-suite
10078/1311/3 (was 10078/1312/3, +1 net). Zero regressions.

**B41.2 (2026-05-17, +1 — flips `numericLiteralsWithTrailingDecimalPoints01_ts` JS-emit)** —
Multi-line property access (`expr\n  /* comment */ .name`) now preserves the leading
comment between the expression and the dot. Previously, the comment was attached to
the dot token in the scanner but lost on the next `scanner.scan()` call (which resets
`leadingComments`). The parser now captures `leadingComments()` BEFORE calling
`nextToken()` to consume the dot (when `newLineBefore=true`), and merges them into
the property name's `leadingComments`. The emitter handles them specially when
`newLineBefore=true`: emit AFTER the indent, BEFORE the dot. Block comments are
followed by a space (`/* comment */ .toString()` form); line comments are followed
by newline + indent (`// comment\n    .toString()` form). Full-suite 10078/1312/3
(was 10078/1313/3, +1 net). Zero regressions; only the target test flips.

**B41.1 (2026-05-17, +2 — flips `functionsMissingReturnStatementsAndExpressions_ts` target_es5/target_es2015)** —
TS2355 ("function whose declared type is neither 'undefined', 'void', nor 'any' must
return a value") now fires for union-with-undefined return types like `undefined | number`
when the function has no explicit return statements. Previously, the "nullable"
classification suppressed TS2355 entirely; the early-return in `checkBodyForImplicitReturn`
matched union-with-undefined unconditionally. Per TypeScript's actual behavior, `undefined`
in a union does not satisfy the "must return a value" rule — only `void`/`any`/`never` (in
a union) or `undefined` (as a bare keyword, or as the single arg to `Promise<...>` for
async functions) suppress TS2355. The fix: replaced the bare early-return with a TS2355
emission for "nullable + !hasAnyReturn"; updated the "pure-undefined" check to also accept
`Promise<undefined>` (where the arg is a `KeywordTypeNode` for `undefined`) so async
`Promise<undefined>` return types still suppress TS2355. Both non-strict (`f23(): undefined
| number`) and strict (`f11(): undefined | number`, `f31(): Promise<undefined | number>`)
behavior covered. Full-suite 10078/1313/3 (was 10078/1315/3, +2 net). Zero regressions.

**B40.1 (2026-05-17, +1 — flips `declarationEmitResolveTypesIfNotReusable_ts` JS-emit)** —
Parser's `TypeOfKeyword` branch in `parseNonArrayType` now handles indexed-access
suffix `typeof X[K]` in addition to the existing array-suffix `typeof X[]` case.
Previously, `(o: typeof a['a']) => {}` would parse `typeof a` as the type and leave
`['a']` for the outer parser, which misinterpreted it as a destructured second
parameter (yielding `(o, []) => 'a';\n{ }`). The extended `while` loop now follows
the same pattern as the primary-type path immediately below — when the bracket is
not empty, consume `[`, parse an index type, expect `]`, wrap in `IndexedAccessType`.
ASI guard added (`!scanner.hasPrecedingLineBreak()`) to match the primary-type
loop's behavior. Full-suite 10078/1315/3 (was 10078/1316/3 post-B39.1, +1 net).
Zero regressions; only the target test flips.

**B39.1 (2026-05-17, +1 — flips `exportAssignmentImportMergeNoCrash_ts` JS-emit)** —
Preserve `const tempName = __importDefault(require(...))` for a default import whose
user-facing local binding name is SHADOWED by a same-name top-level
`VariableStatement`/`FunctionDeclaration`/`ClassDeclaration` declaration in the
original source AND the binding name is referenced in value positions. TypeScript
keeps the require's side-effect emit even when the temp const's identifier becomes
unused in the rewritten output because the shadowing local wins the rename map
(`Obj → exports.Obj` via Direct path) instead of `Obj → <temp>.default`. Example:
`import Obj from "./assignment"; export const Obj = void Obj;` previously elided
`const assignment_1 = __importDefault(require("./assignment"))` because
`assignment_1` appeared unused in the result — now kept. Gate is strictly limited
to default imports (not named — those may resolve to type-only targets via
`export type` re-resolution) AND shadowed cases only (not normal const-enum
imports whose references get inlined to `0 /* X.Foo */` and which must still
elide). 22-line addition in `Transformer.kt` `transformToCommonJS` Step 2
elision (~line 2486). Full-suite 10078/1316/3 (was 10078/1317/3, +1 net). Zero
regressions.

**B38.1 (2026-05-17, +1 — flips `privacyTopLevelInternalReferenceImportWithExport_ts` JS-emit)** —
Exported `import alias = X.Y` is now erased when `X` is a non-exported but
runtime-instantiated namespace AND `Y` is a type-only sub-member (interface,
type alias, or type-only sub-namespace). Previously, the `requireRootExported`
gate on `isQualifiedPathTypeOnly` was too narrow — it kept aliases that TypeScript
erases. The new gate `requireRuntimeOrExportedRoot` allows the root to be EITHER
exported OR runtime-instantiated. Example: `namespace m_private { export class
c_private {}; export interface i_private {}; export namespace mu_private { export
interface i {} } }` + `export import im_public_i_private = m_private.i_private;`
+ `export import im_public_mu_private = m_private.mu_private;` — both now erased
because `m_private` has runtime members (class/enum/var) and `i_private` /
`mu_private` are type-only. Non-runtime-non-exported roots (e.g. `namespace x {
interface c {} }` + `export import a = x.c`) still keep the alias with a
runtime-broken `exports.a = x.c` emit, matching TypeScript's behavior of emitting
syntactic value references even when they'd fail at runtime. Three call sites
updated (CJS pre-scan, AMD pre-scan, `transformImportEqualsDeclaration`); helper
renamed and gate condition extended. Verified zero regressions across 10078-test
suite — only the target test flips.

