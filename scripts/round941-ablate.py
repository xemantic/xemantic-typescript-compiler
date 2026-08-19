#!/usr/bin/env python3
"""Round 941 — one deliberate mistake at a time, from a sha256-VERIFIED snapshot.

Round 807's law: a COMBINED ablation cannot attribute. Each arm reverts exactly one
decision of round 941, rebuilds, runs the two pin classes QUALIFIED to the core module
(CLAUDE.md: a bare `--tests` filter fails the build in the four modules that do not carry
the class), and records which pins reddened.

TWO ARMS PER FIX BY DESIGN: one removes the fix, one removes its BOUND — a "this is now
silent" pin cannot tell a correct refusal from a disabled check, and round 941's TS2376
bound (a COMPUTED member name is still a `this` reference) was in fact wrong on the first
cut and was caught only by the sweep, not by any pin.

Every arm asserts a per-arm RAN COUNT, so a build that died or a filter that matched
nothing reads as a FAILURE rather than as a clean sweep (round 856).

The tree is restored from the snapshot in the FOREGROUND after every arm, and never with
`git checkout` (round 851: that also destroys uncommitted work in the same file).
"""
from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path("/home/claude/git/xemantic-typescript-compiler")
CK = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
SNAP = REPO / "build/bench/round941/Checker.after.kt"
OUT = REPO / "build/bench/round941"
CLASSES = "*SuperCallNotFirstStatement*|*PrivateIdentifierTargetGate*"
EXPECTED_RAN = 21   # 16 TS2376 pins + 5 TS18028 pins

ARMS: dict[str, tuple[str, str]] = {
    # A1 — the fix itself: require `super()` to be the first non-prologue statement again.
    "A1": (
        """                                    var superCallStatement: Statement? = null
                                    for (s in member.body.statements) {
                                        if (s is ExpressionStatement && isSuperCallStatementExpression(s.expression)) {
                                            superCallStatement = s
                                            break
                                        }
                                        if (nodeImmediatelyReferencesSuperOrThis(s)) break
                                    }""",
        """                                    val firstNonPrologue = member.body.statements.firstOrNull { s ->
                                        !(s is ExpressionStatement && s.expression is StringLiteralNode)
                                    }
                                    val superCallStatement: Statement? =
                                        if (firstNonPrologue is ExpressionStatement &&
                                            isSuperCallStatementExpression(firstNonPrologue.expression)
                                        ) firstNonPrologue else null""",
    ),
    # A2 — the fix's BOUND: skip EVERY member name, computed ones included.
    "A2": (
        """            is PropertyAssignment -> node.name as? Identifier
            is MethodDeclaration -> node.name as? Identifier
            is GetAccessor -> node.name as? Identifier
            is SetAccessor -> node.name as? Identifier""",
        """            is PropertyAssignment -> node.name
            is MethodDeclaration -> node.name
            is GetAccessor -> node.name
            is SetAccessor -> node.name""",
    ),
    # A3 — the TS18028 fix: read the raw target again.
    "A3": (
        "        if (options.targetExplicitlySet && options.target <= ScriptTarget.ES5) {",
        "        if (options.target <= ScriptTarget.ES5) {",
    ),
    # A4 — the TS18028 BOUND: use effectiveTarget, which maps an explicit ES5 up to ES2015.
    "A4": (
        "        if (options.targetExplicitlySet && options.target <= ScriptTarget.ES5) {",
        "        if (options.effectiveTarget < ScriptTarget.ES2015) {",
    ),
}


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def restore() -> None:
    CK.write_bytes(SNAP.read_bytes())
    assert sha(CK) == sha(SNAP)


def run_arm(name: str) -> str:
    old, new = ARMS[name]
    src = SNAP.read_text()
    if src.count(old) != 1:
        return f"{name}: REFUSED — anchor occurs {src.count(old)} times"
    CK.write_text(src.replace(old, new, 1))
    diff = subprocess.run(["git", "diff", "--shortstat", "--", str(CK)],
                          cwd=REPO, capture_output=True, text=True).stdout.strip()
    subprocess.run(["rm", "-rf", str(REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest")])
    log = OUT / f"ablate.{name}.log"
    proc = subprocess.run(
        ["./gradlew", ":xemantic-typescript-compiler-core:jvmTest", "--tests", CLASSES],
        cwd=REPO, capture_output=True, text=True)
    log.write_text(proc.stdout + proc.stderr)
    if "BUILD FAILED" in proc.stdout and "compileKotlinJvm" in proc.stdout and "FAILED" in proc.stdout.split("BUILD FAILED")[0][-400:]:
        pass  # a test failure also prints BUILD FAILED; the XML decides
    ran = red = 0
    names: list[str] = []
    for x in (REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest").glob("*.xml"):
        r = ET.parse(x).getroot()
        ran += int(r.get("tests", 0))
        for tc in r.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                red += 1
                names.append(tc.get("name", "?"))
    restore()
    if ran != EXPECTED_RAN:
        return f"{name}: REFUSED — ran {ran}, expected {EXPECTED_RAN} ({diff})"
    return f"{name}: ran {ran}, RED {red}  [{diff}]\n      " + "\n      ".join(sorted(names))


def main() -> int:
    assert sha(CK) == sha(SNAP), "tree is not at the AFTER snapshot"
    print(f"snapshot sha256 {sha(SNAP)}")
    arms = sys.argv[1:] or list(ARMS)
    for a in arms:
        print(run_arm(a), flush=True)
    assert sha(CK) == sha(SNAP), "tree not restored"
    print("tree restored to the AFTER snapshot")
    return 0


if __name__ == "__main__":
    sys.exit(main())
