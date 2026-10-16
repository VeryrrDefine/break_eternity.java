#!/usr/bin/env node
/**
 * Emits a corpus of (function, input, V8 result) triples for transcendental functions,
 * so the Java port can pick Math.* vs StrictMath.* per function by measurement.
 *
 * Each line: <fn>\t<inputBits(hex)>\t<resultBits(hex)>
 *
 * Usage: node tools/fn-parity.mjs <countPerFn> <outFile>
 */
import { writeFileSync } from "node:fs";

const count = Number(process.argv[2] ?? 4000);
const outFile = process.argv[3] ?? "/tmp/fn-parity.tsv";

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
}

const buf = new DataView(new ArrayBuffer(8));
const bits = (x) => {
  buf.setFloat64(0, x);
  return BigInt.asUintN(64, buf.getBigInt64(0)).toString(16).padStart(16, "0");
};

const rng = new JavaRandom(31337);
const gen = {
  any: () => Double(rng.nextLong()),
  unit: () => rng.nextDouble(),
  small: () => (rng.nextDouble() - 0.5) * 10,
  logDomain: () => Math.pow(10, (rng.nextDouble() - 0.5) * 600),
  expDomain: () => (rng.nextDouble() - 0.5) * 1400,
  asinDomain: () => rng.nextDouble() * 2 - 1,
  acoshDomain: () => 1 + rng.nextDouble() * 1e6,
  atanhDomain: () => rng.nextDouble() * 2 - 1,
  trigDomain: () => (rng.nextDouble() - 0.5) * 20,
};

const fns = [
  ["log", Math.log, "logDomain"],
  ["log2", Math.log2, "logDomain"],
  ["log10", Math.log10, "logDomain"],
  ["exp", Math.exp, "expDomain"],
  ["sqrt", Math.sqrt, "logDomain"],
  ["cbrt", Math.cbrt, "logDomain"],
  ["sin", Math.sin, "trigDomain"],
  ["cos", Math.cos, "trigDomain"],
  ["tan", Math.tan, "trigDomain"],
  ["asin", Math.asin, "asinDomain"],
  ["acos", Math.acos, "asinDomain"],
  ["atan", Math.atan, "trigDomain"],
  ["sinh", Math.sinh, "trigDomain"],
  ["cosh", Math.cosh, "trigDomain"],
  ["tanh", Math.tanh, "trigDomain"],
  ["asinh", Math.asinh, "trigDomain"],
  ["acosh", Math.acosh, "acoshDomain"],
  ["atanh", Math.atanh, "atanhDomain"],
];

const lines = [];
for (const [name, fn, domain] of fns) {
  const inputs = [];
  for (let i = 0; i < count; i++) inputs.push(gen[domain]());
  for (const x of [0, -0, 1, -1, 2, 0.5, -0.5, Math.PI, Math.E, 1e-300, 1e300, 1e21, 1e-7, 123.456]) {
    inputs.push(x);
  }
  for (const x of inputs) {
    lines.push(`${name}\t${bits(x)}\t${bits(fn(x))}`);
  }
}

writeFileSync(outFile, lines.join("\n") + "\n");
console.log(`Wrote ${lines.length} cases to ${outFile}`);
