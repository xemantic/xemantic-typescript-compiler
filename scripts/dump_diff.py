#!/usr/bin/env python3
"""Dump the full failure message (expected-vs-actual diff) for a test base name.

Usage: python3 scripts/dump_diff.py <substring> [<substring> ...]
Matches any testcase whose name contains the substring. Prints the full
<failure> text for each match (the unified diff baseline mismatch).
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XML_GLOB = os.path.join(
    REPO_ROOT, "build", "test-results", "jvmTest",
    "TEST-com.xemantic.typescript.compiler.TypeScriptCompilerTests_*.xml",
)


def main(argv):
    if not argv:
        print("usage: dump_diff.py <substring> [...]")
        return 1
    for xml_file in glob.glob(XML_GLOB):
        try:
            tree = ET.parse(xml_file)
        except ET.ParseError:
            continue
        for tc in tree.getroot().iter("testcase"):
            name = tc.get("name", "")
            if not any(s in name for s in argv):
                continue
            failure = tc.find("failure")
            if failure is None:
                continue
            print("=" * 80)
            print("TEST:", name)
            print("=" * 80)
            print(failure.text or "(no message)")
            print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
