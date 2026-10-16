#!/usr/bin/env node
/**
 * Differential harness for the hyperoperator stack: tetration, iterated logs, slog,
 * layeradd, Lambert W, super-roots and pentation.
 *
 * The corpus is curated (rather than randomized) because the inverse functions
 * (linear_sroot, penta_log, linear_penta_root) and small-base tetration iterate
 * internally and are slow on random layer-2+ inputs; randomized property coverage of the
 * same functions lives in the JUnit suite instead.
 *
 * Operands are encoded as component triples `sign,layer,mag`; heights/degrees are plain
 * doubles.
 *
 * Usage: node tools/hyper-diff.mjs <originalRepoPath> <expectedOut>
 * Line format: <op>\t<a>\t<args...>\t<result>  (result = sign,layer,mag|toString, or THROWS|)
 */
import { writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";

const originalRepo = process.argv[2] ?? "../break_eternity.js";
const expectedOut = process.argv[3];
const require = createRequire(import.meta.url);
const Decimal = require(resolve(originalRepo, "break_eternity.cjs.js"));

const enc = (d) => `${String(d.sign)},${String(d.layer)},${String(d.mag)}`;
const encResult = (v) => v instanceof Decimal ? `${enc(v)}|${v.toString()}` : String(v);

const lines = [];
const emit = (op, a, args, fn) => {
  let result;
  try { result = encResult(fn()); } catch (e) { result = "THROWS|"; }
  lines.push(`${op}\t${enc(a)}\t${args.join("\t")}\t${result}`);
};

const bases = [0.5, 1.1, 1.5, 2, Math.E, 3, 5, 10, 100, 1e10].map((b) => new Decimal(b));
const smallBases = bases.slice(0, 6);
const values = [0, 0.5, 1, 2, 10, 100, 1e10, 1e100, 1e300, "ee100"].map((v) => new Decimal(v));
const heights = [0.5, 1, 1.5, 2, 2.5, 3, -1, -1.5];

for (const a of bases) for (const h of heights) {
  emit("tetrateP1", a, [String(h), "false"], () => a.tetrate(h, Decimal.dOne, false));
  emit("tetrateP1", a, [String(h), "true"], () => a.tetrate(h, Decimal.dOne, true));
}
for (const a of bases) for (const h of [1.5, 2.5]) for (const p of [values[3], values[6]]) {
  emit("tetrate", a, [String(h), enc(p), "false"], () => a.tetrate(h, p, false));
}
for (const a of values) for (const b of smallBases) for (const t of [1, 2, 0.5]) {
  emit("iteratedlog", a, [enc(b), String(t), "false"], () => a.iteratedlog(b, t, false));
}
for (const a of values) for (const b of smallBases) {
  emit("slog", a, [enc(b), "false"], () => a.slog(b, 100, false));
  emit("slogInternal", a, [enc(b), "false"], () => a.slog_internal(b, false));
}
for (const a of values) for (const diff of [0.5, 1, -0.5, 2.5]) {
  emit("layeradd10", a, [String(diff), "false"], () => a.layeradd10(diff, false));
}
for (const a of values.slice(0, 6)) for (const b of bases.slice(0, 4)) for (const diff of [0.5, -0.5]) {
  emit("layeradd", a, [String(diff), enc(b), "false"], () => a.layeradd(diff, b, false));
}
for (const a of values) for (const principal of [true, false]) {
  emit("lambertw", a, [String(principal)], () => a.lambertw(principal));
}
for (const b of [2, 2.5, Math.E, 7.5, 10]) for (const h of [0, 0.25, 0.5, 0.75, 0.999]) {
  const bd = new Decimal(b);
  emit("slogCritical", bd, [String(h)], () => Decimal.slog_critical(b, h));
  emit("tetrateCritical", bd, [String(h)], () => Decimal.tetrate_critical(b, h));
}
for (const a of values.slice(0, 8)) for (const degree of [2, 3, 0.5]) {
  emit("linearSroot", a, [String(degree)], () => a.linear_sroot(degree));
  emit("ssqrt", a, [], () => a.ssqrt());
}
for (const a of bases.slice(0, 6)) for (const h of [1, 2, 2.5]) {
  emit("pentate", a, [String(h)], () => a.pentate(h, Decimal.dOne, false));
}
for (const a of [10, 100, 1e10, 1e300].map((v) => new Decimal(v))) {
  emit("pentaLog", a, [], () => a.penta_log(new Decimal(10), 100, false));
}
for (const a of [10, 1e10, 1e300].map((v) => new Decimal(v))) {
  emit("linearPentaRoot", a, ["2"], () => a.linear_penta_root(2));
}

writeFileSync(expectedOut, lines.join("\n") + "\n");
console.log(`Wrote ${lines.length} hyperoperation cases to ${expectedOut}`);
