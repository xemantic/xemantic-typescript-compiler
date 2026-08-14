#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""(WARM.33) round 906 — price the layout simulation printed by the `reachmemo`
tier.

Two quantities, and the second is the one that decides the round.

1. The ACCESS-STREAM cost of each layout. The zeroing of a freshly allocated
   memo array is separated out and NOT charged a DRAM latency: it is a
   sequential streaming write, so it is bandwidth-bound (~40 MB at ~10 GB/s =
   ~4 ms, which is round 875's own "~4 ms of memset"), and charging it 90 ns a
   line would invent ~57 ms of cost that no layout change can touch anyway.
2. The CEILING on any layout change whatsoever: what the access stream costs
   today minus what it would cost if every access were an L1 hit. No layout can
   beat that, and it does not depend on which layout is proposed.

Latencies are AMD Zen 2 (EPYC Rome) at an assumed 2.5 GHz, stated rather than
fitted: L1d 4 cycles = 1.6 ns, L2 13 = 5.2, L3 39 = 15.6, DRAM 90 ns.
"""
import re
import sys

L1, L2, L3, DRAM = 1.6, 5.2, 15.6, 90.0
BYTES_PER_NODE = {"A": 47, "B": 45, "C": 64}   # 43 x 1 B + 2 x 2 B / 45 / padded


def main(path):
    txt = open(path, errors="replace").read()
    m = re.search(r"accesses: probe=(\d+) ascent=(\d+) write=(\d+) TOTAL=(\d+)", txt)
    accesses = int(m.group(4))
    nodes = int(re.search(r"nodes=(\d+)", txt).group(1))
    block = txt[txt.index("LAYOUT SIMULATION"):]
    rows, geo = {}, None
    for line in block.split("\n"):
        g = re.match(r"\s+(\S+\(.*\))\s*$", line)
        if g:
            geo = g.group(1)
            rows[geo] = {}
            continue
        r = re.match(r"\s+([ABC])\s+l1=(\d+) l2=(\d+) l3=(\d+) dram=(\d+)\s*$", line)
        if r and geo:
            rows[geo][r.group(1)] = tuple(int(x) for x in r.groups()[1:])
    print(f"accesses/rebuild={accesses}  nodes={nodes}")
    print(f"latencies ns: L1={L1} L2={L2} L3={L3} DRAM={DRAM}\n")
    for geo, arms in rows.items():
        print(geo)
        base = None
        for arm in ("A", "B", "C"):
            l1, l2, l3, dr = arms[arm]
            zeroing = (l1 + l2 + l3 + dr) - accesses
            real_dram = max(0, dr - zeroing)
            ms = (l1 * L1 + l2 * L2 + l3 * L3 + real_dram * DRAM) / 1e6
            if base is None:
                base = ms
            print(f"  {arm}: access stream {ms:6.2f} ms  (vs A {ms - base:+6.2f})"
                  f"   zeroing {zeroing} lines = {zeroing * 64 / 1e6:.1f} MB")
        l1, l2, l3, dr = arms["A"]
        zeroing = (l1 + l2 + l3 + dr) - accesses
        today = (l1 * L1 + l2 * L2 + l3 * L3 + max(0, dr - zeroing) * DRAM) / 1e6
        print(f"  CEILING on ANY layout change = {today - accesses * L1 / 1e6:.2f} ms\n")


if __name__ == "__main__":
    main(sys.argv[1])
