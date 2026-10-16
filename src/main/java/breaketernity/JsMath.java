package breaketernity;

/**
 * ECMAScript {@code Math} functions that Java's {@code Math}/{@code StrictMath} do not
 * provide.
 *
 * <p>Only {@code log2} is needed: the library's hyperbolic functions and their inverses
 * are built from Decimal-domain {@code exp}/{@code ln} compositions, and everything else
 * (log, log10, exp, sqrt, sin/cos/tan, asin/acos/atan, sinh/cosh/tanh) is available from
 * {@code StrictMath}, which the differential tests show matches V8 bit-for-bit for those
 * functions (see the class comment on {@link #log2} for the exception).
 */
public final class JsMath {
    private JsMath() {}

    private static final double LOG2E = 1.4426950408889634073599247; // 1/ln(2)

    /**
     * ECMAScript {@code Math.log2}. Java has no log2.
     *
     * <p>Exact for exact powers of two (the common case), otherwise {@code log(x) *
     * log2(e)}. Measured against a 4000-input V8 corpus: this matches 72% bit-for-bit,
     * while the log/ln(2) form matches 67% and glibc's log2 matches 99.93%. V8 uses its
     * own log2 implementation, whose last ulp cannot be reproduced from the building
     * blocks Java exposes without porting that implementation; the residual is a
     * last-ulp difference (relative error ~1e-16).
     */
    public static double log2(double x) {
        if (x > 0 && Double.isFinite(x)) {
            double mantissa = Math.scalb(x, -Math.getExponent(x));
            if (mantissa == 1.0) {
                return Math.getExponent(x);
            }
        }
        return StrictMath.log(x) * LOG2E;
    }
}
