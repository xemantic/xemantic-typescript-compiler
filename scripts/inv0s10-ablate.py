#!/usr/bin/env python3
"""(INV.0) step 10a — one deliberate mistake at a time, against a sha256-verified snapshot.

Never `git checkout` (CLAUDE.md round 789: it also destroys every UNCOMMITTED change in the
file, and a probe lives in the file it measures).  Every arm is applied to a copy taken from
the SNAPSHOT and diffed against the SNAPSHOT (round 922: `git diff --shortstat` is vacuous on
a tree that already carries the round's own work), and each arm asserts it RAN the expected
number of pins, so a dead build or an empty `--tests` filter reads as a failure rather than
as "the mistake changed nothing" (round 856).

The change has FOUR independently-breakable parts and the arms are one per part: the STAMP
(which declarations `indexSourceFile` records), the GATE (which names reach the ascent), the
FLAG MASK (which kinds the ascent accepts), and the ORDER (lexical FIRST, at both of the two
sites that resolve a type name).  A5 is the containment control — round 748's forbidden
`existing` read — which is what makes "this cannot move a conventionally-bound name" a
measured claim rather than an argument.
"""
import glob
import hashlib
import pathlib
import shutil
import subprocess
import sys


def say(*a):
    print(*a, flush=True)

import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parent.parent
K = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin"
SNAP = REPO / "build/bench/inv0s10/ablate-snapshot"
FILES = ["NameResolver.kt", "Checker.kt", "NodeWalk.kt", "LexicalScopeResolver.kt"]
CLASSES = ("ScopeSpaceTypeResolutionTest", "LexicalScopeDeferralTest", "LexicalScopeResolverTest")
EXPECTED_RAN = 28  # 8 + 14 + 6

STAMP = """        if (k >= NodeKind.CLASS_DECLARATION && k <= NodeKind.ENUM_DECLARATION &&
            node.parent !== sourceFile
        ) {"""
STAMP_OLD = """        if ((k == NodeKind.TYPE_ALIAS_DECLARATION || k == NodeKind.ENUM_DECLARATION) &&
            node.parent !== sourceFile
        ) {"""

ORDER_FIRST = """                lexicalTypeSymbolForNode(node, node.text)?.let { return it }
                // (CHK.76) a name declared in an enclosing namespace body — innermost
                // first, BEFORE the per-file consult, which would otherwise hand a
                // nested namespace's `Node` to the root's (or a lib's) `Node`.
                if (!enclosingNamespacesDone) {
                    lookupInEnclosingNamespaces(node, node.text, SymbolFlags.Type)?.let { return it }
                }
                val sym = lookupPerFileForNode(node, node.text)"""
ORDER_FALLBACK = """                // (CHK.76) a name declared in an enclosing namespace body — innermost
                // first, BEFORE the per-file consult, which would otherwise hand a
                // nested namespace's `Node` to the root's (or a lib's) `Node`.
                if (!enclosingNamespacesDone) {
                    lookupInEnclosingNamespaces(node, node.text, SymbolFlags.Type)?.let { return it }
                }
                val sym = lookupPerFileForNode(node, node.text)
                    ?: lexicalTypeSymbolForNode(node, node.text)"""

EXISTING_READ = "                val sym = scopes[id]?.symbols?.get(name)"
EXISTING_READ_BAD = "                val sym = scopes[id]?.let { it.symbols[name] ?: it.existing?.get(name) }"

# (file, anchor, replacement)
ARMS = {
    # ── THE STAMP: only `type`/`enum` are recorded, so `class`/`interface` never reach
    #    the projection and the gate never admits their names.
    "A1": ("NodeWalk.kt", STAMP, STAMP_OLD),
    # ── THE GATE: back to round 748's enum-only name set.  The ascent and the flag mask
    #    are untouched; only the pre-gate refuses.
    "A2": ("NameResolver.kt",
           "        if (name !in checker.lexicalBlockScopedTypeNames) return null\n"
           "        val scopes = lexicalResolver.scopesOfOwningFile(node) ?: return null\n"
           "        return lexicalResolver.symbolAt(node, name, scopes, flags = SymbolFlags.ScopeTypeDeclaration)",
           "        if (name !in checker.lexicalBlockScopedEnumNames) return null\n"
           "        val scopes = lexicalResolver.scopesOfOwningFile(node) ?: return null\n"
           "        return lexicalResolver.symbolAt(node, name, scopes, flags = SymbolFlags.ScopeTypeDeclaration)"),
    # ── THE FLAG MASK: the gate still admits the name and the ascent still finds the
    #    scope; only the KIND test refuses.  This is what separates the two halves of the
    #    widening, which one arm cannot.
    "A3": ("NameResolver.kt",
           "        return lexicalResolver.symbolAt(node, name, scopes, flags = SymbolFlags.ScopeTypeDeclaration)",
           "        return lexicalResolver.symbolAt(node, name, scopes, flags = SymbolFlags.Enum)"),
    # ── THE ORDER, site 1: a miss-FALLBACK instead of first.  A unique name still
    #    resolves; a name that SHADOWS an outer one answers the outer declaration, which
    #    is the failure mode with nothing to detect.
    "A4": ("NameResolver.kt", ORDER_FIRST, ORDER_FALLBACK),
    # ── THE ORDER, site 2: `getTypeFromTypeReference` asks the namespace chain itself and
    #    passes `enclosingNamespacesDone = true`, so without its OWN hoist a namespace
    #    member displaces a declaration inside one of its own functions.
    "A5": ("Checker.kt",
           "            lexicalTypeSymbolForNode(it, it.text)\n                ?: lookupInEnclosingNamespaces(it, it.text, SymbolFlags.Type)",
           "            lookupInEnclosingNamespaces(it, it.text, SymbolFlags.Type)"),
    # ── THE CONTAINMENT CONTROL: round 748's forbidden `existing` read.  `existing`
    #    ALIASES the main binder's table, so reading it puts every conventionally-bound
    #    name back in play — which is the one thing this whole consult must not do.
    "A6": ("LexicalScopeResolver.kt", EXISTING_READ, EXISTING_READ_BAD),
    # ── THE ONE REGRESSION THIS STEP EXPOSED: `keyof errorType` back to the CLOSED
    #    domain `string`.  Not a bound of the widening but a pre-existing defect it made
    #    reachable, which is why it gets its own arm and its own pin.
    "A7": ("Checker.kt",
           "        if (type === anyType || type === errorType) {\n"
           "            return getUnionType(listOf(stringType, numberType, esSymbolType))\n"
           "        }",
           "        if (type === anyType) return getUnionType(listOf(stringType, numberType, esSymbolType))\n"
           "        if (type === errorType) return stringType"),
    # ── ITS SIBLING: the intersection-with-a-type-parameter arm, whose key domain is
    #    open for the same reason.  A separate arm because a separate shape reaches it.
    "A8": ("Checker.kt",
           "        if (type is Type.Intersection && type.types.any { it is Type.TypeParam }) {",
           "        if (false) {"),
}


def sha(p):
    return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()


def snapshot():
    SNAP.mkdir(parents=True, exist_ok=True)
    for f in FILES:
        shutil.copy2(K / f, SNAP / f)
    return {f: sha(SNAP / f) for f in FILES}


def restore(base):
    for f in FILES:
        shutil.copy2(SNAP / f, K / f)
        assert sha(K / f) == base[f], f"snapshot restore mismatch for {f}"


def patch(name):
    """Apply ONE arm to the tree, from the SNAPSHOT, and prove the edit landed."""
    fname, old, new = ARMS[name]
    src = (SNAP / fname).read_text()
    if src.count(old) != 1:
        say(f"{name}: REFUSED — anchor occurs {src.count(old)} times in {fname}")
        return 1
    (K / fname).write_text(src.replace(old, new))
    d = subprocess.run(["diff", "-u", str(SNAP / fname), str(K / fname)],
                       capture_output=True, text=True)
    changed = sum(1 for l in d.stdout.splitlines()
                  if l[:1] in "+-" and l[:3] not in ("+++", "---"))
    if changed == 0:
        say(f"{name}: REFUSED — edit produced no diff")
        return 1
    say(f"{name}: patched {fname}, diff={changed} lines")
    return 0


def collect(name):
    """Read the XMLs the caller's gradle run produced and classify the arm."""
    ran, red = 0, []
    for p in glob.glob(str(REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest/*.xml")):
        r = ET.parse(p).getroot()
        # The root `name` is the SIMPLE class name with the KMP target appended
        # (`ScopeSpaceTypeResolutionTest[jvm]`), so an exact-match filter reads ZERO —
        # which is what the `ran != EXPECTED_RAN` guard caught on the first sweep.
        cls = (r.get("name") or "").split(".")[-1].split("[")[0]
        if cls not in CLASSES:
            continue
        for tc in r.iter("testcase"):
            ran += 1
            if tc.find("failure") is not None or tc.find("error") is not None:
                red.append(f"{cls}.{tc.get('name')}")
    if ran != EXPECTED_RAN:
        say(f"{name}: REFUSED — ran {ran}, expected {EXPECTED_RAN}")
        return 1
    say(f"{name}: ran={ran} RED={len(red)}")
    for t in sorted(red):
        say(f"      - {t}")
    (REPO / f"build/bench/inv0s10/arm-{name}.txt").write_text("\n".join(sorted(red)))
    return 0


def main():
    SNAP.mkdir(parents=True, exist_ok=True)
    if not (SNAP / FILES[0]).exists():
        for f in FILES:
            shutil.copy2(K / f, SNAP / f)
    base = {f: sha(SNAP / f) for f in FILES}
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    if cmd == "snapshot":
        for f in FILES:
            shutil.copy2(K / f, SNAP / f)
        say("snapshot taken:", {f: sha(SNAP / f)[:12] for f in FILES})
        return 0
    if cmd == "restore":
        for f in FILES:
            shutil.copy2(SNAP / f, K / f)
            assert sha(K / f) == base[f], f"snapshot restore mismatch for {f}"
        say("tree restored from snapshot, sha256 verified")
        return 0
    if cmd == "patch":
        return patch(sys.argv[2])
    if cmd == "collect":
        return collect(sys.argv[2])
    if cmd == "summarise":
        sets = {}
        for name in ARMS:
            f = REPO / f"build/bench/inv0s10/arm-{name}.txt"
            if f.exists():
                sets[name] = set(x for x in f.read_text().split("\n") if x)
        for a in sets:
            if not sets[a]:
                say(f"UNDISCRIMINATED: {a} reddens nothing")
        for a in sets:
            others = set().union(*[v for k, v in sets.items() if k != a] or [set()])
            uniq = sets[a] - others
            say(f"{a}: RED={len(sets[a])}" + (f"  UNIQUE={sorted(uniq)}" if uniq else "  (no unique pin)"))
        if sets:
            say(f"union of RED pins: {len(set().union(*sets.values()))} of {EXPECTED_RAN}")
        return 0
    say("usage: inv0s10-ablate.py snapshot|patch <ARM>|collect <ARM>|restore|summarise")
    return 1


sys.exit(main())
