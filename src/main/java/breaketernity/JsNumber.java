package breaketernity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ECMAScript number semantics that differ from the corresponding Java facilities.
 *
 * <p>break_eternity.js builds user-visible strings with JavaScript's
 * {@code Number.prototype.toString/toFixed/toExponential/toPrecision} and parses input
 * with {@code parseFloat}. Java's equivalents differ in both formatting and digits, e.g.
 * Java prints {@code 4.9E-324} where V8 prints {@code 5e-324}, and Java's
 * {@code Math.round} returns a {@code long} that overflows for large inputs. This class
 * reproduces the ECMAScript behavior; every method here is exercised by the differential
 * tests against Node.
 */
public final class JsNumber {
    private JsNumber() {}

    /** ECMAScript's Number.MAX_SAFE_INTEGER. */
    public static final double MAX_SAFE_INTEGER = 9007199254740991.0;

    /**
     * ECMAScript {@code Number::toString(x, 10)}: the shortest decimal string that
     * round-trips to {@code x}, in the layout JS uses: plain decimal when
     * 1e-6 <= |x| < 1e21, scientific notation otherwise, no trailing ".0", and
     * {@code -0.0} prints as "0".
     */
    public static String toString(double x) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (x == Double.POSITIVE_INFINITY) {
            return "Infinity";
        }
        if (x == Double.NEGATIVE_INFINITY) {
            return "-Infinity";
        }
        if (x == 0.0) {
            // Covers -0.0, which JS prints as "0".
            return "0";
        }
        String sign = x < 0 ? "-" : "";
        double abs = Math.abs(x);
        BigDecimal exact = new BigDecimal(abs);
        // Find the smallest number of significant digits for which some decimal of that
        // length round-trips to x (the ECMAScript shortest-representation rule). The
        // closest such decimal is one of the two k-digit decimals bracketing x, so
        // consider DOWN and UP roundings; when both work, take the closer one, and on an
        // exact tie take the one with an even last digit (per ECMA-262).
        for (int k = 1; k <= 17; k++) {
            BigDecimal down = exact.round(new MathContext(k, RoundingMode.DOWN));
            BigDecimal up = exact.round(new MathContext(k, RoundingMode.UP));
            boolean downOk = roundTrips(down, abs);
            boolean upOk = roundTrips(up, abs);
            if (!downOk && !upOk) {
                continue;
            }
            BigDecimal chosen;
            if (downOk && upOk && !down.equals(up)) {
                int closer = exact.subtract(down).abs().compareTo(up.subtract(exact).abs());
                if (closer < 0) {
                    chosen = down;
                } else if (closer > 0) {
                    chosen = up;
                } else {
                    chosen = lastDigitIsEven(down) ? down : up;
                }
            } else {
                chosen = downOk ? down : up;
            }
            return sign + renderDecimal(digitsOf(chosen, 0), exponentOf(chosen));
        }
        throw new IllegalStateException("No shortest representation found for " + x);
    }

    /**
     * ECMAScript {@code Number.prototype.toFixed}: {@code x} rounded to
     * {@code fractionDigits} places after the decimal point, half away from zero on the
     * exact binary value (which is what V8 does, including for e.g.
     * {@code (-0.5).toFixed(0) == "-1"} and {@code (1.005).toFixed(2) == "1.00"}).
     */
    public static String toFixed(double x, int fractionDigits) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (!Double.isFinite(x)) {
            return toString(x);
        }
        if (Math.abs(x) >= 1e21) {
            // Spec: if x >= 10^21, return ToString(x).
            return toString(x);
        }
        boolean negative = x < 0; // -0.0 is not negative, matching the spec.
        BigDecimal rounded =
                new BigDecimal(Math.abs(x)).setScale(fractionDigits, RoundingMode.HALF_UP);
        return (negative ? "-" : "") + rounded.toPlainString();
    }

    /**
     * ECMAScript {@code Number.prototype.toExponential}: exactly
     * {@code fractionDigits} digits after the point in the mantissa, always in
     * exponential form.
     */
    public static String toExponential(double x, int fractionDigits) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (!Double.isFinite(x)) {
            return toString(x);
        }
        String sign = x < 0 ? "-" : "";
        if (x == 0.0) {
            return sign + "0" + (fractionDigits > 0 ? "." + zeros(fractionDigits) : "") + "e+0";
        }
        BigDecimal rounded =
                new BigDecimal(Math.abs(x))
                        .round(new MathContext(fractionDigits + 1, RoundingMode.HALF_UP));
        // round() does not pad with zeros, so pad to the requested digit count.
        String digits = digitsOf(rounded, fractionDigits + 1);
        int n = exponentOf(rounded);
        String mantissa =
                digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
        return sign + mantissa + "e" + exponentSuffix(n - 1);
    }

    /**
     * ECMAScript {@code Number.prototype.toPrecision}: {@code precision} significant
     * digits, in exponential form when the exponent is < -6 or >= precision.
     */
    public static String toPrecision(double x, int precision) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (!Double.isFinite(x)) {
            return toString(x);
        }
        String sign = x < 0 ? "-" : "";
        if (x == 0.0) {
            if (precision == 0) {
                return "0";
            }
            return sign + "0." + zeros(precision - 1);
        }
        BigDecimal rounded =
                new BigDecimal(Math.abs(x)).round(new MathContext(precision, RoundingMode.HALF_UP));
        String digits = digitsOf(rounded, precision);
        int n = exponentOf(rounded); // the decimal point sits after n digits
        int e = n - 1; // scientific exponent
        if (e < -6 || e >= precision) {
            String mantissa =
                    digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
            return sign + mantissa + "e" + exponentSuffix(e);
        }
        if (n <= 0) {
            return sign + "0." + zeros(-n) + digits;
        }
        if (n >= digits.length()) {
            return sign + digits + zeros(n - digits.length());
        }
        return sign + digits.substring(0, n) + "." + digits.substring(n);
    }

    /**
     * ECMAScript {@code parseFloat}: parses the longest valid prefix of the string as a
     * decimal literal (sign, digits, optional fraction, optional exponent, or Infinity),
     * returning NaN when there is no valid prefix. Unlike Java's
     * {@code Double.parseDouble}, trailing garbage is ignored and a dangling exponent
     * falls back to the mantissa.
     */
    public static double parseFloat(String s) {
        Matcher m = PARSE_FLOAT_PATTERN.matcher(s);
        if (!m.find()) {
            return Double.NaN;
        }
        String text = m.group();
        if (text.equals("Infinity") || text.equals("+Infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (text.equals("-Infinity")) {
            return Double.NEGATIVE_INFINITY;
        }
        return Double.parseDouble(text);
    }

    /** ECMAScript {@code Math.round}: half toward +Infinity, preserving -0. */
    public static double round(double x) {
        if (!Double.isFinite(x)) {
            return x;
        }
        // Compare the fractional part directly instead of computing floor(x + 0.5):
        // for x = 0.49999999999999994 (the largest double below 0.5), x + 0.5 rounds up
        // to 1.0, but the spec's "closest integral, ties toward +Infinity" gives 0. The
        // subtraction is exact for every finite double, so this also gives identity for
        // |x| >= 2^52 (where the fraction is 0) without a separate guard.
        double floor = Math.floor(x);
        double fraction = x - floor;
        double r = fraction < 0.5 ? floor : floor + 1;
        if (r == 0 && x < 0) {
            return -0.0;
        }
        return r;
    }

    /** ECMAScript {@code Math.trunc}: drops the fraction, preserving -0. (Java has no Math.trunc.) */
    public static double trunc(double x) {
        if (!Double.isFinite(x)) {
            return x;
        }
        return x < 0 ? Math.ceil(x) : Math.floor(x);
    }

    // --- internals ---

    private static final Pattern PARSE_FLOAT_PATTERN =
            Pattern.compile("^\\s*[+-]?(?:Infinity|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)");

    /** Whether the given decimal, at this digit length, parses back to the original double. */
    private static boolean roundTrips(BigDecimal candidate, double abs) {
        return Double.parseDouble(candidate.toString()) == abs;
    }

    /** Whether the last significant digit of the decimal is even (for the ES tie-break). */
    private static boolean lastDigitIsEven(BigDecimal value) {
        BigInteger last = value.stripTrailingZeros().unscaledValue().abs().mod(BigInteger.TEN);
        return last.intValue() % 2 == 0;
    }

    /**
     * The significant digits of a BigDecimal. When {@code minDigits} is 0 trailing
     * zeros are stripped (the canonical shortest form); otherwise the digits are padded
     * with trailing zeros to exactly {@code minDigits} characters, unless rounding
     * produced more.
     */
    private static String digitsOf(BigDecimal value, int minDigits) {
        String digits = value.unscaledValue().abs().toString();
        if (minDigits == 0) {
            int end = digits.length();
            while (end > 1 && digits.charAt(end - 1) == '0') {
                end--;
            }
            return digits.substring(0, end);
        }
        if (digits.length() >= minDigits) {
            return digits;
        }
        return digits + zeros(minDigits - digits.length());
    }

    /**
     * The position of the decimal point: the value equals
     * {@code digits * 10^(n - digits.length())} for the (unstripped, unpadded) digits of
     * the BigDecimal. Stripping trailing zeros or padding does not change this.
     */
    private static int exponentOf(BigDecimal value) {
        int k = value.unscaledValue().abs().toString().length();
        return k - value.scale();
    }

    /** Renders per ECMAScript Number::toString layout rules given digits and point position. */
    private static String renderDecimal(String digits, int n) {
        int k = digits.length();
        if (k <= n && n <= 21) {
            return digits + zeros(n - k);
        }
        if (0 < n && n <= 21) {
            return digits.substring(0, n) + "." + digits.substring(n);
        }
        if (-6 < n && n <= 0) {
            return "0." + zeros(-n) + digits;
        }
        String mantissa = k > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
        return mantissa + "e" + exponentSuffix(n - 1);
    }

    private static String exponentSuffix(int e) {
        return (e >= 0 ? "+" : "-") + Math.abs(e);
    }

    private static String zeros(int count) {
        return "0".repeat(Math.max(count, 0));
    }
}
