#!/usr/bin/env python3
"""Round 945 (CHK.9) — one deliberate mistake at a time, from a sha256-VERIFIED snapshot.

Four arms over the index-signature parameter-type rule: the intersection ARM, the
resolution TRIGGER that offers an `IntersectionType` node to the type engine at all, the
GENERIC test that has to read the AST, and the BOUND — reading tsc's
`some(types, isValidIndexKeyType)` as `every`, which is the plausible misreading and is
what an intersection with an object constituent (i.e. every branded string) distinguishes.

Round 902's law: an arm can be DEAD rather than the pin blind. Each arm asserts a per-arm
RAN COUNT and a non-zero diff against the SNAPSHOT (never `git checkout`, round 851).
"""
from __future__ import annotations

import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path("/home/claude/git/xemantic-typescript-compiler")
CK = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
OUT = REPO / "build/bench/round945b"
CLASSES = ["*IndexSignatureParameterTypeTest*"]
EXPECTED_RAN = 13

INTERSECTION_ARM = """            t is Type.Intersection -> when {
                t.types.any { it is Type.TypeParam } -> 1337
                t.types.any { classifyIndexParamType(it, depth + 1) == 0 } -> 0
                else -> 1268
            }"""
TRIGGER = """                    (pType is TypeReference || pType is UnionType ||
                        pType is IntersectionType || pType is ParenthesizedType)) {"""
GENERIC = """            val isGeneric = indexParamMentionsOuterTypeParam(pType, outerTypeParamNames)"""

ARMS: dict[str, tuple[str, str]] = {
    # B1 — the intersection ARM goes away, so every branded string falls to the `else`
    #      branch and is TS1268 again.  The whole first half of the fix.
    "B1": (INTERSECTION_ARM, """            t is Type.Intersection -> 1268"""),
    # B2 — the resolution TRIGGER narrows back: an `IntersectionType` NODE is never offered
    #      to the type engine, so the arm above exists and is unreachable for a syntactic
    #      intersection.  Separates "we cannot classify it" from "we never look".
    "B2": (TRIGGER, """                    (pType is TypeReference || pType is UnionType)) {"""),
    # B3 — the GENERIC test goes back to a bare `TypeReference`, which is the CODE half:
    #      `[key: T | number]` becomes TS1268 where pristine says TS1337.
    "B3": (GENERIC, """            val isGeneric = pType is TypeReference && pType.typeName is Identifier &&
                outerTypeParamNames.contains((pType.typeName).text)"""),
    # B4 — THE BOUND: `some` read as `every`.  It satisfies every "an intersection resolves"
    #      pin and refuses exactly the branded shape the rule exists for, so a pin that only
    #      asserted "an intersection of TEMPLATE LITERALS works" would be blind to it.
    "B4": (INTERSECTION_ARM, """            t is Type.Intersection -> when {
                t.types.any { it is Type.TypeParam } -> 1337
                t.types.all { classifyIndexParamType(it, depth + 1) == 0 } -> 0
                else -> 1268
            }"""),
}


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def snap() -> Path:
    return OUT / "Checker.kt.after"


def restore() -> None:
    CK.write_bytes(snap().read_bytes())
    assert sha(CK) == sha(snap())


def run_arm(name: str) -> str:
    old, new = ARMS[name]
    src = snap().read_text(encoding="utf8")
    if src.count(old) != 1:
        return f"{name}: REFUSED — anchor occurs {src.count(old)} times"
    CK.write_text(src.replace(old, new, 1), encoding="utf8")
    if sha(CK) == sha(snap()):
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
        return f"{name}: REFUSED — ran {ran}, expected {EXPECTED_RAN}"
    return f"{name}: ran {ran}, RED {red}\n      " + "\n      ".join(sorted(names))


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    if not snap().exists():
        snap().write_bytes(CK.read_bytes())
    assert sha(CK) == sha(snap()), "Checker.kt is not at the AFTER snapshot"
    print(f"snapshot Checker.kt sha256 {sha(snap())}")
    for a in sys.argv[1:] or list(ARMS):
        if a not in ARMS:
            print(f"{a}: unknown arm")
            return 2
        print(run_arm(a), flush=True)
    print("complete; tree restored")
    return 0


if __name__ == "__main__":
    sys.exit(main())
