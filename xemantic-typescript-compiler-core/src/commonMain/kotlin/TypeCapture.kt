/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */

package com.xemantic.typescript.compiler

/**
 * One AST node's RAW span, as the thing a [TypeCaptureRequest] names.
 *
 * RAW deliberately: [start] is `Node.pos` and [end] is `Node.end` exactly as the
 * parser wrote them, INCLUDING the fact that `Node.end` is the end of the token
 * FOLLOWING the node (round 910 — `Parser.getEnd()` is the scanner position read
 * after the one-token lookahead, so sibling spans overlap and `[start, end)` is not
 * a containment test). Nothing here interprets the numbers; they are an IDENTITY,
 * compared for equality against the nodes of a parse of the same text with the same
 * [ParserFlags], which INV.1(e) makes the same parse.
 *
 * That is the whole reason a capture is keyed on a span pair rather than on a caret
 * offset: "which node is the caret on" is a question with real subtleties (the
 * overlapping ends above, trivia, the half-open boundary convention), and every one
 * of them is answered ONCE, by whoever owns the caret — `SourceIndex` in the
 * `-project` module — instead of being approximated a second time inside the
 * checker, where there is no token index to answer it with.
 */
data class TypeCaptureSpan(
    val fileName: String,
    val start: Int,
    val end: Int,
)

/**
 * The type the checker computed at a requested [TypeCaptureSpan], as text.
 *
 * VALUE-TYPED on purpose: no `Type`, no `Symbol`, no `Node`. The perf arc keeps
 * rewriting exactly those structures (rounds 889-908 changed packed-key hashing,
 * container types and memo layouts), so publishing them through a capture would
 * freeze them as API.
 *
 * @property kind the `SyntaxKind` name of the node that was typed — the caller
 *   asked about a span, and this says what the compiler found there.
 * @property typeText the checker's own `typeToString` rendering, i.e. the same text
 *   a diagnostic message would name the type by.
 */
data class CapturedType(
    val fileName: String,
    val start: Int,
    val end: Int,
    val kind: String,
    val typeText: String,
)

/**
 * (API.3b) ONE declaration of the symbol a requested [TypeCaptureSpan] resolved to
 * — a place go-to-definition can navigate to.
 *
 * ## Why this one carries a LENGTH where [CapturedType] carries a raw `end`
 *
 * A captured TYPE describes the span the caller named, so it can hand the raw
 * `(start, end)` identity straight back and let the caller — which owns a token
 * index — say how long the node really is. A DECLARATION is in a different file,
 * usually one the caller never asked about and may not even be able to read (a
 * `lib.*.d.ts` has no path on disk), so the same trick would push a span-semantics
 * problem onto a party with nothing to solve it with. The checker holds every
 * program file's TEXT, so the exact end is computed HERE and only a finished span
 * crosses the boundary.
 *
 * [start] is therefore exact ([Node.pos] is already the first character of the
 * node's first token) and [start] `until` [start] `+` [length] is the real extent
 * — NOT `end - pos`, which would overshoot by a token (round 910).
 *
 * @property fileName the file the declaration is in, as the program names it.
 * @property start the 0-based offset of the declaration's first character.
 * @property length its real extent, half-open with [start].
 * @property kind the `SyntaxKind` name of the node the span covers — the
 *   declaration's NAME where it has one, so an editor highlights `foo` rather
 *   than a whole class body, and the declaration itself otherwise.
 */
data class CapturedDeclaration(
    val fileName: String,
    val start: Int,
    val length: Int,
    val kind: String,
)

/**
 * (API.3b) The symbol a requested [TypeCaptureSpan] resolved to, and where it is
 * declared.
 *
 * Keyed on the query span the same way [CapturedType] is, by the RAW `(pos, end)`
 * pair — see [TypeCaptureSpan] for why identity and extent are deliberately
 * different questions.
 *
 * ## What resolves, through which of the two mechanisms
 *
 * A FREE NAME — an [Identifier] naming something the lexical scope chain in force
 * at that position binds — resolves through that chain, which is why the answer has
 * to be taken DURING the walk.
 *
 * (API.3d) A MEMBER name resolves through its RECEIVER instead: the receiver's type
 * is computed and the property symbol of that type is the answer. It is a second
 * mechanism because it has to be — a member name is bound by no scope, so a scope
 * lookup of the `p` in `o.p` finds whatever unrelated `p` shares the spelling, and
 * a confidently wrong navigation target is worse than none. The receiver-bearing
 * positions are `o.p` (a property, a method, an accessor, a static, `this.p`,
 * `super.p`) and the qualified `N.x` / `N.T` of a namespace, a module alias or an
 * enum, the last three answered from the export table rather than from a type.
 *
 * (API.9) An ELEMENT ACCESS names its member with a STRING LITERAL (`o["p"]`), and
 * a BINDING ELEMENT's `propertyName` names one with an identifier no scope binds
 * (the `p` of `const { p: local } = o`). Both are member positions with a receiver
 * — the access's own expression, and the type the pattern destructures — so both go
 * through the same collection as `o.p`, and the string literal is the one query
 * span in this API that is not an [Identifier].
 *
 * ## What answers NOTHING, and why each is a refusal rather than a gap
 *
 * A PropertyAssignment name (`{ p: v }`) — the answer would be the CONTEXTUAL
 * type's property, which is not a function of any receiver and is not in hand at an
 * arbitrary node. A member DECLARATION's own name (`interface I { p: string }`) —
 * it already IS the declaration, and [related] rather than [locations] is where a
 * class member's relation to the base it implements is recorded. A chained
 * namespace segment (`A.B.x`) — the middle segment would have to be resolved the
 * same way, for a case one caret to the left already answers. A LABEL — not a
 * symbol at all.
 *
 * @property name the resolved symbol's name — the spelling that was looked up,
 *   which after an import hop may differ from the identifier at the query span.
 * @property locations every declaration contributing to that symbol, in the
 *   binder's own order. MORE THAN ONE is normal, not an error: declaration
 *   merging is the language feature that makes `interface I` twice, or a function
 *   and a namespace of the same name, one symbol — and a union or intersection
 *   receiver contributes one per constituent that declares the member, in
 *   constituent order. EMPTY never happens — a symbol with no declarations is not
 *   recorded at all.
 * @property related (API.9) declarations this occurrence is tied to by a DECLARED
 *   HERITAGE EDGE and is not a navigation target for — the `p` of
 *   `class Impl implements Shape` is related to `Shape`'s `p`, transitively through
 *   the whole `extends`/`implements` closure. It is a SEPARATE field rather than
 *   more [locations] because the two questions have different answers and both were
 *   measured against tsc 7.0.2: go-to-definition on an implementor's member answers
 *   THAT MEMBER, while find-references on it answers the base's whole group. Empty
 *   for everything else, and in particular for a class whose shape merely happens to
 *   match — a structurally compatible member with no heritage clause is a different
 *   symbol to tsc too, which is what makes this an edge rather than a similarity.
 */
data class CapturedDefinition(
    val fileName: String,
    val start: Int,
    val end: Int,
    val name: String,
    val locations: List<CapturedDeclaration>,
    val related: List<CapturedDeclaration> = emptyList(),
)

/**
 * (API.4a) ONE member of the type at a requested [TypeCaptureRequest.memberSpans]
 * span — a completion candidate.
 *
 * VALUE-TYPED for [CapturedType]'s reason, and carrying enough for an editor to
 * RENDER the item without a second query: the name to insert, what sort of thing it
 * is, its type as text, and the three facts a list widget shows as decoration.
 *
 * @property name the member's name, exactly as it must be written after the dot.
 * @property kind the `SyntaxKind` name of the member's own declaration —
 *   `PropertyDeclaration` for a property (interface members are `ClassElement`s in
 *   this parser, so a property signature is one too), `MethodDeclaration` for a
 *   method, `GetAccessor` / `SetAccessor` for an accessor, `Parameter` for a
 *   constructor parameter property, `EnumMember` for an enum's member. `"Unknown"`
 *   for a symbol carrying no declaration at all (a synthesized tuple member).
 *   THIS is how a host tells a method from a property; there is no separate flag.
 * @property typeText the member's type, rendered as the compiler renders it in a
 *   message. For a UNION receiver it is the DISTINCT types the member has across
 *   the constituents, in constituent order, joined by `" | "` — the member of
 *   `{ p: string } | { p: number }` reads `string | number`.
 * @property optional true when the member is declared `p?`, or is optional in ANY
 *   constituent of a union receiver: a member that may be absent on one arm of the
 *   union may be absent through the union.
 * @property readonly true when ANY contributing declaration carries `readonly`.
 * @property accessibility `"public"`, `"protected"` or `"private"`, read from the
 *   declaration's modifiers. (API.7) An inaccessible member is no longer RECORDED at
 *   all — see [TypeCaptureRequest.memberSpans] for the rule and its prove-to-hide
 *   bias — so this describes what survived the filter, which is what a caller greying
 *   an item rather than hiding it needs.
 */
data class CapturedMember(
    val name: String,
    val kind: String,
    val typeText: String,
    val optional: Boolean,
    val readonly: Boolean,
    val accessibility: String,
)

/**
 * (API.4a) Everything the type at a requested [TypeCaptureRequest.memberSpans] span
 * calls its own — the member half of a completion list.
 *
 * Keyed on the query span exactly as [CapturedType] and [CapturedDefinition] are, by
 * the RAW `(pos, end)` pair.
 *
 * An entry EXISTS whenever the checker reached the span; [members] being empty is
 * therefore a real answer ("this receiver has no members") and is distinguishable
 * from no entry at all ("the checker never walked that span"). Both are legitimate
 * and both come out of `Project.completionsAt` as an empty list.
 *
 * @property members deduplicated by name and sorted by name ascending. Sorted here
 *   rather than left in table order because a member table's iteration order is an
 *   implementation property (`resolveStructuredTypeMembers` fills it base-first) and
 *   a completion list a user reads must not reorder under a checker change.
 */
data class CapturedMembers(
    val fileName: String,
    val start: Int,
    val end: Int,
    val members: List<CapturedMember>,
)

/**
 * (API.4b) ONE name the lexical scope chain binds at a requested
 * [TypeCaptureRequest.scopeSpans] position — a free-name completion candidate.
 *
 * VALUE-TYPED for [CapturedType]'s reason, and deliberately CARRYING NO TYPE. That
 * is the round-918 decision and it is a measurement, not a taste: a real caret sees
 * hundreds of names (measured below and in `docs/language-service.md`), almost all
 * of them lib globals, and typing every one of them costs a `getTypeOfSymbol` plus
 * a `typeToString` per item — work the checker had no other reason to do, on a
 * query a host wants to run per keystroke. The second reason is correctness rather
 * than cost: a free name may name a TYPE (an interface, a type alias, a namespace,
 * an enum), and `getTypeOfSymbol` of such a symbol is not "the type of the name" at
 * all — it renders `any`, which is a confidently wrong decoration. A host that
 * wants the type of the ONE item its user has highlighted asks `Project.quickInfoAt`
 * for it, which is the shape every LSP server uses (`completionItem/resolve`).
 *
 * @property name the text to insert, exactly as it must be written.
 * @property kind the `SyntaxKind` name of the declaration that binds it —
 *   `VariableDeclaration`, `Parameter`, `FunctionDeclaration`, `ClassDeclaration`,
 *   `InterfaceDeclaration`, `TypeParameter`, `ImportSpecifier`, `EnumMember`, … —
 *   or `"Unknown"` for a symbol carrying no declaration. THIS is what a completion
 *   widget renders as an icon, and it is also what tells a LOCAL binding from the
 *   outer one it shadows.
 */
data class CapturedName(
    val name: String,
    val kind: String,
)

/**
 * (API.4b) Everything in scope at a requested [TypeCaptureRequest.scopeSpans] span
 * — the free-name half of a completion list.
 *
 * Keyed on the query span exactly as [CapturedMembers] is, by the RAW `(pos, end)`
 * pair. An entry exists whenever the checker reached the span, so an empty [names]
 * is a real answer and is distinguishable from no entry at all.
 *
 * @property names deduplicated by name — INNERMOST WINS, which is what makes a
 *   shadowed outer binding disappear rather than appear twice — and sorted by name
 *   ascending, for [CapturedMembers.members]' reason.
 */
data class CapturedScope(
    val fileName: String,
    val start: Int,
    val end: Int,
    val names: List<CapturedName>,
)

/**
 * (API.6) ONE call's span, plus WHICH ARGUMENT the caret is in — what a signature
 * help request names.
 *
 * The only capture span carrying a payload beyond the span itself, and it has to:
 * "which overload is the active one" is a question about the argument the user is
 * TYPING, and that argument is not a node. `f(a, |)` parses to a call with ONE
 * argument while the caret sits in the second slot, so the checker cannot recover
 * the index from the tree — it is a property of the COMMAS, which only the caller's
 * token index can count (see `SourceIndex.signatureAnchorAt`). Recomputing it here
 * would be the second approximation of a caret [TypeCaptureSpan] exists to prevent.
 *
 * [start] and [end] are the CALL node's raw `(pos, end)` identity, exactly as
 * [TypeCaptureSpan]'s are.
 *
 * @property activeArgument the 0-based index of the argument slot the caret is in.
 *   Never negative; it may exceed the number of arguments the parse produced, which
 *   is the ordinary trailing-comma case.
 */
data class SignatureCaptureSpan(
    val fileName: String,
    val start: Int,
    val end: Int,
    val activeArgument: Int,
)

/**
 * (API.6) ONE parameter of a rendered signature, and where it sits in the
 * signature's own label.
 *
 * [labelStart] `until` [labelEnd] index [CapturedSignature.label], NOT the source
 * file — they are what an editor bolds while the user types that argument. They are
 * recorded AS THE LABEL IS BUILT rather than searched for afterwards, because a
 * search for `name: type` finds the wrong occurrence the moment a parameter's TYPE
 * mentions another parameter's spelling.
 *
 * @property name the parameter's name, or its destructuring pattern rendered as
 *   source (`{ a, b }`), without the `...` of a rest parameter.
 * @property typeText the parameter's type as the compiler renders it — the SAME
 *   renderer hover uses, deliberately, so a host never has to reconcile two
 *   spellings of one type.
 * @property optional true when a caller may omit it: declared `p?`, or carrying a
 *   default. A rest parameter is NOT reported optional — it is reported [isRest],
 *   and the two facts mean different things to a widget.
 * @property isRest true for `...args: T[]`.
 */
data class CapturedSignatureParameter(
    val name: String,
    val typeText: String,
    val optional: Boolean,
    val isRest: Boolean,
    val labelStart: Int,
    val labelEnd: Int,
)

/**
 * (API.6) ONE signature of the callee at a requested [SignatureCaptureSpan] —
 * usually the only one, and one of several when the callee is OVERLOADED.
 *
 * @property label the whole signature on one line: the callee's name where it has a
 *   syntactic one, its type parameters where it declares any, the parameter list,
 *   and the return type — `pick<T>(xs: T[], i: number): T`. A construct signature is
 *   prefixed `new `.
 * @property parameters in declaration order, each carrying its own range within
 *   [label].
 * @property returnTypeText the return type alone, for a host that lays the signature
 *   out itself rather than showing [label].
 * @property activeParameter the index into [parameters] the caret's argument lands
 *   on, or -1 when this signature has no parameter for it. It is NOT simply the
 *   request's `activeArgument`: once the caret is past the fixed parameters of a
 *   signature ending in a REST parameter it clamps to that rest parameter, because
 *   every further argument feeds it.
 */
data class CapturedSignature(
    val label: String,
    val parameters: List<CapturedSignatureParameter>,
    val returnTypeText: String,
    val activeParameter: Int,
)

/**
 * (API.6) Every signature the callee of the call at a requested
 * [SignatureCaptureSpan] has.
 *
 * Keyed on the query span exactly as [CapturedMembers] is, by the RAW `(pos, end)`
 * pair. An entry exists whenever the checker reached the call, so an empty
 * [signatures] is a real answer ("this callee has no call signatures — it is `any`,
 * or unresolvable, or not callable") and stays distinguishable from no entry at all.
 *
 * @property signatures in DECLARATION ORDER, which is overload resolution order and
 *   is what an editor's "2 of 3" counts through. Not sorted, unlike
 *   [CapturedMembers.members]: here the order is the language's, not the
 *   implementation's.
 * @property activeSignature the index into [signatures] a host should show first.
 */
data class CapturedSignatures(
    val fileName: String,
    val start: Int,
    val end: Int,
    val signatures: List<CapturedSignature>,
    val activeSignature: Int,
)

/**
 * (API.3) A set of positions a compile is asked to record the type AT, handed to
 * the compiler BEFORE the build.
 *
 * ONE request, TWO recorded facts: at every span the checker records the
 * [CapturedType] and the [CapturedDefinition]. They are recorded together because
 * they are recorded by the same per-node hook and both are functions of the same
 * walk-scoped state, and because separating them would double the number of
 * compiles a host needs to describe one caret.
 *
 * ## Why the direction is inwards
 *
 * `Checker` does all its work in its `init` block, so the instance still holds its
 * tables afterwards and "keep the checker and ask it later" looks free. It is not:
 * `getTypeOfIdentifier` consults, IN ORDER, `currentLocalTypes` (its own comment:
 * *"populated during TS2322 checking walk"*), `currentParamBindingNames`,
 * `currentCheckFileName` -> `fileLocalTypeMaps`, `currentFileLocals`, the
 * inference-namespace chain, and only THEN the node-keyed per-file lookup. At rest
 * the first is an empty map and the file fields are null, so a post-hoc query skips
 * five reads and falls through towards globals — for a function-body local that
 * does not merely lose narrowing, it can resolve to an unrelated same-named global.
 * `currentLocalTypes` is STATEMENT-POSITION-scoped and built as the walk proceeds,
 * so it cannot be reconstructed for an arbitrary position without re-walking to
 * that position — which is the whole argument for capturing during the walk.
 * `TypeCaptureMeasurementTest` measures the difference rather than asserting it.
 *
 * ## Cost
 *
 * A capture is a COMPILE. It BATCHES, which is what makes that acceptable: one
 * build captures every span in the request, so "semantic info for file X" is one
 * compile rather than N. (API.3c) exposes that — `Project.semanticsAt` and
 * `Project.fileSemantics` — and the only thing bulk changed in here is that the
 * per-file key sets are now big enough for their hash spread to matter, so
 * [packSpanKey] finalizes the pack.
 *
 * ## Off is free
 *
 * A null request (the default everywhere) leaves the compiler's behaviour and its
 * counters untouched: the checker's per-node hook is one null-valued instance field
 * read and a perfectly-predicted branch — the shape `SpineDispatch.mode` has had
 * since round 732 — and the field is null for every file when no span is requested.
 * Nothing is allocated and no argument is evaluated at the call site (round 900: a
 * probe's guard cannot protect its own ARGUMENT, because Kotlin evaluates arguments
 * strictly).
 *
 * ## What a capture may cost the build it rides on
 *
 * Typing a node the checker had no reason to type is extra WORK and can populate
 * caches, so a captured build is not guaranteed to produce byte-identical
 * diagnostics to an uncaptured one. Callers therefore do not reuse a captured
 * build's diagnostics as the project's diagnostics.
 */
data class TypeCaptureRequest(
    /**
     * The spans to record, in any order. Duplicates are harmless (a span is
     * recorded once, by the DEEPEST node carrying it).
     */
    val spans: List<TypeCaptureSpan>,
    /**
     * (API.4a) The spans to additionally ENUMERATE THE MEMBERS OF — a completion
     * request names its RECEIVER here, and gets [CapturedMembers] back.
     *
     * ## Why a second list rather than a flag on every span
     *
     * Enumerating a type's members resolves every member's type, which is real work
     * and is work `Project.fileSemantics` must never do: that caller hands in every
     * identifier in a file, and enumerating members at each of them would multiply
     * a linear sweep by the size of every type it touches. A completion asks about
     * ONE receiver. Keeping the two populations apart makes that structural instead
     * of a convention.
     *
     * A span may appear in both lists; it then gets a type, a definition and a
     * member list, all from the one visit. [keysByFile] is the UNION, so the
     * checker's per-node hot-path guard is unchanged — the member test happens only
     * after a span has already matched.
     *
     * ## What member enumeration answers, and what it deliberately does not
     *
     * The receiver's own members, its bases' (through `resolveStructuredTypeMembers`,
     * which copies a base's own symbol into the derived table, so an INHERITED
     * member appears once and an OVERRIDDEN one appears once, as the override), the
     * members of every constituent of an INTERSECTION, and — for a UNION — only the
     * members present on EVERY constituent, because only those may legally be
     * accessed through the union. That last rule is deliberately NOT the rule
     * [CapturedDefinition] uses for the same receiver: "where is `p` declared" is
     * asked about a name the user has already written and every declaration of it is
     * a real place to go, while "what may I write here" must not offer something
     * that will not compile.
     *
     * `undefined` / `null` / `void` constituents of a union are SKIPPED rather than
     * emptying the intersection — they contribute no members, and treating them as
     * a veto would make every `strictNullChecks` union and every optional chain
     * answer nothing.
     *
     * (API.7) ACCESSIBILITY IS FILTERED. A `private` member (including a `#name`
     * field, which is private by its spelling and carries no modifier) is recorded
     * only when the SPAN'S OWN enclosing class — a `parent` ascent out of the receiver
     * node, so a caret in a nested arrow inside a method reaches that method's class —
     * is the class declaring it; a `protected` one also when the enclosing class
     * derives from the declaring one, following `extends` through the same scope
     * lookup and import hop the definition legs use. Statics obey the same rule
     * unchanged.
     *
     * THE BIAS IS PROVE-TO-HIDE, and it is the reason this could be turned on at all.
     * Every unknown — a member whose declaring class cannot be found, a base named by
     * an expression not resolved here, a heritage chain past its depth cap — leaves
     * the member RECORDED. Round 917 refused this feature on the ground that a list
     * which has silently lost a real candidate is indistinguishable from a complete
     * one; hiding only what is decided is what keeps that true.
     *
     * NOT answered: the static side of a class reached through an instance (only the
     * instance member table is read), and index signatures and call/construct
     * signatures, which have no name to complete.
     */
    val memberSpans: List<TypeCaptureSpan> = emptyList(),
    /**
     * (API.4b) The spans to additionally ENUMERATE THE LEXICAL SCOPE CHAIN AT — a
     * free-name completion request names the node it is anchored at here, and gets
     * [CapturedScope] back.
     *
     * ## Why a third list, and why the node rather than the caret
     *
     * [memberSpans]' argument applies unchanged: this population must stay apart
     * from [spans], because `Project.fileSemantics` hands in every identifier in a
     * file and enumerating a whole scope chain at each of them would be quadratic
     * for answers nobody sweeps for.
     *
     * A caret is not a node, so the CALLER — which owns a token index — resolves it
     * to the innermost node enclosing the position and names THAT node's span. The
     * scope in force at a node is the scope the spine has active when it enters it,
     * which is exactly the quantity a completion needs and is exactly the quantity
     * that does not survive the walk: `spineCurrentScope` is nulled per file by the
     * spine's teardown, so there is no post-hoc option at all
     * (`ScopeCaptureMeasurementTest` measures that rather than asserting it).
     *
     * ## What the enumeration is
     *
     * THE SAME WALK `spineScopeLookup` PERFORMS, ENUMERATED. Every level from the
     * innermost outwards contributes its own scope-space bindings and then the
     * binder table it aliases, first sighting wins, and the ascent ends at the
     * source file — after which the merged GLOBALS are added, each filtered through
     * the per-file visibility rule (INV.3(c)) so a module-only name another file
     * declares is not offered here. That the enumeration and the lookup are one
     * traversal is the whole correctness argument: a name this offers is a name
     * `Project.definitionsAt` will resolve, and a name it hides is hidden because
     * something nearer binds the spelling.
     *
     * NOT answered, each for a reason stated in `Project.completionsAt`: KEYWORDS
     * (they are a grammar-position question this token-level anchor cannot ask), a
     * per-item TYPE (see [CapturedName]), and any filtering by the typed prefix
     * (the host filters — `CompletionList` carries the argument).
     */
    val scopeSpans: List<TypeCaptureSpan> = emptyList(),
    /**
     * (API.6) The CALL spans to resolve the callee's SIGNATURES of — a signature
     * help request names the call it is inside here, and gets [CapturedSignatures]
     * back.
     *
     * A fourth list for [memberSpans]' reason, and it is the only one whose elements
     * are not plain [TypeCaptureSpan]s: see [SignatureCaptureSpan] for why the active
     * ARGUMENT has to travel with the span rather than be recomputed here.
     *
     * ## What is answered
     *
     * The call signatures of the callee's type for a `CallExpression`, and the
     * CONSTRUCT signatures for a `NewExpression`. The callee is resolved by the
     * compiler's own `getCalleeType`, which is what makes a method through a
     * receiver (`o.m(`), a callee that is itself a call (`f()(`), a namespace member
     * and an imported function all answer without a rule of their own — it is
     * (API.3d)'s receiver path, one question wider.
     *
     * EVERY overload comes back, in declaration order, because showing one of three
     * is the failure signature help exists to avoid.
     *
     * ## What is NOT answered, each for a stated reason
     *
     * A TAGGED TEMPLATE (`` tag`x` ``) has no parenthesized argument list, so the
     * caller's anchor never names one — its argument positions are template
     * substitutions and counting them is a different mechanism. `super(...)` resolves
     * through no callee type here (`super` is an ordinary `Identifier` in this
     * parser and binds to nothing), so it answers an empty list rather than the
     * enclosing class's constructor. TYPE ARGUMENTS (`f<|>(x)`) are not an argument
     * list. A SPREAD argument makes the index ambiguous — the caller counts commas,
     * so `f(...xs, |)` reports argument 1 whatever `xs` contains.
     */
    val signatureSpans: List<SignatureCaptureSpan> = emptyList(),
) {

    /**
     * The spans indexed by file, as packed `(start, end)` keys — the form the
     * checker's per-node test needs.
     *
     * (API.4a) The UNION of [spans], [memberSpans], (API.4b) [scopeSpans] and
     * (API.6) [signatureSpans]: this set is what the hot-path guard tests, and a
     * member, scope or signature span has to pass it to be visited at all.
     */
    internal val keysByFile: Map<String, Set<Long>> =
        (
            spans + memberSpans + scopeSpans +
                signatureSpans.map { TypeCaptureSpan(it.fileName, it.start, it.end) }
            ).groupBy { it.fileName }
            .mapValues { (_, group) -> group.mapTo(HashSet()) { packSpanKey(it.start, it.end) } }

    /**
     * (API.4a) The [memberSpans] alone, in the same form — consulted only after a
     * span has already matched [keysByFile], i.e. never on the hot path.
     */
    internal val memberKeysByFile: Map<String, Set<Long>> =
        memberSpans.groupBy { it.fileName }
            .mapValues { (_, group) -> group.mapTo(HashSet()) { packSpanKey(it.start, it.end) } }

    /** (API.4b) The [scopeSpans] alone, in the same form and read at the same place. */
    internal val scopeKeysByFile: Map<String, Set<Long>> =
        scopeSpans.groupBy { it.fileName }
            .mapValues { (_, group) -> group.mapTo(HashSet()) { packSpanKey(it.start, it.end) } }

    /**
     * (API.6) The [signatureSpans] alone — a MAP rather than a set, because the value
     * is the active argument index the span travels with. Read at the same place its
     * siblings are, i.e. never on the hot path.
     */
    internal val signatureArgsByFile: Map<String, Map<Long, Int>> =
        signatureSpans.groupBy { it.fileName }
            .mapValues { (_, group) ->
                group.associate { packSpanKey(it.start, it.end) to it.activeArgument }
            }

    internal companion object {

        /**
         * `(start, end)` as one key, through [packIdPair]'s multiplicative
         * finalizer.
         *
         * (API.3c) THE FINALIZER IS LOAD-BEARING HERE AND WAS NOT BEFORE, AND THE
         * REASON IS ROUND 889's IN ITS PUREST FORM. `java.lang.Long.hashCode` is
         * `(int) (v xor (v ushr 32))`, so a plain `(start shl 32) or end` hashes to
         * exactly `start xor end` — and a node's `end` is its `pos` plus its own
         * length plus the following token (round 910), i.e. the two halves are not
         * merely correlated, they are NEIGHBOURS. `pos xor (pos + 12)` is a small
         * number for every position in the file, so a whole file's identifier spans
         * collapse onto a few dozen hashes: exactly the degenerate bucket
         * distribution that treeified `Relation.cache`.
         *
         * While a request held "the handful of spans a host asked about" that could
         * not bite and this packing was deliberately left raw, with a note saying to
         * finalize it should spans ever be requested in BULK. `Project.fileSemantics`
         * is that caller, so the note is now cashed in. `TypeCaptureKeySpreadTest`
         * measures the collapse rather than asserting it, and fails on the raw form.
         *
         * Soundness is [packIdPair]'s and holds by the same two clauses: nothing
         * unpacks the key (the [CapturedType]/[CapturedDefinition] answers carry the
         * real `start`/`end` from the node, never from the key), and nothing iterates
         * the sets — they are membership tests in the checker's per-node hook.
         *
         * Costs production NOTHING: the hook returns on a null per-file key set
         * BEFORE it packs anything, so no uncaptured compile ever performs the
         * multiply.
         */
        internal fun packSpanKey(start: Int, end: Int): Long = packIdPair(start, end)
    }
}
