#!/usr/bin/env python3
"""Round 945 — (CHK.21): one deliberate mistake at a time, from a sha256-VERIFIED snapshot.

Round 807's law: a COMBINED ablation cannot attribute.  Each arm reverts exactly ONE
decision — the whole downlevel flip, its BOUND (`effectiveTarget`), and three individual
gate sites — rebuilds, runs the pin classes QUALIFIED to the core module (CLAUDE.md: a bare
`--tests` filter fails the build in the modules that do not carry the class), and records
which pins reddened.

Two guards this round needs specifically.

(1) The arms may NOT touch `CompilerOptions.defaultedTarget` itself: that accessor is also
round 944's LIB fix, so reverting it would redden `LibAvailabilityDefaultTargetTest` too and
the arm would be a combined one wearing a single arm's name.  Every arm therefore edits
NAMED CALL SITES in `Checker.kt`, and `LibAvailabilityDefaultTargetTest` rides along in the
filter precisely so that a green-there / red-here split is visible.

(2) The 21 pins round 945 re-pointed at an explicit `@target: es5` are in the filter as
well.  They must stay GREEN under A1 (the raw target is what they used to rely on, and an
explicit target reads the same either way) and go RED under A2 (`effectiveTarget` maps that
explicit es5 UP to ES2015 and shuts the gate) — which is what makes A2 a real BOUND rather
than a restatement of A1.

Round 902's law on top: an arm can be DEAD rather than the pin blind, and a diff proves only
that the edit LANDED.  Each arm asserts a per-arm RAN COUNT and a non-zero diff against the
SNAPSHOT (never `git checkout`, round 851).
"""
from __future__ import annotations

import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path("/home/claude/git/xemantic-typescript-compiler")
SRC = REPO / "xemantic-typescript-compiler-core/src/commonMain/kotlin"
CK = SRC / "Checker.kt"
OUT = REPO / "build/bench/round945"
CLASSES = ["*DownlevelGateDefaultTargetTest*", "*LibAvailabilityDefaultTargetTest*",
           "*M04ObjLitSuperSpineMigrationTest*", "*M04ArgsCollisionSpineMigrationTest*",
           "*Inv4SpineBatch10Test*"]
EXPECTED_RAN = 104

# The 23 downlevel-gate lines round 945 flipped, DERIVED from the snapshot rather than
# hard-coded: a line number is a property of a FILE, not of a change, and this round's own
# first ablation attempt refused on a stale one after the KDoc above a gate grew four lines.
# The LIB sites (round 944's) are excluded by name — an arm that reverted those would redden
# `LibAvailabilityDefaultTargetTest` too and be a combined arm wearing a single arm's name.
LIB_MARKERS = ("RealLibResolver.resolve", "RealLibSnapshots.bindLibFiles",
               "if (options.lib.isEmpty()) return")


def downlevel_sites(lines: list[str]) -> list[int]:
    out = []
    for i, l in enumerate(lines, start=1):
        if "options.defaultedTarget" not in l:
            continue
        if l.lstrip().startswith("*") or l.lstrip().startswith("//"):
            continue            # KDoc / comment mention
        if any(m in l for m in LIB_MARKERS):
            continue            # round 944's lib family
        out.append(i)
    return out


def sites_matching(lines: list[str], needle: str) -> list[int]:
    return [i for i in downlevel_sites(lines) if needle in lines[i - 1]]


# Arm selectors, resolved against the snapshot at run time.
ARMS = {
    # A1 — THE FIX: every downlevel gate reads the RAW target again, i.e. a project that
    #      names no target is compiled as if it were ES3 and collects six diagnostics tsc
    #      does not emit.
    "A1": (None, "options.target"),
    # A2 — THE BOUND: `effectiveTarget` instead.  It answers ES2024 for an unset target too,
    #      so it satisfies every unset-target pin — and it maps an EXPLICIT es5 UP to ES2015,
    #      shutting the gates for the one program they exist for.  Only the explicit-target
    #      pins can see the difference, which is why they are more than half of this round.
    "A2": (None, "options.effectiveTarget"),
    # A3 — one site: the TS2737 bigint pass gate.
    "A3": ("ScriptTarget.ES2020", "options.target"),
    # A4 — one site: `spineAccessorModifierActive`, i.e. TS18045.
    "A4": ("spineAccessorModifierActive", "options.target"),
    # A5 — one site with NO dedicated unset-target pin: `checkBlockScopedFunctionDeclarations`'
    #      own `target > ES5 -> return` guard, i.e. TS1250.  (Its pass-slot gate one level up
    #      keeps the fix, so this arm also shows the guard is the load-bearing one.)  Its red
    #      set is a proper subset of A3's and A4's, which is how the round records that four
    #      of the six codes are covered by the combined pin ALONE.
    "A5": ("ScriptTarget.ES5) return", "options.target"),
    # A6 — the TS1250 family as a WHOLE: the pass-slot gate AND the inner guard.  A5 alone
    #      reads RED 0, which is a REDUNDANT GUARD result rather than a blind pin (round 807):
    #      with the pass-slot gate keeping the fix, `checkBlockScopedFunctionDeclarations` is
    #      never scheduled at an unset target, so its own guard cannot be reached.  This arm
    #      reverts both and is what gives TS1250 a discriminating arm at all.
    "A6": ("<pair:TS1250>", "options.target"),
}


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def snap() -> Path:
    return OUT / "Checker.kt.after"


def restore() -> None:
    CK.write_bytes(snap().read_bytes())
    assert sha(CK) == sha(snap())


def run_arm(name: str) -> str:
    needle, repl = ARMS[name]
    lines = snap().read_text(encoding="utf8").split("\n")
    if needle == "<pair:TS1250>":
        anchor = next(i for i, l in enumerate(lines, start=1)
                      if 'pass("checkBlockScopedFunctionDeclarations")' in l)
        sites = [anchor - 1] + sites_matching(lines, "ScriptTarget.ES5) return")
        assert "options.defaultedTarget" in lines[anchor - 2], "pass gate moved"
        sites[0] = anchor - 1
    elif needle is None:
        sites = downlevel_sites(lines)
    else:
        sites = sites_matching(lines, needle)
    if not sites or (needle is None and len(sites) != 23):
        return f"{name}: REFUSED — selector matched {len(sites)} site(s)"
    changed = 0
    for ln in sites:
        s = lines[ln - 1]
        if "options.defaultedTarget" not in s:
            restore()
            return f"{name}: REFUSED — line {ln} does not read options.defaultedTarget"
        lines[ln - 1] = s.replace("options.defaultedTarget", repl)
        changed += 1
    CK.write_text("\n".join(lines), encoding="utf8")
    if changed == 0 or sha(CK) == sha(snap()):
        restore()
        return f"{name}: REFUSED — the edit produced no diff against the snapshot"
    subprocess.run(["rm", "-rf",
                    str(REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest")])
    proc = subprocess.run(
        ["./gradlew", ":xemantic-typescript-compiler-core:jvmTest"]
        + [a for c in CLASSES for a in ("--tests", c)],
        cwd=REPO, capture_output=True, text=True)
    (OUT / f"ablate.{name}.log").write_text(proc.stdout + proc.stderr)
    ran = red = 0
    names: list[str] = []
    res = REPO / "xemantic-typescript-compiler-core/build/test-results/jvmTest"
    for x in sorted(res.glob("*.xml")) if res.exists() else []:
        r = ET.parse(x).getroot()
        ran += int(r.get("tests", 0))
        for tc in r.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                red += 1
                names.append(tc.get("classname", "?").split(".")[-1] + " :: " + tc.get("name", "?"))
    restore()
    if ran != EXPECTED_RAN:
        return f"{name}: REFUSED — ran {ran}, expected {EXPECTED_RAN} (sites {len(sites)})"
    return (f"{name}: ran {ran}, RED {red}  [sites edited {changed}]\n      "
            + "\n      ".join(sorted(names)))


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    if not snap().exists():
        snap().write_bytes(CK.read_bytes())
    assert sha(CK) == sha(snap()), "Checker.kt is not at the AFTER snapshot"
    print(f"snapshot Checker.kt sha256 {sha(snap())}")
    arms = sys.argv[1:] or list(ARMS)
    for a in arms:
        if a not in ARMS:
            print(f"{a}: unknown arm")
            return 2
        print(run_arm(a), flush=True)
    print("complete; tree restored")
    return 0


if __name__ == "__main__":
    sys.exit(main())
