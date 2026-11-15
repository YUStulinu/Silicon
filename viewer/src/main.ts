// The pipeline viewer: one row per fetched instruction, one column per cycle.
// Each row shows how long the instruction spent in each part of the core:
// front end (fetch to dispatch), the issue queue (dispatch to issue),
// execution (issue to completion), and the reorder buffer (completion to
// commit). Squashed instructions (wrong path) are drawn faded.

import { disasm } from "./disasm.js";
import { Inst, Trace, ipcSeries, parseTrace } from "./trace.js";

const ROW = 18;
const LABEL_W = 330;
const PORTS = ["ALU0/BR", "ALU1", "MUL/DIV", "MEM"];

let trace: Trace | null = null;
let rows: Inst[] = [];
let cellW = 12;
let scrollY = 0;
let scrollX = 0;
let hideSquashed = false;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const canvas = $<HTMLCanvasElement>("view");
const spark = $<HTMLCanvasElement>("spark");
const tip = $<HTMLDivElement>("tip");

function css(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function load(text: string, name: string): void {
  try {
    trace = parseTrace(text);
  } catch (e) {
    $("status").textContent = String(e);
    return;
  }
  const t = trace;
  const squashed = t.insts.length - t.committed;
  $("status").textContent =
    `${name}: ${t.cycles} cycles, ${t.committed} committed (IPC ${(t.committed / t.cycles).toFixed(2)}), ` +
    `${squashed} squashed, ${t.redirects.filter((r) => r.kind === "mispredict").length} mispredictions, ` +
    `${t.redirects.filter((r) => r.kind === "flush").length} flushes`;
  scrollY = 0;
  scrollX = 0;
  relayout();
}

function relayout(): void {
  if (!trace) return;
  rows = hideSquashed ? trace.insts.filter((x) => !x.squashed) : trace.insts;
  draw();
  drawSpark();
}

function resize(): void {
  const dpr = window.devicePixelRatio || 1;
  for (const c of [canvas, spark]) {
    const r = c.getBoundingClientRect();
    c.width = Math.max(1, Math.floor(r.width * dpr));
    c.height = Math.max(1, Math.floor(r.height * dpr));
    c.getContext("2d")!.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  draw();
  drawSpark();
}

function segments(x: Inst): { from: number; to: number; color: string; label: string }[] {
  const end = x.commit ?? x.complete ?? x.issue ?? x.dispatch ?? x.fetch;
  const segs: { from: number; to: number; color: string; label: string }[] = [];
  const d = x.dispatch ?? end + 1;
  segs.push({ from: x.fetch, to: Math.min(d, end + 1), color: css("--front"), label: "F" });
  if (x.dispatch !== undefined) {
    const i = x.issue ?? (x.complete !== undefined || x.commit !== undefined ? x.dispatch : end + 1);
    if (i > x.dispatch) segs.push({ from: x.dispatch, to: i, color: css("--queue"), label: "Q" });
    if (x.issue !== undefined) {
      const w = x.complete ?? end + 1;
      segs.push({ from: x.issue, to: Math.max(w, x.issue + 1), color: css("--exec"), label: "E" });
    }
    const c = x.commit ?? end;
    const w = x.complete ?? x.dispatch;
    if (c > w) segs.push({ from: w + 1, to: c, color: css("--rob"), label: "R" });
  }
  if (x.commit !== undefined) segs.push({ from: x.commit, to: x.commit + 1, color: css("--commit"), label: "C" });
  return segs;
}

function draw(): void {
  const ctx = canvas.getContext("2d")!;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  ctx.fillStyle = css("--bg");
  ctx.fillRect(0, 0, w, h);
  if (!trace) {
    ctx.fillStyle = css("--muted");
    ctx.font = "14px system-ui, sans-serif";
    ctx.fillText("Open a trace (sim --trace FILE), or load the sample.", 20, 40);
    return;
  }
  const first = Math.floor(scrollY / ROW);
  const n = Math.ceil(h / ROW) + 1;
  const c0 = Math.floor(scrollX / cellW);
  const cN = Math.ceil((w - LABEL_W) / cellW) + 1;
  ctx.font = "12px ui-monospace, Menlo, Consolas, monospace";
  ctx.textBaseline = "middle";

  // cycle grid and header ticks
  ctx.strokeStyle = css("--grid");
  ctx.lineWidth = 1;
  for (let c = c0; c < c0 + cN; c++) {
    const x = LABEL_W + c * cellW - scrollX;
    if (c % 10 === 0) {
      ctx.beginPath();
      ctx.moveTo(x + 0.5, 0);
      ctx.lineTo(x + 0.5, h);
      ctx.stroke();
    }
  }
  // redirects as vertical marks
  for (const r of trace.redirects) {
    if (r.cycle < c0 || r.cycle > c0 + cN) continue;
    const x = LABEL_W + r.cycle * cellW - scrollX;
    ctx.fillStyle = r.kind === "mispredict" ? css("--mispredict") : css("--flush");
    ctx.fillRect(x, 0, 2, h);
  }

  for (let i = first; i < Math.min(rows.length, first + n); i++) {
    const x = rows[i];
    const y = i * ROW - scrollY;
    if (i % 2 === 1) {
      ctx.fillStyle = css("--stripe");
      ctx.fillRect(0, y, w, ROW);
    }
    ctx.globalAlpha = x.squashed ? 0.35 : 1;
    for (const s of segments(x)) {
      const a = Math.max(s.from, c0);
      const b = Math.min(s.to, c0 + cN);
      if (b <= a) continue;
      const px = LABEL_W + a * cellW - scrollX;
      ctx.fillStyle = s.color;
      ctx.fillRect(px, y + 3, (b - a) * cellW - 1, ROW - 6);
      if (cellW >= 10) {
        ctx.fillStyle = css("--on-seg");
        for (let c = a; c < b; c++) ctx.fillText(s.label, LABEL_W + c * cellW - scrollX + cellW / 2 - 3, y + ROW / 2);
      }
    }
    ctx.globalAlpha = 1;
    // label
    ctx.fillStyle = css("--bg");
    ctx.fillRect(0, y, LABEL_W, ROW);
    ctx.fillStyle = x.squashed ? css("--muted") : css("--fg");
    ctx.fillText(`${x.pc.toString(16).padStart(8, "0")}  ${disasm(x.inst, x.pc)}`, 8, y + ROW / 2);
  }
  ctx.fillStyle = css("--grid");
  ctx.fillRect(LABEL_W - 1, 0, 1, h);
}

function drawSpark(): void {
  const ctx = spark.getContext("2d")!;
  const w = spark.clientWidth;
  const h = spark.clientHeight;
  ctx.fillStyle = css("--panel");
  ctx.fillRect(0, 0, w, h);
  if (!trace) return;
  const s = ipcSeries(trace, 64);
  const maxIpc = 2;
  ctx.strokeStyle = css("--exec");
  ctx.lineWidth = 2;
  ctx.beginPath();
  for (let i = 0; i < w; i++) {
    const c = Math.floor((i / w) * trace.cycles);
    const y = h - 4 - (s[c] / maxIpc) * (h - 8);
    if (i === 0) ctx.moveTo(i, y);
    else ctx.lineTo(i, y);
  }
  ctx.stroke();
  // the visible window
  const first = rows[Math.floor(scrollY / ROW)];
  if (first) {
    const x0 = (first.fetch / trace.cycles) * w;
    ctx.fillStyle = css("--window");
    ctx.fillRect(x0, 0, Math.max(3, ((canvas.clientWidth - LABEL_W) / cellW / trace.cycles) * w), h);
  }
  ctx.fillStyle = css("--muted");
  ctx.font = "11px system-ui, sans-serif";
  ctx.fillText("IPC over 64 cycles (0 to 2); click to jump", 6, 12);
}

function jumpToCycle(c: number): void {
  if (!trace) return;
  let i = rows.findIndex((x) => x.fetch >= c);
  if (i < 0) i = rows.length - 1;
  scrollY = Math.max(0, i * ROW - 2 * ROW);
  scrollX = Math.max(0, c * cellW - 4 * cellW);
  draw();
  drawSpark();
}

canvas.addEventListener("wheel", (e) => {
  e.preventDefault();
  if (e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY)) scrollX = Math.max(0, scrollX + (e.deltaX || e.deltaY));
  else {
    scrollY = Math.max(0, Math.min(rows.length * ROW - 40, scrollY + e.deltaY));
    // follow the instructions in time
    const r = rows[Math.floor(scrollY / ROW)];
    if (r) scrollX = Math.max(0, r.fetch * cellW - 4 * cellW);
  }
  draw();
  drawSpark();
  tip.style.display = "none";
}, { passive: false });

canvas.addEventListener("mousemove", (e) => {
  if (!trace) return;
  const r = canvas.getBoundingClientRect();
  const i = Math.floor((e.clientY - r.top + scrollY) / ROW);
  const x = rows[i];
  if (!x) {
    tip.style.display = "none";
    return;
  }
  const c = Math.floor((e.clientX - r.left - LABEL_W + scrollX) / cellW);
  const lines = [
    `${disasm(x.inst, x.pc)}  (pc 0x${x.pc.toString(16)})`,
    `fetched ${x.fetch}` +
      (x.dispatch !== undefined ? `, dispatched ${x.dispatch}` : "") +
      (x.issue !== undefined ? `, issued ${x.issue} on ${PORTS[x.port ?? 0]}` : "") +
      (x.complete !== undefined ? `, completed ${x.complete}` : "") +
      (x.commit !== undefined ? `, committed ${x.commit}` : ", squashed"),
    `cycle under the cursor: ${c}`,
  ];
  tip.textContent = lines.join("\n");
  tip.style.display = "block";
  tip.style.left = `${Math.min(e.clientX + 14, window.innerWidth - 380)}px`;
  tip.style.top = `${e.clientY + 14}px`;
});
canvas.addEventListener("mouseleave", () => (tip.style.display = "none"));

spark.addEventListener("click", (e) => {
  if (!trace) return;
  const r = spark.getBoundingClientRect();
  jumpToCycle(Math.floor(((e.clientX - r.left) / r.width) * trace.cycles));
});

$<HTMLInputElement>("file").addEventListener("change", async (e) => {
  const f = (e.target as HTMLInputElement).files?.[0];
  if (f) load(await f.text(), f.name);
});
$<HTMLButtonElement>("sample").addEventListener("click", async () => {
  const res = await fetch("sample/sha256.trace");
  load(await res.text(), "sample: sha256 (first 6000 cycles)");
});
$<HTMLInputElement>("zoom").addEventListener("input", (e) => {
  cellW = Number((e.target as HTMLInputElement).value);
  draw();
  drawSpark();
});
$<HTMLInputElement>("hide").addEventListener("change", (e) => {
  hideSquashed = (e.target as HTMLInputElement).checked;
  relayout();
});
$<HTMLInputElement>("goto").addEventListener("change", (e) => jumpToCycle(Number((e.target as HTMLInputElement).value)));
document.addEventListener("dragover", (e) => e.preventDefault());
document.addEventListener("drop", async (e) => {
  e.preventDefault();
  const f = e.dataTransfer?.files?.[0];
  if (f) load(await f.text(), f.name);
});
window.addEventListener("resize", resize);
window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
  draw();
  drawSpark();
});
resize();
