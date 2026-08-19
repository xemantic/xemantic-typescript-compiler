#!/usr/bin/env python3
"""Round 945 (CHK.19) — one deliberate mistake at a time, from a sha256-VERIFIED snapshot.

Four arms over the block-scoped type-alias consult: the CONSULT itself, the name GATE, the
`scope.symbols`-only rule (round 748's invariant, ablated to `existing` — the INV.3 minefield
this design exists to stay out of), and the ancestor WALK reduced to the node's own scope.

Round 902's law: an arm can be DEAD rather than the pin blind.  Each arm asserts a per-arm
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
OUT = REPO / "build/bench/round945c"
CLASSES = ["*BlockScopedTypeAliasArityTest*"]
EXPECTED_RAN = 9

CONSULT = """        val info = lexicalTypeAliasArity(typeRef, name)
            ?: getTypeParamInfo(name, forTypePosition) ?: return"""
GATE = """        if (name !in lexicalBlockScopedTypeAliasNames) return null"""
SYMBOLS = """                val sym = scopes[id]?.symbols?.get(name)
                if (sym != null && sym.flags.hasAny(SymbolFlags.TypeAlias)) {"""
WALK = """            if (cur is SourceFile) break
            cur = cur.parent
        }
        return null
    }

    private fun computeAllEnumValues() {"""

ARMS: dict[str, tuple[str, str]] = {
    # C1 — THE FIX: the consult is gone and `getTypeParamInfo`'s whole-program NAME scan
    #      answers again, i.e. the lib's `Omit` for a body-scoped one.
    "C1": (CONSULT, """        val info = getTypeParamInfo(name, forTypePosition) ?: return"""),
    # C2 — the name GATE is inverted so the consult can never fire.  Distinct from C1: it
    #      leaves the walk in the binary and shows the gate is what selects, not what guards
    #      (a gate computed from the WRONG flag would read exactly like this).
    "C2": (GATE, """        if (name in lexicalBlockScopedTypeAliasNames) return null"""),
    # C3 — round 748's INVARIANT: read `LexicalScope.existing` (the ALIAS of the main
    #      binder's table) instead of `symbols`.  `declareLexical` skips any name the main
    #      binder bound, so `symbols` can only hold declarations the conventional tables do
    #      NOT have; `existing` puts every INV.3 name back in play.
    "C3": (SYMBOLS, """                val sym = scopes[id]?.existing?.get(name)
                if (sym != null && sym.flags.hasAny(SymbolFlags.TypeAlias)) {"""),
    # C4 — the ancestor WALK is cut to the reference's own node, so a body-scoped alias is
    #      only found when the reference IS the scope owner — i.e. never.
    "C4": (WALK, """            break
            cur = cur.parent
        }
        return null
    }

    private fun computeAllEnumValues() {"""),
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
        return f"{name}: REFUSED — ran {ran}, expected {EXPECTED_RAN} (build may have failed)"
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
