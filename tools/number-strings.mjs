#!/usr/bin/env node
/**
 * Emits the ECMAScript formatting of a deterministic double corpus as TSV, for
 * differential comparison against breaketernity.JsNumber in the Java port.
 *
 * The corpus has three phases, all driven by java.util.Random with a fixed seed so
 * the Java side can reproduce the exact same doubles:
 *   A. n1 doubles from nextDouble()          - uniform [0, 1)
 *   B. n2 doubles from longBitsToDouble(nextLong()) - all exponents, NaN, +-Inf, subnormals
 *   C. a fixed list of special values
 *
 * Each line: <String(x)>\t<x.toFixed(0)>\t<toFixed(1)>\t<toFixed(2)>\t<toFixed(6)>\t<toFixed(17)>
 *
 * Usage: node tools/number-strings.mjs [n1] [n2] [seed]
 */

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
    const hi = this.next(26) << 27n;
    const lo = this.next(27);
    return Number(hi | lo) * Math.pow(2, -53);
  }

  nextLong() {
    // Java: ((long) next(32) << 32) + next(32), where next(32) is a SIGNED int
    // (seed >>> 16 can be >= 2^31, and the (int) cast wraps it).
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

const n1 = Number(process.argv[2] ?? 20000);
const n2 = Number(process.argv[3] ?? 20000);
const seed = Number(process.argv[4] ?? 42);

const lines = [];
const emit = (x) => {
  lines.push(
    [String(x), x.toFixed(0), x.toFixed(1), x.toFixed(2), x.toFixed(6), x.toFixed(17)].join("\t"),
  );
};

const rng = new JavaRandom(seed);
for (let i = 0; i < n1; i++) emit(rng.nextDouble());
for (let i = 0; i < n2; i++) emit(bitsToDouble(rng.nextLong()));
for (const x of [
  0, -0, 1, -1, 0.5, -0.5, 1.5, 2.5, 1.005, 0.615, 100, 1e21, 1e20, 1e-6, 1e-7, 5e-324,
  1.7976931348623157e308, -1.7976931348623157e308, 1 / 3, 123.456, NaN, Infinity, -Infinity,
  9007199254740991, 9007199254740992 + 1, 1e100, 4.9e-324, Number.MAX_SAFE_INTEGER,
]) {
  emit(x);
}

process.stdout.write(lines.join("\n") + "\n");
