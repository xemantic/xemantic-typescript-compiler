#!/usr/bin/env python3
"""(JIT.1)(c) round 811 — apply the split of `checkSingleCallExpressionTypesCore`.

Moves four regions of the committed `CallSections` partition into four helpers,
verbatim modulo a dedent and the `return` -> `return true` signal rewrite (which
is spliced at offsets located on the STRING/COMMENT-STRIPPED line, so a `return`
inside a comment or a string can never be rewritten).
"""
import re
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402

PATH = "src/commonMain/kotlin/Checker.kt"

# (name, first, last, dedent, signal): `signal` True => bare returns become
# `return true` and a trailing `return false` is appended.
REGIONS = [
    ("P", 139838, 139968, 0, True),
    ("U", 140085, 140308, 4, True),
    ("N", 140315, 140554, 4, False),
    ("T", 140560, 140681, 4, True),
]

HEADERS = {
    "P": (
        "    /**\n"
        "     * (JIT.1)(c) round 811 — the seven dedicated PROLOGUE walkers of\n"
        "     * [checkSingleCallExpressionTypesCore], moved out so the entry stays under\n"
        "     * HotSpot's 8,000-bytecode `HugeMethodLimit`.\n"
        "     *\n"
        "     * Reached only when round 793's [ccetPrologueMayFire] gate — which STAYS in\n"
        "     * the entry — admits the call; it refuses ~98% of call expressions, and the\n"
        "     * (CALL.1)(a) partition prices this run of sections at 253 ms with ZERO\n"
        "     * firings on the compiler profile. Returns `true` when a walker fired or\n"
        "     * otherwise handled the call, i.e. \"the caller must return\".\n"
        "     */\n"
        "    private fun ccetPrologueWalkers(\n"
        "        expr: CallExpression,\n"
        "        calleeExpr: Expression,\n"
        "        source: String,\n"
        "        fileName: String,\n"
        "        prologueT: Long,\n"
        "    ): Boolean {\n"
    ),
    "U": (
        "    /**\n"
        "     * (JIT.1)(c) round 811 — B60.14's UNION-callee branch\n"
        "     * ([CallSections.UNION_CALLEE]) of [checkSingleCallExpressionTypesCore].\n"
        "     *\n"
        "     * 31 of 52,413 invocations leave the function here on the compiler profile,\n"
        "     * so the whole branch is cold; `true` means \"emitted or decided — the caller\n"
        "     * must return\".\n"
        "     */\n"
        "    private fun ccetUnionCalleeChecks(\n"
        "        expr: CallExpression,\n"
        "        calleeExpr: Expression,\n"
        "        calleeType: Type.Union,\n"
        "        source: String,\n"
        "        fileName: String,\n"
        "    ): Boolean {\n"
    ),
    "N": (
        "    /**\n"
        "     * (JIT.1)(c) round 811 — the `signatures.isEmpty()` branch\n"
        "     * ([CallSections.NO_SIGS]) of [checkSingleCallExpressionTypesCore]: TS2348 /\n"
        "     * TS6234 / TS2721 / TS2722 / TS2723 / TS2349 x3.\n"
        "     *\n"
        "     * The branch returned UNCONDITIONALLY, so every `return` in it is a return\n"
        "     * from this helper and the caller returns straight after the call. It is\n"
        "     * entered ZERO times on the compiler profile (round 734's exit census).\n"
        "     */\n"
        "    private fun ccetNoCallSignatureDiagnostics(\n"
        "        expr: CallExpression,\n"
        "        calleeExpr: Expression,\n"
        "        calleeType: Type,\n"
        "        source: String,\n"
        "        fileName: String,\n"
        "    ) {\n"
    ),
    "T": (
        "    /**\n"
        "     * (JIT.1)(c) round 811 — the EXPLICIT-type-argument branch\n"
        "     * ([CallSections.TYPE_ARGS]) of [checkSingleCallExpressionTypesCore].\n"
        "     *\n"
        "     * 101 of 52,413 invocations leave the function here on the compiler profile.\n"
        "     * `true` means \"checked under the instantiated signature — the caller must\n"
        "     * return\"; falling through means no generic signature accommodated the\n"
        "     * supplied type arguments and the ordinary paths below still apply.\n"
        "     */\n"
        "    private fun ccetExplicitTypeArguments(\n"
        "        expr: CallExpression,\n"
        "        typeArgs: List<TypeNode>,\n"
        "        signatures: List<Signature>,\n"
        "        source: String,\n"
        "        fileName: String,\n"
        "    ): Boolean {\n"
    ),
}

CALLS = {
    "P": "            if (ccetPrologueWalkers(expr, calleeExpr, source, fileName, prologueT)) return\n",
    "U": "            if (ccetUnionCalleeChecks(expr, calleeExpr, calleeType, source, fileName)) return\n",
    "N": ("            ccetNoCallSignatureDiagnostics(expr, calleeExpr, calleeType, source, fileName)\n"
          "            return\n"),
    "T": "            if (ccetExplicitTypeArguments(expr, typeArgs, signatures, source, fileName)) return\n",
}


def rewrite_returns(raw_lines, stripped_lines, dedent, signal):
    """Dedent and, when `signal`, turn each whole-function bare `return` into
    `return true`. Matches are located on the stripped line and spliced into the
    raw one; anything but whitespace after the token is a hard error."""
    out = []
    for raw, st in zip(raw_lines, stripped_lines):
        assert len(raw) == len(st)
        if signal:
            ms = list(re.finditer(r"(?<![@\w.])return(?=\s*$)", st))
            assert len(ms) <= 1, f"two return tokens on one line: {raw!r}"
            if ms:
                e = ms[0].end()
                assert raw[e:].strip() == "", raw
                raw = raw[:e] + " true" + raw[e:]
        if dedent and raw.startswith(" " * dedent):
            raw = raw[dedent:]
        elif dedent and raw.strip() == "":
            raw = raw.strip()
        elif dedent:
            raise AssertionError(f"cannot dedent: {raw!r}")
        out.append(raw)
    return out


def main():
    raw = open(PATH).read()
    st = strip(raw)
    rl = raw.split("\n")
    sl = st.split("\n")
    assert len(rl) == len(sl)

    fn_end = 140787  # the `    }` closing checkSingleCallExpressionTypesCore

    helpers = []
    for name, a, b, dedent, signal in REGIONS:
        body = rewrite_returns(rl[a - 1:b], sl[a - 1:b], dedent, signal)
        text = HEADERS[name] + "\n".join(body) + "\n"
        if signal:
            text += "        return false\n"
        text += "    }\n"
        helpers.append(text)

    # rebuild the file: replace each region by its call site, then append helpers
    new = []
    i = 0
    reg = {a: (name, b) for name, a, b, _, _ in REGIONS}
    while i < len(rl):
        ln = i + 1
        if ln in reg:
            name, b = reg[ln]
            new.append(CALLS[name].rstrip("\n"))
            i = b
            continue
        new.append(rl[i])
        if ln == fn_end:
            new.append("")
            for h in helpers:
                new.append(h.rstrip("\n"))
                new.append("")
            new.pop()  # the file already has a blank line after the function
        i += 1

    open(PATH, "w").write("\n".join(new))
    print("regions moved:", [(n, b - a + 1) for n, a, b, _, _ in REGIONS])


if __name__ == "__main__":
    sys.exit(main())
