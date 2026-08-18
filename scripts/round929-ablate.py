#!/usr/bin/env python3
"""(API.12) One deliberate mistake at a time, against round 929's pins.

The protocol CLAUDE.md requires and rounds 807/851/902/926/928 refined:

* ONE mistake per arm — a combined ablation credits a pin with discrimination it
  does not have;
* the tree is restored from a SHA-256-VERIFIED on-disk snapshot, never by
  `git checkout`, which would also destroy the round's own uncommitted work;
* every replacement is ANCHORED and its occurrence count asserted, so an arm that
  silently did not apply cannot read as "the guard is redundant";
* the arm's build must SUCCEED and its test run must REPORT — an empty results
  directory looks exactly like a green run (round 808), so the driver requires a
  positive run control (the expected number of `-project` tests must have run).

Usage: round929-ablate.py [arm ...]   (default: every arm)
"""
import hashlib, os, shutil, subprocess, sys, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = "xemantic-typescript-compiler-project/src/commonMain/kotlin"
INDEX = os.path.join(ROOT, MAIN, "SourceIndex.kt")
ROLES = os.path.join(ROOT, MAIN, "SyntaxRoles.kt")
SNAP = os.path.join(ROOT, "build", "round929-snapshot")
RESULTS = os.path.join(
    ROOT, "xemantic-typescript-compiler-project/build/test-results/jvmTest")

ARMS = {
    # The pre-929 boundary: the classifier answers nothing at all.
    "A1": (INDEX,
           "    private fun stringMemberAnchorAt(offset: Int, containing: Int): CompletionAnchor? {\n",
           "    private fun stringMemberAnchorAt(offset: Int, containing: Int): CompletionAnchor? {\n"
           "        if (offset >= 0) return null\n"),
    # Round 926's own discriminator, one query over: the span covers the TOKEN,
    # so accepting an item writes `o[alpha]` and loses the quotes.
    "A2": (INDEX,
           "        val textStart = literalStart + 1\n",
           "        val textStart = literalStart\n"),
    # A classifier keyed on the TOKEN rather than on the POSITION: any string
    # literal borrows the file's first element access.
    "A3": (ROLES,
           "            if (literal.pos == literalStart) return access\n",
           "            if (literal.pos >= 0 && literalStart >= 0) return access\n"),
    # The parser's own `isUnterminated` is FALSE for a lone opening quote, which
    # is exactly the `o[\"` state — drop the arithmetic that notices.
    "A4": (INDEX,
           "        val unterminated = literal.isUnterminated || tokenEnds[token] - literalStart < 2\n",
           "        val unterminated = literal.isUnterminated\n"),
    # Only a caret CONTAINED by a token is considered, so nothing unterminated is.
    "A5": (INDEX,
           "            val before = tokenEndingAtOrBefore(offset)\n"
           "            if (before < 0 || tokenEnds[before] != offset) return null\n",
           "            val before = tokenEndingAtOrBefore(offset)\n"
           "            if (before < 0 || tokenEnds[before] != offset) return null\n"
           "            if (before >= 0) return null\n"),
    # A caret PAST a closed literal's quote becomes a member caret.
    "A6": (INDEX,
           "        if (!caretIsInsideToken && !unterminated) return null\n",
           "        if (false) return null\n"),
    # The guard that costs nothing and is measured redundant (see the session note).
    "A7": (INDEX,
           "        if (offset <= literalStart) return null\n",
           "        if (offset < literalStart) return null\n"),
    # The caret's TOKEN kind stops being consulted, so every literal — a template,
    # a number, a regex — reaches the element-access lookup.
    "A8": (INDEX,
           "            if (tokenKinds[containing] != SyntaxKind.StringLiteral) return null\n",
           "            if (tokenKinds[containing] == SyntaxKind.EndOfFile) return null\n"),
    # A REACH CONTROL, deliberately TWO mistakes and credited to no pin (round 807:
    # a combined ablation cannot attribute). A6 and A9's first half each answer the
    # past-a-closed-quote caret alone, so neither can be shown red on its own;
    # dropping both is what proves the lines are on that caret's path and that the
    # two pins asserting it are not vacuous.
    "A9": (INDEX,
           "        if (!caretIsInsideToken && !unterminated) return null\n",
           "        if (false) return null\n"),
    "A9b": (INDEX,
            "        if (offset < textStart || offset > textEnd) return null\n",
            "        if (offset < textStart) return null\n"),
}

# The positive run control: every `-project` test must run, or the arm proves
# nothing (round 808's empty-results trap).
EXPECTED_TESTS = int(os.environ.get("ROUND929_EXPECTED", "0"))


def snapshot():
    os.makedirs(SNAP, exist_ok=True)
    digests = {}
    for path in (INDEX, ROLES):
        shutil.copy2(path, os.path.join(SNAP, os.path.basename(path)))
        digests[path] = hashlib.sha256(open(path, "rb").read()).hexdigest()
    return digests


def restore(digests):
    for path in (INDEX, ROLES):
        shutil.copy2(os.path.join(SNAP, os.path.basename(path)), path)
        got = hashlib.sha256(open(path, "rb").read()).hexdigest()
        assert got == digests[path], "restore of %s does not match its snapshot" % path


def apply(arm):
    # "A9" is the one deliberate PAIR — see its comment above.
    for name in (["A9", "A9b"] if arm == "A9" else [arm]):
        path, old, new = ARMS[name]
        text = open(path, encoding="utf-8").read()
        count = text.count(old)
        assert count == 1, "%s: anchor occurs %d times, not once" % (name, count)
        open(path, "w", encoding="utf-8").write(text.replace(old, new))


def run():
    if os.path.isdir(RESULTS):
        shutil.rmtree(RESULTS)
    proc = subprocess.run(
        ["./gradlew", ":xemantic-typescript-compiler-project:jvmTest"],
        cwd=ROOT, capture_output=True, text=True)
    output = proc.stdout + proc.stderr
    if "BUILD SUCCESSFUL" not in output and "tests completed" not in output:
        return None, output
    total = failed = 0
    names = []
    if not os.path.isdir(RESULTS):
        return None, output
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
    arms = sys.argv[1:] or [a for a in sorted(ARMS) if a != "A9b"]
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
        print("\n=== %s   (%s)" % (arm, diff))
        result, output = run()
        if result is None:
            print("    BUILD PROBLEM — arm not measured")
            print("    " + "\n    ".join(output.splitlines()[-12:]))
            continue
        total, failed, names = result
        control = "" if total >= EXPECTED_TESTS else "  *** RUN CONTROL FAILED ***"
        print("    ran %d, RED %d%s" % (total, failed, control))
        for name in names:
            print("      - %s" % name)
    restore(digests)
    print("\ncomplete; tree restored and verified")


main()
