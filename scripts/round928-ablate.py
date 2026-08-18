#!/usr/bin/env python3
"""(API.11) ablation — ONE deliberate mistake at a time, and which pins see it.

Round 807's law: a combined ablation cannot attribute. Round 902's: an arm can be
DEAD rather than the pin blind, so every arm's edit is anchored, its occurrence
count asserted, and a dry run confirms it produces a real diff. The tree is
restored from a sha256-VERIFIED snapshot, never by `git checkout` — round 851.

Usage: round928-ablate.py [dry] [arm ...]
"""
import hashlib, os, subprocess, sys, glob
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHECKER = os.path.join(ROOT, "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt")

# arm -> (old, new, expected occurrences)
ARMS = {
    # The whole leg, i.e. the pre-928 boundary INCLUDING (API.9)'s heritage tie.
    "A1-leg-off": (
        "        val member = (id as NodeBase).parent ?: return null\n"
        "        if (typeCaptureMemberNameIdentifier(member) !== id) return null\n",
        "        val member = (id as NodeBase).parent ?: return null\n"
        "        if (typeCaptureMemberNameIdentifier(member) !== id) return null\n"
        "        if (fileName.isNotEmpty()) return null\n",
        1,
    ),
    # THE NAIVE FIX the round's KDoc warns against: resolve the name to ITSELF.
    "A2-own-only": (
        "        for (declaration in typeCaptureMemberDeclarations(owner, id.text)) {",
        "        for (declaration in emptyList<Node>()) {",
        1,
    ),
    # The merged half alone: the owner SYMBOL's other declarations stop being containers.
    "A3-no-merged-containers": (
        "            containers.addAll(ownerSymbol.declarations)",
        "            containers.add(owner)",
        1,
    ),
    # The soundness condition: any same-named owner elsewhere contributes members.
    "A4-no-owner-identity-check": (
        "        if (ownerSymbol != null && ownerSymbol.declarations.any { it === owner }) {",
        "        if (ownerSymbol != null) {",
        1,
    ),
    # (API.10)'s territory: an object literal's own member joins this leg.
    "A5-objlit-not-excluded": (
        "        if (owner is ObjectLiteralExpression) return null\n"
        "        val own = typeCaptureDeclarationLocation(member) ?: return null",
        "        val own = typeCaptureDeclarationLocation(member) ?: return null",
        1,
    ),
    # An enum's member stops being a declaration name.
    "A6-no-enum-member": (
        "            is EnumMember -> member.name as? Identifier",
        "            is EnumMember -> null",
        1,
    ),
    # (BUG.4) one position over: the declaration name is asked as a free name again.
    "A7-no-hover-leg": (
        "            ?: typeCaptureMemberDeclarationType(node)\n",
        "",
        1,
    ),
    # (API.9)'s heritage tie, which this round must not have broken.
    "A8-no-heritage-related": (
        "        typeCaptureOwnHeritage(member)?.let {\n"
        "            typeCaptureCollectInherited(it, id.text, related, HashSet(), 0)\n"
        "        }",
        "        typeCaptureOwnHeritage(member)",
        1,
    ),
    # The defence: `own` is added whatever the owner route managed.
    "A9-own-not-added": (
        "        locations.add(own)\n"
        "        val related = LinkedHashSet<CapturedDeclaration>()",
        "        val related = LinkedHashSet<CapturedDeclaration>()",
        1,
    ),
}


def digest(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def ran_tests():
    total = 0
    for p in glob.glob(os.path.join(
            ROOT, "xemantic-typescript-compiler-project/build/test-results/jvmTest/*.xml")):
        total += int(ET.parse(p).getroot().get("tests"))
    return total


def failures():
    out = set()
    for p in glob.glob(os.path.join(
            ROOT, "xemantic-typescript-compiler-project/build/test-results/jvmTest/*.xml")):
        for tc in ET.parse(p).getroot().iter("testcase"):
            if tc.findall("failure") or tc.findall("error"):
                out.add("%s :: %s" % (tc.get("classname").split(".")[-1], tc.get("name")))
    return out


def main():
    args = sys.argv[1:]
    dry = "dry" in args
    arms = [a for a in args if a != "dry"] or list(ARMS)
    original = open(CHECKER, encoding="utf-8").read()
    baseline = digest(CHECKER)
    # A killed run must not leave the tree ablated with no marker (round 805), so the
    # snapshot lives on disk as well as in memory.
    snapshot = os.path.join(ROOT, "build", "round928-Checker.kt.snapshot")
    os.makedirs(os.path.dirname(snapshot), exist_ok=True)
    open(snapshot, "w", encoding="utf-8").write(original)
    print("snapshot %s  sha256 %s" % (snapshot, baseline[:16]))
    results = {}
    for arm in arms:
        old, new, count = ARMS[arm]
        assert original.count(old) == count, "%s: anchor found %d times, want %d" % (
            arm, original.count(old), count)
        mutated = original.replace(old, new, count)
        assert mutated != original, "%s: edit is a no-op" % arm
        open(CHECKER, "w", encoding="utf-8").write(mutated)
        try:
            diff = subprocess.run(["git", "diff", "--shortstat", "--", CHECKER],
                                  cwd=ROOT, capture_output=True, text=True).stdout.strip()
            print("=== %s  (%s)" % (arm, diff or "NO DIFF — DEAD ARM"))
            if dry:
                continue
            subprocess.run(["rm", "-rf",
                            os.path.join(ROOT, "xemantic-typescript-compiler-project/build/test-results/jvmTest")])
            build = subprocess.run(
                ["./gradlew", ":xemantic-typescript-compiler-project:jvmTest"],
                cwd=ROOT, capture_output=True, text=True)
            # POSITIVE CONTROL that the arm was RUN at all: a compile error or a
            # killed daemon leaves an empty results dir, which reads exactly like
            # "the mistake changed nothing" (round 808).
            total = ran_tests()
            if total < 400:
                print("    BUILD PROBLEM — only %d tests ran, not a result:" % total)
                print("\n".join((build.stdout + build.stderr).splitlines()[-20:]))
                results[arm] = None
                continue
            print("    %d tests ran" % total)
            red = failures()
            results[arm] = red
            print("    %d red" % len(red))
            for t in sorted(red):
                print("      %s" % t)
        finally:
            open(CHECKER, "w", encoding="utf-8").write(original)
            assert digest(CHECKER) == baseline, "RESTORE FAILED for %s" % arm
    print("\n=== restored, sha256 %s" % baseline[:16])
    if not dry:
        print("\narm | red | uniquely its own")
        for arm in arms:
            red = results.get(arm)
            if red is None:
                print("%-28s BUILD PROBLEM" % arm)
                continue
            others = set()
            for a2, r2 in results.items():
                if a2 != arm and r2:
                    others |= r2
            print("%-28s %3d | %d" % (arm, len(red), len(red - others)))


main()
