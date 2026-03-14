# Failure Categorization — 323 failing tests

**Generated:** 2026-03-14
**Baseline:** 5,442 tests, 5,119 passing, 323 failing (94.1%)

## Summary by Category

| Cat | Description | Count | Phase 2 relevant? |
|-----|-------------|-------|--------------------|
| A | Parser error recovery | 62 | No (independent, item 6) |
| B | Type-checker-driven transforms | 52 | **Yes** (core Phase 2 work) |
| C | Missing helpers | 16 | Partially |
| D | Module resolution / multi-file | 67 | No (infrastructure) |
| E | Complex transforms | 53 | Partially |
| F | Declaration emit (.d.ts) | 14 | No |
| G | Internal comments | 12 | No (cosmetic) |
| H | Other | 8 | No |
| **Total** | | **284** (some tests have dual causes) | |

Note: a few tests straddle categories; assigned to dominant root cause.

---

## (A) Parser error recovery — 62 tests

Tests expecting TypeScript's exact error recovery output for malformed syntax.

| Test | Detail |
|------|--------|
| ambiguousGenericAssertion1 | `<<T>(x)` parsed differently from TypeScript's error recovery |
| arrowFunctionsMissingTokens | missing arrow `=>` tokens not recovered same as TypeScript |
| assertInWrapSomeTypeParameter | `asserts` type predicate method name emitted as `>>` instead of `foo` |
| bigintArbirtraryIdentifier | bad import/export with bigint string identifiers differs |
| class2 | `static f = 3` misinterpreted in error context |
| ClassDeclaration26 | missing error recovery output `var constructor; () => {};` |
| classMemberWithMissingIdentifier2 | `[name, string]` parsed differently |
| classUpdateTests | constructor parameter properties without visibility modifiers parsed differently |
| constructorWithIncompleteTypeAnnotation | incomplete type annotation in constructor parameter parsed differently |
| declarationEmitOptionalMethod | optional method type annotation parsed differently, garbled output |
| declarationEmitResolveTypesIfNotReusable | `(o) => ['a']` misparse of arrow with type constraint |
| declarationEmitTypeofRest | rest parameter with `typeof` type annotation parsed incorrectly |
| destructuringControlFlowNoCrash | malformed destructuring with comma expression |
| dontShowCompilerGeneratedMembers | extra semicolons for malformed declare syntax |
| errorRecoveryInClassDeclaration | malformed class member produces different AST |
| errorRecoveryWithDotFollowedByNamespaceKeyword | dot followed by namespace keyword parsed differently |
| es6ClassTest9 | malformed class syntax |
| es6ImportNamedImportParsingError | malformed import statements |
| es6ImportParseErrors | `import 10` produces different output |
| expressionTypeNodeShouldError | expression in type position produces different emit |
| expressionWithJSDocTypeArguments | JSDoc type arguments in expressions parsed differently |
| externModule | `declare module` without quotes emits different output |
| fatarrowfunctionsErrors | malformed fat arrow function syntax |
| fatarrowfunctionsOptionalArgs | ternary with arrow functions parsed differently |
| fatarrowfunctionsOptionalArgsErrors2 | malformed arrow function with typed params |
| giant | getter/setter parsed as method + error recovery divergence |
| importTypeWithUnparenthesizedGenericFunctionParsed | import type with generic function type leaks expression |
| interfaceDeclaration4 | interface with errors emits extra statements |
| invalidLetInForOfAndForIn_ES5 | `let` in for-of/for-in with malformed syntax |
| invalidLetInForOfAndForIn_ES6 | `let` in for-of/for-in with malformed syntax |
| invalidUnicodeEscapeSequance | invalid unicode escape; expected `var arg, u003` |
| invalidUnicodeEscapeSequance2 | invalid unicode escape; expected single var decl |
| invalidUnicodeEscapeSequance4 | `\u0031a` not decoded to `u0031a` |
| jsFileCompilationTypeArgumentSyntaxOfCall | JS file type argument syntax parsed differently |
| manyCompilerErrorsInTheTwoFiles | malformed `const` declarations differ from expected |
| moduleElementsInWrongContext | `import I2 = require("foo")` error recovery emitted as `var I = M` |
| moduleElementsInWrongContext2 | `import I2 = require("foo")` error recovery emitted as `var I = M` |
| moduleElementsInWrongContext3 | `import I2 = require("foo")` error recovery emitted as `var I = M` |
| nestedGlobalNamespaceInClass | `declare global` namespace in class not emitted |
| overloadConsecutiveness | overload declarations not consecutive — parser misparses into nested functions |
| overloadingStaticFunctionsInFunctions | static keyword in function context, drops function wrapper |
| parseAssertEntriesError | malformed import attributes with invalid numeric keys |
| parseBigInt | invalid BigInt literal (legacy octal) — splits const into two statements |
| parseErrorIncorrectReturnToken | incorrect return type token — emits extra type name |
| parseGenericArrowRatherThanLeftShift | generic arrow as left shift — emits type params as expressions |
| parseImportAttributesError | malformed import attributes with numeric keys |
| parseInvalidNames | invalid namespace/interface/type identifiers |
| parseInvalidNullableTypes | invalid C#-style nullable types (Type?) |
| parseJsxElementInUnaryExpressionNoCrash1 | JSX unary expression — different AST for malformed JSX |
| parseJsxElementInUnaryExpressionNoCrash2 | JSX unary expression — indentation difference |
| parseJsxElementInUnaryExpressionNoCrash3 | JSX unary expression — malformed spread JSX |
| parserPrivateIdentifierInArrayAssignment | extra indentation on semicolon after array assignment |
| parserUnparsedTokenCrash2 | drops recovered expression statements from malformed code |
| parseUnaryExpressionNoTypeAssertionInJsx4 | JSX type assertion context — different self-closing element parse |
| reachabilityChecksNoCrash1 | async generator function with return type annotation |
| reservedWords2 | reserved words used as identifiers — very different recovered AST |
| reservedWords3 | reserved words as parameters — doesn't extract from param context |
| restParamModifier | rest parameter with modifier — drops constructor body |
| staticClassProps | `static z = 1` inside method body as expression instead of error-recovered |
| staticsInAFunction | `static` keyword inside function as identifier expression |
| staticsInConstructorBodies | `static` keyword inside constructor as identifier expression |
| strictModeReservedWord | strict mode reserved words used as variable names |
| TransportStream | extra semicolons from overload declarations not properly merged |
| unclosedExportClause01 | unclosed export clause — export re-export not emitted |
| unclosedExportClause02 | unclosed export clause — export re-export not emitted |
| unusedLocalsAndParameters | destructuring in for-loop initializer — misparses `{ z }` |
| validRegexp | regex with flags — splits `/ [a-z/]$ /, i` into two statements |

---

## (B) Type-checker-driven transforms — 52 tests

Require type checker / binder to determine which imports are type-only, resolve const enum values, or emit decorator metadata.

### Const enum inlining (9 tests)
| Test | Detail |
|------|--------|
| amdModuleConstEnumUsage | `CharCode.A` → `0 /* CharCode.A */`, import elided |
| constEnumExternalModule | `A.V` → `100 /* A.V */`, import elided |
| constEnumNamespaceReferenceCausesNoImport2 | `Foo.ConstFooEnum.Some` → `0`, import elided |
| constEnumNoEmitReexport | re-exports of const enums should produce empty module bodies |
| constEnumNoPreserveDeclarationReexport | re-exports of const enums not properly handled |
| constEnums | enum member values not computed and replaced with literals |
| declarationEmitStringEnumUsedInNonlocalSpread | `TestEnum.Test1` → `"123123"` |
| importAliasFromNamespace | const enum alias `WhichThing.A` → `0` |
| importElisionEnum | const enum inlining for merged enum across files |

### Import elision (24 tests)
| Test | Detail |
|------|--------|
| aliasInaccessibleModule2 | import alias `var R = N` elided — type-checker needed |
| aliasOnMergedModuleInterface | `require("foo")` not elided — type-checker needed |
| computedPropertyNameWithImportedKey | `require("./a")` elided when should be kept for computed property value use |
| declarationEmitAnyComputedPropertyInClass | default import not retained for computed property key use |
| declarationEmitComputedNameCausesImportToBePainted | `require("./context")` elided when needed for computed property key |
| declarationEmitComputedNameConstEnumAlias | default import not retained, `__importDefault` helper missing |
| declarationEmitExportAliasVisibiilityMarking | type-only re-exports `Suit`, `Rank` should be elided |
| declarationEmitNameConflictsWithAlias | `export var v` should be elided (type-only alias) |
| duplicateVarsAcrossFileBoundaries | import elision drops `var p = P` (P is type-only across files) |
| es6ExportClauseWithoutModuleSpecifier | export elision of type-only re-exports (interface, uninstantiated module) |
| es6ExportEqualsInterop | import elision for type-only import-equals + helper ordering |
| es6ImportDefaultBindingFollowedWithNamespaceBinding1 | import elision drops unused namespace binding |
| exportAssignmentImportMergeNoCrash | import elision drops unused default import |
| exportAssignmentOfDeclaredExternalModule | import elision should drop import of declared module |
| exportDefaultAsyncFunction2 | import elision drops `async` identifier import |
| exportDefaultImportedType | import elision should drop type-only import and default export |
| exportImportNonInstantiatedModule2 | export-import of non-instantiated module should be elided |
| exportSpecifierForAGlobal | export of type-only global (exports.X = void 0 missing) |
| importedAliasesInTypePositions | import elision for type-only import alias |
| importedEnumMemberMergedWithExportedAliasIsError | import elision should drop enum member alias import |
| importInsideModule | import-equals inside module elided when type-only |
| internalAliasInterfaceInsideTopLevelModuleWithExport | import alias of interface should be elided |
| internalAliasUninitializedModuleInsideTopLevelModuleWithExport | import alias of uninitialized module should be elided |
| unusedImports_entireImportDeclaration | __importDefault missing, type-only import not properly retained |

### Decorator metadata (3 tests)
| Test | Detail |
|------|--------|
| decoratorMetadataNoLibIsolatedModulesTypes | `__metadata("design:type", ...)` not emitted with typeof guard |
| decoratorMetadataTypeOnlyExport | `__metadata("design:paramtypes", ...)` not emitted |
| metadataOfUnion | decorator metadata for union types needs type resolution |

### Enum value resolution (4 tests)
| Test | Detail |
|------|--------|
| enumNoInitializerFollowsNonLiteralInitializer | enum member values need cross-file const resolution |
| enumWithNonLiteralStringInitializer | enum with non-literal string initializer needs value resolution |
| isolatedDeclarationErrorsEnums | enum member values need type checker |
| isolatedModulesGlobalNamespacesAndEnums | enum member values need type checker |

### Type-only import/export elision (misc) (8 tests)
| Test | Detail |
|------|--------|
| experimentalDecoratorMetadataUnresolvedTypeObjectInEmit | decorator metadata needs type resolution |
| instantiateTypeParameter | `var x: T` should emit `var x` |
| isolatedModulesExportDeclarationType | type-only export elision |
| isolatedModulesExportImportUninstantiatedNamespace | import elision for type-only namespace |
| isolatedModulesReExportType | type-only re-export elision |
| privacyGloImportParseErrors | type-only import=require aliases not elided |
| privacyImportParseErrors | type-only import=require aliases not elided |
| privacyLocalInternalReferenceImportWithExport | type-only import aliases not elided |

### Other type-checker-dependent (4 tests)
| Test | Detail |
|------|--------|
| isolatedModulesNoEmitOnError | noEmitOnError requires type checker |
| multiImportExport | `exports.Math = require(...)` import=require elided as type-only |
| namespaceMergedWithImportAliasNoCrash | import alias elided as type-only; extra helpers |
| noEmitOnError | noEmitOnError requires type checker |
| privacyCheckAnonymousFunctionParameter2 | import=require should retain exports.x reference |
| privacyTopLevelInternalReferenceImportWithExport | type-only import aliases not elided |
| systemModule17 | type-only exports not elided, var ordering differs |
| unusedImports13 | JSX factory import (React) elided as unused |
| unusedImports15 | JSX factory import (Element) elided as unused |
| typeUsedAsValueError2 | type-only imports not elided |

---

## (C) Missing helpers — 16 tests

Missing `__esDecorate`, `__setFunctionName`, `__classPrivateFieldGet/Set`, `__rest`, WeakMap transforms, tslib re-exports.

| Test | Detail |
|------|--------|
| classNameReferencesInStaticElements | missing `__classPrivateFieldGet` helper and WeakMap |
| constructorWithParameterPropertiesAndPrivateFields_es2015 | missing `__classPrivateFieldSet`/`__classPrivateFieldGet` |
| dynamicImportWithNestedThis_es2015 | missing `__importStar` helper for dynamic import() |
| emitClassExpressionInDeclarationFile2 | missing `__setFunctionName` helper |
| esDecoratorsClassFieldsCrash | missing `__esDecorate`/`__runInitializers` |
| esModuleInteropImportCall | missing `__importStar` helper for dynamic import() |
| expressionsForbiddenInParameterInitializers | missing `__importStar` helper for namespace import |
| extendedUnicodePlaneIdentifiers | missing WeakMap-based private field transform |
| importHelpersNoHelpersForPrivateFields | missing WeakMap transform + tslib helpers |
| modulePreserveImportHelpers | missing `__esDecorate`/`__runInitializers` |
| nestedObjectRest | missing `__rest` helper |
| parameterDecoratorsEmitCrash | missing `__esDecorate`/`__runInitializers` |
| privateFieldsInClassExpressionDeclaration | missing `__setFunctionName` and WeakMap |
| privateNameWeakMapCollision | missing WeakMap-based private field downleveling |
| staticFieldWithInterfaceContext | missing `__setFunctionName` for class expressions |
| tslibReExportHelpers | missing tslib import re-export |
| tslibReExportHelpers2 | missing tslib import for `__classPrivateFieldGet` |

---

## (D) Module resolution / multi-file — 67 tests

Path mapping, symlinks, file ordering, multi-file baseline construction, reference resolution.

### Path mapping (15 tests)
| Test | Detail |
|------|--------|
| pathMappingBasedModuleResolution3_classic | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution3_node | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution4_classic | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution4_node | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution5_classic | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution5_node | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution6_classic | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution6_node | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution7_classic | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution7_node | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution8_classic | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution8_node | baseUrl/path mapping resolution |
| pathMappingBasedModuleResolution_rootImport_aliasWithRoot_realRootFile | baseUrl/path mapping |
| pathMappingBasedModuleResolution_rootImport_noAliasWithRoot_realRootFile | baseUrl/path mapping |
| pathMappingBasedModuleResolution_withExtension_MapedToNodeModules | path mapping |

### Symlinks (5 tests)
| Test | Detail |
|------|--------|
| moduleResolutionWithSymlinks | symlink resolution wrong |
| moduleResolutionWithSymlinks_notInNodeModules | symlink resolution wrong |
| moduleResolutionWithSymlinks_withOutDir | symlink resolution wrong |
| symbolLinkDeclarationEmitModuleNames | symlink module name resolution |
| symlinkedWorkspaceDependenciesNoDirectLinkGeneratesDeepNonrelativeName | symlinked workspace deps |

### Multi-file ordering / inclusion (27 tests)
| Test | Detail |
|------|--------|
| commonSourceDir6 | AMD module name resolution wrong |
| commonSourceDirectory | common source directory calculation wrong |
| commonSourceDirectory_dts | common source directory calculation wrong |
| compositeWithNodeModulesSourceFile | node_modules source file incorrectly emitted |
| declarationEmitMonorepoBaseUrl | files emitted in wrong order |
| declarationEmitNestedBindingPattern | wrong module format (ESM should be CJS) |
| declarationEmitReusesLambdaParameterNodes | wrong module format (ESM should be CJS) |
| declarationEmitTransitiveImportOfHtmlDeclarationItem | `.d.html.ts` file incorrectly emitted |
| declarationEmitUsingTypeAlias1 | wrong module format (ESM should be CJS) |
| declarationEmitUsingTypeAlias2 | wrong module format (ESM should be CJS) |
| duplicatePackage_globalMerge | .d.ts files incorrectly included |
| emitMemberAccessExpression | multi-file output ordering differs |
| externalModuleAssignToVar | multi-file output ordering differs |
| externalModuleRefernceResolutionOrderInImportDeclaration | multi-file output ordering differs |
| externalModuleResolution2 | .d.ts file incorrectly included |
| fileReferencesWithNoExtensions | .d.ts file inclusion and file ordering |
| jsFileCompilationErrorOnDeclarationsWithJsFileReferenceWithOutDir | multi-file output ordering |
| moduleResolutionWithRequire | extra file emitted |
| moduleResolutionWithSuffixes_one_dirModuleWithIndex | extra index.js emitted |
| moduleResolutionWithSuffixes_one_externalModule | .d.ts sections emitted |
| moduleResolutionWithSuffixes_one_externalTSModule | extra files emitted |
| moduleResolutionWithSuffixes_one_jsonModule | file ordering wrong |
| nodeNextPackageSelfNameWithOutDirDeclDirCompositeNestedDirs | file ordering wrong |
| nodeResolution6 | extra file emitted (ref.js) |
| nodeResolution8 | extra file emitted (ref.js) |
| privacyFunctionCannotNameParameterTypeDeclFile | module resolution across declaration files |
| privacyFunctionCannotNameReturnTypeDeclFile | module resolution across declaration files |

### Triple-slash references (6 tests)
| Test | Detail |
|------|--------|
| moduleAugmentationDuringSyntheticDefaultCheck | triple-slash reference directive dropped |
| moduleAugmentationInAmbientModule1 | triple-slash reference directive dropped |
| moduleAugmentationInAmbientModule2 | triple-slash reference directive dropped |
| moduleAugmentationInAmbientModule3 | triple-slash reference directive dropped |
| moduleAugmentationInAmbientModule4 | triple-slash reference directives dropped |
| typeReferenceDirectives3 | triple-slash type reference directives |
| typeReferenceDirectives4 | triple-slash type reference directives |
| typeReferenceDirectives7 | triple-slash type reference directives |
| typeReferenceDirectives9 | triple-slash type reference directives |

### AMD / SystemJS module names (5 tests)
| Test | Detail |
|------|--------|
| amdDeclarationEmitNoExtraDeclare | AMD module name resolution |
| declarationEmitDefaultExportWithTempVarNameWithBundling | SystemJS module name |
| declarationMapsOutFile | AMD module name resolution + export ordering |
| keepImportsInDts3 | AMD module name resolution |
| keepImportsInDts4 | AMD module name and import specifier resolution |
| moduleAugmentationsImports1 | triple-slash + AMD module name |
| moduleAugmentationsImports2 | triple-slash + AMD module names |
| moduleAugmentationsImports3 | AMD module name |
| noBundledEmitFromNodeModules | SystemJS bundled emit |

### Other resolution (9 tests)
| Test | Detail |
|------|--------|
| isolatedDeclarationOutFile | AMD outFile module name resolution |
| privacyTopLevelAmbientExternalModuleImportWithExport | multi-file ordering |
| privacyTopLevelAmbientExternalModuleImportWithoutExport | multi-file ordering |
| reExportGlobalDeclaration1 | global re-exports |
| reExportGlobalDeclaration3 | global re-exports |
| reExportGlobalDeclaration4 | global re-exports |
| requireOfJsonFileNonRelativeWithoutExtensionResolvesToTs | JSON file resolution |
| requireOfJsonFileTypes | JSON file resolution |
| requireOfJsonFileWithModuleNodeResolutionEmitAmdOutFile | JSON file + AMD outFile |
| requireOfJsonFileWithoutExtensionResolvesToTs | JSON file resolution |
| requireOfJsonFileWithoutResolveJsonModule | JSON file resolution |
| tslibMissingHelper | tslib resolution — file ordering |
| tslibMultipleMissingHelper | tslib resolution — file ordering |
| tslibNotFoundDifferentModules | tslib resolution — file ordering |
| uniqueSymbolPropertyDeclarationEmit | unique symbol declaration emit |
| variableDeclarationDeclarationEmitUniqueSymbolPartialStatement | unique symbol variable declaration |
| verbatim-declarations-parameters | verbatim declarations |
| visibilityOfCrossModuleTypeUsage | cross-module type visibility |

---

## (E) Complex transforms — 53 tests

CommonJS export patterns, computed property temp vars, namespace destructuring, tslib import helpers, etc.

### CommonJS export self-reference / Object.defineProperty (14 tests)
| Test | Detail |
|------|--------|
| conflictingDeclarationsImportFromNamespace1 | `(0, exports.pick)()` not emitted |
| conflictingDeclarationsImportFromNamespace2 | `(0, exports.pick)()` not emitted |
| declarationEmitExpandoWithGenericConstraint | `(0, exports.Point)` not emitted |
| declarationsWithRecursiveInternalTypesProduceUniqueTypeParams | `(0, exports.testRecFun)` not emitted |
| declarationEmitDefaultExportWithStaticAssignment | Object.defineProperty getter vs simple assignment |
| declarationMapsMultifile | Object.defineProperty getter vs simple assignment |
| globalThisDeclarationEmit2 | Object.defineProperty getter vs simple assignment |
| globalThisDeclarationEmit3 | Object.defineProperty getter vs simple assignment |
| declFileForExportedImport | `exports.a = require(...)` emitted as `const a = require(...)` |
| internalAliasEnumInsideTopLevelModuleWithExport | should use `exports.b.Sunday` |
| internalAliasFunctionInsideTopLevelModuleWithExport | should use `(0, exports.b)()` |
| internalAliasVarInsideTopLevelModuleWithExport | should use `exports.b` |
| declarationEmitComputedNameWithQuestionToken | `exports.` prefix on computed key |
| declarationEmitReadonlyComputedProperty | `exports.` prefix on computed key |

### Computed property temp var hoisting (5 tests)
| Test | Detail |
|------|--------|
| classDeclarationShouldBeOutOfScopeInComputedNames | `_a`, `_b` not hoisted for class static computed names |
| declarationEmitMultipleComputedNamesSameDomain | `_a`, `_b` not implemented |
| declarationEmitPrivateNameCausesError | Symbol() key temp var |
| declarationEmitPrivateSymbolCausesVarDeclarationEmit2 | imported Symbol key temp var |
| decoratorsOnComputedProperties | decorated class computed property temp vars |

### Namespace destructuring (4 tests)
| Test | Detail |
|------|--------|
| declarationEmitDestructuringArrayPattern3 | `export var [a, b]` inside namespace not lowered |
| declarationEmitDestructuringObjectLiteralPattern | `export var {a4, b4, c4}` not lowered |
| declarationEmitDestructuringObjectLiteralPattern2 | `export var {a4, b4, c4}` not lowered |
| declarationEmitDestructuringPrivacyError | `export var [x, y, z]` not lowered |

### Module format mismatch (7 tests)
| Test | Detail |
|------|--------|
| dynamicImportsDeclaration | CJS transform not applied, emitting ESM |
| enumMemberNameNonIdentifier | emitting CJS but expected ESM |
| es6ImportDefaultBindingFollowedWithNamedImport | CJS transform incorrectly adding `__importStar` |
| es6ImportDefaultBindingFollowedWithNamedImportDts | CJS transform incorrectly adding `__importStar` |
| isolatedDeclarationErrorTypes1 | expected CJS but got ESM |
| modulePreserve1 | `module: preserve` CJS emit not implemented |
| modulePreserve4 | `module: preserve` CJS/MJS emit not implemented |

### tslib helpers (5 tests)
| Test | Detail |
|------|--------|
| esModuleInteropImportTSLibHasImport | should use `tslib_1.__exportStar` |
| esModuleInteropTslibHelpers | should use `tslib_1.__importDefault` |
| importHelpersBundler | should use tslib `__rest` import |
| importHelpersES6 | should use tslib imports |
| importHelpersVerbatimModuleSyntax | should use tslib import |

### Other complex transforms (18 tests)
| Test | Detail |
|------|--------|
| arrowFunctionErrorSpan | comment placement inside function call arguments |
| bangInModuleName | triple-slash reference directive incorrectly stripped |
| bindingPatternOmittedExpressionNesting | ES5 destructuring: nested omitted array binding |
| declarationEmitArrowFunctionNoRenaming | comment between arrow params and body dropped |
| declarationEmitSimpleComputedNames1 | `exports.` prefix on computed key |
| defaultDeclarationEmitDefaultImport | `__importStar` emitted instead of `__importDefault` |
| destructuredDeclarationEmit | CJS re-export uses Object.defineProperty |
| destructuringAssignmentWithExportedName | CJS destructuring assignment needs temp vars |
| dynamicNames | CJS computed property should use `exports.c4` |
| expandoFunctionExpressionsWithDynamicNames | CJS computed property should use `exports.expr` |
| javascriptThisAssignmentInStaticBlock | static block `this`/`super` transform |
| jsFileCompilationAwaitModifier | `__awaiter` `this` arg should be `this` not `void 0` |
| mappedTypeGenericIndexedAccess | optional chaining temp var hoisted incorrectly |
| moduleExportsUnaryExpression | CJS post-increment/decrement export update |
| objectRestSpread | missing `__asyncGenerator` helper and complex destructuring |
| propertyWrappedInTry | class property initializer wrapped in try/catch |
| reactReduxLikeDeferredInferenceAllowsAssignment | async arrow destructured params |
| reexportMissingDefault5 | system module re-export merges incorrectly |
| staticInitializersAndLegacyClassDecorators | class static block self-reference temp var |
| superAccess2 | missing super access Reflect.get transform |
| syntheticDefaultExportsWithDynamicImports | system module transform not applied |
| useBeforeDeclaration | namespace member access not qualified |
| declarationEmitAmdModuleNameDirective | amd-dependency directive name attribute |
| declFileWithExtendsClauseThatHasItsContainerNameConflict | extends clause resolution |

---

## (F) Declaration emit (.d.ts) — 14 tests

Test fails because .d.ts section in baseline isn't properly stripped/handled.

| Test | Detail |
|------|--------|
| bundledDtsLateExportRenaming | .d.ts section not properly stripped |
| declarationEmitForGlobalishSpecifierSymlink | .d.ts section emitted when it should not be |
| declarationEmitForGlobalishSpecifierSymlink2 | .d.ts section emitted when it should not be |
| declarationEmitRecursiveConditionalAliasPreserved | type syntax emitted as JS |
| declarationEmitToDeclarationDirWithDeclarationOption | DtsFileErrors section not handled |
| elidedJSImport1 | .d.ts section incorrectly emitted |
| jsFileCompilationWithDeclarationEmitPathSameAsInput | .d.ts section emitted |
| moduleAugmentationInAmbientModule5 | DtsFileErrors section not stripped |
| moduleResolution_packageJson_yesAtPackageRoot | .d.ts section emitted |
| moduleResolution_packageJson_yesAtPackageRoot_fakeScopedPackage | .d.ts section emitted |
| moduleResolutionWithExtensions_withAmbientPresent | .d.ts section emitted |
| moduleResolutionWithExtensions_withPaths | .d.ts sections emitted |
| moduleResolutionWithSuffixes_one_externalModulePath | .d.ts sections emitted |
| moduleResolutionWithSuffixes_one_externalModule_withPaths | .d.ts sections emitted |
| privacyCannotNameVarTypeDeclFile | DtsFileErrors section not handled |

---

## (G) Internal comments — 12 tests

Comment placement or ordering issues in emitted output.

| Test | Detail |
|------|--------|
| declarationEmitForModuleImportingModuleAugmentationRetainsImport | comment stripped from output |
| declarationEmitInferredUndefinedPropFromFunctionInArray | leading comment before export emitted after Object.defineProperty |
| declarationEmitRetainsJsdocyComments | JSDoc comment before destructured export not emitted |
| declFileGenericType2 | comment `// Module` placed inside namespace body instead of before |
| declFileWithInternalModuleNameConflictsInExtendsClause1 | comment placement inside nested namespaces |
| elementAccessExpressionInternalComments | internal comments inside element access not preserved |
| es6ImportNamedImportWithTypesAndValues | trailing comment on import missing |
| for | comment placement inside empty for-loop incrementer |
| importExportInternalComments | internal comments in import/export not preserved |
| inferTypePredicates | comment placement after assignment |
| numericLiteralsWithTrailingDecimalPoints01 | numeric literal property access with comments |
| propertyAccessExpressionInnerComments | inner comments on property access dot lost |

---

## (H) Other — 8 tests

Miscellaneous issues: BOM, inline source maps, formatting, numeric literals.

| Test | Detail |
|------|--------|
| emitBOM | BOM (byte order mark) handling |
| fakeInfinity2 | `1e999` should be emitted as `Infinity`; DtsFileErrors section |
| fakeInfinity3 | `1e999` should be emitted as `Infinity`; DtsFileErrors section |
| genericsManyTypeParameters | array literal line wrapping differs |
| jsFileCompilationWithMapFileAsJsWithInlineSourceMap | inline source map comment not emitted |
| numericLiteralsWithTrailingDecimalPoints02 | numeric literal property access with trailing decimal |
| optionsInlineSourceMapMapRoot | inline source map generation |
| optionsInlineSourceMapSourcemap | inline source map generation |
| optionsInlineSourceMapSourceRoot | inline source map generation |
| propTypeValidatorInference | array literal formatting |
