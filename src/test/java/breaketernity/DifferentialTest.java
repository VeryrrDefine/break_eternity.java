package breaketernity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Cross-checks this port against the original break_eternity.js running under Node, by
 * running the harnesses in tools/ (which generate a corpus and the original's expected
 * results) and replaying the same operations here.
 *
 * <p>Tagged "differential" and excluded from {@code ./gradlew test}; run it with
 * {@code ./gradlew differentialTest} or {@code -PjsRepo=/path/to/break_eternity.js}.
 *
 * <p>Comparison rules: sign and layer must match exactly; mag must be within a relative
 * tolerance (the port matches the original bit-for-bit for most operations, but a
 * handful of transcendental functions differ by one ulp, so tolerance is the honest
 * criterion); string results are compared exactly for parse/format operations. The
 * harness scripts print a summary of exact vs ulp-level agreement.
 */
@Tag("differential")
class DifferentialTest {

    private static final String NODE =
            System.getProperty("node.bin", System.getenv().getOrDefault("NODE_BIN", "node"));
    private static final String ORIGINAL_REPO =
            System.getProperty("original.repo", "../break_eternity.js");
    private static final double RELATIVE_TOLERANCE = 1e-9;

    /** Runs a harness script and returns its stdout lines. */
    private static List<String> runHarness(String script) throws IOException, InterruptedException {
        Path out = Files.createTempFile("differential", ".tsv");
        ProcessBuilder pb =
                new ProcessBuilder(
                        NODE, Paths.get("tools", script).toString(),
                        Paths.get(ORIGINAL_REPO).toAbsolutePath().toString(), out.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        assertTrue(finished, script + " did not finish in 10 minutes");
        assertEquals(0, process.exitValue(), script + " failed:\n" + output);
        List<String> lines = Files.readAllLines(out);
        Files.deleteIfExists(out);
        assertTrue(lines.size() > 100, script + " produced suspiciously few cases: " + lines.size());
        return lines;
    }

    /** A component triple is "sign,layer,mag", optionally followed by "|toString". */
    private static boolean looksLikeTriple(String column) {
        String triple = column.contains("|") ? column.substring(0, column.indexOf('|')) : column;
        return triple.split(",", -1).length == 3;
    }

    private static Decimal decode(String encoded) {
        // Accepts either "sign,layer,mag" or the full "sign,layer,mag|toString" encoding.
        String triple = encoded.contains("|") ? encoded.substring(0, encoded.indexOf('|')) : encoded;
        String[] c = triple.split(",", -1);
        return Decimal.fromComponents_noNormalize(
                Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]));
    }

    /** Compares one replayed result against the original's encoding. */
    private static void assertSameResult(String context, String expected, String actual) {
        if (expected.equals(actual)) {
            return;
        }
        if (!expected.contains("|") || !actual.contains("|")) {
            // Plain results: booleans and comparison results must match exactly; plain
            // numbers (the critical-section helpers) get the same tolerance as
            // Decimal mags, since they too go through transcendental functions.
            try {
                double expectedNumber = Double.parseDouble(expected);
                double actualNumber = Double.parseDouble(actual);
                double scale = Math.max(Math.abs(expectedNumber), Math.abs(actualNumber));
                double difference = Math.abs(expectedNumber - actualNumber);
                assertTrue(
                        difference <= RELATIVE_TOLERANCE * Math.max(scale, Double.MIN_NORMAL)
                                || difference == 0
                                || (Double.isNaN(expectedNumber) && Double.isNaN(actualNumber)),
                        context + ": expected " + expected + " but got " + actual);
            } catch (NumberFormatException notNumbers) {
                assertEquals(expected, actual, context);
            }
            return;
        }
        String[] expectedParts = expected.split("\\|", -1)[0].split(",", -1);
        String[] actualParts = actual.split("\\|", -1)[0].split(",", -1);
        assertEquals(expectedParts[0], actualParts[0], context + " (sign)");
        assertEquals(expectedParts[1], actualParts[1], context + " (layer)");
        double expectedMag = Double.parseDouble(expectedParts[2]);
        double actualMag = Double.parseDouble(actualParts[2]);
        if (Double.isNaN(expectedMag)) {
            assertTrue(Double.isNaN(actualMag), context + " (nan)");
            return;
        }
        double scale = Math.max(Math.abs(expectedMag), Math.abs(actualMag));
        double difference = Math.abs(expectedMag - actualMag);
        assertTrue(
                difference <= RELATIVE_TOLERANCE * Math.max(scale, Double.MIN_NORMAL)
                        || difference == 0,
                context + ": expected mag " + expectedMag + " but got " + actualMag);
    }

    private static String enc(Decimal d) {
        return JsNumber.toString(d.sign) + "," + JsNumber.toString(d.layer) + ","
                + JsNumber.toString(d.mag) + "|" + d.toString();
    }

    private static String applyArithmetic(String op, Decimal a, Decimal b, String[] p) {
        switch (op) {
            case "add": return enc(a.add(b));
            case "sub": return enc(a.sub(b));
            case "mul": return enc(a.mul(b));
            case "div": return enc(a.div(b));
            case "mod": return enc(a.mod(b));
            case "modFloored": return enc(a.mod(b, true));
            case "cmp": return String.valueOf(a.cmp(b));
            case "cmpabs": return String.valueOf(a.cmpabs(b));
            case "eq": return String.valueOf(a.eq(b));
            case "neq": return String.valueOf(a.neq(b));
            case "lt": return String.valueOf(a.lt(b));
            case "lte": return String.valueOf(a.lte(b));
            case "gt": return String.valueOf(a.gt(b));
            case "gte": return String.valueOf(a.gte(b));
            case "max": return enc(a.max(b));
            case "min": return enc(a.min(b));
            case "maxabs": return enc(a.maxabs(b));
            case "minabs": return enc(a.minabs(b));
            case "eqTol1e-7": return String.valueOf(a.eq_tolerance(b, 1e-7));
            case "eqTol1e-15": return String.valueOf(a.eq_tolerance(b, 1e-15));
            case "ltTol": return String.valueOf(a.lt_tolerance(b, 1e-7));
            case "gteTol": return String.valueOf(a.gte_tolerance(b, 1e-7));
            case "clamp": return enc(a.clamp(b, b.add(1e10)));
            case "log": return enc(a.log(b));
            case "pow": return enc(a.pow(b));
            case "root": return enc(a.root(b));
            case "log10": return enc(a.log10());
            case "log2": return enc(a.log2());
            case "ln": return enc(a.ln());
            case "absLog10": return enc(a.absLog10());
            case "pLog10": return enc(a.pLog10());
            case "pow10": return enc(a.pow10());
            case "exp": return enc(a.exp());
            case "sqrt": return enc(a.sqrt());
            case "sqr": return enc(a.sqr());
            case "cube": return enc(a.cube());
            case "cbrt": return enc(a.cbrt());
            case "gamma": return enc(a.gamma());
            case "factorial": return enc(a.factorial());
            case "lngamma": return enc(a.lngamma());
            case "sin": return enc(a.sin());
            case "cos": return enc(a.cos());
            case "tan": return enc(a.tan());
            case "asin": return enc(a.asin());
            case "acos": return enc(a.acos());
            case "atan": return enc(a.atan());
            case "sinh": return enc(a.sinh());
            case "cosh": return enc(a.cosh());
            case "tanh": return enc(a.tanh());
            case "asinh": return enc(a.asinh());
            case "acosh": return enc(a.acosh());
            case "atanh": return enc(a.atanh());
            case "abs": return enc(a.abs());
            case "neg": return enc(a.neg());
            case "recip": return enc(a.recip());
            case "round": return enc(a.round());
            case "floor": return enc(a.floor());
            case "ceil": return enc(a.ceil());
            case "trunc": return enc(a.trunc());
            case "sign": return JsNumber.toString(a.sgn());
            case "sqrTolerance": return String.valueOf(a.eq_tolerance(a.mul(1 + 1e-9), 1e-7));
            default: return "UNKNOWN_OP";
        }
    }

    private static String applyHyper(String op, Decimal a, String[] p) {
        switch (op) {
            case "tetrate":
                return enc(a.tetrate(
                        Double.parseDouble(p[2]), decode(p[3]), Boolean.parseBoolean(p[4])));
            case "tetrateP1":
                return enc(a.tetrate(
                        Double.parseDouble(p[2]), Decimal.dOne, Boolean.parseBoolean(p[3])));
            case "iteratedlog":
                return enc(a.iteratedlog(
                        decode(p[2]), Double.parseDouble(p[3]), Boolean.parseBoolean(p[4])));
            case "slog":
                return enc(a.slog(decode(p[2]), 100, Boolean.parseBoolean(p[3])));
            case "slogInternal":
                return enc(a.slog_internal(decode(p[2]), Boolean.parseBoolean(p[3])));
            case "layeradd10":
                return enc(a.layeradd10(Double.parseDouble(p[2]), Boolean.parseBoolean(p[3])));
            case "layeradd":
                return enc(a.layeradd(
                        Double.parseDouble(p[2]), decode(p[3]), Boolean.parseBoolean(p[4])));
            case "lambertw":
                return enc(a.lambertw(Boolean.parseBoolean(p[2])));
            case "slogCritical":
                return JsNumber.toString(
                        Decimal.slog_critical(a.toNumber(), Double.parseDouble(p[2])));
            case "tetrateCritical":
                return JsNumber.toString(
                        Decimal.tetrate_critical(a.toNumber(), Double.parseDouble(p[2])));
            case "linearSroot":
                return enc(a.linear_sroot(Double.parseDouble(p[2])));
            case "ssqrt":
                return enc(a.ssqrt());
            case "pentate":
                return enc(a.pentate(Double.parseDouble(p[2]), Decimal.dOne, false));
            case "pentaLog":
                return enc(a.penta_log(new Decimal(10), 100, false));
            case "linearPentaRoot":
                return enc(a.linear_penta_root(Double.parseDouble(p[2])));
            default:
                return "UNKNOWN_OP";
        }
    }

    private static void replay(String script, boolean hyper)
            throws IOException, InterruptedException {
        List<String> lines = runHarness(script);
        int compared = 0;
        List<String> failures = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            String op = p[0];
            Decimal a = decode(p[1]);
            // The second column is an operand for the arithmetic harness, but a plain
            // height/degree for several hyper harness operations, so only decode it when
            // it actually looks like a component triple.
            Decimal b = looksLikeTriple(p[2]) ? decode(p[2]) : null;
            String expected = p[p.length - 1];
            String actual;
            try {
                actual = hyper ? applyHyper(op, a, p) : applyArithmetic(op, a, b, p);
            } catch (RuntimeException e) {
                actual = "THROWS";
            }
            compared++;
            try {
                assertSameResult(script + " " + line, expected, actual);
            } catch (AssertionError e) {
                if (failures.size() < 15) {
                    failures.add(e.getMessage());
                }
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + "+ differences out of " + compared + " cases in " + script
                        + ":\n" + String.join("\n", failures));
    }

    @Test
    void arithmeticAndFunctionsMatchTheOriginal() throws Exception {
        assumeTrue(nodeAvailable());
        replay("arith-diff.mjs", false);
    }

    @Test
    void hyperoperatorsMatchTheOriginal() throws Exception {
        assumeTrue(nodeAvailable());
        replay("hyper-diff.mjs", true);
    }

    @Test
    void numberFormattingMatchesV8() throws Exception {
        assumeTrue(nodeAvailable());
        // number-strings.mjs writes its reference to a path instead of taking an input file.
        Path out = Files.createTempFile("formatting", ".tsv");
        ProcessBuilder pb = new ProcessBuilder(NODE, "tools/number-strings.mjs", "5000", "5000", "42");
        pb.redirectOutput(out.toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "number-strings.mjs timed out");
        List<String> lines = Files.readAllLines(out);
        Files.deleteIfExists(out);
        assertEquals(0, process.exitValue());

        // Regenerate the same corpus in the same order: 5000 nextDouble values, 5000 raw
        // bit patterns, then the harness's fixed special values.
        java.util.Random rng = new java.util.Random(42);
        double[] specials = {
            0, -0.0, 1, -1, 0.5, -0.5, 1.5, 2.5, 1.005, 0.615, 100, 1e21, 1e20, 1e-6, 1e-7, 5e-324,
            1.7976931348623157e308, -1.7976931348623157e308, 1 / 3.0, 123.456, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 9007199254740991.0,
            9007199254740992.0 + 1, 1e100, 4.9e-324, JsNumber.MAX_SAFE_INTEGER
        };
        assertEquals(10000 + specials.length, lines.size(), "unexpected corpus size");
        int compared = 0;
        int index = 0;
        for (int phase = 0; phase < 3; phase++) {
            int count = phase == 0 ? 5000 : phase == 1 ? 5000 : specials.length;
            for (int i = 0; i < count; i++) {
                double value = phase == 0 ? rng.nextDouble()
                        : phase == 1 ? Double.longBitsToDouble(rng.nextLong())
                        : specials[i];
                String[] columns = lines.get(index++).split("\t", -1);
                assertEquals(columns[0], JsNumber.toString(value), "toString case " + index);
                assertEquals(columns[1], JsNumber.toFixed(value, 0), "toFixed(0) case " + index);
                assertEquals(columns[2], JsNumber.toFixed(value, 1), "toFixed(1) case " + index);
                assertEquals(columns[3], JsNumber.toFixed(value, 2), "toFixed(2) case " + index);
                assertEquals(columns[4], JsNumber.toFixed(value, 6), "toFixed(6) case " + index);
                assertEquals(columns[5], JsNumber.toFixed(value, 17), "toFixed(17) case " + index);
                compared++;
            }
        }
        assertEquals(10000 + specials.length, compared);
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder(NODE, "--version").start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
