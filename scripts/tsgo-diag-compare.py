#!/usr/bin/env python3
"""Compare a Kotlin-side diagnostics run against the (TSGO.2) oracle (docs/goport-diag-oracle.md § 3).

    scripts/tsgo-diag-compare.py ACTUAL_DIR [--oracle build/goport/diag-oracle]
                                 [--exclude-phase declaration ...] [--ignore-phase] [--show 20]

ACTUAL_DIR mirrors the oracle layout: <case>/<variation>.jsonl, one JSON object per diagnostic in
the harness's sorted order. A configuration is EQUAL when both files hold the same sequence of
parsed JSON objects (key order and JSON whitespace/escaping are irrelevant; the comparison is on
values). A missing actual file counts as `missing`, never as an empty result.

--exclude-phase P   drop lines whose "phase" is P on BOTH sides first (e.g. `declaration` until the
                    declaration transformer is ported in (TSGO.3)).
--ignore-phase      do not compare the "phase" field.

Exit status 0 only when every oracle configuration is equal.
"""

import argparse
import collections
import json
import os
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def load(path, exclude, ignore_phase):
    rows = []
    for l in open(path, encoding="utf-8"):
        if not l.strip():
            continue
        d = json.loads(l)
        if d.get("phase") in exclude:
            continue
        if ignore_phase:
            d.pop("phase", None)
        rows.append(d)
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("actual")
    ap.add_argument("--oracle", default=os.path.join(REPO, "build/goport/diag-oracle"))
    ap.add_argument("--exclude-phase", action="append", default=[])
    ap.add_argument("--ignore-phase", action="store_true")
    ap.add_argument("--only-corpus", action="store_true", help="only configurations the Kotlin corpus runs")
    ap.add_argument("--show", type=int, default=20)
    args = ap.parse_args()

    m = json.load(open(os.path.join(args.oracle, "manifest.json")))
    if not m.get("complete"):
        sys.exit("tsgo-diag-compare: REFUSED: the oracle manifest is incomplete")
    counts = collections.Counter()
    shown = 0
    for e in m["entries"]:
        if args.only_corpus and not e["inCorpus"]:
            continue
        want = load(os.path.join(args.oracle, e["out"]), args.exclude_phase, args.ignore_phase)
        ap_ = os.path.join(args.actual, e["out"])
        if not os.path.exists(ap_):
            counts["missing"] += 1
            continue
        got = load(ap_, args.exclude_phase, args.ignore_phase)
        if got == want:
            counts["equal"] += 1
            continue
        counts["differ"] += 1
        if shown < args.show:
            shown += 1
            i = next((k for k in range(min(len(got), len(want))) if got[k] != want[k]), min(len(got), len(want)))
            print(f"DIFFER {e['case']} [{e['variation']}] oracle={len(want)} actual={len(got)} first difference at #{i}")
            print("   oracle: " + (json.dumps(want[i], ensure_ascii=False)[:300] if i < len(want) else "<end>"))
            print("   actual: " + (json.dumps(got[i], ensure_ascii=False)[:300] if i < len(got) else "<end>"))
    total = sum(counts.values())
    print(f"{total} configurations: " + ", ".join(f"{k} {v}" for k, v in counts.most_common()))
    sys.exit(0 if counts["equal"] == total else 1)


if __name__ == "__main__":
    main()
