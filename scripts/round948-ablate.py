#!/usr/bin/env python3
"""(CHK.25) round 948 — one deliberate mistake at a time, against a sha256-verified snapshot.

Never `git checkout` (CLAUDE.md round 789: that also destroys every UNCOMMITTED change in
the file, and a probe lives in the file it measures).  Every arm is applied to a copy taken
from the SNAPSHOT and diffed against the SNAPSHOT, and each arm asserts it RAN the expected
number of pins ACROSS TWO MODULES, so a dead build or an empty `--tests` filter reads as a
failure rather than as "the mistake changed nothing" (round 856).

Two flavours of arm, as round 941 established: one removes a FIX, one removes its BOUND.
A `using` head is a contextual keyword, so most of this round's risk is in the bounds —
the lookahead, the same-line (ASI) test, `disallowOf`, and the lib guard on the
disposability rule.
"""
import glob
import hashlib
import pathlib
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parent.parent
K = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin"
SNAP = REPO / "build/bench/round948/ablate-snapshot"
FILES = ["Parser.kt", "Checker.kt", "Emitter.kt", "SyntaxKind.kt"]
EXPECTED_RAN = 38  # 33 in core + 5 in -project

STMT_ARMS = """        UsingKeyword -> if (isUsingDeclaration()) parseVariableStatement() else parseStatementFallback()
        AwaitKeyword -> if (isAwaitUsingDeclaration()) parseVariableStatement() else parseStatementFallback()
"""
STMT_USING_ARM = """        UsingKeyword -> if (isUsingDeclaration()) parseVariableStatement() else parseStatementFallback()
"""
SAME_LINE = """        return (isIdentifier() || token == SyntaxKind.OpenBrace) && !scanner.hasPrecedingLineBreak()"""
FOR_ARMS = """            UsingKeyword -> if (isUsingDeclaration(disallowOf = true)) {
                parseForInitializerDeclList()
            } else parseForInitializerExpression()
            AwaitKeyword -> if (isAwaitUsingDeclaration()) {
                parseForInitializerDeclList()
            } else parseForInitializerExpression()
"""
EMIT_ARMS = """            SyntaxKind.UsingKeyword -> "using"
            SyntaxKind.AwaitUsingKeyword -> "await using"
"""
TS1492 = """        if (decl.name is ObjectBindingPattern || decl.name is ArrayBindingPattern) {
            val end = bindingNameTextEnd(decl.name) ?: return
            emitUsingGrammarError(
                decl.name.pos, end,
                "'$keyword' declarations may not have binding patterns.", 1492,
            )
            return
        }
"""
TS1155_GUARD = """        if (decl.initializer != null) return
        val name = decl.name as? Identifier ?: return
"""
DISPOSE_LIB_GUARD = """        if (globalsForFile(spineFileName, "Disposable") == null) return
"""

# (file, anchor, replacement)
ARMS = {
    # ── THE FIX: the statement form does not exist (the pre-948 grammar) ──────
    "A1": ("Parser.kt", STMT_ARMS, ""),
    # ── THE BOUND: no lookahead, so every statement-position `using` is a head.
    #    `using` is an ordinary identifier — this is the whole of what keeps the
    #    arm additive.
    "A2": ("Parser.kt", STMT_USING_ARM, "        UsingKeyword -> parseVariableStatement()\n"),
    # ── THE BOUND: ASI.  Without the same-line test `using\\nx` is one declaration.
    "A3": ("Parser.kt", SAME_LINE,
           "        return (isIdentifier() || token == SyntaxKind.OpenBrace)"),
    # ── THE FIX: no `using` head in a for header.
    "A4": ("Parser.kt", FOR_ARMS, ""),
    # ── THE BOUND: `disallowOf`.  Without it `for (using of xs)` reads `of` as a
    #    declarator NAME instead of the loop operator.
    "A5": ("Parser.kt", "isUsingDeclaration(disallowOf = true)", "isUsingDeclaration(disallowOf = false)"),
    # ── THE FIX/BOUND: `await using` collapses onto the plain `using` flags value,
    #    so nothing downstream can tell the two heads apart.
    "A6": ("Parser.kt", "            SyntaxKind.AwaitUsingKeyword\n", "            SyntaxKind.UsingKeyword\n"),
    # ── THE FIX, emitter half: the head is rewritten to `var`, which silently
    #    DELETES the disposal.
    "A7": ("Emitter.kt", EMIT_ARMS, ""),
    # ── THE FIX, checker half, one diagnostic per arm.
    "A8": ("Checker.kt", TS1492, ""),
    "A9": ("Checker.kt", TS1155_GUARD, "        return\n"),
    "A10": ("Checker.kt", "                spineCheckUsingForInHead(node)\n", ""),
    "A11": ("Checker.kt", "                spineCheckUsingStatementModifiers(node)\n", ""),
    "A12": ("Checker.kt", "                spineCheckUsingDisposable(node)\n", ""),
    # ── THE BOUND: the disposability rule with no lib guard fires under the
    #    EMBEDDED lib, which declares neither `Disposable` nor `Symbol.dispose`.
    "A13": ("Checker.kt", DISPOSE_LIB_GUARD, ""),
}


def sha(p):
    return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()


def snapshot():
    SNAP.mkdir(parents=True, exist_ok=True)
    for f in FILES:
        shutil.copy2(K / f, SNAP / f)
    return {f: sha(SNAP / f) for f in FILES}


def restore(base):
    for f in FILES:
        shutil.copy2(SNAP / f, K / f)
        assert sha(K / f) == base[f], f"snapshot restore mismatch for {f}"


def run_arm(name):
    fname, old, new = ARMS[name]
    src = (SNAP / fname).read_text()
    if src.count(old) != 1:
        print(f"{name}: REFUSED — anchor occurs {src.count(old)} times in {fname}")
        return None
    (K / fname).write_text(src.replace(old, new))
    # round 855: prove the edit LANDED and is a real diff against the SNAPSHOT.
    d = subprocess.run(["diff", "-u", str(SNAP / fname), str(K / fname)],
                       capture_output=True, text=True)
    changed = sum(1 for l in d.stdout.splitlines()
                  if l[:1] in "+-" and l[:3] not in ("+++", "---"))
    if changed == 0:
        print(f"{name}: REFUSED — edit produced no diff")
        return None
    # Each module is invoked SEPARATELY: a bare `--tests` runs in every module and the
    # ones without a match fail the build outright (CLAUDE.md round 886).
    for mod, filt in ((":xemantic-typescript-compiler-core:jvmTest", "*UsingDeclarationsTest*"),
                      (":xemantic-typescript-compiler-project:jvmTest", "*UsingDeclarationShapeTest*")):
        subprocess.run(["./gradlew", mod, "--tests", filt],
                       cwd=REPO, capture_output=True, text=True)
    ran, red = 0, []
    for p in (glob.glob(str(REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest/*.xml"))
              + glob.glob(str(REPO / "xemantic-typescript-compiler-project/build/test-results/jvmTest/*.xml"))):
        r = ET.parse(p).getroot()
        if "UsingDeclaration" not in (r.get("name") or ""):
            continue
        for tc in r.iter("testcase"):
            ran += 1
            if tc.find("failure") is not None or tc.find("error") is not None:
                red.append(tc.get("name"))
    if ran != EXPECTED_RAN:
        print(f"{name}: REFUSED — ran {ran}, expected {EXPECTED_RAN} (diff lines={changed})")
        return None
    print(f"{name}: diff={changed} lines, ran={ran}, RED={len(red)}")
    for t in sorted(red):
        print(f"      - {t}")
    return red


def main():
    base = snapshot()
    print("snapshot sha256:", {f: base[f][:12] for f in FILES})
    results = {}
    try:
        for name in (sys.argv[1:] or list(ARMS)):
            for m in ("core", "project"):
                subprocess.run(["rm", "-rf",
                                str(REPO / f"xemantic-typescript-compiler-{m}/build/test-results/jvmTest")])
            results[name] = run_arm(name)
            restore(base)
    finally:
        restore(base)
        print("tree restored from snapshot, sha256 verified")
    sets = {k: set(v) for k, v in results.items() if v is not None}
    for a in sets:
        if not sets[a]:
            print(f"UNDISCRIMINATED: {a} reddens nothing")
    for a in sets:
        uniq = sets[a] - set().union(*[v for k, v in sets.items() if k != a] or [set()])
        if uniq:
            print(f"UNIQUE to {a}: {sorted(uniq)}")
    if sets:
        print(f"union of RED pins: {len(set().union(*sets.values()))}")


main()
