#!/usr/bin/env python3
"""(INV.0) step 10b — one deliberate mistake at a time, against a sha256-verified snapshot.

Same shape as `scripts/inv0s10-ablate.py` (step 10a) and for the same reasons: the
gradle run belongs to the CALLER, so a killed arm cannot leave the tree ablated with a
plausible result file beside it (round 805), and every arm is patched from the SNAPSHOT
rather than from the working tree, so round 922's "`git diff --shortstat` is vacuous on a
tree that already carries the round's own work" cannot bite.

    scripts/inv0s10b-ablate.py snapshot
    scripts/inv0s10b-ablate.py patch A1
    ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*ScopeSpaceValueResolutionTest*' ...
    scripts/inv0s10b-ablate.py collect A1
    scripts/inv0s10b-ablate.py restore
    scripts/inv0s10b-ablate.py summarise
"""
import glob
import hashlib
import pathlib
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parent.parent
K = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin"
SNAP = REPO / "build/bench/inv0s10b/ablate-snapshot"
OUT = REPO / "build/bench/inv0s10b"
FILES = ["NodeWalk.kt", "Binder.kt", "Checker.kt", "NameResolver.kt", "LexicalScopeResolver.kt"]
CLASSES = ("ScopeSpaceValueResolutionTest", "LexicalScopeDeferralTest",
           "ScopeSpaceTypeResolutionTest", "LexicalScopeResolverTest")
EXPECTED_RAN = 35  # 6 + 15 + 8 + 6


def say(*a):
    print(*a, flush=True)


ARMS = {
    # The STAMP: `function` and `namespace` leave `indexSourceFile`'s range, so they never
    # reach the projection, the gate or the consult. `class`/`enum` keep theirs.
    "A1": ("NodeWalk.kt",
           "if (k >= NodeKind.FUNCTION_DECLARATION && k <= NodeKind.MODULE_DECLARATION &&",
           "if (k >= NodeKind.CLASS_DECLARATION && k <= NodeKind.ENUM_DECLARATION &&"),
    # The FLAG MASK: the consult accepts only TYPE-space declarations, so no value-space
    # symbol is ever answered. The gate still admits the name and the ascent still runs.
    "A2": ("NameResolver.kt",
           "            flags = SymbolFlags.ScopeValueDeclaration,",
           "            flags = SymbolFlags.ScopeTypeDeclaration,"),
    # The OVERRIDE becomes a FALLBACK: consult only when the conventional ladder answered
    # `any`. That is the shape the step was FIRST written in, and it closes the unique half
    # instead of the shadowing one.
    "A3": ("Checker.kt",
           "        if (conventional === anyType || conventional === errorType) return conventional",
           "        if (conventional !== anyType && conventional !== errorType) return conventional"),
    # `stopFlags`: the ascent filters past an inner VARIABLE and answers an outer nested
    # `function` — a wrong answer rather than a miss.
    "A4": ("NameResolver.kt",
           "            stopFlags = VALUE_SPACE_BINDING,\n",
           ""),
    # The PER-FILE gate: the program-wide union alone decides, so a name declared in
    # another file forces this one's INV.2(c) tables.
    "A5": ("NameResolver.kt",
           "        if (name !in result.scopeValueNames) return null\n",
           ""),
    # `declare global` is an AUGMENTATION and declares nothing; claiming its carrier name
    # puts `global` in the VALUE projection.
    "A6": ("Binder.kt",
           "            if (n.text == \"global\" && ModifierFlag.Declare in decl.modifiers) null else n.text",
           "            n.text"),
}


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def patch(name):
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
    ran, red = 0, []
    for p in glob.glob(str(REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest/*.xml")):
        r = ET.parse(p).getroot()
        # The root `name` carries the KMP target (`FooTest[jvm]`), so an exact-match
        # filter reads ZERO — which is what the `ran != EXPECTED_RAN` guard caught on
        # step 10a's first sweep.
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
    (OUT / f"arm-{name}.txt").write_text("\n".join(sorted(red)))
    return 0


def main():
    SNAP.mkdir(parents=True, exist_ok=True)
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    if cmd == "snapshot":
        for f in FILES:
            shutil.copy2(K / f, SNAP / f)
        say("snapshot taken:", {f: sha(SNAP / f)[:12] for f in FILES})
        return 0
    if not (SNAP / FILES[0]).exists():
        say("REFUSED — take a snapshot first")
        return 1
    base = {f: sha(SNAP / f) for f in FILES}
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
            f = OUT / f"arm-{name}.txt"
            if f.exists():
                sets[name] = {x for x in f.read_text().split("\n") if x}
        for a in sets:
            others = set().union(*[v for k, v in sets.items() if k != a] or [set()])
            uniq = sets[a] - others
            say(f"{a}: RED={len(sets[a])}" + (f"  UNIQUE={sorted(uniq)}" if uniq else "  (no unique pin)"))
            if not sets[a]:
                say(f"    UNDISCRIMINATED: {a} reddens nothing")
        if sets:
            say(f"union of RED pins: {len(set().union(*sets.values()))} of {EXPECTED_RAN}")
        return 0
    say("usage: inv0s10b-ablate.py snapshot|patch <ARM>|collect <ARM>|restore|summarise")
    return 1


sys.exit(main())
