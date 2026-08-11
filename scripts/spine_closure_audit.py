#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""(WARM.13b) — the SYNTACTIC audit of `SpineDispatch.enterClosure`.

A closure is a claim that the handler does nothing observable for every node
kind outside it, and `SpineDispatch`'s soundness rule accepts exactly two
justifications, both machine-checkable:

  (a) the body's top-level gate is `when ((node as NodeBase).kindId) { ... }`
      with no working `else`, or `if (kindId != K) return`, or
      `if (node !is T) return`;
  (b) an `is Statement` gate, whose kind set is `STATEMENT_KINDS`.

The table was derived at round 732 and the handlers have been edited in ~150
rounds since; a closure that has gone STALE silently drops a diagnostic once
the mask ships, and no corpus baseline need notice (round 753: a green run
bounds frequency, never existence). This script re-derives each gate from
today's `Checker.kt` and diffs it against the declared closure.

Round 809's law is obeyed: the comment/string stripper preserves every line's
ORIGINAL LENGTH, and `'` is treated as a CHAR literal, so brace matching cannot
desynchronise on `Checker.kt`'s raw-string regexes.

Usage: spine_closure_audit.py [--verbose]
"""
import re
import sys

CHECKER = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
DISPATCH = "xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt"


# ONE alternation, longest-opener first. A CHAR literal is matched EXPLICITLY
# ('x' / '\n' / '\uXXXX') rather than "scan to the next apostrophe" — round 809:
# Checker.kt carries raw-string regexes with `'` inside a character class, and a
# naive scanner desynchronises there and then mis-reports a function's extent.
_TOKENS = re.compile(
    r"""(?P<blk>/\*)                      # block comment opener (Kotlin NESTS)
      | (?P<line>//[^\n]*)                # line comment
      | (?P<raw>\"\"\"(?:.|\n)*?\"\"\")   # raw string
      | (?P<str>"(?:\\.|[^"\\\n])*")      # ordinary string
      | (?P<chr>'(?:\\u[0-9a-fA-F]{4}|\\.|[^'\\])')   # char literal
    """,
    re.X,
)


def _blank(s: str) -> str:
    """Same length, newlines preserved — the round-809 invariant."""
    return "".join("\n" if c == "\n" else " " for c in s)


def strip_preserving_length(text: str) -> str:
    """Blank out comments and string/char literals, keeping every offset.

    The length invariant is asserted by the caller: every stripped line must
    keep its ORIGINAL length, which is the cheap check that catches a
    desynchronised scanner (round 809).
    """
    out, i, n = [], 0, len(text)
    while i < n:
        m = _TOKENS.search(text, i)
        if not m:
            out.append(text[i:]); break
        out.append(text[i:m.start()])
        if m.lastgroup == "blk":
            # nested block comment: walk to the matching close
            depth, j = 1, m.end()
            while j < n and depth:
                if text.startswith("/*", j):
                    depth += 1; j += 2
                elif text.startswith("*/", j):
                    depth -= 1; j += 2
                else:
                    j += 1
            out.append(_blank(text[m.start():j])); i = j
        else:
            out.append(_blank(m.group(0))); i = m.end()
    return "".join(out)


def function_body(src: str, name: str):
    """-> (body text, start offset) for `fun <name>(`, brace-matched."""
    m = re.search(r"\n\s*(?:private |internal |protected )?fun " + re.escape(name) + r"\(", src)
    if not m:
        return None, None
    i = src.index("{", m.end())
    depth, j = 0, i
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i + 1:j], i
        j += 1
    return None, None


def top_level_statements(body: str):
    """-> list of WHOLE top-level statements (an `if` block is one statement).

    Lines are accumulated while brace/paren depth is above zero, so a
    multi-line `if (...)` header and the block it guards arrive together and
    the gate can be read off the statement's head.
    """
    stmts, depth, cur = [], 0, []
    for line in body.split("\n"):
        if depth == 0 and not line.strip():
            continue
        cur.append(line)
        for c in line:
            if c in "{(":
                depth += 1
            elif c in ")}":
                depth -= 1
        if depth <= 0:
            depth = 0
            text = "\n".join(cur).strip()
            if text:
                stmts.append(text)
            cur = []
    if cur:
        stmts.append("\n".join(cur).strip())
    return stmts


def head_of(stmt: str) -> str:
    """The statement's header: everything up to the first `{`, whitespace-flat."""
    h = stmt.split("{", 1)[0] if "{" in stmt else stmt
    return re.sub(r"\s+", " ", h).strip()


def when_arm_kinds(body: str, idx: int):
    """Kind labels of the `when` whose header starts at idx; None if a working else."""
    brace = body.index("{", idx)
    depth, j = 0, brace
    while j < len(body):
        if body[j] == "{":
            depth += 1
        elif body[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    region = body[brace + 1:j]
    # arms at depth 0 of the when-block (nested whens' arms are excluded)
    kinds, depth, cur = set(), 0, []
    for ch in region:
        if ch in "{(":
            depth += 1
        elif ch in "})":
            depth -= 1
        if depth == 0:
            cur.append(ch)
    head = "".join(cur)
    for m in re.finditer(r"NodeKind\.([A-Z_0-9]+)", head):
        kinds.add(m.group(1))
    em = re.search(r"(?:^|\n)\s*else\s*->(.*)", head)
    if em:
        # A top-level `else` is harmless ONLY if it does nothing. Its body is
        # whatever follows on that arm; `{}` collapses to `}` in `head` because
        # the `{` raised the depth.
        tail = em.group(1).strip()
        if tail not in ("}", "return", "Unit", "null", "", "{}"):
            return None, "when-with-working-else"
        return kinds, "when/else-noop"
    return kinds, "when"


def type_to_kind(t: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", t).upper()


def derive_kind_set(body: str, resolve):
    """Union of every TOP-LEVEL gate's kind set.

    Accepted top-level forms (anything else -> MANUAL):
      * `val`/`var` declaration, a bare `return`, or a `}`-only line
      * `if ((node as NodeBase).kindId != NodeKind.X) return`  -> {X}
      * `if (node !is T) return`                               -> {kind(T)}
      * `if (node is T ...) {`   (gate over the whole arm)      -> {kind(T)}
      * `when ((node as NodeBase).kindId) {`                    -> its arm labels
    `T == Statement` yields the STATEMENT_KINDS marker, which the caller
    resolves — justification (b) of SpineDispatch's soundness rule.
    """
    kinds, how = set(), []
    stmts = top_level_statements(body)
    if not stmts:
        return None, "empty"
    for s in stmts:
        h = head_of(s)
        # a TERMINATING gate guards everything below it -> stop scanning
        m = re.match(r"if \(\(node as NodeBase\)\.kindId != NodeKind\.([A-Z_0-9]+)\) return$", h)
        if m:
            kinds.add(m.group(1)); how.append("if-kindId"); break
        m = re.match(r"if \(node !is ([A-Za-z0-9_]+)\) return$", h)
        if m:
            kinds.add(type_to_kind(m.group(1))); how.append("if-!is"); break
        if h.startswith("when ((node as NodeBase).kindId)") or \
                re.match(r"(val|var) \w+(: [^=]+)? = when \(\(node as NodeBase\)\.kindId\)$", h):
            arms, tag = when_arm_kinds(s, s.index("when ((node as NodeBase).kindId)"))
            if arms is None:
                return None, "top-level " + tag
            kinds |= arms; how.append(tag)
            # `else -> return` makes the whole `when` a terminating gate
            if re.search(r"else\s*->\s*return", s):
                how.append("else-return"); break
            continue
        # a BLOCK gate guards only its own block -> union and keep scanning
        if re.match(r"if \((?:\()?node is ", h):
            ts = re.findall(r"node is ([A-Za-z0-9_]+)", h)
            for t in ts:
                if t == "Statement":
                    kinds |= resolve("STATEMENT_KINDS")
                else:
                    kinds.add(type_to_kind(t))
            how.append("is-" + "/".join(ts)); continue
        if re.match(r"^(val|var)\b", h) or h in ("}", "return", "", "} else {"):
            continue
        return None, f"unrecognised top-level stmt: {h[:60]}"
    return kinds, "+".join(dict.fromkeys(how)) or "none"


def declared_closures():
    """-> ordered list of (index, name, set-or-None) from SpineDispatch.kt."""
    txt = strip_preserving_length(open(DISPATCH, errors="replace").read())
    m = re.search(r"val enterClosure: Array<IntArray\?> = arrayOf\(", txt)
    start = txt.index("(", m.end() - 1)
    depth, j = 0, start
    while j < len(txt):
        if txt[j] == "(":
            depth += 1
        elif txt[j] == ")":
            depth -= 1
            if depth == 0:
                break
        j += 1
    body = txt[start + 1:j]
    entries, depth, cur = [], 0, ""
    for ch in body:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        if ch == "," and depth == 0:
            entries.append(cur); cur = ""
        else:
            cur += ch
    if cur.strip():
        entries.append(cur)
    out = []
    for i, e in enumerate(entries):
        if re.search(r"\bnull\b", e) and "intArrayOf" not in e and "union" not in e:
            out.append((i, None, "OPEN"))
        else:
            kinds = set(re.findall(r"NodeKind\.([A-Z_0-9]+)", e))
            if "STATEMENT_KINDS" in e:
                kinds |= statement_kinds()
            tag = "union(STATEMENT_KINDS)" if "STATEMENT_KINDS" in e else "closed"
            out.append((i, kinds, tag))
    return out


_STMT_CACHE = None


def statement_kinds():
    global _STMT_CACHE
    if _STMT_CACHE is None:
        txt = open(DISPATCH, errors="replace").read()
        m = re.search(r"val STATEMENT_KINDS(?::[^=]*)? = intArrayOf\((.*?)\)\n", txt, re.S)
        if not m:
            sys.exit("REFUSED: STATEMENT_KINDS not found in SpineDispatch.kt")
        _STMT_CACHE = set(re.findall(r"NodeKind\.([A-Z_0-9]+)", m.group(1)))
    return _STMT_CACHE


def main():
    verbose = "--verbose" in sys.argv
    raw = open(CHECKER, errors="replace").read()
    chk = strip_preserving_length(raw)
    # round 809's cheap invariant: a desynchronised stripper changes a length.
    for a, b in zip(raw.split("\n"), chk.split("\n")):
        if len(a) != len(b):
            sys.exit(f"REFUSED: stripper changed a line length: {a[:60]!r}")
    disp = open(DISPATCH, errors="replace").read()
    names = re.search(r"val enterNames: Array<String> = arrayOf\((.*?)\)\n", disp, re.S).group(1)
    names = re.findall(r'"([^"]+)"', names)
    closures = declared_closures()
    assert len(names) == len(closures), f"{len(names)} names vs {len(closures)} closures"

    bad, open_n, checked = [], 0, 0
    for (i, declared, tag), name in zip(closures, names):
        if declared is None:
            open_n += 1
            if verbose:
                print(f"[{i:2d}] {name:28s} OPEN")
            continue
        body, _ = function_body(chk, name)
        if body is None:
            bad.append((i, name, "SOURCE NOT FOUND", None)); continue
        derived, how = derive_kind_set(body, lambda _k: statement_kinds())
        # A pure wrapper (a probe bracket around a `...Core`) is followed one
        # level down; the gate that matters is the Core's.
        if derived is None and re.search(r"\b" + re.escape(name) + r"Core\(", body):
            cbody, _ = function_body(chk, name + "Core")
            if cbody is not None:
                derived, how = derive_kind_set(cbody, lambda _k: statement_kinds())
                how = "via " + name + "Core: " + (how or "?")
        checked += 1
        if derived is None:
            bad.append((i, name, f"no derivable gate ({how})", None)); continue
        missing = derived - declared
        if missing:
            bad.append((i, name, f"gate acts on kinds NOT in closure: {sorted(missing)}", how))
        elif verbose:
            unused = declared - derived
            note = f" (closure wider by {len(unused)})" if unused else ""
            print(f"[{i:2d}] {name:28s} {how[:34]:34s} OK {len(derived):3d} kinds{note}")

    print(f"\nenter handlers: {len(names)}  OPEN: {open_n}  audited: {checked}")
    if bad:
        print(f"\n!! {len(bad)} PROBLEM(S) — a stale closure silently drops a diagnostic:")
        for i, name, why, how in bad:
            print(f"  [{i:2d}] {name}: {why}")
        sys.exit(1)
    print("all declared closures are supersets of their top-level gate's kind set")


if __name__ == "__main__":
    main()
