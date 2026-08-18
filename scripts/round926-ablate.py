#!/usr/bin/env python3
"""(API.9) round 926 — one deliberate mistake at a time in the member occurrence set.

Same shape as `round925-ablate.py`: an ANCHORED replacement whose occurrence count is
asserted (round 922 — `git diff --shortstat` is vacuous on a tree carrying the round's
own uncommitted work), the pin classes run, the red set recorded, and the tree restored
from a SHA-256-VERIFIED snapshot rather than by `git checkout` (round 851).

Every arm must redden a set OF ITS OWN. An arm that reddens nothing is a redundant
guard and is recorded as one rather than counted as coverage (round 807); an arm whose
red set is another's is not an independent signal.
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
INDEX = SRC / "SourceIndex.kt"
CHECKER = ROOT / "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
SNAP = Path("/tmp/api9-snap")
TARGETS = (PROJECT, INDEX, CHECKER)

CLASSES = ["*ProjectMemberOccurrenceTest*", "*ProjectRenameTest*", "*ProjectReferenceTest*",
           "*ProjectDefinitionTest*"]

ARMS = {
    # THE QUOTES. The edit span for an `o["p"]` covers the literal's whole token, so
    # the plan writes `o[renamed]` — which compiles as a computed access and means
    # something else.
    "A1-quote-span": (INDEX, [(
        "        if (node is StringLiteralNode && !node.isUnterminated && end - node.pos >= 2) {",
        "        if (node is StringLiteralNode && node.isUnterminated && end - node.pos >= 2) {")]),
    # The element access leaves the swept POPULATION — the pre-(API.9) boundary.
    "A2-no-element-population": (INDEX, [(
        "        for ((literal, _) in SyntaxRoles.stringElementAccesses(sourceFile)) found.add(literal)",
        "        for ((literal, _) in emptyList<Pair<Node, String>>()) found.add(literal)")]),
    # The binding element's propertyName resolves to nothing again.
    "A3-no-binding-leg": (CHECKER, [(
        "        if (parent is BindingElement && parent.propertyName === node) {",
        "        if (parent is BindingElement && parent.propertyName === node && node.pos < 0) {")]),
    # The heritage edge is not a GROUPING term: implementors fall out of the group.
    "A4-related-not-grouped": (PROJECT, [(
        "        definition.locations.any { it in seed } || definition.related.any { it in seed }",
        "        definition.locations.any { it in seed }")]),
    # The edge is carried only by a DECLARATION name, not by every occurrence — the
    # shape this round started with and measured to be short.
    "A5-declaration-only-edge": (CHECKER, [(
        "        val related = typeCaptureRootDeclarations(symbols, locations)",
        "        val related = emptyList<CapturedDeclaration>()")]),
    # The edge is not transitive: an `override` member two edges away falls out.
    "A6-one-level-edge": (CHECKER, [(
        "                typeCaptureCollectInherited(above, name, out, seen, depth + 1)",
        "                if (depth < 0) typeCaptureCollectInherited(above, name, out, seen, depth + 1)")]),
    # The SEED drops its heritage half: a caret ON an implementor answers only the
    # classes below it, never the interface's group.
    "A7-seed-without-related": (PROJECT, [(
        "                return definition.locations.toSet() + definition.related",
        "                return definition.locations.toSet()")]),
    # The verification looks its own answer up by the EDIT's start rather than by the
    # occurrence NODE's — which for an `o[\"p\"]` is one character earlier, so every
    # element-access rename reads as a change of meaning.
    "A8-verify-by-edit-start": (PROJECT, [(
        "                    resolvedBefore[file to edit.nodePos] ?: emptySet()",
        "                    resolvedBefore[file to edit.start] ?: emptySet()")]),
    # An element access the search cannot place stops refusing the rename.
    "A9-no-unplaceable-net": (PROJECT, [(
        "                if (key !in group && key !in resolved &&\n                    memberPosition == reachedThroughQualifier\n                ) {",
        "                if (key !in group && key !in resolved && id !is StringLiteralNode &&\n                    memberPosition == reachedThroughQualifier\n                ) {")]),
    # Go-to-definition widens to the base as well — the divergence from tsc this
    # round deliberately did NOT take.
    "A10-definition-follows-edge": (CHECKER, [(
        "            fileName, id.pos, id.end, symbols[0].name, locations.toList(), related,",
        "            fileName, id.pos, id.end, symbols[0].name, (locations + related).toList(), related,")]),
}


def snapshot():
    SNAP.mkdir(parents=True, exist_ok=True)
    for target in TARGETS:
        (SNAP / target.name).write_bytes(target.read_bytes())
        (SNAP / (target.name + ".sha256")).write_text(
            hashlib.sha256(target.read_bytes()).hexdigest())


def restore():
    for target in TARGETS:
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
