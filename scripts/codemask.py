#!/usr/bin/env python3
"""(INV.0) step 7, round (P18.59) — a Kotlin code-mask that keeps STRING-TEMPLATE
INTERPOLATIONS visible.

Every other stripper in this repo (`ccet_split_analyze.strip`, the per-round
`spanmask`) blanks a string literal WHOLE, which hides `${ ... }` — and what is
inside those braces is CODE. Step 7 found it the hard way: an ambient reference
living inside `"import(\"${moduleFileBaseNoExt(f)}\")"` was missing from the
extraction's ambient census AND unrewritten by its transform. It failed loudly
there only because a `Checker` member is unreachable from a collaborator, so the
compiler is a complete detector for the AMBIENT case and NOT for the census: a
TOP-LEVEL function called from inside a template resolves fine and would simply
be absent from the answer.

Comments stay blanked, so prose can never be mistaken for code, and the mask is
LENGTH-PRESERVING (round 809's invariant), so every offset in the mask is the
offset in the source.

Use this — not `strip`/`spanmask` — for any census, split transform or verbatim
proof over Kotlin source.
"""
def codemask(src):
    out = list(src); i = 0; n = len(src)

    def blank(a, b):
        for k in range(a, min(b, n)):
            if out[k] != '\n': out[k] = ' '

    def scan_string(i, triple):
        """Blank the literal text but leave `${...}` alone. Returns the index past it."""
        q = 3 if triple else 1
        blank(i, i + q); j = i + q
        while j < n:
            if triple and src.startswith('"""', j):
                blank(j, j + 3); return j + 3
            if not triple and src[j] == '"':
                blank(j, j + 1); return j + 1
            if not triple and src[j] == '\\':
                blank(j, j + 2); j += 2; continue
            if not triple and src[j] == '\n':
                return j                                    # unterminated; give up at EOL
            if src[j] == '$' and j + 1 < n and src[j + 1] == '{':
                blank(j, j + 2)                             # `${` itself is not code
                depth = 1; k = j + 2
                while k < n and depth > 0:                  # leave the interpolation RAW
                    if src[k] == '{': depth += 1
                    elif src[k] == '}':
                        depth -= 1
                        if depth == 0: blank(k, k + 1); k += 1; break
                    elif src[k] == '"':
                        k = scan_string(k, src.startswith('"""', k)); continue
                    k += 1
                j = k; continue
            blank(j, j + 1); j += 1
        return j

    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i); j = n if j < 0 else j
            blank(i, j); i = j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            depth = 1; blank(i, i + 2); j = i + 2
            while j < n and depth > 0:
                if src[j] == '/' and j + 1 < n and src[j + 1] == '*':
                    depth += 1; blank(j, j + 2); j += 2; continue
                if src[j] == '*' and j + 1 < n and src[j + 1] == '/':
                    depth -= 1; blank(j, j + 2); j += 2; continue
                blank(j, j + 1); j += 1
            i = j
        elif c == '"':
            i = scan_string(i, src.startswith('"""', i))
        elif c == "'":
            blank(i, i + 1); j = i + 1
            while j < n and src[j] != "'":
                if src[j] == '\\': blank(j, j + 2); j += 2; continue
                blank(j, j + 1); j += 1
            if j < n: blank(j, j + 1)
            i = j + 1
        else:
            i += 1
    return ''.join(out)
