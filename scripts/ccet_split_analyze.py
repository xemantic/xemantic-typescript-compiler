#!/usr/bin/env python3
"""(JIT.1)(c) round 811 — analysis helper for the split of
`checkSingleCallExpressionTypesCore`.

Strips Kotlin comments and string/char literals LENGTH-PRESERVINGLY (round 809's
trap: `'` is a CHAR literal, never "scan to the next apostrophe"), then reports
per-region brace balance, bare `return`s and free variables.
"""
import re
import sys

PATH = "src/commonMain/kotlin/Checker.kt"


def strip(src: str) -> str:
    """Blank out comments and string/char literal CONTENT, preserving length."""
    out = list(src)
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            if j < 0:
                j = n
            for k in range(i, j):
                out[k] = ' '
            i = j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            depth = 1
            j = i + 2
            while j < n and depth:
                if src[j] == '/' and j + 1 < n and src[j + 1] == '*':
                    depth += 1
                    j += 2
                elif src[j] == '*' and j + 1 < n and src[j + 1] == '/':
                    depth -= 1
                    j += 2
                else:
                    j += 1
            for k in range(i, j):
                if src[k] != '\n':
                    out[k] = ' '
            i = j
        elif src.startswith('"""', i) or c == '"':
            # A Kotlin string may embed `${ … }` holding arbitrary code, INCLUDING
            # further string literals — a scanner that stops at the next quote
            # desynchronises there (that is what blanked 40,000 lines on the first
            # attempt). Walk the template expressions with a brace counter.
            triple = src.startswith('"""', i)
            j = i + (3 if triple else 1)
            while j < n:
                if not triple and src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '$' and src[j + 1:j + 2] == '{':
                    depth = 1
                    j += 2
                    while j < n and depth:
                        if src[j] == '{':
                            depth += 1
                        elif src[j] == '}':
                            depth -= 1
                        elif src[j] == '"':
                            # a nested literal inside the template expression
                            j += 1
                            while j < n and src[j] != '"':
                                j += 2 if src[j] == '\\' else 1
                        j += 1
                    continue
                if triple and src.startswith('"""', j):
                    j += 3
                    break
                if not triple and src[j] == '"':
                    j += 1
                    break
                j += 1
            for k in range(i, min(j, n)):
                if src[k] != '\n':
                    out[k] = ' '
            i = min(j, n)
        elif c == "'":
            # a CHAR literal is 'x' / '\n' / '\uXXXX' and nothing else
            m = re.match(r"'(\\u[0-9a-fA-F]{4}|\\.|[^'\\])'", src[i:])
            if m:
                for k in range(i, i + m.end()):
                    out[k] = ' '
                i += m.end()
            else:
                i += 1
        else:
            i += 1
    return ''.join(out)


def main():
    raw = open(PATH).read()
    st = strip(raw)
    assert len(st) == len(raw)
    rl, sl = raw.split('\n'), st.split('\n')
    assert all(len(a) == len(b) for a, b in zip(rl, sl)), "length not preserved"

    regions = {
        "P_prologue": (139838, 139968),
        "U_union": (140085, 140308),
        "N_nosigs": (140315, 140554),
        "T_typeargs": (140560, 140681),
    }
    fn_start, fn_end = 139792, 140787
    # locals declared in the function before each region
    decl_re = re.compile(r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)")
    outer_decls = []
    for ln in range(fn_start, fn_end + 1):
        s = sl[ln - 1]
        indent = len(s) - len(s.lstrip(' '))
        if indent == 8:  # top level of the function body
            for m in decl_re.finditer(s):
                outer_decls.append((ln, m.group(1)))
    print("top-level locals:", outer_decls)

    for name, (a, b) in regions.items():
        body = sl[a - 1:b]
        bal = sum(l.count('{') - l.count('}') for l in body)
        bares = [a + i for i, l in enumerate(body)
                 if re.search(r"(?<![@\w])return\s*$", l)]
        labeled = [a + i for i, l in enumerate(body) if 'return@' in l]
        used = set()
        for l in body:
            used |= set(re.findall(r"\b[A-Za-z_][A-Za-z0-9_]*\b", l))
        free = [(ln, v) for ln, v in outer_decls if ln < a and v in used]
        print(f"\n=== {name} lines {a}..{b} ({b - a + 1} lines) brace-balance={bal}")
        print(f"  bare returns ({len(bares)}): {bares}")
        print(f"  labeled returns: {len(labeled)}")
        print(f"  free outer locals: {free}")
        print(f"  uses expr/source/fileName: "
              f"{'expr' in used}/{'source' in used}/{'fileName' in used}")

    # whole-function return census
    body = sl[fn_start - 1:fn_end]
    bares = [fn_start + i for i, l in enumerate(body)
             if re.search(r"(?<![@\w])return\s*$", l)]
    print(f"\nfunction bare returns: {len(bares)}")
    other = [fn_start + i for i, l in enumerate(body)
             if re.search(r"(?<![@\w])return\b", l) and (fn_start + i) not in bares]
    print(f"non-bare return lines: {len(other)} -> {other[:60]}")


if __name__ == "__main__":
    sys.exit(main())
