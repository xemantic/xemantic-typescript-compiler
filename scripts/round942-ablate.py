#!/usr/bin/env python3
"""Round 942 — one deliberate mistake at a time, from a sha256-VERIFIED snapshot.

Round 807's law: a COMBINED ablation cannot attribute. Each arm reverts exactly ONE
decision of round 942, rebuilds, runs the two pin classes QUALIFIED to the core module
(CLAUDE.md: a bare `--tests` filter fails the build in the four modules that do not carry
the class), and records which pins reddened.

Round 902's law on top of that: an arm can be DEAD rather than the pin blind, and
`git diff --shortstat` proves only that the edit LANDED. Every arm's mistake is reached
by construction here — each one deletes or inverts a branch that the pins' own fixtures
execute — and the per-arm RAN COUNT assertion makes a dead build or an empty filter read
as a FAILURE rather than as a clean sweep (round 856).

The tree is restored from the snapshot in the FOREGROUND after every arm, and never with
`git checkout` (round 851: that also destroys uncommitted work in the same file).
"""
from __future__ import annotations

import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path("/home/claude/git/xemantic-typescript-compiler")
CK = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
OUT = REPO / "build/bench/round942"
SNAP = OUT / "Checker.after.kt"
CLASSES = ["*ElementAccessDiscriminantNarrowing*", "*SymbolHasInstanceNarrowing*"]
EXPECTED_RAN = 21   # 12 (CHK.11) + 9 (CHK.12)

ARMS: dict[str, tuple[str, str]] = {
    # ---- (CHK.11) ----
    # A1 — the switch discriminant reader stops accepting a BRACKET segment.
    "A1": (
        """            '[' -> if (!subjectPath.endsWith("]")) null else
                subjectPath.substring(name.length + 1, subjectPath.length - 1)
                    .takeIf { it.isNotEmpty() && '.' !in it && '[' !in it && ']' !in it }""",
        """            '[' -> null""",
    ),
    # A2 — an element access stops flow-narrowing its UNION RECEIVER.
    "A2": (
        """            if (rawObjectType is Type.Union && getReferencePath(expr.expression) != null) {
                getNarrowedTypeForReference(rawObjectType, expr.expression)
            } else rawObjectType
        if (objectType === anyType || objectType === errorType) return anyType""",
        """            rawObjectType
        if (objectType === anyType || objectType === errorType) return anyType""",
    ),
    # A3 — a spellable string index stops normalising onto the dotted segment.
    "A3": (
        """                if (isIdentifierSpellableName(idx)) "$receiverPath.$idx"
                else "$receiverPath[$idx]\"""",
        """                "$receiverPath[$idx]\"""",
    ),
    # A4 — the exhaustive-switch key reader stops accepting an ELEMENT-ACCESS discriminant.
    # NOTE: `if (false && expr is …)` is NOT usable as the mistake — Kotlin drops the smart
    # cast and the arm stops COMPILING, which the driver reports as `ran 0` (round 942).
    "A4": (
        """        if (expr is ElementAccessExpression && !expr.questionDotToken) {
            val idxName = when (val arg = unwrapParensExpr(expr.argumentExpression)) {
                is StringLiteralNode -> arg.text
                is NumericLiteralNode -> arg.text
                else -> null
            }
            if (!idxName.isNullOrEmpty()) {
                requiredUnionDiscriminantKeys(expr.expression, idxName)?.let { return it }
            }
        }
""",
        """""",
    ),
    # A5 — the exhaustiveness receiver walk goes back to ONE dotted segment (round 470).
    "A5": (
        """                is ElementAccessExpression -> {
                    if (c.questionDotToken) return null
                    segs.add(
                        when (val a = unwrapParensExpr(c.argumentExpression)) {
                            is StringLiteralNode -> a.text
                            is NumericLiteralNode -> a.text
                            else -> return null
                        }
                    )
                    cur = c.expression
                }""",
        """                is ElementAccessExpression -> return null""",
    ),
    # A9 — an element access stops flow-narrowing its own union RESULT (the 17.34d half).
    # NOTE: the `if (raw is Type.Union …)` block ALONE is not a usable anchor — it occurs
    # verbatim in [getTypeOfPropertyAccess] too, and the driver refuses a 2-hit anchor
    # rather than ablating the wrong function (round 942).
    "A9": (
        """        // not be read by `o["a"]`, however faithfully the paths now normalise.
        if (raw is Type.Union && getReferencePath(expr) != null) {""",
        """        // not be read by `o["a"]`, however faithfully the paths now normalise.
        if (raw is Type.Intersection && getReferencePath(expr) != null) {""",
    ),
    # ---- (CHK.12) ----
    # A6 — the `[Symbol.hasInstance]` leg is removed entirely.
    "A6": (
        """        symbolHasInstancePredicateType(ctorType)?.let { target ->
            return target.takeIf { it !== anyType && it !== errorType && it !== unknownType }
        }""",
        """""",
    ),
    # A7 — a UNION candidate stops being distributed (round 425's form for every shape).
    "A7": (
        """                val candidates = (classType as? Type.Union)?.types
                if (candidates != null) {""",
        """                val candidates: List<Type>? = null
                if (candidates != null) {""",
    ),
    # A8 — the leg's BOUND: a wide predicate target falls THROUGH to prototype/ctors
    #      instead of deciding, which silences pristine's own `value is any` rows.
    "A8": (
        """        symbolHasInstancePredicateType(ctorType)?.let { target ->
            return target.takeIf { it !== anyType && it !== errorType && it !== unknownType }
        }""",
        """        symbolHasInstancePredicateType(ctorType)
            ?.takeIf { it !== anyType && it !== errorType && it !== unknownType }
            ?.let { return it }""",
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
    # Round 855: the diff is against the SNAPSHOT, never against HEAD.
    diff = subprocess.run(["diff", "-U0", str(SNAP), str(CK)],
                          capture_output=True, text=True).stdout
    changed = sum(1 for l in diff.splitlines() if l[:1] in "+-" and l[:3] not in ("+++", "---"))
    if changed == 0:
        restore()
        return f"{name}: REFUSED — the edit produced no diff against the snapshot"
    subprocess.run(["rm", "-rf",
                    str(REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest")])
    proc = subprocess.run(
        ["./gradlew", ":xemantic-typescript-compiler-core:jvmTest"]
        + [a for c in CLASSES for a in ("--tests", c)],
        cwd=REPO, capture_output=True, text=True)
    (OUT / f"ablate.{name}.log").write_text(proc.stdout + proc.stderr)
    ran = red = 0
    names: list[str] = []
    res = REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest"
    for x in sorted(res.glob("*.xml")) if res.exists() else []:
        r = ET.parse(x).getroot()
        ran += int(r.get("tests", 0))
        for tc in r.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                red += 1
                names.append(tc.get("name", "?"))
    restore()
    if ran != EXPECTED_RAN:
        return f"{name}: REFUSED — ran {ran}, expected {EXPECTED_RAN} (diff lines {changed})"
    return (f"{name}: ran {ran}, RED {red}  [diff lines {changed}]\n      "
            + "\n      ".join(sorted(names)))


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    if not SNAP.exists():
        SNAP.write_bytes(CK.read_bytes())
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
