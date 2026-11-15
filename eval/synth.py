"""Synthesis with Yosys: an area and frequency estimate.

The cache arrays (tags and data of both caches) are left as black boxes and
counted as SRAM bits, as they would be memory macros in a real chip; all
other state, including the physical register file and the predictor
tables, is synthesized to flip-flops and logic.

Two targets:
  - generic gates (Yosys' internal cell library, mapped by ABC for delay): gate and
    flip-flop counts, and the depth of the longest combinational path in gates;
  - Xilinx 7-series (synth_xilinx): LUTs, flip-flops, carry chains, DSP blocks,
    and the depth of the longest path in cells.

For the depth, the SRAM macros (and on the FPGA the flip-flop cells) are
deleted first, so that every path starts and ends at state, and the wires on
the longest path are reported to show where it runs. The frequency estimate
turns the depth into a period with a stated delay per level; it ignores wires
and placement, so it is a rough upper bound.

    python3 eval/synth.py GENERATED_DIR CONFIG [--out results/synth-CONFIG.json] [--no-fpga]
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import subprocess
import tempfile
import time

GATE_PS = 50  # assumed delay of one 2-input gate level (an FO4-loaded gate in a ~28 nm process)
LUT_NS = 0.6  # assumed delay of one 7-series cell level including local routing


def memories(sv: str) -> dict[str, dict]:
    """Chisel emits each Mem as a module named <name>_<depth>x<width>."""
    out = {}
    for m in re.finditer(r"^module ((\w+?)_(\d+)x(\d+)(_\d+)?)\(", sv, re.M):
        out[m.group(1)] = {"depth": int(m.group(3)), "width": int(m.group(4))}
    return out


def run_yosys(script: str, cwd: str) -> None:
    p = subprocess.run(["yosys", "-q", "-s", script], cwd=cwd, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr[-3000:] + p.stdout[-3000:])


def parse_stat(text: str) -> dict[str, int]:
    cells = {}
    for m in re.finditer(r"^\s+(\$?\w+)\s+(\d+)\s*$", text, re.M):
        cells[m.group(1)] = int(m.group(2))
    return cells


def parse_ltp(path: str) -> tuple[int, list[str]]:
    """The length of the longest path and the names of the wires on it."""
    length, names = -1, []
    with open(path, errors="replace") as f:
        for line in f:
            m = re.search(r"Longest topological path in \S+ \(length=(\d+)\)", line)
            if m:
                length = int(m.group(1))
                names = []
                continue
            m = re.match(r"\s+(\d+): (\S+)", line)
            if m and length >= 0:
                names.append(m.group(2))
    return length, names


def where(names: list[str]) -> list[tuple[str, int]]:
    """Group the wires on a path by the name they carry (Yosys keeps the
    Chisel signal names that survive), to see which parts of the core it
    crosses."""
    c = collections.Counter()
    for n in names:
        n = n.lstrip("\\$")
        if "new_n" in n:  # ABC's anonymous nodes say nothing about where the path runs
            continue
        if n.startswith("abc") or n.startswith("auto") or n.startswith("flatten"):
            m = re.search(r"(\w+?)(?:_\d+)?(?:\[|$)", n.split(".")[-1])
            key = m.group(1) if m else n
        else:
            key = re.sub(r"\[.*", "", n).split(".")[-1]
        key = re.sub(r"_[0-9]+$", "", key)
        c[key] += 1
    return c.most_common(15)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("generated")
    ap.add_argument("config")
    ap.add_argument("--out")
    ap.add_argument("--no-fpga", action="store_true")
    a = ap.parse_args()
    src = os.path.abspath(os.path.join(a.generated, a.config, "Core.sv"))
    sv = open(src, encoding="utf-8").read()
    mems = memories(sv)
    caches = {k: v for k, v in mems.items() if k.split("_")[0] in ("tags", "data", "lines")}
    sram_bits = sum(v["depth"] * v["width"] for v in caches.values())
    res = {"config": a.config, "sram_bits": sram_bits, "sram_macros": caches,
           "assumptions": {"gate_ps": GATE_PS, "lut_ns": LUT_NS}}
    work = tempfile.mkdtemp(prefix="silicon-synth-")
    common = f"read_verilog -sv {src}\nhierarchy -top Core\n" + (f"blackbox {' '.join(caches)}\n" if caches else "")
    cut = f"delete {' '.join('t:' + c for c in caches)}\n" if caches else ""

    t0 = time.time()
    with open(os.path.join(work, "generic.ys"), "w") as f:
        # delay-driven mapping ("dch; map"): ABC's default area-driven script
        # turns the Brent-Kung adders Yosys builds into ripple chains
        f.write(common + "synth -top Core -flatten -noabc\n"
                "abc -g AND,NAND,OR,NOR,XOR,XNOR,ANDNOT,ORNOT,MUX -script +strash;dch;map\nopt_clean\n"
                "tee -q -o generic_stat.txt stat\n" + cut + "tee -q -o generic_ltp.txt ltp -noff\n")
    run_yosys("generic.ys", work)
    st = parse_stat(open(os.path.join(work, "generic_stat.txt")).read())
    ffs = sum(v for k, v in st.items() if "DFF" in k or "DLATCH" in k)
    gates = sum(v for k, v in st.items() if k.startswith("$_") and "DFF" not in k)
    depth, names = parse_ltp(os.path.join(work, "generic_ltp.txt"))
    named = [n for n in names if "new_n" not in n]
    res["generic"] = {"gates": gates, "flip_flops": ffs, "depth_gates": depth,
                      "critical_path_named_wires": named[:12] + ["..."] + named[-12:],
                      "fmax_mhz_estimate": round(1e6 / (depth * GATE_PS), 1) if depth > 0 else None,
                      "critical_path_signals": where(names),
                      "seconds": round(time.time() - t0, 1), "cells": st}

    if not a.no_fpga:
        t0 = time.time()
        with open(os.path.join(work, "xilinx.ys"), "w") as f:
            f.write(common + "synth_xilinx -top Core -flatten -nobram -nolutram\n"
                    "tee -q -o xilinx_stat.txt stat\n" + cut + "delete t:FD*\n" + "tee -q -o xilinx_ltp.txt ltp -noff\n")
        run_yosys("xilinx.ys", work)
        st = parse_stat(open(os.path.join(work, "xilinx_stat.txt")).read())
        luts = sum(v for k, v in st.items() if k.startswith("LUT"))
        ffx = sum(v for k, v in st.items() if k in ("FDRE", "FDSE", "FDCE", "FDPE"))
        depth, names = parse_ltp(os.path.join(work, "xilinx_ltp.txt"))
        res["xilinx"] = {"luts": luts, "flip_flops": ffx, "carry4": st.get("CARRY4", 0), "dsp48": st.get("DSP48E1", 0),
                         "muxf7_f8": st.get("MUXF7", 0) + st.get("MUXF8", 0), "depth_cells": depth,
                         "fmax_mhz_estimate": round(1e3 / (depth * LUT_NS), 1) if depth > 0 else None,
                         "critical_path_signals": where(names),
                         "seconds": round(time.time() - t0, 1), "cells": st}
    for k in ("generic", "xilinx"):
        if k in res:
            print(k, json.dumps({x: y for x, y in res[k].items() if x != "cells"}, default=str))
    if a.out:
        with open(a.out, "w") as f:
            json.dump(res, f, indent=1)
    subprocess.run(["rm", "-rf", work])


if __name__ == "__main__":
    main()
