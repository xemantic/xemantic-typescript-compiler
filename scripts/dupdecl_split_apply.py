#!/usr/bin/env python3
"""(JIT.1)(d) round 812 — apply the split of `checkDuplicateDeclarations`.

Moves five contiguous regions of the function into five helpers, verbatim modulo
a dedent and two rewrites, both spliced at offsets located on the
STRING/COMMENT-STRIPPED line so a token inside a comment or a string can never
be touched:

  * the seven `continue`s of the V region that bind to the OUTER
    `for ((_, group) in byName)` loop become `return true` (the entry replays
    them with `if (cddCheckValueRedeclarations(…)) continue`); the ONE `continue`
    that binds to an inner `for (decl in group)` stays verbatim — which loop each
    binds to is decided by the brace-matching census in
    `dupdecl_split_analyze.py`, never by indentation;
  * the X region gains a trailing `return emitted2395`, the one value that
    crosses a boundary (the entry's `if (emitted2395) continue` reads it).

The local `data class DeclInfo` is HOISTED to a private nested class, because
five helper signatures now name it.
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402

PATH = "src/commonMain/kotlin/Checker.kt"

FN_HEAD = 41227          # `    private fun checkDuplicateDeclarations(`
FN_END = 42098           # its closing `    }`
DECLINFO_LINE = 41235    # the local `data class DeclInfo(…)` being hoisted
DECLINFO_ANCHOR = 41223  # the function's KDoc opening — hoist lands above it

# (key, first, last, dedent, continue-lines-to-rewrite, tail)
REGIONS = [
    ("I", 41433, 41486, 4, (), None),
    ("E", 41488, 41541, 4, (), None),
    ("G", 41549, 41698, 4, (), None),
    ("X", 41735, 41832, 8, (), "        return emitted2395"),
    ("V", 41837, 42085, 8,
     (41861, 41932, 41953, 41980, 42017, 42050, 42084), "        return false"),
]

HOIST = (
    "    /**\n"
    "     * (JIT.1)(d) round 812 — one collected declaration of\n"
    "     * [checkDuplicateDeclarations]: a name, the syntactic KIND that declared it,\n"
    "     * the name node the diagnostic is reported on, and the owning statement.\n"
    "     *\n"
    "     * It was a LOCAL data class of that function until the split; five helpers\n"
    "     * now name it in their signatures, so it is a nested class. Nothing else\n"
    "     * changed — it is still constructed only by the collection loop.\n"
    "     */\n"
    "    private data class DeclInfo("
    "val name: String, val kind: String, val nameNode: Node, val stmt: Statement? = null)\n"
    "\n"
)

HEADERS = {
    "I": (
        "    /**\n"
        "     * (JIT.1)(d) round 812 — the IMPORT-binding duplicates of\n"
        "     * [checkDuplicateDeclarations]: TS2300 for two `import =` declarations or two\n"
        "     * import bindings of one name, and 17.127's TS2395 for a default import paired\n"
        "     * with an exported var/let/const.\n"
        "     *\n"
        "     * Reached only for a group of two or more declarations SHARING a name, which\n"
        "     * is what the entry's `if (group.size < 2) continue` selects; the collection\n"
        "     * loop that every statement list pays for stays in the entry.\n"
        "     */\n"
        "    private fun cddCheckImportBindings(\n"
        "        group: List<DeclInfo>,\n"
        "        kinds: Set<String>,\n"
        "        source: String,\n"
        "        fileName: String,\n"
        "    ) {\n"
    ),
    "E": (
        "    /**\n"
        "     * (JIT.1)(d) round 812 — the merged-ENUM checks of\n"
        "     * [checkDuplicateDeclarations]: TS2432 (only one declaration of a merged enum\n"
        "     * may omit the first member's initializer) and TS2300 for a member name\n"
        "     * declared in two of the merged enum bodies.\n"
        "     */\n"
        "    private fun cddCheckMergedEnums(\n"
        "        group: List<DeclInfo>,\n"
        "        hasEnum: Boolean,\n"
        "        source: String,\n"
        "        fileName: String,\n"
        "    ) {\n"
    ),
    "G": (
        "    /**\n"
        "     * (JIT.1)(d) round 812 — TS2428 of [checkDuplicateDeclarations]: all\n"
        "     * declarations of a merged interface (or a class merged with an interface)\n"
        "     * must have IDENTICAL type parameters — names, constraint text and defaults,\n"
        "     * compared as normalised source text.\n"
        "     *\n"
        "     * `hasInterface` is computed by the caller and STAYS there: the V region's\n"
        "     * TS2451 gates read it too, so it is not this block's to own.\n"
        "     */\n"
        "    private fun cddCheckMergedTypeParameters(\n"
        "        group: List<DeclInfo>,\n"
        "        hasInterface: Boolean,\n"
        "        source: String,\n"
        "        fileName: String,\n"
        "    ) {\n"
    ),
    "X": (
        "    /**\n"
        "     * (JIT.1)(d) round 812 — TS2395 and TS2434 of [checkDuplicateDeclarations]:\n"
        "     * every declaration of a merged name must be uniformly exported or local\n"
        "     * (checked per declaration SPACE — type / value / namespace), and an\n"
        "     * instantiated namespace may not be located before the class or function it\n"
        "     * merges with.\n"
        "     *\n"
        "     * Returns whether TS2395 fired — **the one value that crosses a boundary in\n"
        "     * this split**. The caller reads it as `if (emitted2395) continue`, because\n"
        "     * TS2300/TS2393/TS2813/TS2451 are all superseded by the \"all exported or all\n"
        "     * local\" category. TS2434 deliberately fires ALONGSIDE TS2395, which is why\n"
        "     * it is inside this region rather than after the caller's `continue`.\n"
        "     */\n"
        "    private fun cddCheckExportUniformity(\n"
        "        statements: List<Statement>,\n"
        "        group: List<DeclInfo>,\n"
        "        hasClass: Boolean,\n"
        "        hasFunc: Boolean,\n"
        "        hasNamespace: Boolean,\n"
        "        isAmbientContext: Boolean,\n"
        "        source: String,\n"
        "        fileName: String,\n"
        "    ): Boolean {\n"
    ),
    "V": (
        "    /**\n"
        "     * (JIT.1)(d) round 812 — the VALUE-space redeclaration checks of\n"
        "     * [checkDuplicateDeclarations]: TS2393 (duplicate function implementation),\n"
        "     * TS2813/TS2814 (function merged with a non-ambient class), TS2323 (redeclared\n"
        "     * exported variable) and the TS2451/TS2300 block-scoped cluster.\n"
        "     *\n"
        "     * `true` means \"the caller must `continue` to the next name group\": the seven\n"
        "     * `continue`s of this region that bound to the OUTER `for ((_, group) in\n"
        "     * byName)` loop are returns from here. The eighth, inside\n"
        "     * `for (decl in group)`, is untouched — which loop a `continue` binds to is a\n"
        "     * brace-matching question, and indentation is not evidence.\n"
        "     */\n"
        "    private fun cddCheckValueRedeclarations(\n"
        "        group: List<DeclInfo>,\n"
        "        kinds: Set<String>,\n"
        "        hasVar: Boolean,\n"
        "        hasClass: Boolean,\n"
        "        hasEnum: Boolean,\n"
        "        hasFunc: Boolean,\n"
        "        hasInterface: Boolean,\n"
        "        hasNamespace: Boolean,\n"
        "        isAmbientContext: Boolean,\n"
        "        source: String,\n"
        "        fileName: String,\n"
        "    ): Boolean {\n"
    ),
}

CALLS = {
    "I": "            cddCheckImportBindings(group, kinds, source, fileName)\n",
    "E": "            cddCheckMergedEnums(group, hasEnum, source, fileName)\n",
    "G": "            cddCheckMergedTypeParameters(group, hasInterface, source, fileName)\n",
    "X": ("                val emitted2395 = cddCheckExportUniformity(\n"
          "                    statements, group, hasClass, hasFunc, hasNamespace,\n"
          "                    isAmbientContext, source, fileName,\n"
          "                )\n"),
    "V": ("                if (cddCheckValueRedeclarations(\n"
          "                        group, kinds, hasVar, hasClass, hasEnum, hasFunc, hasInterface,\n"
          "                        hasNamespace, isAmbientContext, source, fileName,\n"
          "                    )\n"
          "                ) continue\n"),
}


def rewrite(raw_lines, stripped_lines, first, dedent, continues):
    """Dedent, and turn each listed `continue` into `return true`."""
    out = []
    for k, (rawline, st) in enumerate(zip(raw_lines, stripped_lines)):
        ln = first + k
        assert len(rawline) == len(st)
        if ln in continues:
            ms = list(re.finditer(r"(?<![@\w.])continue\b", st))
            assert len(ms) == 1, f"expected one continue on {ln}: {rawline!r}"
            s, e = ms[0].span()
            assert st[e:].strip() == "", f"trailing code after continue on {ln}"
            rawline = rawline[:s] + "return true" + rawline[e:]
        if dedent and rawline.startswith(" " * dedent):
            rawline = rawline[dedent:]
        elif dedent and rawline.strip() == "":
            rawline = rawline.strip()
        elif dedent:
            raise AssertionError(f"cannot dedent line {ln}: {rawline!r}")
        out.append(rawline)
    return out


def build(rl, sl):
    """Return (new_lines, helper_texts)."""
    helpers = []
    for key, a, b, dedent, continues, tail in REGIONS:
        body = rewrite(rl[a - 1:b], sl[a - 1:b], a, dedent, set(continues))
        text = HEADERS[key] + "\n".join(body) + "\n"
        if tail:
            text += tail + "\n"
        text += "    }\n"
        helpers.append(text)

    reg = {a: (key, b) for key, a, b, _, _, _ in REGIONS}
    new = []
    i = 0
    while i < len(rl):
        ln = i + 1
        if ln == DECLINFO_ANCHOR:
            new.extend(HOIST.rstrip("\n").split("\n"))
            new.append("")
        if ln == DECLINFO_LINE:          # the hoisted declaration leaves the body
            i += 1
            continue
        if ln in reg:
            key, b = reg[ln]
            new.extend(CALLS[key].rstrip("\n").split("\n"))
            i = b
            continue
        new.append(rl[i])
        if ln == FN_END:
            for h in helpers:
                new.append("")
                new.extend(h.rstrip("\n").split("\n"))
        i += 1
    return new, helpers


def main():
    raw = open(PATH).read()
    st = strip(raw)
    rl, sl = raw.split("\n"), st.split("\n")
    assert len(rl) == len(sl)
    assert "data class DeclInfo" in sl[DECLINFO_LINE - 1]
    new, helpers = build(rl, sl)
    open(PATH, "w").write("\n".join(new))
    print("regions moved:", [(k, b - a + 1) for k, a, b, _, _, _ in REGIONS])
    print("DeclInfo hoisted above line", DECLINFO_ANCHOR)


if __name__ == "__main__":
    sys.exit(main())
