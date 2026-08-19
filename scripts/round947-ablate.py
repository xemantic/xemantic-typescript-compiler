#!/usr/bin/env python3
"""(CHK.14) round 947 — one deliberate mistake at a time, against a sha256-verified snapshot.

Never `git checkout` (CLAUDE.md round 789: that also destroys uncommitted work in the
file, and a probe lives in the file it measures).  Every arm is applied to a copy taken
from the SNAPSHOT and diffed against the SNAPSHOT, and each arm asserts it RAN the
expected number of pins so a dead build or an empty `--tests` filter reads as a failure
rather than as "the mistake changed nothing" (round 856).

Two arms per fix by design (round 941): one removes the fix, one removes its BOUND —
a "this is now silent" pin cannot tell a correct refusal from a disabled check.
"""
import hashlib, pathlib, shutil, subprocess, sys, glob
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parent.parent
K = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin"
SNAP = REPO / "build/bench/round947/ablate-snapshot"
FILES = ["Parser.kt", "Checker.kt"]
EXPECTED_RAN = 19   # 16 in core + 3 in -project (the span bound is unobservable from core)

LOOKAHEAD = """        if (token == SyntaxKind.AbstractKeyword &&
            scanner.lookAhead { scanner.scan() == SyntaxKind.NewKeyword }
        ) {
            nextToken()  // consume 'abstract'; the node still starts at `pos`
            return parseConstructorType(modifiers = setOf(ModifierFlag.Abstract), startPos = pos)
        }
"""
CTOR_INFER = """            is ConstructorType -> {
                type.parameters.forEach { p -> p.type?.let { collectInferTypeNames(it, scope) } }
                collectInferTypeNames(type.type, scope)
            }
            is RestType -> collectInferTypeNames(type.type, scope)"""

ARMS = {
    # THE FIX: no `abstract new` production at all — the pre-947 grammar.
    "A1": ("Parser.kt", LOOKAHEAD, ""),
    # THE BOUND: fire on EVERY `abstract` in type position, with no lookahead for `new`.
    # `abstract` is an ordinary identifier there, so the lookahead is the whole of what
    # keeps the arm additive.
    "A2": ("Parser.kt", LOOKAHEAD,
           LOOKAHEAD.replace(" &&\n            scanner.lookAhead { scanner.scan() == SyntaxKind.NewKeyword }\n        ",
                             "\n        ")),
    # THE FIX, checker half: no ConstructorType arm in the infer-name collector.
    "A3": ("Checker.kt", CTOR_INFER,
           "            is RestType -> collectInferTypeNames(type.type, scope)"),
    # THE BOUND: build the node at the `new` instead of at the `abstract`, i.e. drop the
    # span correction.  Recorded as UNDISCRIMINATED if nothing reddens.
    "A4": ("Parser.kt", "startPos = pos)", "startPos = -1)"),
}


def sha(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()


def snapshot():
    SNAP.mkdir(parents=True, exist_ok=True)
    for f in FILES:
        shutil.copy2(K / f, SNAP / f)
    return {f: sha(SNAP / f) for f in FILES}


def restore(base):
    for f in FILES:
        shutil.copy2(SNAP / f, K / f)
        assert sha(K / f) == base[f], f"snapshot restore mismatch for {f}"


def run_arm(name, base):
    fname, old, new = ARMS[name]
    src = (SNAP / fname).read_text()
    if src.count(old) != 1:
        print(f"{name}: REFUSED — anchor occurs {src.count(old)} times in {fname}")
        return None
    (K / fname).write_text(src.replace(old, new))
    # round 855: prove the edit LANDED and is a real diff against the snapshot.
    d = subprocess.run(["diff", "-u", str(SNAP / fname), str(K / fname)],
                       capture_output=True, text=True)
    changed = sum(1 for l in d.stdout.splitlines() if l[:1] in "+-" and l[:3] not in ("+++", "---"))
    if changed == 0:
        print(f"{name}: REFUSED — edit produced no diff")
        return None
    # Each module is invoked SEPARATELY: a bare `--tests` runs in every module and the
    # ones without a match fail the build outright (CLAUDE.md round 886).
    ok = True
    for mod, filt in ((":xemantic-typescript-compiler-core:jvmTest", "*AbstractConstructorTypeTest*"),
                      (":xemantic-typescript-compiler-project:jvmTest", "*AbstractConstructorTypeSpanTest*")):
        b = subprocess.run(["./gradlew", mod, "--tests", filt],
                           cwd=REPO, capture_output=True, text=True)
        ok = ok and ("BUILD SUCCESSFUL" in b.stdout or "tests completed" in b.stdout or b.returncode in (0, 1))
    ran, red = 0, []
    for p in (glob.glob(str(REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest/*.xml"))
              + glob.glob(str(REPO / "xemantic-typescript-compiler-project/build/test-results/jvmTest/*.xml"))):
        r = ET.parse(p).getroot()
        if "AbstractConstructorType" not in (r.get("name") or ""):
            continue
        for tc in r.iter("testcase"):
            ran += 1
            if tc.find("failure") is not None or tc.find("error") is not None:
                red.append(tc.get("name"))
    if ran != EXPECTED_RAN:
        print(f"{name}: REFUSED — ran {ran}, expected {EXPECTED_RAN}"
              f" (builds ok={ok}, diff lines={changed})")
        return None
    print(f"{name}: diff={changed} lines, ran={ran}, RED={len(red)}")
    for t in sorted(red):
        print(f"      - {t}")
    return red


def main():
    if subprocess.run(["git", "diff", "--quiet", "--", *[f"xemantic-typescript-compiler-core/src/commonMain/kotlin/{f}" for f in FILES]],
                      cwd=REPO).returncode == 0:
        print("NOTE: the two sources match HEAD — the snapshot is the un-fixed tree")
    base = snapshot()
    print("snapshot sha256:", {f: base[f][:12] for f in FILES})
    results = {}
    try:
        for name in (sys.argv[1:] or list(ARMS)):
            for m in ("core", "project"):
                subprocess.run(["rm", "-rf",
                                str(REPO / f"xemantic-typescript-compiler-{m}/build/test-results/jvmTest")])
            results[name] = run_arm(name, base)
            restore(base)
    finally:
        restore(base)
        print("tree restored from snapshot, sha256 verified")
    sets = {k: set(v) for k, v in results.items() if v is not None}
    for a in sets:
        for c in sets:
            if a < c and sets[a] & sets[c]:
                print(f"OVERLAP {a} ∩ {c}: {sorted(sets[a] & sets[c])}")
    if sets:
        union = set().union(*sets.values())
        print(f"union of RED pins: {len(union)}")


main()
