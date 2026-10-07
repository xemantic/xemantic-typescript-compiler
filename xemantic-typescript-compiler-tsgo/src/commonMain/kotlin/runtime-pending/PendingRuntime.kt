package com.xemantic.typescript.tsgo.runtime

// Runtime additions requested by the porter (-goport), pending merge into runtime/ by its owner.
// docs/goport-lowering.md lists why each exists.

/**
 * The zero value of a type parameter `T` WITHOUT an element kind: `null`. Correct for every
 * reference instantiation (pointers, interfaces, structs reached by pointer); an instantiation
 * with a Kotlin primitive (`int`, `bool`, …) reads it as an NPE — a known limit of erasure,
 * recorded in docs/goport-lowering.md.
 */
@Suppress("UNCHECKED_CAST")
fun <T> goZeroTP(): T = null as T

/** `struct{}` as a container element: the zero (and only) value is `Unit`. */
val goUnitElem: GoElem<Unit> = GoElem({ })

/** The end of a Go function whose last statement is terminating in Go but not to Kotlin's flow analysis. */
fun goUnreachable(): Nothing = throw IllegalStateException("goport: unreachable")
