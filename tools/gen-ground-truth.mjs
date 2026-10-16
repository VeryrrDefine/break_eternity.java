#!/usr/bin/env node
/**
 * Extracts the ground-truth assertion tables from the original src/unit_tests.js and
 * writes them as TSV fixtures for the JUnit suite, so the ported tests use the original
 * literals verbatim instead of hand-transcribed copies.
 *
 * Output (src/test/resources/):
 *   ground_truth_tetrate.tsv       <height>\t<expected>\t<tolerance>
 *   ground_truth_slog.tsv          <value>\t<expected>\t<tolerance>
 *   ground_truth_tetrate_linear.tsv <height>\t<expected>\t<tolerance>
 *
 * The "actual" side of each row is fixed per file: 10.tetrate(height),
 * value.slog(10), and 10.tetrate(height, 1, true) respectively.
 *
 * Usage: node tools/gen-ground-truth.mjs [path/to/break_eternity.js]
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const originalRepo = process.argv[2] ?? resolve(here, "..", "..", "break_eternity.js");
const source = readFileSync(resolve(originalRepo, "src", "unit_tests.js"), "utf8");

/** Splits a function body out of the source by name. */
function bodyOf(name) {
  const start = source.indexOf(`var ${name} = function`);
  if (start < 0) throw new Error(`no such function: ${name}`);
  const end = source.indexOf("\n}", start);
  return source.slice(start, end);
}

/** Normalises an expected-value expression into a plain string we can re-parse in Java. */
function normExpected(expr) {
  expr = expr.trim();
  let m;
  if ((m = expr.match(/^new Decimal\("(.*)"\)$/))) return m[1];
  if (expr === "Number.NaN") return "NaN";
  return expr;
}

/** Parses assert_eq_tolerance(msg, actual, expected[, tol]) rows; returns [actualArgs, expected, tol]. */
function rows(body, actualPattern) {
  const out = [];
  const re = /^\s*assert_eq_tolerance\((.*)\)\s*$/gm;
  let m;
  while ((m = re.exec(body))) {
    const args = m[1];
    const parts = splitTopLevel(args);
    if (parts.length < 3) continue;
    const expected = normExpected(parts[2]);
    const tol = parts.length > 3 ? parts[3].trim() : "1e-7";
    const actual = parts[1].trim();
    const am = actual.match(actualPattern);
    if (!am) {
      console.warn(`  skipping (different shape): ${actual}`);
      continue;
    }
    out.push([am[1].trim(), expected, tol]);
  }
  return out;
}

/** Splits on commas at paren/quote depth 0. */
function splitTopLevel(s) {
  const parts = [];
  let depth = 0;
  let cur = "";
  let inStr = false;
  for (const c of s) {
    if (c === '"') inStr = !inStr;
    if (!inStr) {
      if (c === "(" || c === "[") depth++;
      else if (c === ")" || c === "]") depth--;
      else if (c === "," && depth === 0) {
        parts.push(cur);
        cur = "";
        continue;
      }
    }
    cur += c;
  }
  parts.push(cur);
  return parts;
}

const resources = resolve(here, "..", "src", "test", "resources");
mkdirSync(resources, { recursive: true });

const files = [
  ["ground_truth_tetrate.tsv", "test_tetrate_ground_truth", /^new Decimal\(10\)\.tetrate\(([^)]*)\)$/],
  ["ground_truth_slog.tsv", "test_slog_ground_truth", /^new Decimal\((.*)\)\.slog\(10\)$/],
  [
    "ground_truth_tetrate_linear.tsv",
    "test_tetrate_linear_truth",
    /^new Decimal\(10\)\.tetrate\(([\d.\-]+), 1, true\)$/,
  ],
];

for (const [file, fn, pattern] of files) {
  const data = rows(bodyOf(fn), pattern);
  writeFileSync(resolve(resources, file), data.map((r) => r.join("\t")).join("\n") + "\n");
  console.log(`${file}: ${data.length} rows`);
}
