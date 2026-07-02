#!/usr/bin/env python3
"""Extract the sorted set of FAILING test names from jvmTest XMLs (xml.etree, not regex).

Usage:
    python3 scripts/fail_set.py            # print failing test names, one per line
    python3 scripts/fail_set.py > /tmp/fail_baseline.txt
    python3 scripts/fail_set.py --diff /tmp/fail_baseline.txt   # diff vs a saved baseline
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
