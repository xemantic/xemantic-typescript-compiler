// go: github.com/microsoft/typescript-go/internal/tsoptions.floatOrInt32ToFlag 5e695971
// OVERRIDE: `value.(T)` and `T(float)` on a type parameter `T ~int32` — an erased value class can be neither
// tested nor constructed generically; the six instantiations are the core option enums. See checker.hashWrite32.
@Suppress("UNCHECKED_CAST")
fun <T : Comparable<T>> floatOrInt32ToFlag(goElem_T: GoElem<T>, value_1: Any?): T {
    val zero: Any = goElem_T.zeroValue()!!
    if (value_1 != null && value_1::class == zero::class) return value_1 as T
    val n = goFloat64ToInt(value_1 as Double)
    return when (zero) {
        is com.xemantic.typescript.tsgo.core.JsxEmit -> com.xemantic.typescript.tsgo.core.JsxEmit(n)
        is com.xemantic.typescript.tsgo.core.ModuleKind -> com.xemantic.typescript.tsgo.core.ModuleKind(n)
        is com.xemantic.typescript.tsgo.core.ModuleDetectionKind -> com.xemantic.typescript.tsgo.core.ModuleDetectionKind(n)
        is com.xemantic.typescript.tsgo.core.ModuleResolutionKind -> com.xemantic.typescript.tsgo.core.ModuleResolutionKind(n)
        is com.xemantic.typescript.tsgo.core.NewLineKind -> com.xemantic.typescript.tsgo.core.NewLineKind(n)
        is com.xemantic.typescript.tsgo.core.ScriptTarget -> com.xemantic.typescript.tsgo.core.ScriptTarget(n)
        else -> error("floatOrInt32ToFlag: unexpected ${zero::class}")
    } as T
}
