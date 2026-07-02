#!/usr/bin/env python3
"""Dump the full failure message (expected-vs-actual diff) for a test base name.

Usage: python3 scripts/dump_diff.py <substring> [<substring> ...]
Matches any testcase whose name contains the substring. Prints the full
<failure> text for each match (the unified diff baseline mismatch).
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
#  xemantic-typescript-compiler - a conformant TypeScript compiler and type
#  checker that runs on JVM, native, and WebAssembly
#  Copyright (C) 2026 Kazimierz Pogoda / Xemantic
#
#  This program is free software: you can redistribute it and/or modify
#  it under the terms of the GNU Affero General Public License as
#  published by the Free Software Foundation, version 3 of the License.
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU Affero General Public License for more details.
#
#  You should have received a copy of the GNU Affero General Public
#  License along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
#  As a special exception, this file contains Helper Code covered by the
#  xemantic-typescript-compiler Output Exception; additional permissions
#  are granted as described in the file LICENSE-EXCEPTION.

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
