package breaketernity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Port of the original src/unit_tests.js. The ground-truth tables are read from fixtures
 * generated from the original file by tools/gen-ground-truth.mjs (so the expected values
 * are the original literals, not hand transcriptions); the randomized property tests use
 * a seeded RNG instead of Math.random, and turn the original's console.log-only
 * assertions into real failures.
 */
class UpstreamUnitTests {

    /** The original's assert_eq_tolerance: both NaN, or both infinite with equal sign, pass. */
    static void assertEqTolerance(String message, Decimal actual, Decimal expected, double tolerance) {
        if (!actual.isFinite() && !expected.isFinite()) {
            if (actual.isNan() && expected.isNan()) {
                return;
            }
            if (actual.sign == expected.sign) {
                return;
            }
        }
        assertTrue(
                actual.eq_tolerance(expected, tolerance),
                message + ": expected " + expected + " (" + expected.sign + "," + expected.layer + ","
                        + expected.mag + ") but got " + actual + " (" + actual.sign + ","
                        + actual.layer + "," + actual.mag + ")");
    }

    private static Decimal parseExpected(String s) {
        return new Decimal(s);
    }

    private static List<String[]> readFixture(String name) throws IOException {
        Path path = Paths.get("src", "test", "resources", name);
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) {
                rows.add(line.split("\t", -1));
            }
        }
        return rows;
    }

    // --- ground truths (test_tetrate_ground_truth, test_slog_ground_truth, test_tetrate_linear_truth) ---

    static Stream<String[]> tetrateGroundTruth() throws IOException {
        return readFixture("ground_truth_tetrate.tsv").stream();
    }

    @ParameterizedTest
    @MethodSource("tetrateGroundTruth")
    void tetrateGroundTruth(String height, String expected, String tolerance) {
        assertEqTolerance(
                "tetrate(" + height + ")",
                new Decimal(10).tetrate(Double.parseDouble(height)),
                parseExpected(expected),
                Double.parseDouble(tolerance));
    }

    static Stream<String[]> slogGroundTruth() throws IOException {
        return readFixture("ground_truth_slog.tsv").stream();
    }

    @ParameterizedTest
    @MethodSource("slogGroundTruth")
    void slogGroundTruth(String value, String expected, String tolerance) {
        assertEqTolerance(
                "slog(" + value + ")",
                new Decimal(value).slog(new Decimal(10)),
                parseExpected(expected),
                Double.parseDouble(tolerance));
    }

    static Stream<String[]> tetrateLinearGroundTruth() throws IOException {
        return readFixture("ground_truth_tetrate_linear.tsv").stream();
    }

    @ParameterizedTest
    @MethodSource("tetrateLinearGroundTruth")
    void tetrateLinearGroundTruth(String height, String expected, String tolerance) {
        assertEqTolerance(
                "linear tetrate(" + height + ")",
                new Decimal(10).tetrate(Double.parseDouble(height), Decimal.dOne, true),
                parseExpected(expected),
                Double.parseDouble(tolerance));
    }

    // --- randomized property tests (seeded for reproducibility) ---

    private static final int ITERATIONS = 300;

    @Test
    void tetrateSlogRoundTrip() {
        Random rng = new Random(1);
        for (int i = 0; i < ITERATIONS; ++i) {
            double base = rng.nextDouble() * 10 + 1.5;
            double tower = rng.nextDouble() * 10 - 1;
            double roundTrip = new Decimal(base).tetrate(tower).slog(new Decimal(base)).toNumber();
            double roundTripLinear =
                    new Decimal(base).tetrate(tower, Decimal.dOne, true)
                            .slog(new Decimal(base), 100, true)
                            .toNumber();
            assertEqTolerance(
                    "tetrateSlogRoundTrip " + base + ", " + tower + ", analytic",
                    new Decimal(roundTrip),
                    new Decimal(tower),
                    base < 2 ? 1e-2 : 1e-10);
            assertEqTolerance(
                    "tetrateSlogRoundTrip " + base + ", " + tower + ", linear",
                    new Decimal(roundTripLinear),
                    new Decimal(tower),
                    1e-10);
        }
    }

    @Test
    void layeradd10Twice() {
        Random rng = new Random(2);
        for (int i = 0; i < ITERATIONS; ++i) {
            double first = rng.nextDouble() * 100;
            double both = rng.nextDouble() * 100;
            double expected = first + both + 1;
            Decimal result = new Decimal(10).layeradd10(first).layeradd10(both).slog();
            Decimal resultLinear =
                    new Decimal(10).layeradd10(first, true).layeradd10(both, true).slog(new Decimal(10), 100, true);
            assertEqTolerance("layeradd10Twice analytic " + first + ", " + both, result, new Decimal(expected), 1e-7);
            assertEqTolerance("layeradd10Twice linear " + first + ", " + both, resultLinear, new Decimal(expected), 1e-7);
        }
    }

    @Test
    void layeradd10Reverse() {
        Random rng = new Random(3);
        for (int i = 0; i < ITERATIONS; ++i) {
            double first = rng.nextDouble() * 100;
            double both = rng.nextDouble() * 100;
            first += both;
            double expected = first - both + 1;
            Decimal result = new Decimal(10).layeradd10(first).layeradd10(-both).slog();
            Decimal resultLinear =
                    new Decimal(10).layeradd10(first, true).layeradd10(-both, true).slog(new Decimal(10), 100, true);
            assertEqTolerance("layeradd10Reverse analytic " + first + ", " + both, result, new Decimal(expected), 1e-7);
            assertEqTolerance("layeradd10Reverse linear " + first + ", " + both, resultLinear, new Decimal(expected), 1e-7);
        }
    }

    @Test
    void layeradd10TwiceAnyBase() {
        Random rng = new Random(4);
        for (int i = 0; i < ITERATIONS; ++i) {
            double first = rng.nextDouble() * 100;
            double both = rng.nextDouble() * 100;
            double base = rng.nextDouble() * 8 + 2;
            double expected = first + both + 1;
            Decimal result = new Decimal(base).layeradd(first, base).layeradd(both, base).slog(new Decimal(base));
            Decimal resultLinear =
                    new Decimal(base).layeradd(first, base, true).layeradd(both, base, true)
                            .slog(new Decimal(base), 100, true);
            assertEqTolerance("layeradd10TwiceAnyBase analytic " + first + ", " + both + ", " + base, result, new Decimal(expected), 1e-7);
            assertEqTolerance("layeradd10TwiceAnyBase linear " + first + ", " + both + ", " + base, resultLinear, new Decimal(expected), 1e-7);
        }
    }

    @Test
    void layeradd10ReverseAnyBase() {
        Random rng = new Random(5);
        for (int i = 0; i < ITERATIONS; ++i) {
            double first = rng.nextDouble() * 100;
            double both = rng.nextDouble() * 100;
            double base = rng.nextDouble() * 8 + 2;
            first += both;
            double expected = first - both + 1;
            Decimal result = new Decimal(base).layeradd(first, base).layeradd(-both, base).slog(new Decimal(base));
            Decimal resultLinear =
                    new Decimal(base).layeradd(first, base, true).layeradd(-both, base, true)
                            .slog(new Decimal(base), 100, true);
            assertEqTolerance("layeradd10ReverseAnyBase analytic " + first + ", " + both + ", " + base, result, new Decimal(expected), 1e-7);
            assertEqTolerance("layeradd10ReverseAnyBase linear " + first + ", " + both + ", " + base, resultLinear, new Decimal(expected), 1e-7);
        }
    }

    @Test
    void tetrateIteratedlog() {
        Random rng = new Random(6);
        for (int i = 0; i < ITERATIONS; ++i) {
            double first = Math.round(rng.nextDouble() * 30) / 10.0;
            double both = Math.round(rng.nextDouble() * 30) / 10.0;
            Decimal tetrateonly = Decimal.tetrate(new Decimal(10), first);
            Decimal tetrateandlog = Decimal.tetrate(new Decimal(10), first + both).iteratedlog(new Decimal(10), both);
            Decimal tetrateonlyLinear = Decimal.tetrate(new Decimal(10), first, Decimal.dOne, true);
            Decimal tetrateandlogLinear =
                    Decimal.tetrate(new Decimal(10), first + both, Decimal.dOne, true).iteratedlog(new Decimal(10), both, true);
            assertEqTolerance("tetrateIteratedlog analytic " + first + ", " + both, tetrateandlog, tetrateonly, 1e-7);
            assertEqTolerance("tetrateIteratedlog linear " + first + ", " + both, tetrateandlogLinear, tetrateonlyLinear, 1e-7);
        }
    }

    @Test
    void tetrateBase() {
        Random rng = new Random(7);
        for (int i = 0; i < ITERATIONS; ++i) {
            double first = Math.round(rng.nextDouble() * 30) / 10.0;
            double both = Math.round(rng.nextDouble() * 30) / 10.0;
            double base = rng.nextDouble() * 9 + 1;
            if (base == 1) {
                base = 2;
            }
            Decimal baseD = new Decimal(base);
            Decimal tetrateonly = Decimal.tetrate(baseD, first);
            Decimal tetrateandlog = Decimal.tetrate(baseD, first + both).iteratedlog(baseD, both);
            Decimal tetrateonlyLinear = Decimal.tetrate(baseD, first, Decimal.dOne, true);
            Decimal tetrateandlogLinear =
                    Decimal.tetrate(baseD, first + both, Decimal.dOne, true).iteratedlog(baseD, both, true);
            assertEqTolerance("tetrateBase analytic " + base, tetrateandlog, tetrateonly, 1e-7);
            assertEqTolerance("tetrateBase linear " + base, tetrateandlogLinear, tetrateonlyLinear, 1e-7);
        }
    }

    @Test
    void tetrateBaseWithPayload() {
        Random rng = new Random(8);
        for (int i = 0; i < ITERATIONS; ++i) {
            double first = Math.round(rng.nextDouble() * 30) / 10.0;
            double both = Math.round(rng.nextDouble() * 30) / 10.0;
            double base = rng.nextDouble() * 9 + 1;
            if (base == 1) {
                base = 2;
            }
            Decimal baseD = new Decimal(base);
            Decimal tetrateonly = Decimal.tetrate(baseD, first, baseD);
            Decimal tetrateandlog = Decimal.tetrate(baseD, first + both, baseD).iteratedlog(baseD, both);
            Decimal tetrateonlyLinear = Decimal.tetrate(baseD, first, baseD, true);
            Decimal tetrateandlogLinear =
                    Decimal.tetrate(baseD, first + both, baseD, true).iteratedlog(baseD, both, true);
            assertEqTolerance("tetrateBaseWithPayload analytic " + base, tetrateandlog, tetrateonly, 1e-7);
            assertEqTolerance("tetrateBaseWithPayload linear " + base, tetrateandlogLinear, tetrateonlyLinear, 1e-7);
        }
    }

    @Test
    void lambertwPrincipal() {
        Random rng = new Random(9);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal xex = new Decimal(-0.3678794411710499 + rng.nextDouble() * 100);
            Decimal x = xex.lambertw();
            assertEqTolerance("lambertw " + xex, x.mul(x.exp()), xex, 1e-7);
        }
    }

    @Test
    void lambertwPrincipalLarge() {
        Random rng = new Random(10);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal xex = new Decimal(-0.3678794411710499 + Math.exp(rng.nextDouble() * 100));
            Decimal x = xex.lambertw();
            assertEqTolerance("lambertwLarge " + xex, x.mul(x.exp()), xex, 1e-7);
        }
    }

    @Test
    void lambertwPrincipalHuge() {
        Random rng = new Random(11);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal xex = Decimal.iteratedexp(new Decimal(10), 3, new Decimal(rng.nextDouble() * 16));
            Decimal x = xex.lambertw();
            assertEqTolerance("lambertwHuge " + xex, x.mul(x.exp()), xex, 1e-7);
        }
    }

    @Test
    void addAgainstNumber() {
        Random rng = new Random(12);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal a = Decimal.randomDecimalForTesting(rng.nextDouble() > 0.5 ? 0 : 1, rng);
            Decimal b = Decimal.randomDecimalForTesting(rng.nextDouble() > 0.5 ? 0 : 1, rng);
            if (rng.nextDouble() > 0.5) {
                a = a.recip();
            }
            if (rng.nextDouble() > 0.5) {
                b = b.recip();
            }
            double c = a.add(b).toNumber();
            if (Double.isFinite(c)) {
                assertEqTolerance("add " + a + ", " + b, new Decimal(c), new Decimal(a.toNumber() + b.toNumber()), 1e-7);
            }
        }
    }

    @Test
    void mulAgainstNumber() {
        Random rng = new Random(13);
        for (int i = 0; i < 100; ++i) {
            Decimal a = Decimal.randomDecimalForTesting((int) Math.round(rng.nextDouble() * 4), rng);
            Decimal b = Decimal.randomDecimalForTesting((int) Math.round(rng.nextDouble() * 4), rng);
            if (rng.nextDouble() > 0.5) {
                a = a.recip();
            }
            if (rng.nextDouble() > 0.5) {
                b = b.recip();
            }
            double c = a.mul(b).toNumber();
            if (Double.isFinite(a.toNumber()) && Double.isFinite(b.toNumber()) && Double.isFinite(c)
                    && a.toNumber() != 0 && b.toNumber() != 0) {
                assertEqTolerance("mul " + a + ", " + b, new Decimal(c), new Decimal(a.toNumber() * b.toNumber()), 1e-7);
            }
            assertEqTolerance(
                    "mul recip " + a + ", " + b,
                    Decimal.mul(a.recip(), b.recip()),
                    Decimal.mul(a, b).recip(),
                    1e-7);
        }
    }

    @Test
    void powRoot() {
        Random rng = new Random(14);
        for (int i = 0; i < 10; ++i) {
            Decimal a = Decimal.randomDecimalForTesting((int) Math.round(rng.nextDouble() * 4), rng);
            Decimal b = Decimal.randomDecimalForTesting((int) Math.round(rng.nextDouble() * 4), rng);
            if (b.eq(0)) {
                continue;
            }
            if (rng.nextDouble() > 0.5 && a.sign != 0) {
                a = a.recip();
            }
            if (rng.nextDouble() > 0.5 && b.sign != 0) {
                b = b.recip();
            }
            assertEqTolerance("powRoot 1 " + a + ", " + b, a.pow(b), a.root(b.recip()), 1e-7);
            assertEqTolerance("powRoot 2 " + a + ", " + b, a.pow(b.recip()), a.root(b), 1e-7);
        }
    }

    @Test
    void modulo() {
        Random rng = new Random(15);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal a = Decimal.pow(new Decimal(10), rng.nextDouble() * 15);
            Decimal b = Decimal.pow(new Decimal(10), rng.nextDouble() * 15);
            if (rng.nextDouble() > 0.5 && a.sign != 0) {
                a = a.recip();
            }
            if (rng.nextDouble() > 0.5 && b.sign != 0) {
                b = b.recip();
            }
            if (rng.nextDouble() > 0.5) {
                a = a.neg();
            }
            if (rng.nextDouble() > 0.5) {
                b = b.neg();
            }
            Decimal c = a.mod(b);
            Decimal d = a.abs().div(b.abs()).floor().mul(b.abs()).add(c.abs()).mul(a.sgn());
            assertEqTolerance("modulo " + a + ", " + b, d, a, 1e-5);
        }
    }

    @Test
    void tetrateLinearSroot() {
        Random rng = new Random(16);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal base = Decimal.randomDecimalForTesting((int) Math.round(rng.nextDouble() * 4), rng);
            if (base.lt(0)) {
                base = base.neg();
            }
            double degree = rng.nextDouble() * 10;
            if (base.eq(0)) {
                base = Decimal.dOne;
            }
            Decimal roundTrip = base.linear_sroot(degree).tetrate(degree, Decimal.dOne, true);
            if (!roundTrip.isFinite()) {
                continue;
            }
            assertEqTolerance("linear_sroot " + base + ", " + degree, roundTrip, base, 1e-7);
        }
    }

    @Test
    void ssqrt() {
        Random rng = new Random(17);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal base = Decimal.randomDecimalForTesting((int) Math.round(rng.nextDouble() * 4), rng);
            if (base.lt(0)) {
                base = base.neg();
            }
            if (base.eq(0)) {
                base = Decimal.dOne;
            }
            Decimal srootResult = base.ssqrt();
            Decimal wResult;
            try {
                wResult = base.ln().div(base.ln().lambertw());
            } catch (RuntimeException e) {
                continue;
            }
            if (!wResult.isFinite()) {
                continue;
            }
            assertEqTolerance("ssqrt " + base, srootResult, wResult, 1e-7);
        }
    }

    @Test
    void lambertwNonPrincipal() {
        Random rng = new Random(18);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal xex = new Decimal(rng.nextDouble() * -0.3678794411710499);
            Decimal x = xex.lambertw(false);
            assertEqTolerance("lambertwNonPrincipal " + xex, x.mul(x.exp()), xex, 1e-7);
        }
    }

    @Test
    void lambertwNonPrincipalSmall() {
        Random rng = new Random(19);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal xex = new Decimal(Math.exp(rng.nextDouble() * -100 - 1)).neg();
            Decimal x = xex.lambertw(false);
            assertEqTolerance("lambertwNonPrincipalSmall " + xex, x.mul(x.exp()), xex, 1e-7);
        }
    }

    @Test
    void lambertwNonPrincipalHuge() {
        Random rng = new Random(20);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal xex = Decimal.iteratedexp(new Decimal(10), 3, new Decimal(rng.nextDouble() * 16)).neg().recip();
            Decimal x = xex.lambertw(false);
            assertEqTolerance("lambertwNonPrincipalHuge " + xex, x.mul(x.exp()), xex, 1e-7);
        }
    }

    @Test
    void tetrateSmallBases() {
        Random rng = new Random(21);
        for (int i = 0; i < ITERATIONS; ++i) {
            double base = 1.44466786100976613366 - rng.nextDouble();
            Decimal baseD = new Decimal(base);
            Decimal negln = baseD.ln().neg();
            Decimal lower = negln.lambertw().div(negln);
            Decimal upper = Decimal.dInf;
            Decimal payload = Decimal.pow10(new Decimal(rng.nextDouble() * 6 - 3));
            if (base > 1) {
                upper = negln.lambertw(false).div(negln);
            }
            Decimal finite = Decimal.tetrate(baseD, 20000 + rng.nextDouble(), payload);
            Decimal infinite = Decimal.tetrate(baseD, Double.POSITIVE_INFINITY, payload);
            Decimal finiteLinear = Decimal.tetrate(baseD, 20000 + rng.nextDouble(), payload, true);
            if (payload.gt(upper)) {
                assertTrue(
                        finite.gt(new Decimal("F10000"))
                                || infinite.gt(new Decimal("F10000"))
                                || finiteLinear.gt(new Decimal("F10000")),
                        "tetrateSmallBases above upper " + base + " " + payload);
            } else if (payload.eq(upper)) {
                assertEqTolerance("tetrateSmallBases finite " + base, finite, upper, 1e-7);
                assertEqTolerance("tetrateSmallBases infinite " + base, infinite, upper, 1e-7);
                assertEqTolerance("tetrateSmallBases finiteLinear " + base, finiteLinear, upper, 1e-7);
            } else {
                assertEqTolerance("tetrateSmallBases finite " + base, finite, lower, 1e-7);
                assertEqTolerance("tetrateSmallBases infinite " + base, infinite, lower, 1e-7);
                assertEqTolerance("tetrateSmallBases finiteLinear " + base, finiteLinear, lower, 1e-7);
            }
        }
    }

    @Test
    void layeraddRecursiveProperty() {
        Random rng = new Random(22);
        for (int i = 0; i < ITERATIONS; ++i) {
            double base = 1 + rng.nextDouble() * 20;
            Decimal payload = new Decimal(rng.nextDouble() * 100);
            assertEqTolerance(
                    "layeradd recursive analytic " + base + ", " + payload,
                    payload.layeradd(1, base),
                    Decimal.pow(new Decimal(base), payload),
                    1e-7);
            assertEqTolerance(
                    "layeradd recursive linear " + base + ", " + payload,
                    payload.layeradd(1, base, true),
                    Decimal.pow(new Decimal(base), payload),
                    1e-7);
        }
    }

    @Test
    void tetrateIncreasing() {
        Random rng = new Random(23);
        int checked = 0;
        for (int i = 0; i < 100; ++i) {
            double base = 1 + rng.nextDouble() * 5;
            Decimal payload = new Decimal(rng.nextDouble() * 100);
            double height = rng.nextDouble() * 4 - 1;
            double nextheight = height + rng.nextDouble();
            boolean linear = rng.nextDouble() > 0.5;
            Decimal baseD = new Decimal(base);
            Decimal lowertetrate = Decimal.tetrate(baseD, height, payload, linear);
            Decimal highertetrate = Decimal.tetrate(baseD, nextheight, payload, linear);
            Decimal lower = Decimal.dInf;
            Decimal upper = Decimal.dInf;
            if (base < 1.44466786100976613366) {
                Decimal negln = baseD.ln().neg();
                lower = negln.lambertw().div(negln);
                if (base > 1) {
                    upper = negln.lambertw(false).div(negln);
                }
            }
            if (payload.eq(lower) || payload.eq(upper)) {
                continue;
            }
            boolean decreasing = payload.gt(lower) && payload.lt(upper);
            for (int j = 0; j < 5; j++) {
                assertFalse(
                        (!decreasing && highertetrate.lt(lowertetrate))
                                || (decreasing && highertetrate.gt(lowertetrate)),
                        "tetrate not monotonic at base " + base + ", payload " + payload
                                + (linear ? " linear" : " analytic"));
                checked++;
                height = nextheight;
                nextheight = height + rng.nextDouble();
                linear = rng.nextDouble() > 0.5;
                lowertetrate = Decimal.tetrate(baseD, height, payload, linear);
                highertetrate = Decimal.tetrate(baseD, nextheight, payload, linear);
            }
        }
        assertTrue(checked > 0, "no monotonicity comparisons were made");
    }

    @Test
    void pentaLogRoundTrip() {
        Random rng = new Random(24);
        for (int i = 0; i < ITERATIONS; ++i) {
            double base = rng.nextDouble() * 10 + 1.7;
            double tower = rng.nextDouble() * 10;
            Decimal part1 = new Decimal(base).pentate(tower);
            Decimal part1Linear = new Decimal(base).pentate(tower, Decimal.dOne, true);
            if (!part1.isFinite() || !part1Linear.isFinite()) {
                continue;
            }
            Decimal roundTrip = part1.penta_log(new Decimal(base));
            Decimal roundTripLinear = part1Linear.penta_log(new Decimal(base), 100, true);
            assertEqTolerance("pentaLog analytic " + base + ", " + tower, roundTrip, new Decimal(tower), base < 2 ? 1e-2 : 1e-10);
            assertEqTolerance("pentaLog linear " + base + ", " + tower, roundTripLinear, new Decimal(tower), 1e-10);
        }
    }

    @Test
    void linearPentaRoot() {
        Random rng = new Random(25);
        for (int i = 0; i < ITERATIONS; ++i) {
            Decimal base = Decimal.randomDecimalForTesting((int) Math.round(rng.nextDouble() * 4), rng);
            if (base.lt(0)) {
                base = base.neg();
            }
            double degree = rng.nextDouble() * 10;
            if (base.eq(0)) {
                base = Decimal.dOne;
            }
            Decimal roundTrip = base.linear_penta_root(degree).pentate(degree, Decimal.dOne, true);
            if (!roundTrip.isFinite()) {
                continue;
            }
            assertEqTolerance("linearPentaRoot " + base + ", " + degree, roundTrip, base, 1e-7);
        }
    }
}
