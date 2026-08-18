#!/usr/bin/env python3
"""(BUG.4) round 924 — one deliberate mistake at a time in the member-hover channel.

Each arm patches `Checker.kt` by an ANCHORED replacement whose occurrence count is
asserted (round 922: `git diff --shortstat` is vacuous as an arm dry-run on a tree
carrying the round's own uncommitted work, so the anchor count is the check that the
edit landed), runs the four pin classes, records which fail, and restores from a
SHA-256-VERIFIED snapshot — never `git checkout`, which would also destroy the
round's uncommitted work (round 851).
"""
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from glob import glob
from pathlib import Path

ROOT = Path("/home/claude/git/xemantic-typescript-compiler")
TARGET = ROOT / "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
SNAP = Path("/tmp/bug4-snap/Checker.kt.FIX")
SNAP_SHA = Path("/tmp/bug4-snap/FIX.sha256").read_text().split()[0]

CLASSES = ["*ProjectMemberHoverTest*", "*ProjectSemanticsTest*",
           "*ProjectThisReceiverTest*", "*ProjectQuickInfoTest*"]

ARMS = {
    # The whole channel off — the pre-fix behaviour, expressed inside the new helper.
    "A1-channel-off": [(
        "        typeCaptureMemberAccessType(node) ?: getTypeOfExpression(node)",
        "        getTypeOfExpression(node)")],
    # Read the MEMBER TABLE instead of the carrier: CLAUDE.md's trap, which answers
    # a type-parameter-typed member globally as `any`.
    "A2-member-table": [(
        """        return getTypeOfExpression(access)
    }""",
        """        val receiverType = getTypeOfExpression(access.expression)
        val prop = (receiverType as? Type.Object)?.also { resolveStructuredTypeMembers(it) }
            ?.members?.get(access.name.text)
        if (prop != null) return getTypeOfSymbol(prop)
        return getTypeOfExpression(access)
    }""")],
    # Drop the explicit member resolution in the `super` leg (round 833's laziness).
    "A3-no-resolve-members": [(
        """        val declared = thisType as? Type.Interface ?: return null
        resolveStructuredTypeMembers(declared)
        val bases = declared.baseTypes ?: return null""",
        """        val declared = thisType as? Type.Interface ?: return null
        val bases = declared.baseTypes ?: return null""")],
    # The naive fix: let the FREE-NAME path answer wherever the access says `any`.
    "A4-free-name-fallback": [(
        "    private fun typeCaptureReportedType(node: Expression): Type =\n"
        "        typeCaptureMemberAccessType(node) ?: getTypeOfExpression(node)",
        "    private fun typeCaptureReportedType(node: Expression): Type =\n"
        "        typeCaptureMemberAccessType(node)?.takeIf { it !== anyType }\n"
        "            ?: getTypeOfExpression(node)")],
    # No `this`/`super` carrier leg at all.
    "A5-no-this-leg": [(
        """        if (receiver is Identifier && (receiver.text == "this" || receiver.text == "super")) {
            typeCaptureThisMemberType(receiver, access.name.text)?.let { return it }
        }
        return getTypeOfExpression(access)""",
        """        if (receiver is Identifier && receiver.text == "never-a-receiver") {
            typeCaptureThisMemberType(receiver, access.name.text)?.let { return it }
        }
        return getTypeOfExpression(access)""")],
    # `super` answered from the THIS type rather than from the base.
    "A6-super-reads-this": [(
        '        if (receiver.text != "super") return resolveMemberPropertyType(thisType, name)',
        "        if (true) return resolveMemberPropertyType(thisType, name)")],
    # No element-access leg.
    "A7-no-element-access": [(
        """            is ElementAccessExpression ->
                if (node !is StringLiteralNode || parent.argumentExpression !== node) null
                else getTypeOfExpression(parent)""",
        """            is ElementAccessExpression -> null""")],
    # No qualified-type-name leg.
    "A8-no-qualified-name": [(
        """            is QualifiedName ->
                if (parent.right !== node) null else typeCaptureQualifiedNameType(parent)""",
        """            is QualifiedName -> null""")],
}


def restore():
    TARGET.write_bytes(SNAP.read_bytes())
    got = hashlib.sha256(TARGET.read_bytes()).hexdigest()
    assert got == SNAP_SHA, "restore failed: %s != %s" % (got, SNAP_SHA)


def patch(edits):
    text = TARGET.read_text()
    for old, new in edits:
        n = text.count(old)
        if n != 1:
            raise SystemExit("anchor occurs %d times, expected 1:\n%s" % (n, old[:120]))
        text = text.replace(old, new)
    TARGET.write_text(text)


def run():
    for f in glob(str(ROOT / "*/build/test-results/jvmTest")):
        subprocess.run(["rm", "-rf", f], check=True)
    cmd = ["./gradlew", ":xemantic-typescript-compiler-project:jvmTest"]
    for c in CLASSES:
        cmd += ["--tests", c]
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if "BUILD SUCCESSFUL" not in p.stdout and "tests completed" not in p.stdout:
        if "e: file" in p.stdout:
            return None, [l for l in p.stdout.splitlines() if l.startswith("e:")][:3]
    failed, total = [], 0
    for f in glob(str(ROOT / "*/build/test-results/jvmTest/*.xml")):
        r = ET.parse(f).getroot()
        total += int(r.get("tests"))
        for tc in r.iter("testcase"):
            for fl in tc:
                if fl.tag in ("failure", "error"):
                    failed.append(tc.get("name").replace("[jvm]", ""))
    return total, sorted(failed)


def main():
    arms = sys.argv[1:] or list(ARMS)
    restore()
    results = {}
    for name in arms:
        patch(ARMS[name])
        total, failed = run()
        restore()
        results[name] = (total, failed)
        print("=== %s: ran %s, %d red" % (name, total, len(failed) if failed else 0),
              flush=True)
        for t in (failed or []):
            print("      %s" % t, flush=True)
    print("\n=== SUMMARY")
    for name, (total, failed) in results.items():
        print("%-24s ran %-5s red %d" % (name, total, len(failed or [])))


main()
