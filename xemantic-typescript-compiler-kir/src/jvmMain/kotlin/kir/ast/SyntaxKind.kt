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

package com.xemantic.typescript.compiler.kir.ast

/** The token and node kinds of the lowering's syntax (`-core`'s `SyntaxKind`, (TSGO.7-f)). */
public enum class SyntaxKind {
    // Special
    Unknown,
    EndOfFile,

    // Trivia
    SingleLineComment,
    MultiLineComment,
    NewLine,
    Whitespace,

    // Literals
    NumericLiteral,
    BigIntLiteral,
    StringLiteral,
    RegularExpressionLiteral,
    NoSubstitutionTemplateLiteral,
    TemplateHead,
    TemplateMiddle,
    TemplateTail,

    // Punctuation
    OpenBrace,           // {
    CloseBrace,          // }
    OpenParen,           // (
    CloseParen,          // )
    OpenBracket,         // [
    CloseBracket,        // ]
    Dot,                 // .
    DotDotDot,           // ...
    Semicolon,           // ;
    Comma,               // ,
    QuestionDot,         // ?.
    LessThan,            // <
    GreaterThan,         // >
    LessThanEquals,      // <=
    GreaterThanEquals,   // >=
    EqualsEquals,        // ==
    ExclamationEquals,   // !=
    EqualsEqualsEquals,  // ===
    ExclamationEqualsEquals, // !==
    EqualsGreaterThan,   // =>
    Plus,                // +
    Minus,               // -
    Asterisk,            // *
    AsteriskAsterisk,    // **
    Slash,               // /
    Percent,             // %
    PlusPlus,            // ++
    MinusMinus,          // --
    LessThanLessThan,    // <<
    GreaterThanGreaterThan,       // >>
    GreaterThanGreaterThanGreaterThan, // >>>
    Ampersand,           // &
    Bar,                 // |
    Caret,               // ^
    Exclamation,         // !
    Tilde,               // ~
    AmpersandAmpersand,  // &&
    BarBar,              // ||
    QuestionQuestion,    // ??
    Question,            // ?
    Colon,               // :
    At,                  // @
    Hash,                // #
    Backtick,            // `

    // Assignment operators
    Equals,              // =
    PlusEquals,          // +=
    MinusEquals,         // -=
    AsteriskEquals,      // *=
    AsteriskAsteriskEquals, // **=
    SlashEquals,         // /=
    PercentEquals,       // %=
    LessThanLessThanEquals,          // <<=
    GreaterThanGreaterThanEquals,    // >>=
    GreaterThanGreaterThanGreaterThanEquals, // >>>=
    AmpersandEquals,     // &=
    BarEquals,           // |=
    CaretEquals,         // ^=
    BarBarEquals,        // ||=
    AmpersandAmpersandEquals, // &&=
    QuestionQuestionEquals,   // ??=

    // Identifiers and keywords
    Identifier,

    // Keywords
    AbstractKeyword,
    AccessorKeyword,
    AnyKeyword,
    AsKeyword,
    AssertsKeyword,
    AsyncKeyword,
    AwaitKeyword,
    BigIntKeyword,
    BooleanKeyword,
    BreakKeyword,
    CaseKeyword,
    CatchKeyword,
    ClassKeyword,
    ConstKeyword,
    ConstructorKeyword,
    ContinueKeyword,
    DeclareKeyword,
    DefaultKeyword,
    DeleteKeyword,
    DoKeyword,
    ElseKeyword,
    EnumKeyword,
    ExportKeyword,
    ExtendsKeyword,
    FalseKeyword,
    FinallyKeyword,
    ForKeyword,
    FromKeyword,
    FunctionKeyword,
    GetKeyword,
    GlobalKeyword,
    IfKeyword,
    ImplementsKeyword,
    ImportKeyword,
    InKeyword,
    InferKeyword,
    InstanceOfKeyword,
    InterfaceKeyword,
    IsKeyword,
    KeyOfKeyword,
    LetKeyword,
    ModuleKeyword,
    NamespaceKeyword,
    NeverKeyword,
    NewKeyword,
    NullKeyword,
    NumberKeyword,
    ObjectKeyword,
    OfKeyword,
    OutKeyword,
    OverrideKeyword,
    PackageKeyword,
    PrivateKeyword,
    ProtectedKeyword,
    PublicKeyword,
    ReadonlyKeyword,
    RequireKeyword,
    ReturnKeyword,
    SatisfiesKeyword,
    SetKeyword,
    StaticKeyword,
    StringKeyword,
    SuperKeyword,
    SwitchKeyword,
    SymbolKeyword,
    ThisKeyword,
    ThrowKeyword,
    TrueKeyword,
    TryKeyword,
    TypeKeyword,
    TypeOfKeyword,
    UndefinedKeyword,
    UniqueKeyword,
    UnknownKeyword,
    UsingKeyword,
    
    AwaitUsingKeyword,
    VarKeyword,
    VoidKeyword,
    WhileKeyword,
    WithKeyword,
    YieldKeyword,
    DebuggerKeyword,

    // Node types (AST)
    SourceFile,
    Block,
    EmptyStatement,
    VariableStatement,
    ExpressionStatement,
    IfStatement,
    DoStatement,
    WhileStatement,
    ForStatement,
    ForInStatement,
    ForOfStatement,
    ContinueStatement,
    BreakStatement,
    ReturnStatement,
    WithStatement,
    SwitchStatement,
    LabeledStatement,
    ThrowStatement,
    TryStatement,
    DebuggerStatement,

    VariableDeclaration,
    VariableDeclarationList,
    FunctionDeclaration,
    ClassDeclaration,
    InterfaceDeclaration,
    TypeAliasDeclaration,
    EnumDeclaration,
    ModuleDeclaration,
    ModuleBlock,
    ImportDeclaration,
    ImportEqualsDeclaration,
    ExportDeclaration,
    ExportAssignment,

    CaseClause,
    DefaultClause,
    CatchClause,

    // Class elements
    PropertyDeclaration,
    MethodDeclaration,
    ConstructorDeclaration,
    GetAccessor,
    SetAccessor,
    IndexSignature,
    ClassStaticBlockDeclaration,
    SemicolonClassElement,

    // Expressions
    PrefixUnaryExpression,
    PostfixUnaryExpression,
    BinaryExpression,
    ConditionalExpression,
    CallExpression,
    NewExpression,
    PropertyAccessExpression,
    ElementAccessExpression,
    TaggedTemplateExpression,
    TypeAssertionExpression,
    ParenthesizedExpression,
    DeleteExpression,
    TypeOfExpression,
    VoidExpression,
    AwaitExpression,
    YieldExpression,
    ArrowFunction,
    FunctionExpression,
    ClassExpression,
    SpreadElement,
    AsExpression,
    NonNullExpression,
    SatisfiesExpression,
    CommaListExpression,
    OmittedExpression,
    TemplateExpression,
    TemplateSpan,

    // Object/Array
    ObjectLiteralExpression,
    ArrayLiteralExpression,
    PropertyAssignment,
    ShorthandPropertyAssignment,
    SpreadAssignment,
    ComputedPropertyName,

    // Binding patterns
    ObjectBindingPattern,
    ArrayBindingPattern,
    BindingElement,

    // Type nodes
    TypeReference,
    FunctionType,
    ConstructorType,
    TypeQuery,
    TypeLiteral,
    ArrayType,
    TupleType,
    UnionType,
    IntersectionType,
    ConditionalType,
    IndexedAccessType,
    MappedType,
    LiteralType,
    TemplateLiteralType,
    TemplateLiteralTypeSpan,
    ParenthesizedType,
    TypePredicate,
    TypeOperator,
    RestType,
    NamedTupleMember,
    OptionalType,
    ImportType,
    ThisType,
    InferType,

    // Other
    Parameter,
    Decorator,
    HeritageClause,
    ExpressionWithTypeArguments,
    EnumMember,
    TypeParameter,
    QualifiedName,
    ExternalModuleReference,
    NamespaceImport,
    NamedImports,
    ImportSpecifier,
    NamespaceExport,
    NamedExports,
    ExportSpecifier,
    ImportClause,
    MetaProperty,
    MissingDeclaration,

    // JSX
    JsxElement,
    JsxSelfClosingElement,
    JsxOpeningElement,
    JsxClosingElement,
    JsxFragment,
    JsxOpeningFragment,
    JsxClosingFragment,
    JsxAttribute,
    JsxAttributes,
    JsxSpreadAttribute,
    JsxExpression,
    JsxText,

    // JSDoc (minimal)
    JSDocComment,
    JSDocTag,

    // Synthetic
    SyntheticExpression,
    NotEmittedStatement,
    ;
}
