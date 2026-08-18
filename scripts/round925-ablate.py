#!/usr/bin/env python3
"""(API.8) round 925 — one deliberate mistake at a time in the rename channel.

Each arm patches `Project.kt` or `SyntaxRoles.kt` by an ANCHORED replacement whose
occurrence count is asserted (round 922: `git diff --shortstat` is vacuous as an arm
dry-run on a tree carrying the round's own uncommitted work, so the anchor count is
the check that the edit landed), runs the two pin classes, records which fail, and
restores from a SHA-256-VERIFIED snapshot — never `git checkout`, which would also
destroy the round's uncommitted work (round 851).

Every arm must redden a set of its own. An arm that reddens NOTHING is a redundant
guard and must be recorded as one rather than counted as coverage (round 807).
"""
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from glob import glob
from pathlib import Path

ROOT = Path("/home/claude/git/xemantic-typescript-compiler")
SRC = ROOT / "xemantic-typescript-compiler-project/src/commonMain/kotlin"
PROJECT = SRC / "Project.kt"
ROLES = SRC / "SyntaxRoles.kt"
SNAP = Path("/tmp/api8-snap")

CLASSES = ["*ProjectRenameTest*", "*RenameShapeTest*"]

ARMS = {
    # The DISCRIMINATOR's mistake: rewrite a shorthand as a plain occurrence, which
    # compiles and renames the object's key.
    "A1-shorthand-plain": (ROLES, [(
        """            parent is ShorthandPropertyAssignment && parent.name === node ->
                Rewrite("$oldName: $newName", oldName.length + 2)
            parent is BindingElement && parent.propertyName == null && parent.name === node ->
                Rewrite("$oldName: $newName", oldName.length + 2)
""", "")]),
    # No safety refusal for a declaration in a library.
    "A2-no-lib-refusal": (PROJECT, [(
        "if (seed.any { it.fileName !in sweep.indexes })",
        'if (seed.any { it.fileName == "never-a-file" })')]),
    # Verification without its DIAGNOSTIC half — the collision check.
    "A3-no-diagnostic-check": (PROJECT, [(
        "        if (conflicts.isNotEmpty()) return refuse(RenameRefusal.WOULD_NOT_COMPILE)",
        "        conflicts.clear()")]),
    # Verification without its RESOLUTION half — the capture check. The arm that
    # separates "compiles" from "means the same".
    "A4-no-capture-check": (PROJECT, [(
        "            if (got != expected) {", "            if (got != expected && where.second < 0) {")]),
    # Group by SPELLING instead of by declaration set — the text-search rename.
    "A5-name-matching": (PROJECT, [(
        "        for (location in seed) group.add(location.fileName to location.start)",
        """        for (location in seed) group.add(location.fileName to location.start)
        for ((ablationFile, ablationFound) in sweep.identifiers) {
            for (id in ablationFound) {
                if ((id as Identifier).text == oldName) group.add(ablationFile to id.pos)
            }
        }""")]),
    # No completeness net at all: a member rename then silently misses implementors.
    "A6-no-completeness-net": (PROJECT, [(
        "        if (conflicts.isNotEmpty()) {\n            return refuse(RenameRefusal.OCCURRENCES_INCOMPLETE, conflicts)",
        "        if (conflicts.size < 0) {\n            return refuse(RenameRefusal.OCCURRENCES_INCOMPLETE, conflicts)")]),
    # No alias refusal: one new name applied to two spellings.
    "A7-no-alias-refusal": (PROJECT, [(
        "if (occurrences.any { (it.node as Identifier).text != oldName })",
        'if (occurrences.any { (it.node as Identifier).text == "\\u0000" })')]),
    # No reserved-word check — tsc's own behaviour, which writes `const class = 1`.
    "A8-no-reserved-check": (PROJECT, [(
        "        if (newName in SyntaxRoles.RESERVED_WORDS) {",
        "        if (newName in emptySet<String>()) {")]),
    # The raw `Node.end`, which reaches into the FOLLOWING token (round 910).
    "A9-raw-node-end": (PROJECT, [(
        "                    end = index.realEndOf(occurrence.node),",
        "                    end = occurrence.node.end,")]),
    # Expect the SEED rather than each occurrence's own prior answer — the mistake
    # this round made and measured: a member's declaration name resolves to nothing.
    "A10-expect-seed": (PROJECT, [(
        "                expectedPlaces[file to node.pos] =\n                    resolvedBefore[file to edit.start] ?: emptySet()",
        "                expectedPlaces[file to node.pos] =\n                    seed.map { placeOf(it.fileName, it.start) }.toSet()")]),
    # The net without its shorthand half.
    "A11-no-shorthand-net": (PROJECT, [(
        "                if (symbolIsMember && key !in group && SyntaxRoles.isPropertyHidingShorthand(id)) {",
        "                if (symbolIsMember && key !in group && id.pos < 0 &&\n                    SyntaxRoles.isPropertyHidingShorthand(id)\n                ) {")]),
    # The net without its element-access half.
    "A12-no-element-access-net": (PROJECT, [(
        "                if (text != oldName) continue\n                conflicts.add(\n                    RenameConflict(\n                        RenameConflictKind.ELEMENT_ACCESS",
        "                if (text != oldName || literal.pos >= 0) continue\n                conflicts.add(\n                    RenameConflict(\n                        RenameConflictKind.ELEMENT_ACCESS")]),
}


def snapshot():
    SNAP.mkdir(parents=True, exist_ok=True)
    for target in (PROJECT, ROLES):
        (SNAP / target.name).write_bytes(target.read_bytes())
        (SNAP / (target.name + ".sha256")).write_text(
            hashlib.sha256(target.read_bytes()).hexdigest())


def restore():
    for target in (PROJECT, ROLES):
        target.write_bytes((SNAP / target.name).read_bytes())
        want = (SNAP / (target.name + ".sha256")).read_text().strip()
        got = hashlib.sha256(target.read_bytes()).hexdigest()
        assert got == want, "restore failed for %s: %s != %s" % (target.name, got, want)


def patch(target, edits):
    text = target.read_text()
    for old, new in edits:
        n = text.count(old)
        if n != 1:
            raise SystemExit("anchor occurs %d times, expected 1:\n%s" % (n, old[:160]))
        text = text.replace(old, new)
    target.write_text(text)


def run():
    for f in glob(str(ROOT / "*/build/test-results/jvmTest")):
        subprocess.run(["rm", "-rf", f], check=True)
    cmd = ["./gradlew", ":xemantic-typescript-compiler-project:jvmTest"]
    for c in CLASSES:
        cmd += ["--tests", c]
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
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
    snapshot()
    results = {}
    for name in arms:
        target, edits = ARMS[name]
        patch(target, edits)
        total, failed = run()
        restore()
        results[name] = (total, failed)
        print("=== %s: ran %s, %d red" % (name, total, len(failed or [])), flush=True)
        for t in (failed or []):
            print("      %s" % t, flush=True)
    print("\n=== SUMMARY")
    for name, (total, failed) in results.items():
        print("%-28s ran %-5s red %d" % (name, total, len(failed or [])))


main()
