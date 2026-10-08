// go: github.com/microsoft/typescript-go/internal/diagnostics.loadLocaleData 1f99a75d
// OVERRIDE ((TSGO.5), docs/goport-cli.md): `json.UnmarshalRead` into a `map[Key]string`. A GoMap carries its
// VALUE kind but not its KEY type, so the json shim decodes object names as plain strings and every lookup by
// a `Key` missed — `tsc --locale de` printed English. Go's body, decoding a `map[string]string` and re-keying it.
fun loadLocaleData(data: String): GoMap<Key, String> {
    return withDefers({ GoMap.nil<Key, String>(GoElem.STRING) }) { df0 ->
        val t1 = com.xemantic.typescript.tsgo.go.compress.gzip.newReader(com.xemantic.typescript.tsgo.go.strings.newReader(data))
        val gr: com.xemantic.typescript.tsgo.go.compress.gzip.Reader? = t1.first
        val err: GoError? = t1.second
        if (err != null) {
            goPanic("failed to create gzip reader: " + err!!.error())
        }
        val dr2 = gr!!
        df0.defer { dr2.close() }
        val raw: GoBox<GoMap<String, String>> = GoBox(GoMap.nil<String, String>(GoElem.STRING))
        val err_1: GoError? = com.xemantic.typescript.tsgo.json.unmarshalRead(gr, raw, GoElem.ref<com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.Options?>().nilSlice)
        if (err_1 != null) {
            goPanic("failed to unmarshal locale data: " + err_1!!.error())
        }
        val result = GoMap.make<Key, String>(GoElem.STRING, raw.value.len)
        raw.value.range { k, v ->
            result[Key(k)] = v
            true
        }
        return result
    }
}
