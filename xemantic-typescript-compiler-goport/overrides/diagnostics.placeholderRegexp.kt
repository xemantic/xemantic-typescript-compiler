// go: github.com/microsoft/typescript-go/internal/diagnostics.placeholderRegexp 618396c8
// OVERRIDE: Go's RE2 reads `{` that does not start a repetition as a literal; java.util.regex
// (behind the `regexp` shim, docs/goport-runtime.md § 10.7) rejects it. Same pattern, escaped.
// Remove when the regexp shim translates RE2 syntax.
@kotlin.jvm.JvmField val placeholderRegexp: com.xemantic.typescript.tsgo.go.regexp.Regexp? = com.xemantic.typescript.tsgo.go.regexp.mustCompile("\\{(\\d+)\\}")
