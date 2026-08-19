#!/usr/bin/env python3
"""Round 946 — (CHK.22): one deliberate mistake at a time, from a sha256-VERIFIED snapshot.

Round 807's law: a COMBINED ablation cannot attribute.  Each arm reverts exactly ONE
decision of the iterability check — the run gate, the optional-member test, the
`this`-return route, each of the three BAILS that make the check safe, the construct
gates, and the `.d.ts` skip — rebuilds, runs the pin classes QUALIFIED to the core module
(CLAUDE.md: a bare `--tests` filter fails the build in the modules that do not carry the
class), and records which pins reddened.

Two guards this round needs specifically.

(1) MOST OF THIS ROUND'S PINS ARE NEGATIVE, so most arms are expected to redden a NEGATIVE
    pin — an arm that removes a BAIL must make a legitimate program report TS2488.  That is
    the whole point: a green negative pin under its own arm would mean the pin cannot see
    the false positive it exists to forbid.

(2) Round 902's law: an arm can be DEAD rather than the pin blind, and a diff proves only
    that the edit LANDED.  Each arm asserts a per-arm RAN COUNT and a non-zero diff against
    the SNAPSHOT (never `git checkout`, round 851).

`Inv4SpineBatch2Test` rides along because it owns B438e — the TS2488/TS2504 walker whose
population this check deliberately does not enter — so an arm that starts double-emitting
there is visible rather than silent.
"""
from __future__ import annotations

import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path("/home/claude/git/xemantic-typescript-compiler")
CK = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
OUT = REPO / "build/bench/round946"
CLASSES = ["*IterableOperandProtocolTest*", "*Inv4SpineBatch2Test*",
           "*Inv4SpineBatch31Test*"]
# (find, replace) — each `find` must occur EXACTLY once in the snapshot.
ARMS: dict[str, tuple[str, str]] = {
    # A1 — THE FIX itself: the run gate never opens, so no position is ever checked.
    "A1": ("spineIterableOperandActive = options.defaultedTarget >= ScriptTarget.ES2015 &&",
           "spineIterableOperandActive = false && options.defaultedTarget >= ScriptTarget.ES2015 &&"),
    # A2 — the OPTIONAL-member test (tsc's `method && !(method.flags & Optional)`).
    "A2": ("if (isOptionalProperty(sym)) return ITER_FAIL_OPTIONAL",
           "if (false && isOptionalProperty(sym)) return ITER_FAIL_OPTIONAL"),
    # A3 — the `this`-return route, i.e. the only thing that makes the commonest broken
    #      shape (`[Symbol.iterator]() { return this }`) reachable at all.
    "A3": ("ret = iteratorMethodThisReturn(sym) ?: return null",
           "ret = (null as Type?) ?: return null"),
    # A4 — BAIL: an empty member table is not evidence of a missing `next`.
    "A4": ("if (retObj.properties.isNullOrEmpty()) return null",
           "if (false && retObj.properties.isNullOrEmpty()) return null"),
    # A5 — BAIL: a string index signature absorbs `next`.
    "A5": ("if (retObj.stringIndexInfo != null) return null",
           "if (false && retObj.stringIndexInfo != null) return null"),
    # A6 — the zero-argument signature filter, i.e. B438e's population boundary.
    "A6": ("val zeroArg = sigs.filter { it.minArgumentCount == 0 }",
           "val zeroArg = sigs.filter { true }"),
    # A7 — THE BOUND of the optional-`next` refusal: adopt tsc's FULL rule
    #      (`next == null || isOptionalProperty(next)`).  Unlike A2-A6 this arm ADDS a
    #      diagnostic rather than removing one, because what is being pinned is a
    #      deliberate refusal — the pin it must redden is a negative.
    "A7": ("return if (next == null) ITER_FAIL_NO_NEXT else null",
           "return if (next == null || isOptionalProperty(next)) ITER_FAIL_NO_NEXT else null"),
    # A8 — the LIB half of the run gate: `noLib` / an es5-only `@lib` must leave the
    #      position to the array-like leg (TS2495 / TS2461).
    "A8": ("!options.noLib && !spineForOfNonIterableActive",
           "true || (!options.noLib && !spineForOfNonIterableActive)"),
    # A9 — the ARRAY-LITERAL gate on the spread construct: a CALL-argument spread is a
    #      different `IterationUse` with a different diagnostic family.
    "A9": ("if ((node as NodeBase).parent is ArrayLiteralExpression) {",
           "if (true) {"),
    # A10 — the for-AWAIT gate: an async iteration position is TS2504's, not TS2488's.
    "A10": ("if (!node.awaitModifier) spineCheckIterableOperand(node.expression)",
            "if (true) spineCheckIterableOperand(node.expression)"),
    # A11 — the `.d.ts` skip.
    "A11": ("if (!spineIterableOperandActive || spineIsDts) return",
            "if (!spineIterableOperandActive) return"),
}


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def snap() -> Path:
    return OUT / "Checker.kt.after"


def restore() -> None:
    CK.write_bytes(snap().read_bytes())
    assert sha(CK) == sha(snap())


def run_arm(name: str, expected_ran: int) -> str:
    find, repl = ARMS[name]
    text = snap().read_text(encoding="utf8")
    if text.count(find) != 1:
        return f"{name}: REFUSED — anchor matched {text.count(find)} site(s)"
    CK.write_text(text.replace(find, repl), encoding="utf8")
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
    if expected_ran and ran != expected_ran:
        return f"{name}: REFUSED — ran {ran}, expected {expected_ran}"
    return f"{name}: ran {ran}, RED {red}\n      " + "\n      ".join(sorted(names))


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    if not snap().exists():
        snap().write_bytes(CK.read_bytes())
    assert sha(CK) == sha(snap()), "Checker.kt is not at the AFTER snapshot"
    print(f"snapshot Checker.kt sha256 {sha(snap())}")
    args = [a for a in sys.argv[1:] if not a.startswith("--ran=")]
    ran_opt = [a for a in sys.argv[1:] if a.startswith("--ran=")]
    expected = int(ran_opt[0].split("=")[1]) if ran_opt else 0
    arms = args or list(ARMS)
    for a in arms:
        if a not in ARMS:
            print(f"{a}: unknown arm")
            return 2
        print(run_arm(a, expected), flush=True)
    print("complete; tree restored")
    return 0


if __name__ == "__main__":
    sys.exit(main())
