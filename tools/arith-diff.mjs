#!/usr/bin/env node
/**
 * Differential harness for arithmetic/comparison: generates random operand pairs,
 * runs the original library, and emits TSV for comparison against the Java port.
 *
 * Operands are encoded as exact component triples `sign,layer,mag` (using JS number
 * strings, which round-trip exactly) so the comparison isolates the operations from
 * parsing and from the known last-ulp differences in transcendental conversions.
 *
 * Usage: node tools/arith-diff.mjs <originalRepoPath> <expectedOut>
 *
 * Line format: <op>\t<a>\t<b>\t<result>
 *   Decimal results are encoded `sign,layer,mag|<toString>`, booleans as true/false,
 *   numbers as their JS string, exceptions as THROWS.
 */
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { createRequire } from "node:module";

const originalRepo = process.argv[2] ?? "../break_eternity.js";
const expectedOut = process.argv[3];
const require = createRequire(import.meta.url);
const Decimal = require(resolve(originalRepo, "break_eternity.cjs.js"));

const MASK48 = (1n << 48n) - 1n;
class JavaRandom {
  constructor(seed) {
    this.seed = (BigInt(seed) ^ 0x5deece66dn) & MASK48;
  }
  next(bits) {
    this.seed = (this.seed * 0x5deece66dn + 0xbn) & MASK48;
    return this.seed >> BigInt(48 - bits);
  }
  nextDouble() {
    return Number((this.next(26) << 27n) | this.next(27)) * Math.pow(2, -53);
  }
  nextLong() {
    const hi = BigInt.asIntN(32, this.next(32));
    const lo = BigInt.asIntN(32, this.next(32));
    return BigInt.asIntN(64, (hi << 32n) + lo);
  }
  nextInt(bound) {
    return Number(BigInt.asUintN(64, this.nextLong()) % BigInt(bound));
  }
}
const bitsToDouble = (() => {
  const buf = new DataView(new ArrayBuffer(8));
  return (bits) => {
    buf.setBigInt64(0, BigInt.asIntN(64, bits));
    return buf.getFloat64(0);
  };
})();

const rng = new JavaRandom(2024);

/** Mirrors the original's randomDecimalForTesting. */
function randomDecimal(maxLayers) {
  if (rng.nextDouble() < 0.05) return new Decimal(0);
  if (rng.nextDouble() < 0.05) return new Decimal(rng.nextDouble() < 0.5 ? -1 : 1);
  const randomsign = rng.nextDouble() < 0.5 ? -1 : 1;
  const randomlayer = Math.floor(rng.nextDouble() * maxLayers);
  let randomexp;
  if (randomlayer === 0) randomexp = (rng.nextDouble() - 0.5) * 616;
  else randomexp = rng.nextDouble() * 16;
  if (rng.nextDouble() < 0.1) randomexp = Math.trunc(randomexp);
  return Decimal.fromComponents(randomsign, randomlayer, randomexp);
}

const enc = (d) => `${String(d.sign)},${String(d.layer)},${String(d.mag)}`;
const encResult = (v) => {
  if (v instanceof Decimal) return `${enc(v)}|${v.toString()}`;
  if (typeof v === "boolean") return String(v);
  if (typeof v === "number") return String(v);
  return String(v);
};

const operands = [];
for (let i = 0; i < 700; i++) operands.push(randomDecimal(4));
for (let i = 0; i < 150; i++) {
  operands.push(Decimal.fromComponents(1, 0, Math.floor((rng.nextDouble() - 0.5) * 1e6)));
}
for (const d of [
  new Decimal(0), new Decimal(1), new Decimal(-1), new Decimal(2), new Decimal(10),
  Decimal.dNaN, Decimal.dInf, Decimal.dNegInf, Decimal.dNumberMax, Decimal.dNumberMin,
  Decimal.dLayerSafeMax, Decimal.dLayerSafeMin, Decimal.dLayerMax, Decimal.dLayerMin,
  new Decimal("ee100"), new Decimal("1e-300"), new Decimal("9e15"), new Decimal("1e16"),
]) {
  operands.push(d);
}

const ops = [
  ["log", (a, b) => a.log(b)],
  ["pow", (a, b) => a.pow(b)],
  ["root", (a, b) => a.root(b)],
  ["add", (a, b) => a.add(b)],
  ["sub", (a, b) => a.sub(b)],
  ["mul", (a, b) => a.mul(b)],
  ["div", (a, b) => a.div(b)],
  ["mod", (a, b) => a.mod(b)],
  ["modFloored", (a, b) => a.mod(b, true)],
  ["cmp", (a, b) => a.cmp(b)],
  ["cmpabs", (a, b) => a.cmpabs(b)],
  ["eq", (a, b) => a.eq(b)],
  ["neq", (a, b) => a.neq(b)],
  ["lt", (a, b) => a.lt(b)],
  ["lte", (a, b) => a.lte(b)],
  ["gt", (a, b) => a.gt(b)],
  ["gte", (a, b) => a.gte(b)],
  ["max", (a, b) => a.max(b)],
  ["min", (a, b) => a.min(b)],
  ["maxabs", (a, b) => a.maxabs(b)],
  ["minabs", (a, b) => a.minabs(b)],
  ["eqTol1e-7", (a, b) => a.eq_tolerance(b, 1e-7)],
  ["eqTol1e-15", (a, b) => a.eq_tolerance(b, 1e-15)],
  ["ltTol", (a, b) => a.lt_tolerance(b, 1e-7)],
  ["gteTol", (a, b) => a.gte_tolerance(b, 1e-7)],
  ["clamp", (a, b) => a.clamp(b, b.add(1e10))],
];
const unaryOps = [
  ["log10", (a) => a.log10()],
  ["log2", (a) => a.log2()],
  ["ln", (a) => a.ln()],
  ["absLog10", (a) => a.absLog10()],
  ["pLog10", (a) => a.pLog10()],
  ["pow10", (a) => a.pow10()],
  ["exp", (a) => a.exp()],
  ["sqrt", (a) => a.sqrt()],
  ["sqr", (a) => a.sqr()],
  ["cube", (a) => a.cube()],
  ["cbrt", (a) => a.cbrt()],
  ["gamma", (a) => a.gamma()],
  ["factorial", (a) => a.factorial()],
  ["lngamma", (a) => a.lngamma()],
  ["sin", (a) => a.sin()],
  ["cos", (a) => a.cos()],
  ["tan", (a) => a.tan()],
  ["asin", (a) => a.asin()],
  ["acos", (a) => a.acos()],
  ["atan", (a) => a.atan()],
  ["sinh", (a) => a.sinh()],
  ["cosh", (a) => a.cosh()],
  ["tanh", (a) => a.tanh()],
  ["asinh", (a) => a.asinh()],
  ["acosh", (a) => a.acosh()],
  ["atanh", (a) => a.atanh()],
  ["abs", (a) => a.abs()],
  ["neg", (a) => a.neg()],
  ["recip", (a) => a.recip()],
  ["round", (a) => a.round()],
  ["floor", (a) => a.floor()],
  ["ceil", (a) => a.ceil()],
  ["trunc", (a) => a.trunc()],
  ["sign", (a) => a.sgn()],
  ["sqrTolerance", (a) => a.eq_tolerance(a.mul(1 + 1e-9), 1e-7)],
];

const lines = [];
const pairCount = 1200;
for (let i = 0; i < pairCount; i++) {
  const a = operands[rng.nextInt(operands.length)];
  const b = operands[rng.nextInt(operands.length)];
  for (const [name, fn] of ops) {
    let result;
    try {
      result = encResult(fn(a, b));
      // JS cmp returns NaN for some NaN operands; the Java port documents an explicit
      // int rule instead, so skip those cases here (covered by unit tests).
      if ((name === "cmp" || name === "cmpabs") && result === "NaN") continue;
    } catch (e) {
      result = "THROWS";
    }
    lines.push(`${name}\t${enc(a)}\t${enc(b)}\t${result}`);
  }
  for (const [name, fn] of unaryOps) {
    let result;
    try {
      result = encResult(fn(a));
    } catch (e) {
      result = "THROWS";
    }
    lines.push(`${name}\t${enc(a)}\t-\t${result}`);
  }
}

writeFileSync(expectedOut, lines.join("\n") + "\n");
console.log(`Wrote ${lines.length} op cases to ${expectedOut}`);
