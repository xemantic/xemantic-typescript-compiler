#!/usr/bin/env python3
"""(API.10) ONE MISTAKE AT A TIME — the ablation for the contextual-key / shorthand work.

Each arm injects exactly ONE deliberate mistake, rebuilds, runs the `-project` module's
suite and records which tests redden. An arm whose replacement does not apply is a hard
error (never a silently green arm — round 926's A10), and the tree is restored from a
sha256-verified snapshot rather than with `git checkout`, which on a dirty tree would
destroy the round's own uncommitted work.

Usage: round927-ablate.py [ARM ...]   (default: all)
"""
import hashlib, os, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHECKER = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
PROJECT = "xemantic-typescript-compiler-project/src/commonMain/kotlin/Project.kt"
ROLES = "xemantic-typescript-compiler-project/src/commonMain/kotlin/SyntaxRoles.kt"
RESULTS = "xemantic-typescript-compiler-project/build/test-results/jvmTest"

ARMS = {
    # A1 — the member's group stops containing a shorthand's token.
    "A1": (PROJECT,
           """        definition.locations.any { it in seed } ||
            definition.related.any { it in seed } ||
            definition.shorthand.any { it in seed }""",
           """        definition.locations.any { it in seed } ||
            definition.related.any { it in seed }"""),
    # A2 — the shorthand's member joins the SEED, so a caret there merges both groups.
    "A2": (PROJECT,
           "                return definition.locations.toSet() + definition.related",
           "                return definition.locations.toSet() + definition.related +\n"
           "                    definition.shorthand"),
    # A3 — THE DISCRIMINATOR: a shorthand always expands the local's way.
    "A3": (ROLES,
           "            shorthand && asMember -> Rewrite(\"$newName: $oldName\", 0)\n",
           ""),
    # A4 — an object-literal key resolves to nothing again.
    "A4": (CHECKER,
           "        if (id is Identifier) typeCaptureObjectLiteralKeyAt(id, fileName)?.let { return it }",
           "        if (false) typeCaptureObjectLiteralKeyAt(id as Identifier, fileName)?.let { return it }"),
    # A5 — a contextual key stops carrying the literal's OWN property.
    "A5": (CHECKER,
           """        return CapturedDefinition(
            fileName, id.pos, id.end, id.text, locations.toList(), listOf(own),
        )""",
           """        return CapturedDefinition(
            fileName, id.pos, id.end, id.text, locations.toList(),
        )"""),
    # A6 — the contextual walk no longer passes through a ternary's branches, which is
    # the position the checker's own walk-scoped contextual type is missing outright.
    "A6": (CHECKER,
           """            is ConditionalExpression ->
                if (parent.whenTrue === node || parent.whenFalse === node) {
                    typeCaptureContextualType(parent, depth + 1)
                } else {
                    null
                }""",
           "            is ConditionalExpression -> null"),
    # A7 — a call's EXPLICIT type arguments stop instantiating the signature.
    "A7": (CHECKER,
           """                if (!typeArguments.isNullOrEmpty() && !parameters.isNullOrEmpty()) {
                    instantiateSignature(
                        signature,
                        createTypeMapper(parameters, typeArguments.map { getTypeFromTypeNode(it) }),
                    )
                } else {
                    signature
                }""",
           "                signature"),
    # A8 — the rename verification asks for the shorthand's OTHER answer by the wrong key.
    "A8": (PROJECT,
           """                val before =
                    if (edit.asMember) resolvedBeforeAsMember[file to edit.nodePos]
                    else resolvedBefore[file to edit.nodePos]""",
           "                val before = resolvedBefore[file to edit.nodePos]"),
    # A9 — a key with NO contextual type resolves to nothing, as it did before.
    "A9": (CHECKER,
           """        if (locations.isEmpty()) {
            return CapturedDefinition(fileName, id.pos, id.end, id.text, listOf(own))
        }""",
           "        if (locations.isEmpty()) return null"),
    # A10 — go-to-definition stops offering the shorthand's member.
    "A10": (PROJECT,
            "            ?.let { it.locations + it.shorthand }",
            "            ?.let { it.locations }"),
}


def digest(path):
    return hashlib.sha256(open(os.path.join(ROOT, path), "rb").read()).hexdigest()


def run_arm(name, snapshot):
    path, old, new = ARMS[name]
    full = os.path.join(ROOT, path)
    text = open(full).read()
    if text.count(old) != 1:
        raise SystemExit("arm %s: anchor occurs %d times in %s" % (name, text.count(old), path))
    open(full, "w").write(text.replace(old, new))
    if digest(path) == snapshot[path]:
        raise SystemExit("arm %s: the edit changed nothing" % name)
    try:
        shutil.rmtree(os.path.join(ROOT, RESULTS), ignore_errors=True)
        build = subprocess.run(
            ["./gradlew", ":xemantic-typescript-compiler-project:jvmTest"],
            cwd=ROOT, capture_output=True, text=True)
        red = set()
        results = os.path.join(ROOT, RESULTS)
        if not os.path.isdir(results):
            out = build.stdout + build.stderr
            if "BUILD SUCCESSFUL" in out:
                raise SystemExit("arm %s: no results and a successful build" % name)
            return None, out.splitlines()[-30:]
        for fn in os.listdir(results):
            if not fn.endswith(".xml"):
                continue
            for tc in ET.parse(os.path.join(results, fn)).getroot().iter("testcase"):
                for child in tc:
                    if child.tag in ("failure", "error"):
                        red.add("%s.%s" % (tc.get("classname").split(".")[-1], tc.get("name")))
        return red, None
    finally:
        open(full, "w").write(text)
        assert digest(path) == snapshot[path], "restore of %s did not reproduce the snapshot" % path


def main():
    wanted = sys.argv[1:] or list(ARMS)
    snapshot = {p: digest(p) for p in (CHECKER, PROJECT, ROLES)}
    report = {}
    for name in wanted:
        red, failure = run_arm(name, snapshot)
        if red is None:
            print("=== %s: DID NOT COMPILE" % name)
            print("\n".join(failure))
            report[name] = None
            continue
        report[name] = red
        print("=== %s: %d red" % (name, len(red)))
        for t in sorted(red):
            print("      %s" % t)
    print("\n--- summary")
    for name in wanted:
        red = report[name]
        print("%-4s %s" % (name, "did not compile" if red is None else "%d red" % len(red)))
    sets = {n: r for n, r in report.items() if r}
    for a in sets:
        for b in sets:
            if a < b and sets[a] == sets[b]:
                print("WARNING: %s and %s reddened the SAME set" % (a, b))
    for name, red in report.items():
        if red is not None and not red:
            print("WARNING: %s reddened NOTHING — the arm may be DEAD rather than the guard "
                  "redundant (round 902)" % name)


main()
