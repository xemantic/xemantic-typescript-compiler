#!/usr/bin/env python3
"""(INC.41) step 1 — classify the replay-vs-fresh captured-type divergences PER ELEMENT.

`Inc41ClassifyMain` dumps every diverging row (span, source text, both renderings).
This reduces them the way (INC.23) insists a capture population must be reduced:
NOT by counting rows — a divergence nested inside a 400-character signature would
otherwise be counted once per parameter it fragments into, and a substring heuristic
over-reported a 168-row population at 100% — but by DIFFING the two renderings into
their minimal differing ELEMENTS and counting DISTINCT (fresh, replay) element pairs.

Usage: inc41_classify.py <rows.tsv> [--carets <n> <out.tsv>]
"""
import collections, difflib, re, sys

TOK = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*|=>|\.\.\.|[^\sA-Za-z0-9_$]")


def toks(s):
    return TOK.findall(s)


def elements(fresh, replay):
    """The minimal differing (fresh-chunk, replay-chunk) pairs of one row."""
    a, b = toks(fresh), toks(replay)
    out = []
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
        if tag == "equal":
            continue
        out.append((" ".join(a[i1:i2]), " ".join(b[j1:j2])))
    return out


BARE = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")


def classify(f, r):
    """One element pair -> a cause label."""
    fs, rs = f.strip(), r.strip()
    fa = re.search(r"\bany\b", fs) is not None
    ra = re.search(r"\bany\b", rs) is not None
    if ra and not fa:
        return "LOST-INFERENCE (replay says any)"
    if fa and not ra:
        return "GAINED-INFERENCE (fresh says any)"
    fb, rb = BARE.match(fs) is not None, BARE.match(rs) is not None
    if fb and rb:
        return "ALIAS-vs-ALIAS (two names, one interned type)"
    if rb and not fb:
        return "ALIAS-vs-EXPANSION (replay names it, fresh expands)"
    if fb and not rb:
        return "EXPANSION-vs-ALIAS (fresh names it, replay expands)"
    return "OTHER"


def main():
    path = sys.argv[1]
    rows = []
    with open(path) as fh:
        header = fh.readline()
        for line in fh:
            p = line.rstrip("\n").split("\t")
            if len(p) < 9:
                continue
            rows.append(dict(file=p[0], start=int(p[1]), end=int(p[2]), line=int(p[3]),
                             char=int(p[4]), kind=p[5], span=p[6], fresh=p[7], replay=p[8]))

    pair_rows = collections.defaultdict(list)   # (freshChunk, replayChunk) -> rows
    cause_rows = collections.defaultdict(set)   # cause -> row indices
    cause_pairs = collections.defaultdict(set)
    multi = 0
    for idx, row in enumerate(rows):
        els = elements(row["fresh"], row["replay"])
        if len(els) > 1:
            multi += 1
        for f, r in els:
            pair_rows[(f, r)].append(idx)
            c = classify(f, r)
            cause_rows[c].add(idx)
            cause_pairs[c].add((f, r))

    print("rows=%d  distinctElementPairs=%d  rowsWithMoreThanOneDifferingElement=%d"
          % (len(rows), len(pair_rows), multi))
    print()
    print("%-46s %7s %7s %7s" % ("cause", "rows", "pairs", "files"))
    for c in sorted(cause_rows, key=lambda k: -len(cause_rows[k])):
        files = {rows[i]["file"] for i in cause_rows[c]}
        print("%-46s %7d %7d %7d" % (c, len(cause_rows[c]), len(cause_pairs[c]), len(files)))
    print()
    print("=== the 30 largest distinct element pairs")
    for (f, r), idxs in sorted(pair_rows.items(), key=lambda kv: -len(kv[1]))[:30]:
        print("%5d  [%s]" % (len(idxs), classify(f, r)))
        print("       fresh : %s" % f[:170])
        print("       replay: %s" % r[:170])

    if "--carets" in sys.argv:
        i = sys.argv.index("--carets")
        n, out = int(sys.argv[i + 1]), sys.argv[i + 2]
        # One caret per DISTINCT element pair, largest first — the sample an LSP
        # cross-check needs: every cause is represented, and no cause is represented
        # by its row count.
        picked = []
        for (f, r), idxs in sorted(pair_rows.items(), key=lambda kv: -len(kv[1])):
            row = rows[idxs[0]]
            picked.append((("p%03d" % len(picked)), row, f, r, len(idxs)))
            if len(picked) >= n:
                break
        with open(out, "w") as fh:
            for label, row, f, r, n_ in picked:
                fh.write("%s\t%s\t%d\n" % (label, row["file"], row["start"]))
        with open(out + ".key", "w") as fh:
            for label, row, f, r, n_ in picked:
                fh.write("%s\t%s\t%d\t%d\t%s\t%s\t%s\t%s\t%s\n"
                         % (label, row["file"], row["start"], n_, classify(f, r),
                            row["span"], f, r, row["fresh"] + " ||| " + row["replay"]))
        print("\nwrote %d carets -> %s (+ .key)" % (len(picked), out))


main()
