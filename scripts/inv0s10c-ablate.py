#!/usr/bin/env python3
"""(INV.0) step 10c — one deliberate mistake at a time. Same protocol as 10a/10b."""
import glob
import hashlib
import pathlib
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parent.parent
K = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin"
SNAP = REPO / "build/bench/inv0s10c/ablate-snapshot"
OUT = REPO / "build/bench/inv0s10c"
FILES = ["Checker.kt", "NameResolver.kt"]
CLASSES = ("ScopeSpaceHeritageResolutionTest",)
EXPECTED_RAN = 5


def say(*a):
    print(*a, flush=True)


ARMS = {
    # The BASE-TYPE resolver — the fourth one, and the one `resolveBaseTypesLazy` calls.
    "C1": ("Checker.kt",
           "            is Identifier -> nameResolver.lexicalHeritageSymbolForNode(baseExpr, baseExpr.text)\n"
           "                ?: lookupInEnclosingNamespaces(baseExpr, baseExpr.text, SymbolFlags.Type)",
           "            is Identifier -> lookupInEnclosingNamespaces(baseExpr, baseExpr.text, SymbolFlags.Type)"),
    # The BASE-TYPE resolver as a FALLBACK instead of first — the ordering question round
    # 748 answered for type references, re-asked one resolver over.
    "C2": ("Checker.kt",
           "            is Identifier -> nameResolver.lexicalHeritageSymbolForNode(baseExpr, baseExpr.text)\n"
           "                ?: lookupInEnclosingNamespaces(baseExpr, baseExpr.text, SymbolFlags.Type)\n"
           "                ?: lookupTypeSymbolInInferenceNamespace(baseExpr.text)\n"
           "                ?: lookupPerFileForNode(baseExpr, baseExpr.text)",
           "            is Identifier -> lookupInEnclosingNamespaces(baseExpr, baseExpr.text, SymbolFlags.Type)\n"
           "                ?: lookupTypeSymbolInInferenceNamespace(baseExpr.text)\n"
           "                ?: lookupPerFileForNode(baseExpr, baseExpr.text)\n"
           "                ?: nameResolver.lexicalHeritageSymbolForNode(baseExpr, baseExpr.text)"),
    # The IMPLEMENTS walker's own probe — the fifth resolver.
    "C3": ("Checker.kt",
           "                    is Identifier -> nameResolver.lexicalHeritageSymbolForNode(tn, baseIfaceName)\n"
           "                        ?: lookupPerFileForNode(tn, baseIfaceName)",
           "                    is Identifier -> lookupPerFileForNode(tn, baseIfaceName)"),
    # The QUALIFIED-NAME root.
    "C4": ("NameResolver.kt",
           "            is Identifier -> lexicalQualifiedRootSymbolForNode(l, l.text)\n"
           "                ?: lookupInEnclosingNamespaces(l, l.text, checker.QUALIFIED_LEFT_MEANING)",
           "            is Identifier -> lookupInEnclosingNamespaces(l, l.text, checker.QUALIFIED_LEFT_MEANING)"),
    # The qualified root's EVIDENCE gate: adopt a root that cannot answer a member.
    "C5": ("NameResolver.kt",
           "        return sym.takeIf { it.exports != null }",
           "        return sym"),
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
    OUT.mkdir(parents=True, exist_ok=True)
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
    say("usage: inv0s10c-ablate.py snapshot|patch <ARM>|collect <ARM>|restore|summarise")
    return 1


sys.exit(main())
