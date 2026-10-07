// go: github.com/microsoft/typescript-go/internal/ast.getCombinedFlags e917b239
// OVERRIDE: `flags |= getFlags(node)` on a type parameter `T ~uint32` — Kotlin generics have no
// arithmetic, and the two instantiations (ModifierFlags, NodeFlags) are distinct value classes.
// Remove when the lowering monomorphizes generic functions over basic-core type parameters.
fun <T : Comparable<T>> getCombinedFlags(goElem_T: GoElem<T>, node_0: Node?, getFlags: ((Node?) -> T)?): T {
    var node: Node? = getRootDeclaration(node_0)
    var flags: T = getFlags!!(node)
    if (node!!.kind == KindVariableDeclaration) {
        node = node.parent
    }
    if (node != null && node.kind == KindVariableDeclarationList) {
        flags = combinedFlagsOr(flags, getFlags(node))
        node = node.parent
    }
    if (node != null && node.kind == KindVariableStatement) {
        flags = combinedFlagsOr(flags, getFlags(node))
    }
    return flags
}

@Suppress("UNCHECKED_CAST")
private fun <T> combinedFlagsOr(a: T, b: T): T = when (a) {
    is ModifierFlags -> ModifierFlags(a.value or (b as ModifierFlags).value) as T
    is NodeFlags -> NodeFlags(a.value or (b as NodeFlags).value) as T
    else -> error("getCombinedFlags: unexpected flag type ${a?.let { it::class }}")
}
