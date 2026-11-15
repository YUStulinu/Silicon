// Reading the pipeline trace written by the simulator (sim --trace FILE):
//
//   silicon-trace 1
//   C <cycle>             the following events happen in this cycle
//   F <seq> <pc> <inst>   fetched (hex pc and instruction)
//   D <seq>               renamed and dispatched
//   I <seq> <port>        issued to an execution port
//   W <seq>               result written back / completed
//   R <seq>               committed (retired)
//   X flush|mispredict    the pipeline was redirected

export interface Inst {
  seq: number;
  pc: bigint;
  inst: number;
  fetch: number;
  dispatch?: number;
  issue?: number;
  port?: number;
  complete?: number;
  commit?: number;
  // the instruction was fetched but never committed (squashed)
  squashed: boolean;
}

export interface Trace {
  insts: Inst[]; // in fetch order
  cycles: number;
  redirects: { cycle: number; kind: string }[];
  committed: number;
}

export function parseTrace(text: string): Trace {
  const lines = text.split("\n");
  if (!lines[0].startsWith("silicon-trace")) throw new Error("not a Silicon pipeline trace");
  const bySeq = new Map<number, Inst>();
  const order: Inst[] = [];
  const redirects: { cycle: number; kind: string }[] = [];
  let cycle = 0;
  let last = 0;
  for (let i = 1; i < lines.length; i++) {
    const l = lines[i];
    if (l.length === 0) continue;
    const w = l.split(" ");
    switch (w[0]) {
      case "C":
        cycle = Number(w[1]);
        last = cycle;
        break;
      case "F": {
        const seq = Number(w[1]);
        // a sequence number is reused after a redirect: start a new record
        const rec: Inst = { seq, pc: BigInt("0x" + w[2]), inst: parseInt(w[3], 16) >>> 0, fetch: cycle, squashed: true };
        bySeq.set(seq, rec);
        order.push(rec);
        break;
      }
      case "D":
      case "I":
      case "W":
      case "R": {
        const rec = bySeq.get(Number(w[1]));
        if (!rec) break;
        if (w[0] === "D") rec.dispatch = cycle;
        else if (w[0] === "I") {
          rec.issue = cycle;
          rec.port = Number(w[2]);
        } else if (w[0] === "W") rec.complete = rec.complete ?? cycle;
        else {
          rec.commit = cycle;
          rec.squashed = false;
        }
        break;
      }
      case "X":
        redirects.push({ cycle, kind: w[1] });
        break;
    }
  }
  return { insts: order, cycles: last + 1, redirects, committed: order.filter((x) => !x.squashed).length };
}

/** Instructions committed per cycle over a sliding window. */
export function ipcSeries(t: Trace, window: number): number[] {
  const commits = new Array<number>(t.cycles).fill(0);
  for (const x of t.insts) if (x.commit !== undefined) commits[x.commit]++;
  const out: number[] = [];
  let sum = 0;
  for (let c = 0; c < t.cycles; c++) {
    sum += commits[c];
    if (c >= window) sum -= commits[c - window];
    out.push(sum / Math.min(window, c + 1));
  }
  return out;
}
