#!/usr/bin/env python3
"""Fail when PLAN-PHASE-5.md has lost a load-bearing structural anchor.

CLAUDE.md § "Execution protocol" sends every agent to the WORK ORDER note at the
top of the QUEUE.  That section has now been carried out of the file TWICE by a
"retire the oldest round notes" trim ((P18.44) restored it, (P18.66) lost it
again) — each time silently, because the file still parses, the queue counts are
unchanged, and an agent that cannot find the section simply picks by its own
judgement.  This script is the thing that notices.

Run it after any scripted edit of PLAN-PHASE-5.md; exit 0 = intact.
"""
import re
import sys
from pathlib import Path

PLAN = Path(__file__).resolve().parent.parent / "PLAN-PHASE-5.md"

# (description, predicate over the file text)
CHECKS = [
    ("the `## QUEUE` heading", lambda t: re.search(r"^## QUEUE\s*$", t, re.M)),
    (
        "the WORK ORDER note (CLAUDE.md's Execution protocol points at it)",
        lambda t: re.search(r"^### WORK ORDER \(owner directive 2026-09-01\)", t, re.M),
    ),
    ("at least one unchecked queue item", lambda t: re.search(r"^- \[ \] ", t, re.M)),
]


def main() -> int:
    text = PLAN.read_text()
    missing = [name for name, ok in CHECKS if not ok(text)]

    # The WORK ORDER must sit UNDER the QUEUE heading and ABOVE the first item:
    # that ordering is what keeps a note-trimming slice bounded by `### ` from
    # reaching it.
    if not missing:
        q = text.index("\n## QUEUE\n")
        w = text.index("\n### WORK ORDER (owner directive 2026-09-01)")
        i = text.index("\n- [ ] ")
        if not (q < w < i):
            missing.append("the QUEUE -> WORK ORDER -> first-item ordering")

    if missing:
        print("PLAN STRUCTURE BROKEN — PLAN-PHASE-5.md is missing:", file=sys.stderr)
        for name in missing:
            print(f"  - {name}", file=sys.stderr)
        print(
            "\nRecover from git history (`git log -S '### WORK ORDER (owner directive'"
            " -- PLAN-PHASE-5.md`) rather than rewriting it from memory.",
            file=sys.stderr,
        )
        return 1

    items = len(re.findall(r"^- \[ \] ", text, re.M))
    done = len(re.findall(r"^- \[x\] ", text, re.M))
    print(f"PLAN STRUCTURE OK — QUEUE + WORK ORDER present, {items} open / {done} done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
