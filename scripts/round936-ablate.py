#!/usr/bin/env python3
"""Round 936 — one-mistake-at-a-time ablation for the QUALIFIED / TYPE-ANNOTATION /
WELL-KNOWN-SYMBOL routes of late-bound computed keys.

Same protocol as round 935: every arm is applied to, and restored from, a sha256-VERIFIED
on-disk snapshot of `Checker.kt` (never `git checkout` — CLAUDE.md's round-851 rule), the
diff is taken against the SNAPSHOT rather than HEAD (a `git diff` is non-empty for a dead
arm, because HEAD already carries the round's own fix), and every arm asserts a RAN-COUNT
so a build that never reached the tests cannot read as "the mistake changed nothing"
(round 808). Both late-binding pin classes run, so an arm that reddens round 935's rows
as well as this round's is visible rather than hidden by the filter.

Usage:  python3 scripts/round936-ablate.py [A1 .. A11]
"""
import glob
import hashlib
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CK = os.path.join(ROOT, "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt")
SNAP = os.path.join(ROOT, "build", "round936-Checker.kt.snapshot")
PINS = ["*LateBoundQualifiedKeyTest*", "*LateBoundComputedKeyTest*"]

ARMS = [
    (
        "A1",
        "the QUALIFIED route is off — `NS.K` is a dynamic key again",
        "        if (e is PropertyAccessExpression) return qualifiedLateBoundKeyValue(e, hops)\n",
        "        if (e is PropertyAccessExpression && hops < 0) return qualifiedLateBoundKeyValue(e, hops)\n",
    ),
    (
        "A2",
        "the ENUM leaf of a namespace descent is dropped",
        "                is EnumDeclaration ->\n"
        "                    if (remaining == 2 && st.name.text == path[from]) {\n"
        "                        enumMemberValueFromDecl(st, path[from + 1])?.let { return it }\n"
        "                    }\n",
        "                is EnumDeclaration -> {}\n",
    ),
    (
        "A3",
        "namespace MERGING is lost — the first name-matching block decides",
        "                    resolveDottedInStatements(body.statements, path, from + np.size, hops + 1)\n"
        "                        ?.let { return it }\n",
        "                    return resolveDottedInStatements(body.statements, path, from + np.size, hops + 1)\n",
    ),
    (
        "A4",
        "a DOTTED namespace name is truncated to its head — `namespace A.B` reads as `A`",
        "        while (cur is PropertyAccessExpression) {\n"
        "            out.add(cur.name.text)\n"
        "            cur = cur.expression\n"
        "        }\n",
        "        while (cur is PropertyAccessExpression) {\n"
        "            cur = cur.expression\n"
        "        }\n",
    ),
    (
        "A5",
        "the template-literal TYPE annotation route is dropped",
        "            is TemplateLiteralType -> templateLiteralTypeFixedText(t)\n",
        "            is TemplateLiteralType -> null\n",
    ),
    (
        "A6",
        "the `${` discriminator is dropped — a SUBSTITUTING template type binds too",
        r'        if (inner.contains("\${") || inner.contains(' + "'\\\\')) return null\n",
        "        if (inner.contains('\\\\')) return null\n",
    ),
    (
        "A7",
        "the TYPE-ALIAS hop is dropped — only a direct annotation resolves",
        "                if (!t.typeArguments.isNullOrEmpty()) return null\n",
        "                if (t.typeArguments == null || !t.typeArguments.isNullOrEmpty()) return null\n",
    ),
    (
        "A8",
        "the WELL-KNOWN-SYMBOL excess naming is dropped (the pre-936 boundary)",
        "            computedLiteralKey(n) ?: lateBoundComputedKeyName(n) ?: wellKnownSymbolKey(n)\n",
        "            computedLiteralKey(n) ?: lateBoundComputedKeyName(n)\n",
    ),
    (
        "A9",
        "the local-`Symbol`-shadow guard is dropped",
        "        if (lateBindResolveVarDecl(recv) != null) return null\n",
        "",
    ),
    (
        "A10",
        "the well-known route is widened to ANY dotted receiver — round 934's exclusion undone",
        '        if (recv.text != "Symbol") return null\n',
        "",
    ),
    (
        "A11",
        "the `const` guard is dropped — a widened namespace `let` late-binds",
        "        if (list?.flags == SyntaxKind.ConstKeyword) {\n",
        "        if (list != null) {\n",
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
    args = ["./gradlew", ":xemantic-typescript-compiler-core:jvmTest"]
    for p in PINS:
        args += ["--tests", p]
    proc = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
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

    print("\n=== control (unablated) ===", flush=True)
    ran, red, names = run_pins()
    print(f"control: ran {ran}, red {red} {names}", flush=True)
    if red != 0:
        raise SystemExit("control is not green — refusing to ablate")
    control_ran = ran

    rows = []
    for name, desc, old, new in ARMS:
        if name not in arms:
            continue
        assert sha(SNAP) == digest, "snapshot mutated"
        src = read(SNAP)
        assert src.count(old) == 1, f"{name}: anchor not unique ({src.count(old)})"
        write(CK, src.replace(old, new, 1))
        diff = subprocess.run(["diff", "-u", SNAP, CK],
                              cwd=ROOT, capture_output=True, text=True).stdout
        changed = sum(1 for ln in diff.splitlines()
                      if (ln.startswith("+") or ln.startswith("-"))
                      and not ln.startswith(("+++", "---")))
        print(f"\n=== {name}: {desc} ===\n  vs snapshot: {changed} changed line(s)", flush=True)
        if changed == 0:
            raise SystemExit(f"{name}: edit produced NO diff — dead arm")
        try:
            ran, red, names = run_pins()
        finally:
            assert sha(SNAP) == digest, "snapshot mutated"
            write(CK, read(SNAP))
        print(f"  ran {ran}, red {red}", flush=True)
        if ran != control_ran:
            print(f"  !! RAN-COUNT {ran} != control {control_ran} — arm not comparable", flush=True)
        for n in sorted(names):
            print(f"    RED  {n}", flush=True)
        rows.append((name, desc, ran, red, sorted(names)))

    print("\n\n=== SUMMARY ===")
    for name, desc, ran, red, names in rows:
        print(f"| {name} | {desc} | ran {ran} | red {red} |")
        for n in names:
            print(f"|    |   {n} | | |")

    write(CK, read(SNAP))
    assert sha(CK) == digest, "tree not restored"
    print(f"\ntree restored; Checker.kt sha256 {sha(CK)} == snapshot")


if __name__ == "__main__":
    main()
