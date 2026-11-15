"""Render results/*.json as results/README.md."""

import json
import os
import sys


def main():
    d = sys.argv[1]
    ipc = json.load(open(os.path.join(d, "ipc.json")))
    synth = {}
    for c in ("ooo", "inorder", "small"):
        p = os.path.join(d, f"synth-{c}.json")
        if os.path.exists(p):
            synth[c] = json.load(open(p))
    p = print
    p("# Evaluation results\n")
    p("Produced by `eval/run_all.sh`. Every simulation ran with co-simulation against the reference model.\n")
    configs = list(next(iter(ipc["benchmarks"].values())).keys())
    p("## IPC (memory latency 20 cycles)\n")
    p("| benchmark | " + " | ".join(configs) + " | speedup of ooo over inorder |")
    p("|---|" + "---:|" * (len(configs) + 1))
    for n, row in ipc["benchmarks"].items():
        sp = row["inorder"]["cycles"] / row["ooo"]["cycles"]
        p(f"| {n} | " + " | ".join(f"{row[c]['ipc']:.3f}" for c in configs) + f" | {sp:.2f}x |")
    p("| geometric mean | " + " | ".join(f"{ipc['summary'][c]['geomean_ipc']:.3f}" for c in configs) +
      f" | {ipc['summary']['ooo']['geomean_speedup_vs_inorder']:.2f}x |")
    p("")
    p("## Branch prediction and caches (ooo)\n")
    p("| benchmark | conditional branches | mispredicted | jumps and branches mispredicted | I-cache misses / 1k instr | D-cache misses / 1k instr | average ROB occupancy |")
    p("|---|---:|---:|---:|---:|---:|---:|")
    for n, row in ipc["benchmarks"].items():
        r = row["ooo"]
        cb = max(r["cond_branches"], 1)
        p(f"| {n} | {r['cond_branches']} | {100 * r['cond_mispredicts'] / cb:.1f}% | {r['mispredicts']} | "
          f"{1000 * r['icache_misses'] / r['instret']:.2f} | {1000 * r['dcache_misses'] / r['instret']:.2f} | {r['avg_rob']:.1f} |")
    p("")
    p("## Memory latency sweep (cycles, ooo / inorder)\n")
    lats = sorted({int(l) for v in ipc["latency_sweep"].values() for l in v})
    p("| benchmark | " + " | ".join(f"latency {l}" for l in lats) + " |")
    p("|---|" + "---:|" * len(lats))
    for n, v in sorted(ipc["latency_sweep"].items()):
        cells = []
        for l in lats:
            e = v.get(str(l), {})
            if "ooo" in e and "inorder" in e:
                cells.append(f"{e['inorder'] / e['ooo']:.2f}x")
            else:
                cells.append("")
        p(f"| {n} | " + " | ".join(cells) + " |")
    p("\n(speedup of ooo over inorder at each latency)\n")
    if synth:
        p("## Synthesis (Yosys)\n")
        p("| | " + " | ".join(synth) + " |")
        p("|---|" + "---:|" * len(synth))
        rows = [
            ("generic gates", lambda s: s["generic"]["gates"]),
            ("flip-flops", lambda s: s["generic"]["flip_flops"]),
            ("longest path, gates", lambda s: s["generic"]["depth_gates"]),
            ("frequency estimate at 50 ps per gate level (MHz)", lambda s: s["generic"]["fmax_mhz_estimate"]),
            ("7-series LUTs", lambda s: s["xilinx"]["luts"]),
            ("7-series flip-flops", lambda s: s["xilinx"]["flip_flops"]),
            ("7-series DSP48", lambda s: s["xilinx"]["dsp48"]),
            ("longest path, 7-series cells", lambda s: s["xilinx"]["depth_cells"]),
            ("frequency estimate at 0.6 ns per cell (MHz)", lambda s: s["xilinx"]["fmax_mhz_estimate"]),
            ("cache SRAM bits (black boxes)", lambda s: s["sram_bits"]),
        ]
        for name, f in rows:
            p(f"| {name} | " + " | ".join(str(f(s)) for s in synth.values()) + " |")


if __name__ == "__main__":
    main()
