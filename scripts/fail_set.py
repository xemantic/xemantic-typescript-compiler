#!/usr/bin/env python3
"""Extract the sorted set of FAILING test names from jvmTest XMLs (xml.etree, not regex).

Usage:
    python3 scripts/fail_set.py            # print failing test names, one per line
    python3 scripts/fail_set.py > /tmp/fail_baseline.txt
    python3 scripts/fail_set.py --diff /tmp/fail_baseline.txt   # diff vs a saved baseline
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XML_GLOB = os.path.join(REPO, "build", "test-results", "jvmTest",
                        "TEST-com.xemantic.typescript.compiler.TypeScriptCompilerTests_*.xml")


def failing_set():
    names = set()
    for path in glob.glob(XML_GLOB):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for tc in root.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                names.add(tc.get("name"))
    return names


def main():
    cur = failing_set()
    if len(sys.argv) >= 3 and sys.argv[1] == "--diff":
        with open(sys.argv[2]) as f:
            base = set(l.strip() for l in f if l.strip())
        newly_failing = sorted(cur - base)
        newly_passing = sorted(base - cur)
        print(f"baseline={len(base)} current={len(cur)}")
        print(f"REGRESSED (newly failing): {len(newly_failing)}")
        for n in newly_failing:
            print(f"  - {n}")
        print(f"FIXED (newly passing): {len(newly_passing)}")
        for n in newly_passing:
            print(f"  + {n}")
    else:
        for n in sorted(cur):
            print(n)


if __name__ == "__main__":
    main()
