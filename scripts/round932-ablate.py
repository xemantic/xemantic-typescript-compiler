#!/usr/bin/env python3
"""(API.17) One deliberate mistake at a time, against round 932's pins.

The protocol CLAUDE.md requires and rounds 807/851/902/926/928/931 refined:

* ONE mistake per arm — a combined ablation credits a pin with discrimination it
  does not have;
* the tree is restored from a SHA-256-VERIFIED on-disk snapshot, never by
  `git checkout`, which would also destroy the round's own uncommitted work;
* every replacement is ANCHORED and its occurrence count asserted, so an arm that
  silently did not apply cannot read as "the guard is redundant";
* the arm's build must SUCCEED and its test run must REPORT a RAN-COUNT — round
  931's B4 read `ran 0` behind a green `git diff --shortstat` and was a DEAD ARM,
  which is indistinguishable from a redundant guard without this control.

Usage: round932-ablate.py [arm ...]   (default: every arm)
"""
import hashlib, os, shutil, subprocess, sys, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJ = "xemantic-typescript-compiler-project/src/commonMain/kotlin"
CORE = "xemantic-typescript-compiler-core/src/commonMain/kotlin"
ROLES = os.path.join(ROOT, PROJ, "SyntaxRoles.kt")
INDEX = os.path.join(ROOT, PROJ, "SourceIndex.kt")
PROJECT = os.path.join(ROOT, PROJ, "Project.kt")
CHECKER = os.path.join(ROOT, CORE, "Checker.kt")
FILES = (ROLES, INDEX, PROJECT, CHECKER)
SNAP = os.path.join(ROOT, "build", "round932-snapshot")
RESULTS = os.path.join(
    ROOT, "xemantic-typescript-compiler-project/build/test-results/jvmTest")

ARMS = {
    # THE PRE-932 BOUNDARY: the population is element accesses only, which is what
    # (API.9)/(API.16) swept and where the silent gap lived.
    "C1": (ROLES,
           "            if (isMemberNameLiteral(node) && isMemberPosition(node)) found.add(node)\n",
           "            if (isMemberNameLiteral(node) &&\n"
           "                parentOf(node) is ElementAccessExpression\n"
           "            ) {\n"
           "                found.add(node)\n"
           "            }\n"),
    # The key's OWN declaration is taken from the ASSIGNMENT rather than from the KEY
    # NODE, so a computed key's group names the `[` and no occurrence begins there.
    "C2": (CHECKER,
           "        val own = typeCaptureDeclarationLocation(id) ?: return null\n",
           "        val own = typeCaptureDeclarationLocation(assignment) ?: return null\n"),
    # A computed member DECLARATION's location stops unwrapping to its literal — the
    # coarser answer this round measured to be a refusal rather than an imprecision.
    "C3": (CHECKER,
           "        if (name is ComputedPropertyName) {\n"
           "            val inner = name.expression\n"
           "            return if (typeCaptureLiteralMemberName(inner) != null) inner else null\n"
           "        }\n",
           ""),
    # A computed key that is a NAME is admitted as if it spelled that name — the
    # `{ [K]: v }` regression this round measured and backed out mid-flight.
    "C4": (CHECKER,
           "        val name = typeCaptureLiteralMemberName(node) ?: return null\n"
           "        val assignment = (parent as NodeBase).parent as? PropertyAssignment ?: return null\n",
           "        val name = typeCaptureKeyName(node) ?: return null\n"
           "        val assignment = (parent as NodeBase).parent as? PropertyAssignment ?: return null\n"),
    # The contextual walk's step OUT of a nested literal stops reading a computed
    # outer key, so an inner computed key resolves only to itself.
    "C5": (CHECKER,
           "            is ComputedPropertyName -> typeCaptureLiteralMemberName(name.expression)\n",
           "            is ComputedPropertyName -> null\n"),
    # HOVER: the object-literal key arms are dropped, so a key falls back to the
    # free-name path — `any`, or the COLLIDER's type.
    "C6": (CHECKER,
           "            is PropertyAssignment ->\n"
           "                if (parent.name !== node) null else typeCaptureObjectLiteralKeyType(node)\n"
           "            is ComputedPropertyName ->\n"
           "                if (parent.expression !== node) null\n"
           "                else typeCaptureObjectLiteralKeyType(node)\n",
           ""),
    # `isMemberPosition`'s computed arm stops filtering to LITERALS, so `{ [K]: v }`
    # becomes a member position and the completeness net's polarity flips.
    "C7": (ROLES,
           "            is ComputedPropertyName ->\n"
           "                parent.expression === node && isMemberNameLiteral(node)\n",
           "            is ComputedPropertyName -> parent.expression === node\n"),
    # THE REACH PROOF FOR C7: the same line, removed outright. If C7 reads zero it is
    # a redundant guard only if this shows the line is live and load-bearing.
    "C7b": (ROLES,
            "            is ComputedPropertyName ->\n"
            "                parent.expression === node && isMemberNameLiteral(node)\n",
            "            is ComputedPropertyName -> false\n"),
    # A DECLARATION named by a literal reports its raw extent again, delimiters and
    # all, where every occurrence of it reports the text.
    "C8": (PROJECT,
           "                    start = extent?.start ?: location.start,\n"
           "                    end = extent?.end ?: (location.start + location.length),\n",
           "                    start = location.start,\n"
           "                    end = location.start + location.length,\n"),
    # The population is enumerated but the OCCURRENCE CARET is not, so the FROM-the-key
    # direction dies while the sweep still finds it.
    "C9": (PROJECT,
           "        return if (SyntaxRoles.isMemberPosition(node) && SyntaxRoles.isMemberNameLiteral(node)) {\n",
           "        return if (false) {\n"),
}

# The positive run control: the arm's `-project` run must REPORT this many tests or
# more, or the arm proves nothing (round 931's dead-arm trap, round 808's empty one).
EXPECTED_TESTS = int(os.environ.get("ROUND932_EXPECTED", "540"))


def snapshot():
    os.makedirs(SNAP, exist_ok=True)
    digests = {}
    for path in FILES:
        shutil.copy2(path, os.path.join(SNAP, os.path.basename(path)))
        digests[path] = hashlib.sha256(open(path, "rb").read()).hexdigest()
    return digests


def restore(digests):
    for path in FILES:
        shutil.copy2(os.path.join(SNAP, os.path.basename(path)), path)
        got = hashlib.sha256(open(path, "rb").read()).hexdigest()
        assert got == digests[path], "restore of %s does not match its snapshot" % path


def apply(arm):
    path, old, new = ARMS[arm]
    text = open(path, encoding="utf-8").read()
    count = text.count(old)
    assert count == 1, "%s: anchor occurs %d times, not once" % (arm, count)
    open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))


def run():
    if os.path.isdir(RESULTS):
        shutil.rmtree(RESULTS)
    proc = subprocess.run(
        ["./gradlew", ":xemantic-typescript-compiler-project:jvmTest"],
        cwd=ROOT, capture_output=True, text=True)
    output = proc.stdout + proc.stderr
    if not os.path.isdir(RESULTS):
        return None, output
    total = failed = 0
    names = []
    for name in os.listdir(RESULTS):
        if not name.endswith(".xml"):
            continue
        root = ET.parse(os.path.join(RESULTS, name)).getroot()
        total += int(root.get("tests"))
        for case in root.iter("testcase"):
            if list(case.iter("failure")) or list(case.iter("error")):
                failed += 1
                names.append(case.get("name"))
    return (total, failed, sorted(names)), output


def main():
    arms = sys.argv[1:] or sorted(ARMS)
    digests = snapshot()
    print("snapshot sha256:")
    for path, digest in digests.items():
        print("  %s  %s" % (digest[:16], os.path.basename(path)))
    for arm in arms:
        assert arm in ARMS, "unknown arm %s" % arm
        restore(digests)
        apply(arm)
        diff = subprocess.run(["git", "diff", "--shortstat"], cwd=ROOT,
                              capture_output=True, text=True).stdout.strip()
        print("\n=== %s   (%s)" % (arm, diff), flush=True)
        result, output = run()
        if result is None:
            print("    BUILD PROBLEM — arm not measured")
            print("    " + "\n    ".join(output.splitlines()[-12:]))
            continue
        total, failed, names = result
        control = "" if total >= EXPECTED_TESTS else "  *** RUN CONTROL FAILED ***"
        print("    ran %d, RED %d%s" % (total, failed, control), flush=True)
        for name in names:
            print("      - %s" % name)
    restore(digests)
    print("\ncomplete; tree restored and verified")


main()
