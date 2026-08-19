#!/usr/bin/env python3
"""Round 940 — (CHK.7)(i)/(iii) + (CHK.5)(f) ablation: ONE deliberate mistake at a time.

Same contract as `scripts/round938-ablate.py`, which this is modelled on. Every arm is
applied to, and restored from, a sha256-VERIFIED on-disk snapshot of `Checker.kt` — never
`git checkout` (round 851). Each arm must produce a real diff against the SNAPSHOT
(round 855) and the run must reach EXPECTED_RAN testcases, so an arm whose build died
reads as a failure rather than as a clean sweep (round 808). The filter carries this
round's pin class AND the GENERATED corpus classes that pristine's own baselines put on
these decisions, so an arm that reddens one of those is VISIBLE rather than filtered out.

TWO ARMS PER FIX, BY DESIGN: one removes the fix, one removes the BOUND on the fix. A
"this is now silent" pin cannot tell a correct refusal from a disabled check, and this
family has produced a blind pin in five consecutive rounds — the bound arms are what say
the positive controls discriminate.

Usage: python3 scripts/round940-ablate.py <snapshot-Checker.kt> [arm ...]
"""
import glob, hashlib, os, shutil, subprocess, sys, xml.etree.ElementTree as ET

ROOT = "/home/claude/git/xemantic-typescript-compiler"
CK = os.path.join(ROOT, "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt")
XML = os.path.join(ROOT, "xemantic-typescript-compiler-core/build/test-results/jvmTest")
FILTER = ("--tests '*PristineDivergenceRound940Test*' "
          "--tests '*DuplicateMemberDeclarationTest*' "
          "--tests '*duplicateObjectLiteralProperty*' "
          "--tests '*duplicateClassElements*' "
          "--tests '*classWithDuplicateIdentifier*' "
          "--tests '*reassignStaticProp*' "
          "--tests '*gettersAndSettersErrors*' "
          "--tests '*dynamicNames*'")
EXPECTED_RAN = int(os.environ.get("R940_EXPECTED_RAN", "0"))

ARMS = {
 # A1 — (CHK.7)(i) REMOVED: the reference arms go back to naming a computed key by its
 #      SPELLING, which is the pre-940 false positive (`symbolProperty1`/`symbolProperty3`).
 "A1": [("""        // (2) the key's REAL name, when the program proves one.
        lateBoundComputedKeyName(cpn)?.let { return it }
        wellKnownSymbolKey(cpn)?.let { return it }
        // (3) ABSTAIN: the declaration is in hand and denotes no fixed name.
        if (expr is Identifier && lateBindResolveVarDecl(expr) != null) return null
""", "")],
 # A2 — (CHK.7)(i)'s BOUND REMOVED: abstain for EVERY unresolved reference key, i.e. drop
 #      step (4). This is the shape that regresses `duplicateObjectLiteralProperty_computedName3`
 #      (an ACTIVE gate whose keys arrive through an `import * as keys`).
 "A2": [("""        // (4) unresolvable — keep the pre-940 syntactic comparison.
        // Prefix with __@computed: to avoid conflicts with regular property names.
        return when (expr) {
            is Identifier -> "__@computed:${expr.text}"
            is PropertyAccessExpression -> {
                val path = computedPropertyAccessPath(expr) ?: return null
                "__@computed:$path"
            }
            else -> null
        }""",
         """        return null""")],
 # A3 — (CHK.7)(iii) REMOVED: accessor(s) + property flags the WHOLE group again, whatever
 #      the order — the pre-940 false positive on `privateNameDuplicateField`.
 "A3": [("""                    val propIdx = group.indexOfFirst { it.kind == "property" }
                    val lastAccessorIdx = maxOf(
                        group.indexOfLast { it.kind == "getter" },
                        group.indexOfLast { it.kind == "setter" },
                    )
                    if (propIdx > lastAccessorIdx) {
                        group.filter { it.kind == "property" }
                    } else {
                        group
                    }""",
         """                    group""")],
 # A4 — (CHK.7)(iii)'s ORDER CLAUSE REMOVED: always flag only the property. Pristine flags
 #      BOTH when the property comes first (`privateNameDuplicateField` 17/18, 23/24), so
 #      this is the over-correction the positive controls exist to catch.
 "A4": [("""                    if (propIdx > lastAccessorIdx) {
                        group.filter { it.kind == "property" }
                    } else {
                        group
                    }""",
         """                    group.filter { it.kind == "property" }""")],
 # A5 — (CHK.5)(f) REMOVED: a missing late-bound member is named by its VALUE again.
 "A5": [("""        if (nameNode is ComputedPropertyName) {
            computedKeyWrittenText(nameNode)?.let { return it }
        }
""", "")],
}

def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()

def restore(snap):
    shutil.copyfile(snap, CK)
    assert sha(CK) == sha(snap), "restore failed"

def run(cmd, log):
    with open(log, "w") as f:
        return subprocess.call(cmd, cwd=ROOT, stdout=f, stderr=subprocess.STDOUT, shell=True)

def collect():
    reds, ran = [], 0
    for f in glob.glob(os.path.join(XML, "*.xml")):
        r = ET.parse(f).getroot()
        for tc in r.iter("testcase"):
            ran += 1
            if tc.find("failure") is not None or tc.find("error") is not None:
                reds.append(r.get("name").replace("[jvm]", "") + "." + tc.get("name"))
    return ran, reds

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
        ok = True
        for old, new in ARMS[arm]:
            n = src.count(old)
            if n != 1:
                print(f"{arm}: REFUSED — anchor occurs {n} times"); ok = False; break
            src = src.replace(old, new, 1)
        if not ok:
            results.append((arm, "ANCHOR", [])); restore(snap); continue
        open(CK, "w", encoding="utf-8").write(src)
        if sha(CK) == base:
            print(f"{arm}: DEAD ARM — no diff against the snapshot"); restore(snap); continue
        shutil.rmtree(XML, ignore_errors=True)
        log = f"/tmp/ablate940-{arm}.log"
        run(f"./gradlew :xemantic-typescript-compiler-core:jvmTest {FILTER}", log)
        text = open(log, errors="ignore").read()
        if "BUILD SUCCESSFUL" not in text and "FAILED" not in text:
            print(f"{arm}: BUILD PROBLEM"); results.append((arm, "BUILD", [])); restore(snap); continue
        ran, reds = collect()
        if EXPECTED_RAN and ran != EXPECTED_RAN:
            print(f"{arm}: RAN {ran} != {EXPECTED_RAN} — result void")
            results.append((arm, f"ran{ran}", reds)); restore(snap); continue
        print(f"{arm}: ran {ran}, red {len(reds)}")
        for t in sorted(reds): print(f"    - {t}")
        results.append((arm, len(reds), reds))
        restore(snap)
    assert sha(CK) == base, "FATAL: tree not restored"
    print("\n=== SUMMARY (tree restored) ===")
    for arm, red, _ in results:
        print(f"{arm}\t{red}")

main()
