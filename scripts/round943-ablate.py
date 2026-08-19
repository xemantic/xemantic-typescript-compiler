#!/usr/bin/env python3
"""Round 943 — one deliberate mistake at a time, from a sha256-VERIFIED snapshot.

Round 807's law: a COMBINED ablation cannot attribute. Each arm reverts exactly ONE
decision of round 943, rebuilds, runs the two pin classes QUALIFIED to the core module
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
OUT = REPO / "build/bench/round943"
SNAP = OUT / "Checker.after.kt"
CLASSES = ["*AliasTypeParamShadowedInConstraintCheck*"]
EXPECTED_RAN = 13   # the (CHK.16) pin class

ARMS: dict[str, tuple[str, str]] = {
    # A1 — THE FIX: the alias's own type parameters go back to being pushed only for an
    #      `ImportType` body, i.e. invisible to the walker for every other alias.
    "A1": (
        """        if (tps.isNullOrEmpty()) {
            body()
            return
        }""",
        """        if (tps.isNullOrEmpty() || true) {
            body()
            return
        }""",
    ),
    # A2 — THE BOUND: the parameters are pushed but their CONSTRAINTS are not resolved,
    #      so every alias parameter looks unconstrained and fails its callee's constraint.
    #      Separates "the parameter is in scope" from "its constraint is honoured": a pin
    #      that only asserted silence could be satisfied by the parameter resolving to
    #      anything at all.
    "A2": (
        """                tp.constraint?.let { p.constraint = getTypeFromTypeNode(it) }
                scope[tp.name.text] = p""",
        """                scope[tp.name.text] = p""",
    ),
    # A3 — the CLASS and INTERFACE branches lose the scope again (the alias keeps it), which
    #      is the state the first cut of this round shipped and the pin class caught.
    "A3": (
        """                is ClassDeclaration -> withDeclTypeParamScope(stmt.typeParameters) {""",
        """                is ClassDeclaration -> run {""",
    ),
    "A4": (
        """                is InterfaceDeclaration -> withDeclTypeParamScope(stmt.typeParameters) {""",
        """                is InterfaceDeclaration -> run {""",
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
