#!/usr/bin/env node
/**
 * Differential harness for the core Decimal behavior: emits an input corpus and the
 * original library's results as TSV, for comparison against the Java port.
 *
 * Usage: node tools/decimal-diff.mjs <originalRepoPath> <inputsOut> <expectedOut>
 *
 * Input file lines:   <kind>\t<payload>   (kind "n" = number, "s" = string)
 * Expected file lines: <sign>\t<layer>\t<mag>\t<toString>\t<toNumber>\t<toFixed2>\t<tSWDP3>
 */
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { createRequire } from "node:module";

const originalRepo = process.argv[2] ?? "../break_eternity.js";
const inputsOut = process.argv[3];
const expectedOut = process.argv[4];

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
}
const bitsToDouble = (() => {
  const buf = new DataView(new ArrayBuffer(8));
  return (bits) => {
    buf.setBigInt64(0, BigInt.asIntN(64, bits));
    return buf.getFloat64(0);
  };
})();

// --- corpus ---
const inputs = [];
const addNumber = (x) => inputs.push(["n", String(x)]);
const addString = (s) => inputs.push(["s", s]);

const rng = new JavaRandom(7);
for (let i = 0; i < 3000; i++) addNumber(bitsToDouble(rng.nextLong()));
for (let i = 0; i < 3000; i++) addNumber((rng.nextDouble() - 0.5) * Math.pow(10, Math.floor(rng.nextDouble() * 640) - 320));
for (let i = 0; i < 500; i++) addNumber(Math.floor((rng.nextDouble() - 0.5) * 1e18));

addNumber(0);
addNumber(-0);
addNumber(1);
addNumber(5e-324);
addNumber(1.7976931348623157e308);
addNumber(9e15);
addNumber(9007199254740991);
addNumber(1e-307);
addNumber(1e-308);

const plainStrings = [
  "0", "-0", "1", "-1", "10", "-10.5", "123.456", "1e5", "-1e5", "1e-5", "1e1000", "1e-1000",
  "2e-310", "2e-3000", "1e-307", "1e-308", "5e-324", "1.7976931348623157e308", "9007199254740991",
  "1000000000000000000000000", "0.000001", "0.0000001", "1e21", "1e20", "9e15", "9.1e15",
  "1.5e3", ".5", "-.5", "+5", "1.", "3e", "e5", "-e5", "ee5", "eee5", "e100", "ee100", "eee100",
  "eeee100", "eeeeeeeeeeee100", "1e1e3", "1.5e3e2", "2ee3", "1eee3", "-1eee3", "1,000", "1,5e3",
  "nan", "NaN", "NAN", "infinity", "Infinity", "INFINITY", "-infinity", "-Infinity", "-INFINITY",
  "(e^5)100", "(e^3)1", "(e^1)5", "-(e^5)100", "(e^10)2.5", "(e^5)", "(e^0)7", "5e3",
];
for (const s of plainStrings) addString(s);

// Random MeX / eX / eeX strings built from the PRNG.
for (let i = 0; i < 800; i++) {
  const m = Math.floor(rng.nextDouble() * 1e6) / 1e3;
  const e = Math.floor((rng.nextDouble() - 0.5) * 2000);
  addString(`${m}e${e}`);
}
for (let i = 0; i < 400; i++) {
  const e = Math.floor((rng.nextDouble() - 0.5) * 2000);
  addString(`${"e".repeat(1 + Math.floor(rng.nextDouble() * 4))}${e}`);
}
for (let i = 0; i < 300; i++) {
  const layer = Math.floor(rng.nextDouble() * 12);
  const mag = Math.floor((rng.nextDouble() - 0.5) * 2000) / 10;
  addString(`(e^${layer})${mag}`);
}

// --- expected outputs ---
const lines = [];
const expected = [];
const num = (x) => String(x);
for (const [kind, payload] of inputs) {
  let d;
  try {
    d = new Decimal(payload);
  } catch (e) {
    // The Java side mirrors this with THROWS.
    lines.push(`${kind}\t${payload}`);
    expected.push("THROWS");
    continue;
  }
  lines.push(`${kind}\t${payload}`);
  expected.push(
    [
      num(d.sign),
      num(d.layer),
      num(d.mag),
      d.toString(),
      num(d.toNumber()),
      d.toFixed(2),
      d.toStringWithDecimalPlaces(3),
    ].join("\t"),
  );
}

writeFileSync(inputsOut, lines.join("\n") + "\n");
writeFileSync(expectedOut, expected.join("\n") + "\n");
console.log(`Wrote ${inputs.length} inputs to ${inputsOut} and expectations to ${expectedOut}`);
