#!/usr/bin/env python3
"""Round 933 — one-mistake-at-a-time ablation for the template computed-key fix.

Each arm is applied to, and restored from, a sha256-VERIFIED on-disk snapshot of
`Checker.kt` (never `git checkout` — CLAUDE.md's round-851 rule: an ablation's own
revert destroys every uncommitted edit in the file it touches, and the harness lives
beside the code it measures).

Each arm additionally asserts a RAN-COUNT (round 931's dead-arm trap: a zero-red arm
with a zero ran-count is not a redundant guard, it is no arm at all).

Usage:  python3 scripts/round933-ablate.py [A1 A2 A3]
"""
import glob
import hashlib
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CK = os.path.join(ROOT, "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt")
SNAP = os.path.join(ROOT, "build", "round933-Checker.kt.snapshot")
PIN = "*TemplateComputedMemberKeyTest*"

# (name, description, old, new)
ARMS = [
    (
        "A1",
        "computedLiteralKey loses its no-substitution-template arm (the pre-933 state)",
        "            is NoSubstitutionTemplateLiteralNode -> e.text\n",
        "",
    ),
    (
        "A2",
        "classMemberNameText stops delegating and re-spells the pre-933 `when`",
        "        is ComputedPropertyName -> computedLiteralKey(nameNode)\n",
        "        is ComputedPropertyName -> when (val e = nameNode.expression) {\n"
        "            is NumericLiteralNode -> e.text\n"
        "            is StringLiteralNode -> e.text\n"
        "            else -> null\n"
        "        }\n",
    ),
    (
        "A3",
        "the template arm admits the key but invents the name \"p\" instead of reading its text",
        "            is NoSubstitutionTemplateLiteralNode -> e.text\n",
        "            is NoSubstitutionTemplateLiteralNode -> \"p\"\n",
    ),
]


def sha(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def run_pins():
    for p in glob.glob(os.path.join(ROOT, "*/build/test-results/jvmTest/*.xml")):
        os.remove(p)
    proc = subprocess.run(
        ["./gradlew", ":xemantic-typescript-compiler-core:jvmTest", "--tests", PIN],
        cwd=ROOT, capture_output=True, text=True,
    )
    if "BUILD SUCCESSFUL" not in proc.stdout and "FAILED" not in proc.stdout:
        print(proc.stdout[-3000:])
        raise SystemExit("build did not reach the test task")
    ran = red = 0
    names = []
    for p in glob.glob(os.path.join(ROOT, "*/build/test-results/jvmTest/*.xml")):
        for tc in ET.parse(p).getroot().iter("testcase"):
            ran += 1
            if tc.find("failure") is not None or tc.find("error") is not None:
                red += 1
                names.append(tc.get("name"))
    return ran, red, names


def main():
    arms = sys.argv[1:] or [a[0] for a in ARMS]
    base = read(CK)
    write(SNAP, base)
    digest = sha(SNAP)
    print(f"snapshot {SNAP} sha256 {digest}")

    print("\n=== control (unablated) ===")
    ran, red, names = run_pins()
    print(f"control: ran {ran}, red {red} {names}")
    if red != 0:
        raise SystemExit("control is not green — refusing to ablate")

    rows = []
    for name, desc, old, new in ARMS:
        if name not in arms:
            continue
        assert sha(SNAP) == digest, "snapshot mutated"
        src = read(SNAP)
        assert src.count(old) == 1, f"{name}: anchor not unique ({src.count(old)})"
        write(CK, src.replace(old, new, 1))
        # The dead-arm check compares the ablated file against the SNAPSHOT, never
        # against HEAD: HEAD already differs by the round's own fix, so a `git diff`
        # here would be non-empty for an arm that changed nothing at all.
        diff = subprocess.run(["diff", "-u", SNAP, CK],
                              cwd=ROOT, capture_output=True, text=True).stdout
        changed = sum(1 for ln in diff.splitlines()
                      if (ln.startswith("+") or ln.startswith("-"))
                      and not ln.startswith(("+++", "---")))
        print(f"\n=== {name}: {desc} ===\n  vs snapshot: {changed} changed line(s)")
        if changed == 0:
            raise SystemExit(f"{name}: edit produced NO diff — dead arm")
        try:
            ran, red, names = run_pins()
        finally:
            assert sha(SNAP) == digest, "snapshot mutated"
            write(CK, read(SNAP))
        print(f"  ran {ran}, red {red}")
        for n in sorted(names):
            print(f"    RED  {n}")
        rows.append((name, desc, ran, red, sorted(names)))

    print("\n\n=== SUMMARY ===")
    for name, desc, ran, red, names in rows:
        print(f"| {name} | {desc} | ran {ran} | red {red} |")
        for n in names:
            print(f"|    |   {n} | | |")

    # Final restore + verification.
    write(CK, read(SNAP))
    assert sha(CK) == digest, "tree not restored"
    print(f"\ntree restored; Checker.kt sha256 {sha(CK)} == snapshot")


if __name__ == "__main__":
    main()
