#!/usr/bin/env python3
"""(INV.0) step 10d — one deliberate mistake at a time. Same protocol as 10a/10b/10c."""
import glob
import hashlib
import pathlib
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parent.parent
K = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin"
SNAP = REPO / "build/bench/inv0s10d/ablate-snapshot"
OUT = REPO / "build/bench/inv0s10d"
FILES = ["Checker.kt", "NameResolver.kt"]
CLASSES = ("TypeOracleTest",)
EXPECTED_RAN = 27


def say(*a):
    print(*a, flush=True)


ARMS = {
    # The SCOPE-SPACE leg removed: the whole B83.5 population becomes unreachable, so a
    # parameter and a body local answer nothing and a shadowing name answers the outer.
    "D1": ("Checker.kt",
           "        nameResolver.lexicalSymbolForOracle(location, name, meaning)?.let { return it }\n",
           ""),
    # The same leg as a FALLBACK instead of first — round 748's ordering question, asked
    # of the oracle: the unique half still answers and every shadowing name answers the
    # OUTER declaration.
    "D2": ("Checker.kt",
           "        nameResolver.lexicalSymbolForOracle(location, name, meaning)?.let { return it }\n"
           "        lookupInEnclosingNamespaces(location, name, meaning)?.let { return it }\n"
           "        val symbol = lookupPerFileForNode(location, name) ?: return null\n"
           "        return symbol.takeIf { it.flags.hasAny(meaning) }",
           "        lookupInEnclosingNamespaces(location, name, meaning)?.let { return it }\n"
           "        lookupPerFileForNode(location, name)?.takeIf { it.flags.hasAny(meaning) }\n"
           "            ?.let { return it }\n"
           "        return nameResolver.lexicalSymbolForOracle(location, name, meaning)"),
    # The MEANING mask dropped from the per-file leg: the declaration spaces stop being
    # split for every conventionally-bound name.
    "D3": ("Checker.kt",
           "        return symbol.takeIf { it.flags.hasAny(meaning) }",
           "        return symbol"),
    # `stopFlags` on the oracle's ascent.
    "D4": ("NameResolver.kt",
           "            stopFlags = if (meaning.hasAny(SymbolFlags.Value)) VALUE_SPACE_BINDING else null,",
           "            stopFlags = null,"),
    # The ascent GATED on the program-wide name gate, which is what every other consult
    # does — and which covers only the six DECLARATION kinds, so a parameter and a
    # variable fall out of the answer.
    "D5": ("NameResolver.kt",
           "    fun lexicalSymbolForOracle(node: Node, name: String, meaning: SymbolFlags): Symbol? {\n"
           "        val scopes = lexicalResolver.scopesOfOwningFile(node) ?: return null",
           "    fun lexicalSymbolForOracle(node: Node, name: String, meaning: SymbolFlags): Symbol? {\n"
           "        if (name !in checker.lexicalBlockScopedTypeNames &&\n"
           "            name !in checker.lexicalBlockScopedValueNames\n"
           "        ) {\n"
           "            return null\n"
           "        }\n"
           "        val scopes = lexicalResolver.scopesOfOwningFile(node) ?: return null"),
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
    say("usage: inv0s10d-ablate.py snapshot|patch <ARM>|collect <ARM>|restore|summarise")
    return 1


sys.exit(main())
