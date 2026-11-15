# Evaluation results

Produced by `eval/run_all.sh`. Every simulation ran with co-simulation against the reference model.

## IPC (memory latency 20 cycles)

| benchmark | ooo | inorder | nopred | small | speedup of ooo over inorder |
|---|---:|---:|---:|---:|---:|
| bsearch | 0.488 | 0.421 | 0.435 | 0.463 | 1.16x |
| chase | 0.289 | 0.259 | 0.287 | 0.280 | 1.11x |
| crc32 | 1.140 | 0.864 | 0.835 | 1.128 | 1.32x |
| div | 0.048 | 0.048 | 0.046 | 0.048 | 1.00x |
| fib | 1.600 | 1.330 | 0.716 | 1.108 | 1.20x |
| matmul | 0.784 | 0.455 | 0.557 | 0.663 | 1.73x |
| nqueens | 1.292 | 1.234 | 1.091 | 0.996 | 1.05x |
| qsort | 0.651 | 0.560 | 0.513 | 0.626 | 1.16x |
| sha256 | 1.667 | 1.231 | 1.224 | 1.568 | 1.35x |
| sieve | 0.313 | 0.297 | 0.268 | 0.305 | 1.05x |
| strings | 0.409 | 0.334 | 0.326 | 0.407 | 1.23x |
| geometric mean | 0.561 | 0.467 | 0.438 | 0.512 | 1.20x |

## Branch prediction and caches (ooo)

| benchmark | conditional branches | mispredicted | jumps and branches mispredicted | I-cache misses / 1k instr | D-cache misses / 1k instr | average ROB occupancy |
|---|---:|---:|---:|---:|---:|---:|
| bsearch | 308778 | 16.4% | 50718 | 0.03 | 43.29 | 22.0 |
| chase | 69742 | 0.2% | 124 | 0.06 | 71.40 | 19.8 |
| crc32 | 37351 | 0.3% | 112 | 0.09 | 2.16 | 22.0 |
| div | 20209 | 6.9% | 1407 | 0.28 | 0.05 | 19.4 |
| fib | 57412 | 7.6% | 4366 | 0.05 | 0.06 | 12.0 |
| matmul | 39113 | 3.2% | 1247 | 0.12 | 10.70 | 25.0 |
| nqueens | 25395 | 24.7% | 6274 | 0.07 | 0.07 | 9.5 |
| qsort | 92228 | 27.6% | 25438 | 0.09 | 5.97 | 17.4 |
| sha256 | 6745 | 3.3% | 228 | 0.32 | 0.69 | 20.4 |
| sieve | 290119 | 2.6% | 7437 | 0.02 | 64.27 | 3.0 |
| strings | 124696 | 0.2% | 220 | 0.05 | 0.27 | 30.0 |

## Memory latency sweep (cycles, ooo / inorder)

| benchmark | latency 5 | latency 20 | latency 50 | latency 100 |
|---|---:|---:|---:|---:|
| bsearch | 1.21x | 1.16x | 1.03x | 0.94x |
| chase | 1.15x | 1.11x | 1.08x | 1.05x |
| crc32 | 1.33x | 1.32x | 1.30x | 1.26x |
| div | 1.00x | 1.00x | 1.00x | 1.00x |
| fib | 1.20x | 1.20x | 1.20x | 1.20x |
| matmul | 1.82x | 1.73x | 1.63x | 1.41x |
| nqueens | 1.05x | 1.05x | 1.05x | 1.05x |
| qsort | 1.17x | 1.16x | 1.14x | 1.11x |
| sha256 | 1.36x | 1.35x | 1.34x | 1.31x |
| sieve | 1.15x | 1.05x | 1.02x | 1.01x |
| strings | 1.23x | 1.23x | 1.22x | 1.22x |

(speedup of ooo over inorder at each latency)

## Synthesis (Yosys)

| | ooo | inorder | small |
|---|---:|---:|---:|
| generic gates | 333651 | 333558 | 230686 |
| flip-flops | 38385 | 38385 | 26664 |
| longest path, gates | 105 | 105 | 83 |
| frequency estimate at 50 ps per gate level (MHz) | 190.5 | 190.5 | 241.0 |
| 7-series LUTs | 102456 | 104549 | 73150 |
| 7-series flip-flops | 38377 | 38377 | 26662 |
| 7-series DSP48 | 0 | 0 | 0 |
| longest path, 7-series cells | 44 | 41 | 33 |
| frequency estimate at 0.6 ns per cell (MHz) | 37.9 | 40.7 | 50.5 |
| cache SRAM bits (black boxes) | 235264 | 235264 | 235264 |
