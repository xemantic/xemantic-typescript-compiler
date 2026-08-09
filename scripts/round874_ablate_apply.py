#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""(WARM.21) round 874 — inject ONE deliberate mistake into the TAV candidate gate.

Round 807: a COMBINED ablation cannot attribute, so exactly one arm is applied
per invocation and the tree is restored before the next. Rounds 855/856: every
arm is dry-run first and must produce a REAL diff that reverts clean — an arm
that edits nothing is indistinguishable from a redundant guard.

Each arm drops ONE SOURCE of candidate names, which is the shape the pins were
written against: the gate's soundness is the claim that its sources are
exhaustive over `tavBuildLevel`, so dropping one must lose exactly the
diagnostics that source feeds.

Usage: round874_ablate_apply.py <A1..A8>
"""
import sys

CHECKER = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"

ARMS = {
    # The file ROOT's two contributions, dropped one at a time.
    "A1": (CHECKER,
           "        root.typeOnly?.let { spineTavCandidates.addAll(it) }\n",
           ""),
    "A2": (CHECKER,
           "        root.nsOnly?.let { spineTavCandidates.addAll(it) }\n",
           ""),
    # `tavFnLevel`'s only contribution: TYPE PARAMETER names.
    "A3": (CHECKER,
           "            is TypeParameter -> spineTavCandidates.add(node.name.text)\n",
           ""),
    # `tavModuleLevel`'s three, dropped one at a time — they are three different
    # syntaxes feeding two different diagnostics, so a single "the module arm is
    # wrong" mistake would have been satisfied by any of them.
    "A4": (CHECKER,
           "                is InterfaceDeclaration -> spineTavCandidates.add(st.name.text)\n",
           ""),
    "A5": (CHECKER,
           "                is TypeAliasDeclaration -> spineTavCandidates.add(st.name.text)\n",
           ""),
    "A6": (CHECKER,
           "                is ModuleDeclaration ->\n"
           "                    (st.name as? Identifier)?.text?.let { spineTavCandidates.add(it) }\n",
           ""),
    # The static keyword set, folded in so the gate is one probe rather than two.
    "A7": (CHECKER,
           "        spineTavCandidates.addAll(TYPE_ONLY_KEYWORDS)\n",
           ""),
    # The hook itself: the collector is never called, so every non-root source
    # disappears at once. It is the "is it wired at all" arm, and its red set
    # must be the UNION of A3-A6's.
    "A8": (CHECKER,
           "            NodeKind.TYPE_PARAMETER, NodeKind.MODULE_BLOCK -> spineTavCandidateNode(node)\n",
           ""),
}


def main() -> None:
    if len(sys.argv) != 2 or sys.argv[1] not in ARMS:
        sys.exit(__doc__)
    path, old, new = ARMS[sys.argv[1]]
    src = open(path).read()
    if src.count(old) != 1:
        sys.exit(f"REFUSED: {sys.argv[1]} anchor occurs {src.count(old)} times, expected 1")
    open(path, "w").write(src.replace(old, new, 1))
    print(f"applied {sys.argv[1]} to {path}")


if __name__ == "__main__":
    main()
