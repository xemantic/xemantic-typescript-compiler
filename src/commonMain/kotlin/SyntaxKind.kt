/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

enum class SyntaxKind {
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

    // JSDoc (minimal)
    JSDocComment,
    JSDocTag,

    // Synthetic
    SyntheticExpression,
    NotEmittedStatement,
    ;
}

val STRICT_MODE_RESERVED_WORDS = setOf(
    "implements", "interface", "let", "package",
    "private", "protected", "public", "static", "yield"
)

val KEYWORDS: Map<String, SyntaxKind> = buildMap {
    put("abstract", SyntaxKind.AbstractKeyword)
    put("accessor", SyntaxKind.AccessorKeyword)
    put("any", SyntaxKind.AnyKeyword)
    put("as", SyntaxKind.AsKeyword)
    put("asserts", SyntaxKind.AssertsKeyword)
    put("async", SyntaxKind.AsyncKeyword)
    put("await", SyntaxKind.AwaitKeyword)
    put("bigint", SyntaxKind.BigIntKeyword)
    put("boolean", SyntaxKind.BooleanKeyword)
    put("break", SyntaxKind.BreakKeyword)
    put("case", SyntaxKind.CaseKeyword)
    put("catch", SyntaxKind.CatchKeyword)
    put("class", SyntaxKind.ClassKeyword)
    put("const", SyntaxKind.ConstKeyword)
    put("constructor", SyntaxKind.ConstructorKeyword)
    put("continue", SyntaxKind.ContinueKeyword)
    put("debugger", SyntaxKind.DebuggerKeyword)
    put("declare", SyntaxKind.DeclareKeyword)
    put("default", SyntaxKind.DefaultKeyword)
    put("delete", SyntaxKind.DeleteKeyword)
    put("do", SyntaxKind.DoKeyword)
    put("else", SyntaxKind.ElseKeyword)
    put("enum", SyntaxKind.EnumKeyword)
    put("export", SyntaxKind.ExportKeyword)
    put("extends", SyntaxKind.ExtendsKeyword)
    put("false", SyntaxKind.FalseKeyword)
    put("finally", SyntaxKind.FinallyKeyword)
    put("for", SyntaxKind.ForKeyword)
    put("from", SyntaxKind.FromKeyword)
    put("function", SyntaxKind.FunctionKeyword)
    put("get", SyntaxKind.GetKeyword)
    put("global", SyntaxKind.GlobalKeyword)
    put("if", SyntaxKind.IfKeyword)
    put("implements", SyntaxKind.ImplementsKeyword)
    put("import", SyntaxKind.ImportKeyword)
    put("in", SyntaxKind.InKeyword)
    put("infer", SyntaxKind.InferKeyword)
    put("instanceof", SyntaxKind.InstanceOfKeyword)
    put("interface", SyntaxKind.InterfaceKeyword)
    put("is", SyntaxKind.IsKeyword)
    put("keyof", SyntaxKind.KeyOfKeyword)
    put("let", SyntaxKind.LetKeyword)
    put("module", SyntaxKind.ModuleKeyword)
    put("namespace", SyntaxKind.NamespaceKeyword)
    put("never", SyntaxKind.NeverKeyword)
    put("new", SyntaxKind.NewKeyword)
    put("null", SyntaxKind.NullKeyword)
    put("number", SyntaxKind.NumberKeyword)
    put("object", SyntaxKind.ObjectKeyword)
    put("of", SyntaxKind.OfKeyword)
    put("out", SyntaxKind.OutKeyword)
    put("override", SyntaxKind.OverrideKeyword)
    put("package", SyntaxKind.PackageKeyword)
    put("private", SyntaxKind.PrivateKeyword)
    put("protected", SyntaxKind.ProtectedKeyword)
    put("public", SyntaxKind.PublicKeyword)
    put("readonly", SyntaxKind.ReadonlyKeyword)
    put("require", SyntaxKind.RequireKeyword)
    put("return", SyntaxKind.ReturnKeyword)
    put("satisfies", SyntaxKind.SatisfiesKeyword)
    put("set", SyntaxKind.SetKeyword)
    put("static", SyntaxKind.StaticKeyword)
    put("string", SyntaxKind.StringKeyword)
    put("super", SyntaxKind.SuperKeyword)
    put("switch", SyntaxKind.SwitchKeyword)
    put("symbol", SyntaxKind.SymbolKeyword)
    put("this", SyntaxKind.ThisKeyword)
    put("throw", SyntaxKind.ThrowKeyword)
    put("true", SyntaxKind.TrueKeyword)
    put("try", SyntaxKind.TryKeyword)
    put("type", SyntaxKind.TypeKeyword)
    put("typeof", SyntaxKind.TypeOfKeyword)
    put("undefined", SyntaxKind.UndefinedKeyword)
    put("unique", SyntaxKind.UniqueKeyword)
    put("unknown", SyntaxKind.UnknownKeyword)
    put("using", SyntaxKind.UsingKeyword)
    put("var", SyntaxKind.VarKeyword)
    put("void", SyntaxKind.VoidKeyword)
    put("while", SyntaxKind.WhileKeyword)
    put("with", SyntaxKind.WithKeyword)
    put("yield", SyntaxKind.YieldKeyword)
}

fun isAssignmentOperator(kind: SyntaxKind): Boolean = kind in ASSIGNMENT_OPERATORS

val ASSIGNMENT_OPERATORS = setOf(
    SyntaxKind.Equals,
    SyntaxKind.PlusEquals,
    SyntaxKind.MinusEquals,
    SyntaxKind.AsteriskEquals,
    SyntaxKind.AsteriskAsteriskEquals,
    SyntaxKind.SlashEquals,
    SyntaxKind.PercentEquals,
    SyntaxKind.LessThanLessThanEquals,
    SyntaxKind.GreaterThanGreaterThanEquals,
    SyntaxKind.GreaterThanGreaterThanGreaterThanEquals,
    SyntaxKind.AmpersandEquals,
    SyntaxKind.BarEquals,
    SyntaxKind.CaretEquals,
    SyntaxKind.BarBarEquals,
    SyntaxKind.AmpersandAmpersandEquals,
    SyntaxKind.QuestionQuestionEquals,
)
