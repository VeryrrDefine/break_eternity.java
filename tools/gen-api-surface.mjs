#!/usr/bin/env node
/**
 * Extracts the public API surface from the original's index.d.ts and writes the list of
 * member names to src/test/resources/api_surface.txt, so ApiSurfaceTest can assert that
 * every method of the original exists in the Java port without needing the original repo
 * at test time.
 *
 * Usage: node tools/gen-api-surface.mjs [path/to/break_eternity.js]
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const originalRepo = process.argv[2] ?? resolve(here, "..", "..", "break_eternity.js");
const source = readFileSync(resolve(originalRepo, "index.d.ts"), "utf8");

const names = new Set();
const memberPattern = /^\s*(?:public\s+)?(?:static\s+)?(?:get\s+|set\s+)?([A-Za-z_$][\w$]*)\s*[(<]/gm;
let match;
while ((match = memberPattern.exec(source))) {
  const name = match[1];
  if (["constructor", "function", "if", "for", "return", "new"].includes(name)) {
    continue;
  }
  names.add(name);
}

const sorted = [...names].sort();
const out = resolve(here, "..", "src", "test", "resources", "api_surface.txt");
writeFileSync(
  out,
  "# Member names extracted from break_eternity.js index.d.ts by tools/gen-api-surface.mjs.\n" +
    "# ApiSurfaceTest asserts each exists in breaketernity.Decimal.\n" +
    sorted.join("\n") +
    "\n",
);
console.log(`Wrote ${sorted.length} member names to ${out}`);
