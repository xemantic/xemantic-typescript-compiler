#!/usr/bin/env python3
"""Fail when PLAN-PHASE-5.md has lost a load-bearing structural anchor.

CLAUDE.md § "Execution protocol" sends every agent to the WORK ORDER note at the
top of the QUEUE.  That section has now been carried out of the file TWICE by a
"retire the oldest round notes" trim ((P18.44) restored it, (P18.66) lost it
again) — each time silently, because the file still parses, the queue counts are
unchanged, and an agent that cannot find the section simply picks by its own
judgement.  This script is the thing that notices.

Run it after any scripted edit of PLAN-PHASE-5.md; exit 0 = intact.

It also counts the queue items, and `--expect-open N --expect-done M` turns that
into an assertion.  Use it around any scripted slice: a slice bounded by a
hand-picked later item silently eats every item in between, which is how (P18.66)
lost the WORK ORDER and how one edit in (P18.70) ate four queue items before the
counts were read.  The structural checks above cannot see that — the file still
has a QUEUE, a WORK ORDER and items; there are simply fewer of them.
"""
import argparse
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
    ap = argparse.ArgumentParser()
    ap.add_argument("--expect-open", type=int, help="fail unless exactly N open items remain")
    ap.add_argument("--expect-done", type=int, help="fail unless exactly M done items remain")
    args = ap.parse_args()

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

    for label, got, want in (("open", items, args.expect_open), ("done", done, args.expect_done)):
        if want is not None and got != want:
            print(
                f"QUEUE ITEM COUNT CHANGED — {label}: expected {want}, found {got}.\n"
                "A scripted slice bounded by a hand-picked later item eats everything\n"
                "between the two. Check `git diff PLAN-PHASE-5.md | grep '^-- \\['`.",
                file=sys.stderr,
            )
            return 1

    print(f"PLAN STRUCTURE OK — QUEUE + WORK ORDER present, {items} open / {done} done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
