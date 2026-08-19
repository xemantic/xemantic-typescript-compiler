#!/usr/bin/env python3
"""Round 938 — (CHK.5)(b) ablation: ONE deliberate mistake at a time.

Every arm is applied to, and restored from, a sha256-VERIFIED on-disk snapshot of
`Checker.kt` — never `git checkout` (round 851: an ablation's own revert destroys every
uncommitted edit in the file it touches). Each arm asserts a real diff against the
SNAPSHOT (round 855) and a RAN-COUNT, so an arm whose build died reads as a failure
rather than as a clean sweep (round 808). The filter carries this round's pin class, all
three late-binding classes from rounds 935-937, and the GENERATED corpus classes holding
`dynamicNames` / `dynamicNamesErrors` — the pristine baselines that decide the
binder-visibility rule — so an arm that reddens one of those is visible rather than
hidden by the filter.

Usage: python3 scripts/round938-ablate.py <snapshot-Checker.kt> [arm ...]
"""
import glob, hashlib, os, shutil, subprocess, sys, xml.etree.ElementTree as ET

ROOT = "/home/claude/git/xemantic-typescript-compiler"
CK = os.path.join(ROOT, "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt")
XML = os.path.join(ROOT, "xemantic-typescript-compiler-core/build/test-results/jvmTest")
FILTER = ("--tests '*DuplicateMemberDeclarationTest*' --tests '*LateBound*' "
          "--tests '*dynamicNames*'")
EXPECTED_RAN = 127

ARMS = {
 # A1: the member map goes back to LAST-WINS for a duplicate property.
 "A1": [("""                        val propKey = (if (ModifierFlag.Static in member.modifiers) "static:" else "") + name
                        if (ownPropertyDecls.put(propKey, member) != null) continue
""", "")],
 # A2: the guard's OWN-ONLY clause breaks — it consults the whole member map, which is
 #     PRE-POPULATED with the base types' members, so an override is silently dropped.
 "A2": [("""                        val propKey = (if (ModifierFlag.Static in member.modifiers) "static:" else "") + name
                        if (ownPropertyDecls.put(propKey, member) != null) continue""",
         """                        if (members[name] != null) continue""")],
 # A3: the guard's STATICNESS clause is dropped — a static and an instance member of one
 #     name collide in the single pre-dual-population map.
 "A3": [("""                        val propKey = (if (ModifierFlag.Static in member.modifiers) "static:" else "") + name""",
         """                        val propKey = name""")],
 # A4: the TYPE LITERAL site goes back to last-wins.
 "A4": [("""                    if (!ownLiteralPropertyNames.add(name)) continue
""", "")],
 # A5: the CLASS duplicate scan's computed arm reverts to its own pre-938 `when`.
 "A5": [("""                    duplicateScanComputedKey(nameNode)
                else -> null""",
         """                    when (val e = nameNode.expression) {
                        is StringLiteralNode -> "[\\"${e.text}\\"]" to e.text
                        is NumericLiteralNode -> "[${e.text}]" to normalizeNumericKey(e.text)
                        else -> null
                    }
                else -> null""")],
 # A6: the INTERFACE duplicate scan loses its computed arm (its pre-938 state).
 "A6": [("""                val (text, display) = if (nameNode is ComputedPropertyName) {
                    val (d, k) = duplicateScanComputedKey(nameNode) ?: continue
                    k to d
                } else {
                    val t = getMemberNameText(nameNode) ?: continue
                    t to t
                }""",
         """                val t = getMemberNameText(nameNode) ?: continue
                val (text, display) = t to t""")],
 # A7: the B357 walker stops retracting the general scan's TS2717.
 "A7": [("""                    diagnostics.removeAll {
                        it.code == 2717 && it.fileName == fileName && it.start == cm.namePos
                    }
""", "")],
 # A8: the BINDER-VISIBILITY rule is dropped — a late-bound duplicate reaches TS2300/TS2687,
 #     which is tsc 7.0.2's answer and NOT pristine tsc's (`dynamicNamesErrors`).
 "A8": [("""        name !is ComputedPropertyName || computedLiteralKey(name) != null""",
         """        name !is ComputedPropertyName || computedLiteralKey(name) != null ||
            lateBoundComputedKeyName(name) != null""")],
 # A9: the written-text renderer answers the BOUND key instead of the source spelling.
 "A9": [("""        return txt(cpn.expression)?.let { "[$it]" }""",
         """        return txt(cpn.expression)?.let {
            computedLiteralKey(cpn) ?: lateBoundComputedKeyName(cpn) ?: "[$it]"
        }""")],
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
            log = f"/tmp/ablate938-{arm}.log"
            run(f"./gradlew :xemantic-typescript-compiler-core:jvmTest {FILTER}", log)
            text = open(log, errors="ignore").read()
            if "BUILD SUCCESSFUL" not in text and "FAILED" not in text:
                print(f"{arm}: BUILD PROBLEM"); results.append((arm, "BUILD", [])); restore(snap); continue
            reds, ran = [], 0
            for f in glob.glob(os.path.join(XML, "*.xml")):
                r = ET.parse(f).getroot()
                for tc in r.iter("testcase"):
                    ran += 1
                    if tc.find("failure") is not None or tc.find("error") is not None:
                        reds.append(r.get("name").replace("[jvm]", "") + "." + tc.get("name"))
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
