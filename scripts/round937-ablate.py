#!/usr/bin/env python3
"""Round 937 — (CHK.5)(a) ablation: ONE deliberate mistake at a time.

Every arm is applied to, and restored from, a sha256-VERIFIED on-disk snapshot of
`Checker.kt` — never `git checkout` (round 851: an ablation's own revert destroys
every uncommitted edit in the file it touches). Each arm asserts a real diff against
the SNAPSHOT (round 855: an arm that changes nothing prints as "ALL PINS GREEN" and
is indistinguishable from a redundant guard) and asserts a RAN-COUNT, so an arm whose
build died reads as a failure rather than as a clean sweep (round 808).

Both prior late-binding pin classes run alongside this round's, so an arm that reddens
a round-935/936 row is visible rather than hidden by the filter.

Usage: python3 scripts/round937-ablate.py <snapshot-Checker.kt> [arm ...]
"""
import hashlib, os, re, shutil, subprocess, sys, xml.etree.ElementTree as ET, glob

ROOT = "/home/claude/git/xemantic-typescript-compiler"
CK = os.path.join(ROOT, "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt")
XML = os.path.join(ROOT, "xemantic-typescript-compiler-core/build/test-results/jvmTest")
EXPECTED_RAN = 91

ARMS = {
 # A1: the declaration-side namer loses late binding entirely.
 "A1": [("        getMemberName(name) ?: computedLiteralKey(name) ?: lateBoundComputedKeyName(name)",
         "        getMemberName(name) ?: computedLiteralKey(name)")],
 # A2: the TYPE LITERAL member site reverts to its pre-937 `when`.
 "A2": [("""                    val name = declaredMemberName(member.name) ?: continue
                    if (name.isEmpty()) {""",
         """                    val name = when (val n = member.name) {
                        is Identifier -> n.text
                        is StringLiteralNode -> n.text
                        is NumericLiteralNode -> n.text
                        else -> continue
                    }
                    if (name.isEmpty()) {""")],
 # A3: a computed METHOD name no longer reaches getTypeOfSymbolWorker's method branch.
 "A3": [("""                val methodName = (decl.name as? Identifier)?.text
                    ?: (decl.name as? ComputedPropertyName)?.let { declaredMemberName(it) }""",
         """                val methodName = (decl.name as? Identifier)?.text"""),],
 # A4: the TS2339 firewall's namer refuses a late-bound key again.
 "A4": [("        is ComputedPropertyName -> computedLiteralKey(nameNode) ?: lateBoundComputedKeyName(nameNode)",
         "        is ComputedPropertyName -> computedLiteralKey(nameNode)")],
 # A5: the class/interface member loop's METHOD and ACCESSOR arms only.
 "A5": [("""                        val name = declaredMemberName(member.name) ?: continue
                        // Call signatures (empty name) and construct signatures ("new") are""",
         """                        val name = getMemberName(member.name) ?: continue
                        // Call signatures (empty name) and construct signatures ("new") are"""),
        ("""                    is GetAccessor -> {
                        val name = declaredMemberName(member.name) ?: continue""",
         """                    is GetAccessor -> {
                        val name = getMemberName(member.name) ?: continue"""),
        ("""                    is SetAccessor -> {
                        val name = declaredMemberName(member.name) ?: continue""",
         """                    is SetAccessor -> {
                        val name = getMemberName(member.name) ?: continue""")],
 # A6: checkImplementsClauses' own-member collection reverts to Identifier-only.
 "A6": [("""                is PropertyDeclaration -> declaredMemberName(member.name)
                is MethodDeclaration -> declaredMemberName(member.name)
                is GetAccessor -> declaredMemberName(member.name)
                is SetAccessor -> declaredMemberName(member.name)""",
         """                is PropertyDeclaration -> (member.name as? Identifier)?.text
                is MethodDeclaration -> (member.name as? Identifier)?.text
                is GetAccessor -> (member.name as? Identifier)?.text
                is SetAccessor -> (member.name as? Identifier)?.text""")],
 # A7: classMemberNamesTransitive (B175's class-value comparison) reverts.
 "A7": [("""                    is PropertyDeclaration -> declaredMemberName(m.name) to m.modifiers
                    is MethodDeclaration -> declaredMemberName(m.name) to m.modifiers
                    is GetAccessor -> declaredMemberName(m.name) to m.modifiers
                    is SetAccessor -> declaredMemberName(m.name) to m.modifiers""",
         """                    is PropertyDeclaration -> (m.name as? Identifier)?.text to m.modifiers
                    is MethodDeclaration -> (m.name as? Identifier)?.text to m.modifiers
                    is GetAccessor -> (m.name as? Identifier)?.text to m.modifiers
                    is SetAccessor -> (m.name as? Identifier)?.text to m.modifiers""")],
 # A8: the dedicated walker stops retracting the general relation's duplicate.
 "A8": [("""                diagnostics.removeAll {
                    it.code == 2322 && it.fileName == fileName && it.start == lhs.pos &&
                        it.message == dupMessage
                }
""", "")],
 # A9: the member loop's PROPERTY arm alone loses late binding (site independence).
 "A9": [("""                    is PropertyDeclaration -> {
                        val name = declaredMemberName(member.name) ?: continue
                        val propSymbol = Symbol(SymbolFlags.Property, name)""",
         """                    is PropertyDeclaration -> {
                        val name = getMemberName(member.name) ?: computedLiteralKey(member.name) ?: continue
                        val propSymbol = Symbol(SymbolFlags.Property, name)""")],
 # A10: the fnsCheckClass sibling (namespace-local implements) reverts.
 "A10": [("""            is PropertyDeclaration ->
                ((m.name as? Identifier)?.text ?: declaredComputedMemberName(m.name))?.let { names.add(it) }
            is MethodDeclaration ->
                ((m.name as? Identifier)?.text ?: declaredComputedMemberName(m.name))?.let { names.add(it) }
            is GetAccessor ->
                ((m.name as? Identifier)?.text ?: declaredComputedMemberName(m.name))?.let { names.add(it) }
            is SetAccessor ->
                ((m.name as? Identifier)?.text ?: declaredComputedMemberName(m.name))?.let { names.add(it) }""",
          """            is PropertyDeclaration -> (m.name as? Identifier)?.text?.let { names.add(it) }
            is MethodDeclaration -> (m.name as? Identifier)?.text?.let { names.add(it) }
            is GetAccessor -> (m.name as? Identifier)?.text?.let { names.add(it) }
            is SetAccessor -> (m.name as? Identifier)?.text?.let { names.add(it) }""")],
}

def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()

def restore(snap):
    shutil.copyfile(snap, CK)
    assert sha(CK) == sha(snap), "restore failed"

def run(cmd, log):
    with open(log, "w") as f:
        return subprocess.call(cmd, cwd=ROOT, stdout=f, stderr=subprocess.STDOUT, shell=True)

def main():
    snap = sys.argv[1]
    arms = sys.argv[2:] or list(ARMS)
    base = sha(snap)
    print(f"snapshot sha256 {base}")
    assert sha(CK) == base, "tree does not match the snapshot — refusing"
    results = []
    for arm in arms:
        if arm not in ARMS:
            print(f"UNKNOWN ARM {arm}"); results.append((arm, "UNKNOWN", [])); continue
        src = open(snap, encoding="utf-8").read()
        for old, new in ARMS[arm]:
            n = src.count(old)
            if n != 1:
                print(f"{arm}: REFUSED — anchor occurs {n} times"); break
            src = src.replace(old, new, 1)
        else:
            open(CK, "w", encoding="utf-8").write(src)
            if sha(CK) == base:
                print(f"{arm}: DEAD ARM — no diff against the snapshot"); restore(snap); continue
            ok = run("./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*LateBound*'",
                     f"/tmp/ablate-{arm}.log")
            reds, ran = [], 0
            for f in glob.glob(os.path.join(XML, "*LateBound*.xml")):
                r = ET.parse(f).getroot()
                for tc in r.iter("testcase"):
                    ran += 1
                    for _ in tc.findall("failure"):
                        reds.append(tc.get("name"))
            if "BUILD SUCCESSFUL" not in open(f"/tmp/ablate-{arm}.log", errors="ignore").read() \
               and "FAILED" not in open(f"/tmp/ablate-{arm}.log", errors="ignore").read():
                print(f"{arm}: BUILD PROBLEM"); results.append((arm, "BUILD", [])); restore(snap); continue
            if ran != EXPECTED_RAN:
                print(f"{arm}: RAN {ran} != {EXPECTED_RAN} — result void")
                results.append((arm, f"ran{ran}", reds)); restore(snap); continue
            print(f"{arm}: ran {ran}, red {len(reds)}")
            for t in sorted(reds): print(f"    - {t}")
            results.append((arm, len(reds), reds))
            restore(snap)
            continue
        restore(snap)
    assert sha(CK) == base, "FATAL: tree not restored"
    print("\n=== SUMMARY (tree restored) ===")
    for arm, red, _ in results:
        print(f"{arm}\t{red}")

main()
