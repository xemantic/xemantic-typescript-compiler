#!/usr/bin/env python3
"""Round 944 — one deliberate mistake at a time, from a sha256-VERIFIED snapshot.

Round 807's law: a COMBINED ablation cannot attribute.  Each arm reverts exactly ONE
decision of round 944 (the whole fix, its BOUND, the availability GATE alone, the lib SET
alone), rebuilds, runs the pin class QUALIFIED to the core module (CLAUDE.md: a bare
`--tests` filter fails the build in the four modules that do not carry the class), and
records which pins reddened.

Round 902's law on top of that: an arm can be DEAD rather than the pin blind, and
`git diff --shortstat` proves only that the edit LANDED.  Every arm here inverts a branch
the pins' own fixtures execute, and the per-arm RAN COUNT assertion makes a dead build or
an empty filter read as a FAILURE rather than as a clean sweep (round 856).

This round's change spans THREE files, so the snapshot is a dict — restoring one file and
leaving another ablated would be a combined arm wearing a single arm's name.  The tree is
restored in the FOREGROUND after every arm, and never with `git checkout` (round 851).
"""
from __future__ import annotations

import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path("/home/claude/git/xemantic-typescript-compiler")
SRC = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin"
FILES = {"opts": SRC / "CompilerOptions.kt", "ck": SRC / "Checker.kt", "libs": SRC / "RealLibs.kt"}
OUT = REPO / "build/bench/round944"
CLASSES = ["*LibAvailabilityDefaultTarget*"]
EXPECTED_RAN = 14

LIB_TARGET = """    val libTarget: ScriptTarget
        get() = if (targetExplicitlySet) target else ScriptTarget.ES2024"""

ARMS: dict[str, list[tuple[str, str, str]]] = {
    # A1 — THE FIX: lib availability reads the RAW target again, i.e. an unset target is
    #      ES3 and a project with no `target` gets the es5 lib and its diagnostics.
    "A1": [("opts", LIB_TARGET, """    val libTarget: ScriptTarget
        get() = target""")],
    # A2 — THE BOUND: `effectiveTarget` instead, which maps an EXPLICIT es3/es5 UP to
    #      ES2015.  Separates "an unset target is the latest" from "an explicit target
    #      answers for itself": a pin that only asserted the unset side would be satisfied
    #      by this, and every genuine es5 TS2550/TS2583 would be gone.
    "A2": [("opts", LIB_TARGET, """    val libTarget: ScriptTarget
        get() = effectiveTarget""")],
    # A3 — the availability GATE alone goes back to the raw target; the lib SET keeps the
    #      fix.  The program is then given the es2024 lib and told its members are not
    #      available at this target.
    "A3": [("ck", "        if (options.lib.isEmpty()) return options.libTarget >= intro\n        val introNum = if (intro == ScriptTarget.ESNext) 9999",
                  "        if (options.lib.isEmpty()) return options.target >= intro\n        val introNum = if (intro == ScriptTarget.ESNext) 9999"),
           ("ck", "        if (options.lib.isEmpty()) return options.libTarget >= intro\n        val introNum = intro.name.removePrefix(\"ES\").toIntOrNull() ?: return true",
                  "        if (options.lib.isEmpty()) return options.target >= intro\n        val introNum = intro.name.removePrefix(\"ES\").toIntOrNull() ?: return true")],
    # A4 — the lib SET alone goes back to the raw target; the GATE keeps the fix.  Every
    #      later-lib name is then declared available and is not in the loaded lib, so the
    #      diagnostic becomes TS2304 instead of TS2583 — which no "is there a TS2583" pin
    #      can see, and is exactly why the end-to-end pins assert an EMPTY diagnostic list.
    "A4": [("ck", "        realLibUnknownNames = RealLibResolver.resolve(libNames, options.libTarget).unknownNames\n        val results = RealLibSnapshots.bindLibFiles(libNames, options.libTarget, options)",
                  "        realLibUnknownNames = RealLibResolver.resolve(libNames, options.target).unknownNames\n        val results = RealLibSnapshots.bindLibFiles(libNames, options.target, options)"),
           ("libs", "        parsedLibFiles(options.lib.ifEmpty { null }, options.libTarget)",
                    "        parsedLibFiles(options.lib.ifEmpty { null }, options.target)")],
}


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def snap_path(key: str) -> Path:
    return OUT / f"{FILES[key].name}.after"


def restore() -> None:
    for k, f in FILES.items():
        f.write_bytes(snap_path(k).read_bytes())
        assert sha(f) == sha(snap_path(k))


def run_arm(name: str) -> str:
    edits = ARMS[name]
    changed = 0
    for key, old, new in edits:
        src = snap_path(key).read_text()
        if src.count(old) != 1:
            restore()
            return f"{name}: REFUSED — anchor occurs {src.count(old)} times in {FILES[key].name}"
        FILES[key].write_text(src.replace(old, new, 1))
        diff = subprocess.run(["diff", "-U0", str(snap_path(key)), str(FILES[key])],
                              capture_output=True, text=True).stdout
        changed += sum(1 for l in diff.splitlines()
                       if l[:1] in "+-" and l[:3] not in ("+++", "---"))
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
    for k, f in FILES.items():
        if not snap_path(k).exists():
            snap_path(k).write_bytes(f.read_bytes())
        assert sha(f) == sha(snap_path(k)), f"{f.name} is not at the AFTER snapshot"
    for k in FILES:
        print(f"snapshot {FILES[k].name} sha256 {sha(snap_path(k))}")
    arms = sys.argv[1:] or list(ARMS)
    for a in arms:
        if a not in ARMS:
            print(f"{a}: unknown arm")
            return 2
        print(run_arm(a), flush=True)
    print("complete; tree restored")
    return 0


if __name__ == "__main__":
    sys.exit(main())
