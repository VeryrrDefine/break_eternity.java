package breaketernity;

import java.util.Locale;
import java.util.function.Function;

/**
 * A number of the form {@code sign * 10^10^...^mag} with {@code layer} 10s, able to
 * represent numbers as large as 10^^1e308 and as small as 10^-(10^^1e308).
 *
 * <p>Java port of break_eternity.js v2.1.3 (src/index.ts). Method names, static/instance
 * duality and aliases follow the original so JS game code can be ported mechanically.
 * Unlike the original, instances are <em>immutable</em>: {@code sign}, {@code mag} and
 * {@code layer} are final, and the original's mutating {@code from*} methods / property
 * setters are replaced by {@code with*} copies and the static factories.
 *
 * <p>The internal representation mirrors the original exactly:
 * <ul>
 *   <li>{@code sign} is -1, 0 or 1 (NaN for NaN).
 *   <li>{@code layer} is a non-negative integer valued double (it can be as large as
 *       1.79e308, so it is a double, not an int).
 *   <li>{@code mag} is normalized by {@link #normalize}.
 * </ul>
 *
 * <p>Section order and comments mirror src/index.ts; line references like
 * {@code (index.ts:1648)} point at the original implementation.
 */
public final class Decimal {

    // --- module-level constants (index.ts:5-22, generated into CriticalTables) ---

    private static final int MAX_SIGNIFICANT_DIGITS = CriticalTables.MAX_SIGNIFICANT_DIGITS;
    private static final double EXP_LIMIT = CriticalTables.EXP_LIMIT;
    private static final double LAYER_DOWN = CriticalTables.LAYER_DOWN;
    private static final double FIRST_NEG_LAYER = CriticalTables.FIRST_NEG_LAYER;
    private static final int NUMBER_EXP_MAX = CriticalTables.NUMBER_EXP_MAX;
    private static final int NUMBER_EXP_MIN = CriticalTables.NUMBER_EXP_MIN;
    private static final int MAX_ES_IN_A_ROW = CriticalTables.MAX_ES_IN_A_ROW;
    private static final int DEFAULT_FROM_STRING_CACHE_SIZE =
            CriticalTables.DEFAULT_FROM_STRING_CACHE_SIZE;
    private static final boolean IGNORE_COMMAS = CriticalTables.IGNORE_COMMAS;
    private static final boolean COMMAS_ARE_DECIMAL_POINTS = CriticalTables.COMMAS_ARE_DECIMAL_POINTS;

    /**
     * Lookup table of powers of 10 (index.ts:24-39). Needed because Math.pow(10, exponent)
     * is slightly inaccurate when the exponent's absolute value is large. Covers 10^-323
     * to 10^308; out-of-range lookups return NaN (the original returns undefined, which
     * poisons arithmetic into NaN the same way).
     */
    private static final double[] POWER_OF_10;

    private static final int INDEX_OF_0_IN_POWER_OF_10 = 323;

    static {
        POWER_OF_10 = new double[NUMBER_EXP_MAX - (NUMBER_EXP_MIN + 1) + 1];
        for (int i = NUMBER_EXP_MIN + 1; i <= NUMBER_EXP_MAX; i++) {
            POWER_OF_10[i - (NUMBER_EXP_MIN + 1)] = Double.parseDouble("1e" + i);
        }
    }

    private static double powerOf10(double power) {
        int index = (int) (power + INDEX_OF_0_IN_POWER_OF_10);
        if (index < 0 || index >= POWER_OF_10.length) {
            return Double.NaN; // original: undefined
        }
        return POWER_OF_10[index];
    }

    private static double f_maglog10(double n) {
        return Math.signum(n) * StrictMath.log10(Math.abs(n));
    }

    /**
     * Rounds a number to the given number of decimal places (index.ts:207-212).
     * Faithful port, including the quirk that {@code value == 0} yields NaN.
     */
    private static double decimalPlaces(double value, double places) {
        double len = places + 1;
        double numDigits = Math.ceil(StrictMath.log10(Math.abs(value)));
        double rounded =
                JsNumber.round(value * Math.pow(10, len - numDigits))
                        * Math.pow(10, numDigits - len);
        return JsNumber.parseFloat(JsNumber.toFixed(rounded, (int) Math.max(len - numDigits, 0)));
    }

    // Non-allocating sentinels used inside normalize() (declared before the constants so
    // that class initialization cannot observe them as null).
    private static final Decimal ZERO = new Decimal(0, 0, 0);
    private static final Decimal NAN = new Decimal(Double.NaN, Double.NaN, Double.NaN);

    // --- static constants (index.ts:368-422) ---

    /** Represents the number 0. */
    public static final Decimal dZero = fromComponents_noNormalize(0, 0, 0);
    /** Represents the number 1. */
    public static final Decimal dOne = fromComponents_noNormalize(1, 0, 1);
    /** Represents the number -1. */
    public static final Decimal dNegOne = fromComponents_noNormalize(-1, 0, 1);
    /** Represents the number 2. */
    public static final Decimal dTwo = fromComponents_noNormalize(1, 0, 2);
    /** Represents the number 10. */
    public static final Decimal dTen = fromComponents_noNormalize(1, 0, 10);
    /** Represents a NaN (Not A Number) value. */
    public static final Decimal dNaN =
            fromComponents_noNormalize(Double.NaN, Double.NaN, Double.NaN);
    /** Represents positive infinity. */
    public static final Decimal dInf =
            fromComponents_noNormalize(1, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    /** Represents negative infinity. */
    public static final Decimal dNegInf =
            fromComponents_noNormalize(-1, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    /** Represents the largest value a JavaScript number can have, approximately 1.79e308. */
    public static final Decimal dNumberMax = fromComponents(1, 0, CriticalTables.MAX_VALUE);
    /** Represents the smallest value a JavaScript number can have, approximately 5e-324. */
    public static final Decimal dNumberMin = fromComponents(1, 0, CriticalTables.MIN_VALUE);
    /**
     * Represents the largest Decimal where adding 1 to the layer is a safe operation
     * (Decimals larger than this are too big for pow/exp/log to affect, but
     * tetrate/iteratedlog/slog can still affect them). Approximately 10^^(9.007e15).
     */
    public static final Decimal dLayerSafeMax =
            fromComponents(1, CriticalTables.MAX_SAFE_INTEGER, EXP_LIMIT - 1);
    /**
     * Represents the smallest Decimal where adding 1 to the layer is a safe operation.
     * Approximately 1 / (10^^(9.007e15)).
     */
    public static final Decimal dLayerSafeMin =
            fromComponents(1, CriticalTables.MAX_SAFE_INTEGER, -(EXP_LIMIT - 1));
    /** Represents the largest finite value a Decimal can represent, approximately 10^^(1.79e308). */
    public static final Decimal dLayerMax =
            fromComponents(1, CriticalTables.MAX_VALUE, EXP_LIMIT - 1);
    /** Represents the smallest non-zero value a Decimal can represent. */
    public static final Decimal dLayerMin =
            fromComponents(1, CriticalTables.MAX_VALUE, -(EXP_LIMIT - 1));

    private static final LRUCache<String, Decimal> fromStringCache =
            new LRUCache<>(DEFAULT_FROM_STRING_CACHE_SIZE);

    // --- fields (index.ts:426-428) ---

    public final double sign;
    public final double mag;
    public final double layer;

    /** Raw component constructor; does NOT normalize. Use the static factories instead. */
    private Decimal(double sign, double layer, double mag) {
        this.sign = sign;
        this.layer = layer;
        this.mag = mag;
    }

    // --- constructors (index.ts:430-438) ---

    /** Constructs 0, like {@code new Decimal()}. */
    public Decimal() {
        this(0, 0, 0);
    }

    /** {@code new Decimal(number)}. */
    public Decimal(double value) {
        Decimal d = fromNumber(value);
        this.sign = d.sign;
        this.layer = d.layer;
        this.mag = d.mag;
    }

    /** {@code new Decimal(string)}. */
    public Decimal(String value) {
        Decimal d = fromString(value);
        this.sign = d.sign;
        this.layer = d.layer;
        this.mag = d.mag;
    }

    /**
     * {@code new Decimal(decimal)}. Because Decimals are immutable this shares the
     * components of {@code value}; it is the equivalent of the original's deep copy.
     */
    public Decimal(Decimal value) {
        this.sign = value.sign;
        this.layer = value.layer;
        this.mag = value.mag;
    }

    // --- accessors (index.ts:440-519) ---

    /**
     * The mantissa: for layer 0 the sign-multiplied mantissa in [1, 10) (or the value
     * itself when smaller), for layer 1 {@code sign * 10^residue}, and for layer >= 2
     * just the sign, as the mantissa stops being relevant past 1e9e15 / ee15.954.
     */
    public double m() {
        if (this.sign == 0) {
            return 0;
        } else if (this.layer == 0) {
            double exp = Math.floor(StrictMath.log10(this.mag));
            // handle special case 5e-324
            double man;
            if (this.mag == 5e-324) {
                man = 5;
            } else {
                man = this.mag / powerOf10(exp);
            }
            return this.sign * man;
        } else if (this.layer == 1) {
            double residue = this.mag - Math.floor(this.mag);
            return this.sign * Math.pow(10, residue);
        } else {
            // mantissa stops being relevant past 1e9e15 / ee15.954
            return this.sign;
        }
    }

    /** The immutable equivalent of the original's {@code m} setter (index.ts:462-473). */
    public Decimal withM(double value) {
        if (this.layer <= 2) {
            return fromMantissaExponent(value, this.e());
        } else {
            // don't even pretend mantissa is meaningful
            double newSign = Math.signum(value);
            if (newSign == 0) {
                // The original then sets exponent = 0, which normalizes back to zero.
                return dZero;
            }
            return new Decimal(newSign, this.layer, this.mag);
        }
    }

    /** The exponent that would be used to display this Decimal in scientific notation. */
    public double e() {
        if (this.sign == 0) {
            return 0;
        } else if (this.layer == 0) {
            return Math.floor(StrictMath.log10(this.mag));
        } else if (this.layer == 1) {
            return Math.floor(this.mag);
        } else if (this.layer == 2) {
            return Math.floor(Math.signum(this.mag) * Math.pow(10, Math.abs(this.mag)));
        } else {
            return this.mag * Double.POSITIVE_INFINITY;
        }
    }

    /** The immutable equivalent of the original's {@code e} setter (index.ts:488-490). */
    public Decimal withE(double value) {
        return fromMantissaExponent(this.m(), value);
    }

    /** The sign: -1, 0 or 1 (NaN for NaN). */
    public double s() {
        return this.sign;
    }

    /** The immutable equivalent of the original's {@code s} setter (index.ts:495-503). */
    public Decimal withS(double value) {
        if (value == 0) {
            return dZero;
        }
        return new Decimal(value, this.layer, this.mag);
    }

    /** Alias of {@link #m()}. */
    public double mantissa() {
        return this.m();
    }

    /** Alias of {@link #withM}. */
    public Decimal withMantissa(double value) {
        return withM(value);
    }

    /** Alias of {@link #e()}. */
    public double exponent() {
        return this.e();
    }

    /** Alias of {@link #withE}. */
    public Decimal withExponent(double value) {
        return withE(value);
    }

    // --- normalization (index.ts:1648-1730) ---

    /**
     * Returns a new Decimal with the same components turned into a valid Decimal (the
     * immutable equivalent of the original's mutating {@code normalize()}).
     *
     * <p>PSEUDOCODE (from the original):
     * Whenever we are partially 0 (sign is 0 or mag and layer is 0), make it fully 0.
     * Whenever we are at or hit layer 0, extract sign from negative mag.
     * If layer === 0 and mag &lt; FIRST_NEG_LAYER (1/9e15), shift to 'first negative layer'
     * (add layer, log10 mag).
     * While abs(mag) &gt; EXP_LIMIT (9e15), layer += 1, mag = maglog10(mag).
     * While abs(mag) &lt; LAYER_DOWN (15.954) and layer &gt; 0, layer -= 1, mag = pow(10, mag).
     */
    private Decimal normalize() {
        double sign = this.sign;
        double layer = this.layer;
        double mag = this.mag;

        // Any 0 is totally 0
        if (sign == 0
                || (mag == 0 && layer == 0)
                || (mag == Double.NEGATIVE_INFINITY && layer > 0 && Double.isFinite(layer))) {
            return ZERO;
        }

        // extract sign from negative mag at layer 0
        if (layer == 0 && mag < 0) {
            mag = -mag;
            sign = -sign;
        }

        // Handle infinities
        if (mag == Double.POSITIVE_INFINITY
                || layer == Double.POSITIVE_INFINITY
                || mag == Double.NEGATIVE_INFINITY
                || layer == Double.NEGATIVE_INFINITY) {
            return new Decimal(sign, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }

        // Handle shifting from layer 0 to negative layers.
        if (layer == 0 && mag < FIRST_NEG_LAYER) {
            layer += 1;
            mag = StrictMath.log10(mag);
            return new Decimal(sign, layer, mag);
        }

        double absmag = Math.abs(mag);
        double signmag = Math.signum(mag);

        if (absmag >= EXP_LIMIT) {
            layer += 1;
            mag = signmag * StrictMath.log10(absmag);
            return new Decimal(sign, layer, mag);
        } else {
            while (absmag < LAYER_DOWN && layer > 0) {
                layer -= 1;
                if (layer == 0) {
                    mag = Math.pow(10, mag);
                } else {
                    mag = signmag * Math.pow(10, absmag);
                    absmag = Math.abs(mag);
                    signmag = Math.signum(mag);
                }
            }
            if (layer == 0) {
                if (mag < 0) {
                    // extract sign from negative mag at layer 0
                    mag = -mag;
                    sign = -sign;
                } else if (mag == 0) {
                    // excessive rounding can give us all zeroes
                    sign = 0;
                }
            }
        }

        if (Double.isNaN(sign) || Double.isNaN(layer) || Double.isNaN(mag)) {
            return NAN;
        }
        return new Decimal(sign, layer, mag);
    }

    /** The immutable equivalent of the original's mutating {@code normalize()}. */
    public Decimal normalized() {
        return normalize();
    }

    // --- factories (index.ts:524-605) ---

    /** Turns the given components into a valid Decimal. */
    public static Decimal fromComponents(double sign, double layer, double mag) {
        return new Decimal(sign, layer, mag).normalize();
    }

    /**
     * Turns the given components into a Decimal, but not necessarily a valid one (it's
     * only valid if the components would already create a valid Decimal without
     * normalization). Users of this library should not use this function.
     */
    public static Decimal fromComponents_noNormalize(double sign, double layer, double mag) {
        return new Decimal(sign, layer, mag);
    }

    /** Turns the mantissa and exponent into a valid Decimal with value mantissa * 10^exponent. */
    public static Decimal fromMantissaExponent(double mantissa, double exponent) {
        double sign = Math.signum(mantissa);
        mantissa = Math.abs(mantissa);
        double mag = exponent + StrictMath.log10(mantissa);
        return new Decimal(sign, 1, mag).normalize();
    }

    /**
     * Turns the mantissa and exponent into a Decimal, but not necessarily a valid one.
     * Users of this library should not use this function.
     *
     * <p>Note: like the original (index.ts:1778-1782), this <em>does</em> normalize,
     * despite its name.
     */
    public static Decimal fromMantissaExponent_noNormalize(double mantissa, double exponent) {
        return fromMantissaExponent(mantissa, exponent);
    }

    /** Creates a copy of the provided value (the value itself, as Decimals are immutable). */
    public static Decimal fromDecimal(Decimal value) {
        return value;
    }

    /** Converts a floating-point number into a Decimal. */
    public static Decimal fromNumber(double value) {
        return new Decimal(Math.signum(value), 0, Math.abs(value)).normalize();
    }

    /**
     * The function used by {@code new Decimal(...)} to create a new Decimal: uses
     * fromNumber for a double, fromString for a String and fromDecimal for a Decimal.
     */
    public static Decimal fromValue(double value) {
        return fromNumber(value);
    }

    /** {@link #fromValue(double)} for strings. */
    public static Decimal fromValue(String value) {
        return fromString(value);
    }

    /** {@link #fromValue(double)} for Decimals. */
    public static Decimal fromValue(Decimal value) {
        return fromDecimal(value);
    }

    /**
     * Converts a value to a Decimal, without constructing a new Decimal if the provided
     * value is already one. Safe to hand out shared instances because Decimals are
     * immutable (the original returns a {@code Readonly<Decimal>} for the same reason).
     */
    public static Decimal fromValue_noAlloc(Decimal value) {
        return value;
    }

    /** {@link #fromValue_noAlloc(Decimal)} for strings, using the fromString cache. */
    public static Decimal fromValue_noAlloc(String value) {
        Decimal cached = fromStringCache.get(value);
        if (cached != null) {
            return cached;
        }
        return fromString(value);
    }

    /** {@link #fromValue_noAlloc(Decimal)} for numbers. */
    public static Decimal fromValue_noAlloc(double value) {
        return fromNumber(value);
    }

    // --- fromString (index.ts:1816-2124) ---

    /** Converts a string into a Decimal. */
    public static Decimal fromString(String value) {
        return fromString(value, false);
    }

    /**
     * Converts a string into a Decimal.
     *
     * <p>Supports the original's full grammar: "NaN"/"Infinity"/"-Infinity", plain
     * numbers, "MeX", "eX", "MeXeY", "Meee...Y", "(e^N)X", the hyperoperator forms
     * "x^y", "x^^y[;payload]", "x^^^y[;payload]", "X PT Y", "XpY" and "XFY", with the
     * comma policy and string caching of the original (including its quirk that only the
     * first comma is removed and that "linearhyper4" is not part of the cache key).
     */
    public static Decimal fromString(String value, boolean linearhyper4) {
        String originalValue = value;
        Decimal cached = fromStringCache.get(originalValue);
        if (cached != null) {
            return cached;
        }
        if (IGNORE_COMMAS) {
            value = replaceFirst(value, ",", "");
        } else if (COMMAS_ARE_DECIMAL_POINTS) {
            value = replaceFirst(value, ",", ".");
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("nan")) {
            return dNaN;
        }
        if (lower.equals("infinity")) {
            return dInf;
        }
        if (lower.equals("-infinity")) {
            return dNegInf;
        }

        // Handle x^^^y format. Note that no linearhyper5 parameter is needed, as
        // pentation has no analytic approximation. As in the original, strings that do
        // not parse to finite operands fall through to the next format.
        String[] pentationparts = value.split("\\^\\^\\^", -1);
        if (pentationparts.length == 2) {
            double base = JsNumber.parseFloat(pentationparts[0]);
            double height = JsNumber.parseFloat(pentationparts[1]);
            String[] heightparts = pentationparts[1].split(";", -1);
            double payload = 1;
            if (heightparts.length == 2) {
                payload = JsNumber.parseFloat(heightparts[1]);
                if (!Double.isFinite(payload)) {
                    payload = 1;
                }
            }
            if (Double.isFinite(base) && Double.isFinite(height)) {
                // No linearhyper5 parameter is needed, as pentation has no analytic
                // approximation; linearhyper4 is passed through anyway, like the original.
                Decimal result = fromNumber(base).pentate(height, fromNumber(payload), linearhyper4);
                if (fromStringCache.maxSize >= 1) {
                    fromStringCache.set(originalValue, result);
                }
                return result;
            }
        }

        // Handle x^^y format.
        String[] tetrationparts = value.split("\\^\\^", -1);
        if (tetrationparts.length == 2) {
            double base = JsNumber.parseFloat(tetrationparts[0]);
            double height = JsNumber.parseFloat(tetrationparts[1]);
            String[] heightparts = tetrationparts[1].split(";", -1);
            double payload = 1;
            if (heightparts.length == 2) {
                payload = JsNumber.parseFloat(heightparts[1]);
                if (!Double.isFinite(payload)) {
                    payload = 1;
                }
            }
            if (Double.isFinite(base) && Double.isFinite(height)) {
                Decimal result = fromNumber(base).tetrate(height, fromNumber(payload), linearhyper4);
                if (fromStringCache.maxSize >= 1) {
                    fromStringCache.set(originalValue, result);
                }
                return result;
            }
        }

        // Handle x^y format.
        String[] powparts = value.split("\\^", -1);
        if (powparts.length == 2) {
            double base = JsNumber.parseFloat(powparts[0]);
            double exponent = JsNumber.parseFloat(powparts[1]);
            if (Double.isFinite(base) && Double.isFinite(exponent)) {
                Decimal result = fromNumber(base).pow(exponent);
                if (fromStringCache.maxSize >= 1) {
                    fromStringCache.set(originalValue, result);
                }
                return result;
            }
        }

        // Handle various cases involving it being a Big Number.
        value = value.trim().toLowerCase(Locale.ROOT);

        // handle X PT Y format.
        String[] ptparts = value.split("pt", -1);
        if (ptparts.length == 2) {
            boolean negative = false;
            if (ptparts[0].startsWith("-")) {
                negative = true;
                ptparts[0] = ptparts[0].substring(1);
            }
            double height = JsNumber.parseFloat(ptparts[0]);
            ptparts[1] = replaceFirst(ptparts[1], "(", "");
            ptparts[1] = replaceFirst(ptparts[1], ")", "");
            double payload = JsNumber.parseFloat(ptparts[1]);
            if (!Double.isFinite(payload)) {
                payload = 1;
            }
            if (Double.isFinite(height)) {
                Decimal result = fromNumber(10).tetrate(height, fromNumber(payload), linearhyper4);
                if (fromStringCache.maxSize >= 1) {
                    fromStringCache.set(originalValue, result);
                }
                if (negative) {
                    result = result.neg();
                }
                return result;
            }
        }

        // handle XpY format (it's the same thing just with p).
        ptparts = value.split("p", -1);
        if (ptparts.length == 2) {
            boolean negative = false;
            if (ptparts[0].startsWith("-")) {
                negative = true;
                ptparts[0] = ptparts[0].substring(1);
            }
            double height = JsNumber.parseFloat(ptparts[0]);
            ptparts[1] = replaceFirst(ptparts[1], "(", "");
            ptparts[1] = replaceFirst(ptparts[1], ")", "");
            double payload = JsNumber.parseFloat(ptparts[1]);
            if (!Double.isFinite(payload)) {
                payload = 1;
            }
            if (Double.isFinite(height)) {
                Decimal result = fromNumber(10).tetrate(height, fromNumber(payload), linearhyper4);
                if (fromStringCache.maxSize >= 1) {
                    fromStringCache.set(originalValue, result);
                }
                if (negative) {
                    result = result.neg();
                }
                return result;
            }
        }

        // handle XFY format
        ptparts = value.split("f", -1);
        if (ptparts.length == 2) {
            boolean negative = false;
            if (ptparts[0].startsWith("-")) {
                negative = true;
                ptparts[0] = ptparts[0].substring(1);
            }
            ptparts[0] = replaceFirst(ptparts[0], "(", "");
            ptparts[0] = replaceFirst(ptparts[0], ")", "");
            double payload = JsNumber.parseFloat(ptparts[0]);
            ptparts[1] = replaceFirst(ptparts[1], "(", "");
            ptparts[1] = replaceFirst(ptparts[1], ")", "");
            double height = JsNumber.parseFloat(ptparts[1]);
            if (!Double.isFinite(payload)) {
                payload = 1;
            }
            if (Double.isFinite(height)) {
                Decimal result = fromNumber(10).tetrate(height, fromNumber(payload), linearhyper4);
                if (fromStringCache.maxSize >= 1) {
                    fromStringCache.set(originalValue, result);
                }
                if (negative) {
                    result = result.neg();
                }
                return result;
            }
        }

        String[] parts = value.split("e", -1);
        int ecount = parts.length - 1;

        // Handle numbers that are exactly floats (0 or 1 es).
        if (ecount == 0) {
            double numberAttempt = JsNumber.parseFloat(value);
            if (Double.isFinite(numberAttempt)) {
                Decimal result = fromNumber(numberAttempt);
                // JS-QUIRK(index.ts:2007): the original tests .size here, not .maxSize, so
                // the very first parsed number is not cached. Replicated for fidelity.
                if (fromStringCache.size() >= 1) {
                    fromStringCache.set(originalValue, result);
                }
                return result;
            }
        } else if (ecount == 1) {
            // Very small numbers ("2e-3000" and so on) may look like valid floats but
            // round to 0. Additionally, small numbers (like 1e-308) look like valid
            // floats but are starting to lose precision.
            double numberAttempt = JsNumber.parseFloat(value);
            if (Double.isFinite(numberAttempt) && Math.abs(numberAttempt) > 1e-307) {
                Decimal result = fromNumber(numberAttempt);
                if (fromStringCache.maxSize >= 1) {
                    fromStringCache.set(originalValue, result);
                }
                return result;
            }
        }

        // Handle new (e^N)X format.
        String[] newparts = value.split("e\\^", -1);
        if (newparts.length == 2) {
            double sign = 1;
            if (newparts[0].startsWith("-")) {
                sign = -1;
            }
            String layerstring = "";
            for (int i = 0; i < newparts[1].length(); ++i) {
                int chrcode = newparts[1].charAt(i);
                if ((chrcode >= 43 && chrcode <= 57) || chrcode == 101) {
                    // is "0" to "9" or "+" or "-" or "." or "e" (or "," or "/")
                    layerstring += newparts[1].charAt(i);
                } else {
                    // we found the end of the layer count
                    double layer = JsNumber.parseFloat(layerstring);
                    double mag = JsNumber.parseFloat(newparts[1].substring(i + 1));
                    // Handle invalid cases like (e^-8)1 and (e^10.5)1 by just calling tetrate
                    Decimal result;
                    if (layer < 0 || (layer % 1 != 0)) {
                        // Handle invalid cases like (e^-8)1 and (e^10.5)1 by just calling tetrate
                        result = fromNumber(10).tetrate(layer, fromNumber(mag), linearhyper4);
                    } else {
                        result = fromComponents(sign, layer, mag);
                    }
                    if (fromStringCache.maxSize >= 1) {
                        fromStringCache.set(originalValue, result);
                    }
                    return result;
                }
            }
        }

        // Local variables mirroring the original's mutations of `this` (starts at 0,0,0).
        double sign = 0;
        double layer = 0;
        double mag = 0;

        if (ecount < 1) {
            Decimal result = ZERO;
            if (fromStringCache.maxSize >= 1) {
                fromStringCache.set(originalValue, result);
            }
            return result;
        }
        double mantissa = JsNumber.parseFloat(parts[0]);
        if (mantissa == 0) {
            if (fromStringCache.maxSize >= 1) {
                fromStringCache.set(originalValue, ZERO);
            }
            return ZERO;
        }
        double exponent = JsNumber.parseFloat(parts[parts.length - 1]);
        // handle numbers like AeBeC and AeeeeBeC
        if (ecount >= 2) {
            double me = JsNumber.parseFloat(parts[parts.length - 2]);
            if (Double.isFinite(me)) {
                exponent *= Math.signum(me);
                exponent += f_maglog10(me);
            }
        }

        // Handle numbers written like eee... (N es) X
        if (!Double.isFinite(mantissa)) {
            sign = parts[0].equals("-") ? -1 : 1;
            layer = ecount;
            mag = exponent;
        }
        // Handle numbers written like XeY
        else if (ecount == 1) {
            sign = Math.signum(mantissa);
            layer = 1;
            // Example: 2e10 is equal to 10^log10(2e10) which is equal to 10^(10+log10(2))
            mag = exponent + StrictMath.log10(Math.abs(mantissa));
        }
        // Handle numbers written like Xeee... (N es) Y
        else {
            sign = Math.signum(mantissa);
            layer = ecount;
            if (ecount == 2) {
                Decimal result = fromComponents(1, 2, exponent).mul(fromNumber(mantissa));
                if (fromStringCache.maxSize >= 1) {
                    fromStringCache.set(originalValue, result);
                }
                return result;
            } else {
                // at eee and above, mantissa is too small to be recognizable!
                mag = exponent;
            }
        }

        Decimal result = new Decimal(sign, layer, mag).normalize();
        if (fromStringCache.maxSize >= 1) {
            fromStringCache.set(originalValue, result);
        }
        return result;
    }

    private static String replaceFirst(String value, String target, String replacement) {
        int index = value.indexOf(target);
        if (index < 0) {
            return value;
        }
        return value.substring(0, index) + replacement + value.substring(index + target.length());
    }

    // --- conversion and output (index.ts:2153-2287) ---

    /**
     * Returns the numeric value of this Decimal, or +/-Infinity for Decimals larger than
     * a double can hold, or 0 for Decimals smaller than 5e-324.
     */
    public double toNumber() {
        if (this.mag == Double.POSITIVE_INFINITY
                && this.layer == Double.POSITIVE_INFINITY
                && this.sign == 1) {
            return Double.POSITIVE_INFINITY;
        }
        if (this.mag == Double.POSITIVE_INFINITY
                && this.layer == Double.POSITIVE_INFINITY
                && this.sign == -1) {
            return Double.NEGATIVE_INFINITY;
        }
        if (!Double.isFinite(this.layer)) {
            return Double.NaN;
        }
        if (this.layer == 0) {
            return this.sign * this.mag;
        } else if (this.layer == 1) {
            return this.sign * Math.pow(10, this.mag);
        } else {
            // overflow for any normalized Decimal
            return this.mag > 0
                    ? (this.sign > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY)
                    : 0;
        }
    }

    public double mantissaWithDecimalPlaces(double places) {
        if (Double.isNaN(this.m())) {
            return Double.NaN;
        }
        if (this.m() == 0) {
            return 0;
        }
        return decimalPlaces(this.m(), places);
    }

    public double magnitudeWithDecimalPlaces(double places) {
        if (Double.isNaN(this.mag)) {
            return Double.NaN;
        }
        if (this.mag == 0) {
            return 0;
        }
        return decimalPlaces(this.mag, places);
    }

    /**
     * Returns a string representation of this Decimal. This string is written as a plain
     * number for most layer 0 numbers, in scientific notation for layer 1 numbers (and
     * layer 0 numbers below 1e-6), in "ee...X" form for numbers from layers 2 to 5, and
     * in (e^N)X form for layer > 5.
     */
    @Override
    public String toString() {
        if (Double.isNaN(this.layer) || Double.isNaN(this.sign) || Double.isNaN(this.mag)) {
            return "NaN";
        }
        if (this.mag == Double.POSITIVE_INFINITY || this.layer == Double.POSITIVE_INFINITY) {
            return this.sign == 1 ? "Infinity" : "-Infinity";
        }

        if (this.layer == 0) {
            if ((this.mag < 1e21 && this.mag > 1e-7) || this.mag == 0) {
                return JsNumber.toString(this.sign * this.mag);
            }
            return JsNumber.toString(this.m()) + "e" + JsNumber.toString(this.e());
        } else if (this.layer == 1) {
            return JsNumber.toString(this.m()) + "e" + JsNumber.toString(this.e());
        } else {
            // layer 2+
            if (this.layer <= MAX_ES_IN_A_ROW) {
                return (this.sign == -1 ? "-" : "")
                        + "e".repeat((int) this.layer)
                        + JsNumber.toString(this.mag);
            } else {
                return (this.sign == -1 ? "-" : "")
                        + "(e^"
                        + JsNumber.toString(this.layer)
                        + ")"
                        + JsNumber.toString(this.mag);
            }
        }
    }

    public String toExponential(int places) {
        if (this.layer == 0) {
            return JsNumber.toExponential(this.sign * this.mag, places);
        }
        return this.toStringWithDecimalPlaces(places);
    }

    public String toFixed(int places) {
        if (this.layer == 0) {
            return JsNumber.toFixed(this.sign * this.mag, places);
        }
        return this.toStringWithDecimalPlaces(places);
    }

    public String toPrecision(int places) {
        if (this.e() <= -7) {
            return this.toExponential(places - 1);
        }

        if (places > this.e()) {
            return this.toFixed((int) (places - this.exponent() - 1));
        }

        return this.toExponential(places - 1);
    }

    /**
     * Returns {@link #toString()}, like the original. Note that in JS this makes
     * relational operators and {@code +} operate on strings; Java has no equivalent
     * coercion, so this is kept only for API parity.
     */
    public String valueOf() {
        return this.toString();
    }

    /** Returns {@link #toString()} (the original's toJSON, which serializes as a string). */
    public String toJSON() {
        return this.toString();
    }

    public String toStringWithDecimalPlaces(int places) {
        if (this.layer == 0) {
            if ((this.mag < 1e21 && this.mag > 1e-7) || this.mag == 0) {
                return JsNumber.toFixed(this.sign * this.mag, places);
            }
            return JsNumber.toString(decimalPlaces(this.m(), places))
                    + "e"
                    + JsNumber.toString(decimalPlaces(this.e(), places));
        } else if (this.layer == 1) {
            return JsNumber.toString(decimalPlaces(this.m(), places))
                    + "e"
                    + JsNumber.toString(decimalPlaces(this.e(), places));
        } else {
            // layer 2+
            if (this.layer <= MAX_ES_IN_A_ROW) {
                return (this.sign == -1 ? "-" : "")
                        + "e".repeat((int) this.layer)
                        + JsNumber.toString(decimalPlaces(this.mag, places));
            } else {
                return (this.sign == -1 ? "-" : "")
                        + "(e^"
                        + JsNumber.toString(this.layer)
                        + ")"
                        + JsNumber.toString(decimalPlaces(this.mag, places));
            }
        }
    }

    /** The number of significant digits of precision assumed in a double (17). */
    public static int maxSignificantDigits() {
        return MAX_SIGNIFICANT_DIGITS;
    }

    // --- sign and rounding (index.ts:2292-2382) ---

    /** Returns the absolute value of this Decimal. */
    public Decimal abs() {
        return fromComponents_noNormalize(this.sign == 0 ? 0 : 1, this.layer, this.mag);
    }

    /** Negates this Decimal: when given X, returns -X. */
    public Decimal neg() {
        return fromComponents_noNormalize(-this.sign, this.layer, this.mag);
    }

    /** Alias of {@link #neg}. */
    public Decimal negate() {
        return this.neg();
    }

    /** Alias of {@link #neg}. */
    public Decimal negated() {
        return this.neg();
    }

    /**
     * Returns the sign of this Decimal. (Though, since sign is a public field of Decimal,
     * you might as well just read {@code .sign} instead of calling sgn().)
     */
    public double sgn() {
        return this.sign;
    }

    /** Rounds this Decimal to the nearest integer. */
    public Decimal round() {
        if (this.mag < 0) {
            return dZero;
        }
        if (this.layer == 0) {
            return fromComponents(this.sign, 0, JsNumber.round(this.mag));
        }
        return this;
    }

    /** "Rounds" this Decimal to the nearest integer that's less than or equal to it. */
    public Decimal floor() {
        if (this.mag < 0) {
            if (this.sign == -1) {
                return fromComponents_noNormalize(-1, 0, 1);
            } else {
                return dZero;
            }
        }
        if (this.sign == -1) {
            return this.neg().ceil().neg();
        }
        if (this.layer == 0) {
            return fromComponents(this.sign, 0, Math.floor(this.mag));
        }
        return this;
    }

    /** "Rounds" this Decimal to the nearest integer that's greater than or equal to it. */
    public Decimal ceil() {
        if (this.mag < 0) {
            if (this.sign == 1) {
                // The ceiling function called on something tiny like 10^10^-100 should
                // return 1, since 10^10^-100 is still greater than 0
                return fromComponents_noNormalize(1, 0, 1);
            } else {
                return dZero;
            }
        }
        if (this.sign == -1) {
            return this.neg().floor().neg();
        }
        if (this.layer == 0) {
            return fromComponents(this.sign, 0, Math.ceil(this.mag));
        }
        return this;
    }

    /**
     * Extracts the integer part of this Decimal. Behaves like floor on positive numbers,
     * but like ceiling on negative numbers.
     */
    public Decimal trunc() {
        if (this.mag < 0) {
            return dZero;
        }
        if (this.layer == 0) {
            return fromComponents(this.sign, 0, JsNumber.trunc(this.mag));
        }
        return this;
    }

    // --- arithmetic (index.ts:2387-2718) ---

    /** Addition: returns the sum of this and value. */
    public Decimal add(Decimal value) {
        // Infinity + -Infinity = NaN
        if ((this.eq(dInf) && value.eq(dNegInf)) || (this.eq(dNegInf) && value.eq(dInf))) {
            return dNaN;
        }

        // inf/nan check
        if (!Double.isFinite(this.layer)) {
            return this;
        }
        if (!Double.isFinite(value.layer)) {
            return value;
        }

        // Special case - if one of the numbers is 0, return the other number.
        if (this.sign == 0) {
            return value;
        }
        if (value.sign == 0) {
            return this;
        }

        // Special case - Adding a number to its negation produces 0, no matter how large.
        if (this.sign == -value.sign && this.layer == value.layer && this.mag == value.mag) {
            return dZero;
        }

        Decimal a;
        Decimal b;

        // Special case: If one of the numbers is layer 2 or higher, just take the bigger number.
        if (this.layer >= 2 || value.layer >= 2) {
            return this.maxabs(value);
        }

        if (cmpabs(this, value) > 0) {
            a = this;
            b = value;
        } else {
            a = value;
            b = this;
        }

        if (a.layer == 0 && b.layer == 0) {
            return fromNumber(a.sign * a.mag + b.sign * b.mag);
        }

        double layera = a.layer * Math.signum(a.mag);
        double layerb = b.layer * Math.signum(b.mag);

        // If one of the numbers is 2+ layers higher than the other, just take the bigger number.
        if (layera - layerb >= 2) {
            return a;
        }

        if (layera == 0 && layerb == -1) {
            if (Math.abs(b.mag - StrictMath.log10(a.mag)) > MAX_SIGNIFICANT_DIGITS) {
                return a;
            } else {
                double magdiff = Math.pow(10, StrictMath.log10(a.mag) - b.mag);
                double mantissa = b.sign + a.sign * magdiff;
                return fromComponents(
                        Math.signum(mantissa), 1, b.mag + StrictMath.log10(Math.abs(mantissa)));
            }
        }

        if (layera == 1 && layerb == 0) {
            if (Math.abs(a.mag - StrictMath.log10(b.mag)) > MAX_SIGNIFICANT_DIGITS) {
                return a;
            } else {
                double magdiff = Math.pow(10, a.mag - StrictMath.log10(b.mag));
                double mantissa = b.sign + a.sign * magdiff;
                return fromComponents(
                        Math.signum(mantissa),
                        1,
                        StrictMath.log10(b.mag) + StrictMath.log10(Math.abs(mantissa)));
            }
        }

        if (Math.abs(a.mag - b.mag) > MAX_SIGNIFICANT_DIGITS) {
            return a;
        } else {
            double magdiff = Math.pow(10, a.mag - b.mag);
            double mantissa = b.sign + a.sign * magdiff;
            return fromComponents(
                    Math.signum(mantissa), 1, b.mag + StrictMath.log10(Math.abs(mantissa)));
        }
    }

    /** {@link #add(Decimal)} for a double. */
    public Decimal add(double value) {
        return this.add(fromNumber(value));
    }

    /** {@link #add(Decimal)} for a string. */
    public Decimal add(String value) {
        return this.add(fromString(value));
    }

    /** Alias of {@link #add(Decimal)}. */
    public Decimal plus(Decimal value) {
        return this.add(value);
    }

    /** Alias of {@link #add(Decimal)}. */
    public Decimal plus(double value) {
        return this.add(value);
    }

    /** Alias of {@link #add(Decimal)}. */
    public Decimal plus(String value) {
        return this.add(value);
    }

    /** Subtraction: returns the difference between this and value. */
    public Decimal sub(Decimal value) {
        return this.add(value.neg());
    }

    /** {@link #sub(Decimal)} for a double. */
    public Decimal sub(double value) {
        return this.sub(fromNumber(value));
    }

    /** {@link #sub(Decimal)} for a string. */
    public Decimal sub(String value) {
        return this.sub(fromString(value));
    }

    /** Alias of {@link #sub(Decimal)}. */
    public Decimal subtract(Decimal value) {
        return this.sub(value);
    }

    /** Alias of {@link #sub(Decimal)}. */
    public Decimal subtract(double value) {
        return this.sub(value);
    }

    /** Alias of {@link #sub(Decimal)}. */
    public Decimal subtract(String value) {
        return this.sub(value);
    }

    /** Alias of {@link #sub(Decimal)}. */
    public Decimal minus(Decimal value) {
        return this.sub(value);
    }

    /** Alias of {@link #sub(Decimal)}. */
    public Decimal minus(double value) {
        return this.sub(value);
    }

    /** Alias of {@link #sub(Decimal)}. */
    public Decimal minus(String value) {
        return this.sub(value);
    }

    /** Multiplication: returns the product of this and value. */
    public Decimal mul(Decimal value) {
        // Infinity * -Infinity = -Infinity
        if ((this.eq(dInf) && value.eq(dNegInf)) || (this.eq(dNegInf) && value.eq(dInf))) {
            return dNegInf;
        }

        // Infinity * 0 = NaN
        // JS-QUIRK(index.ts:2515): the second clause of the original's condition tests
        // `this` twice instead of testing `value`, so e.g. Infinity * 0 is Infinity or 0
        // depending on the operand order. Replicated exactly.
        if ((this.mag == Double.POSITIVE_INFINITY && value.eq(dZero))
                || (this.eq(dZero) && this.mag == Double.POSITIVE_INFINITY)) {
            return dNaN;
        }

        // -Infinity * -Infinity = Infinity
        if ((this.eq(dNegInf) && value.eq(dNegInf))) {
            return dInf;
        }

        // inf/nan check
        if (!Double.isFinite(this.layer)) {
            return this;
        }
        if (!Double.isFinite(value.layer)) {
            return value;
        }

        // Special case - if one of the numbers is 0, return 0.
        if (this.sign == 0 || value.sign == 0) {
            return dZero;
        }

        // Special case - Multiplying a number by its own reciprocal yields +/- 1, no matter how large.
        if (this.layer == value.layer && this.mag == -value.mag) {
            return fromComponents_noNormalize(this.sign * value.sign, 0, 1);
        }

        Decimal a;
        Decimal b;

        // Which number is bigger in terms of its multiplicative distance from 1?
        if (this.layer > value.layer
                || (this.layer == value.layer && Math.abs(this.mag) > Math.abs(value.mag))) {
            a = this;
            b = value;
        } else {
            a = value;
            b = this;
        }

        if (a.layer == 0 && b.layer == 0) {
            return fromNumber(a.sign * b.sign * a.mag * b.mag);
        }

        // Special case: If one of the numbers is layer 3 or higher or one of the numbers
        // is 2+ layers bigger than the other, just take the bigger number.
        if (a.layer >= 3 || a.layer - b.layer >= 2) {
            return fromComponents(a.sign * b.sign, a.layer, a.mag);
        }

        if (a.layer == 1 && b.layer == 0) {
            return fromComponents(a.sign * b.sign, 1, a.mag + StrictMath.log10(b.mag));
        }

        if (a.layer == 1 && b.layer == 1) {
            return fromComponents(a.sign * b.sign, 1, a.mag + b.mag);
        }

        if (a.layer == 2 && b.layer == 1) {
            Decimal newmag =
                    fromComponents(Math.signum(a.mag), a.layer - 1, Math.abs(a.mag))
                            .add(fromComponents(Math.signum(b.mag), b.layer - 1, Math.abs(b.mag)));
            return fromComponents(
                    a.sign * b.sign, newmag.layer + 1, newmag.sign * newmag.mag);
        }

        if (a.layer == 2 && b.layer == 2) {
            Decimal newmag =
                    fromComponents(Math.signum(a.mag), a.layer - 1, Math.abs(a.mag))
                            .add(fromComponents(Math.signum(b.mag), b.layer - 1, Math.abs(b.mag)));
            return fromComponents(
                    a.sign * b.sign, newmag.layer + 1, newmag.sign * newmag.mag);
        }

        throw new IllegalStateException("Bad arguments to mul: " + this + ", " + value);
    }

    /** {@link #mul(Decimal)} for a double. */
    public Decimal mul(double value) {
        return this.mul(fromNumber(value));
    }

    /** {@link #mul(Decimal)} for a string. */
    public Decimal mul(String value) {
        return this.mul(fromString(value));
    }

    /** Alias of {@link #mul(Decimal)}. */
    public Decimal multiply(Decimal value) {
        return this.mul(value);
    }

    /** Alias of {@link #mul(Decimal)}. */
    public Decimal multiply(double value) {
        return this.mul(value);
    }

    /** Alias of {@link #mul(Decimal)}. */
    public Decimal multiply(String value) {
        return this.mul(value);
    }

    /** Alias of {@link #mul(Decimal)}. */
    public Decimal times(Decimal value) {
        return this.mul(value);
    }

    /** Alias of {@link #mul(Decimal)}. */
    public Decimal times(double value) {
        return this.mul(value);
    }

    /** Alias of {@link #mul(Decimal)}. */
    public Decimal times(String value) {
        return this.mul(value);
    }

    /** Division: returns the quotient of this and value. */
    public Decimal div(Decimal value) {
        return this.mul(value.recip());
    }

    /** {@link #div(Decimal)} for a double. */
    public Decimal div(double value) {
        return this.div(fromNumber(value));
    }

    /** {@link #div(Decimal)} for a string. */
    public Decimal div(String value) {
        return this.div(fromString(value));
    }

    /** Alias of {@link #div(Decimal)}. */
    public Decimal divide(Decimal value) {
        return this.div(value);
    }

    /** Alias of {@link #div(Decimal)}. */
    public Decimal divide(double value) {
        return this.div(value);
    }

    /** Alias of {@link #div(Decimal)}. */
    public Decimal divide(String value) {
        return this.div(value);
    }

    /** Alias of {@link #div(Decimal)}. */
    public Decimal divideBy(Decimal value) {
        return this.div(value);
    }

    /** Alias of {@link #div(Decimal)}. */
    public Decimal divideBy(double value) {
        return this.div(value);
    }

    /** Alias of {@link #div(Decimal)}. */
    public Decimal divideBy(String value) {
        return this.div(value);
    }

    /** Alias of {@link #div(Decimal)}. */
    public Decimal dividedBy(Decimal value) {
        return this.div(value);
    }

    /** Alias of {@link #div(Decimal)}. */
    public Decimal dividedBy(double value) {
        return this.div(value);
    }

    /** Alias of {@link #div(Decimal)}. */
    public Decimal dividedBy(String value) {
        return this.div(value);
    }

    /** Returns the reciprocal (1 / X) of this Decimal. */
    public Decimal recip() {
        if (this.mag == 0) {
            return dNaN;
        } else if (this.mag == Double.POSITIVE_INFINITY) {
            return dZero;
        } else if (this.layer == 0) {
            return fromComponents(this.sign, 0, 1 / this.mag);
        } else {
            return fromComponents(this.sign, this.layer, -this.mag);
        }
    }

    /** Alias of {@link #recip}. */
    public Decimal reciprocal() {
        return this.recip();
    }

    /** Alias of {@link #recip}. */
    public Decimal reciprocate() {
        return this.recip();
    }

    /**
     * Returns the remainder of this divided by value: for example, 5 mod 2 = 1, because
     * the remainder of 5 / 2 is 1. Uses the "truncated division" modulo, which is the
     * same as JavaScript's native modulo operator (%)... unless 'floored' is true, in
     * which case it uses the "floored" modulo, which is closer to how modulo works in
     * number theory. These two forms of modulo are the same when only positive numbers
     * are involved, but differ in how they work with negative numbers.
     *
     * <p>Taken from OmegaNum.js, with a couple touch-ups, like the original.
     */
    public Decimal mod(Decimal value, boolean floored) {
        Decimal vd = value;
        Decimal decimal = vd.abs();

        if (this.eq(dZero) || decimal.eq(dZero)) {
            return dZero;
        }
        if (floored) {
            Decimal absmod = this.abs().mod(decimal);
            if ((this.sign == -1) != (vd.sign == -1)) {
                absmod = vd.abs().sub(absmod);
            }
            return absmod.mul(vd.sign);
        }
        double num_this = this.toNumber();
        double num_decimal = decimal.toNumber();
        // Special case: To avoid precision issues, if both numbers are valid JS numbers,
        // just call % on those.
        if (Double.isFinite(num_this)
                && Double.isFinite(num_decimal)
                && num_this != 0
                && num_decimal != 0) {
            return fromNumber(num_this % num_decimal);
        }
        if (this.sub(decimal).eq(this)) {
            // decimal is too small to register to this
            return dZero;
        }
        if (decimal.sub(this).eq(decimal)) {
            // this is too small to register to decimal
            return this;
        }
        if (this.sign == -1) {
            return this.abs().mod(decimal).neg();
        }
        return this.sub(this.div(decimal).floor().mul(decimal));
    }

    /** {@link #mod(Decimal, boolean)} with floored = false. */
    public Decimal mod(Decimal value) {
        return this.mod(value, false);
    }

    /** {@link #mod(Decimal, boolean)} for a double. */
    public Decimal mod(double value, boolean floored) {
        return this.mod(fromNumber(value), floored);
    }

    /** {@link #mod(Decimal, boolean)} for a double, floored = false. */
    public Decimal mod(double value) {
        return this.mod(fromNumber(value), false);
    }

    /** {@link #mod(Decimal, boolean)} for a string. */
    public Decimal mod(String value, boolean floored) {
        return this.mod(fromString(value), floored);
    }

    /** {@link #mod(Decimal, boolean)} for a string, floored = false. */
    public Decimal mod(String value) {
        return this.mod(fromString(value), false);
    }

    /** Alias of {@link #mod(Decimal, boolean)}. */
    public Decimal modulo(Decimal value, boolean floored) {
        return this.mod(value, floored);
    }

    /** Alias of {@link #mod(Decimal, boolean)}. */
    public Decimal modulo(Decimal value) {
        return this.mod(value);
    }

    /** Alias of {@link #mod(Decimal, boolean)}. */
    public Decimal modular(Decimal value, boolean floored) {
        return this.mod(value, floored);
    }

    /** Alias of {@link #mod(Decimal, boolean)}. */
    public Decimal modular(Decimal value) {
        return this.mod(value);
    }

    // --- logarithms (index.ts:3023-3116) ---

    /**
     * "Positive log10": Returns the base-10 logarithm of nonnegative Decimals, but
     * returns 0 for negative Decimals.
     */
    public Decimal pLog10() {
        if (this.lt(dZero)) {
            return dZero;
        }
        return this.log10();
    }

    /** Returns the base-10 logarithm of abs(this). */
    public Decimal absLog10() {
        if (this.sign == 0) {
            return dNaN;
        } else if (this.layer > 0) {
            return fromComponents(Math.signum(this.mag), this.layer - 1, Math.abs(this.mag));
        } else {
            return fromComponents(1, 0, StrictMath.log10(this.mag));
        }
    }

    /**
     * Base-10 logarithm: returns the Decimal X such that 10^X = this. For numbers above
     * layer 0, this is equivalent to subtracting 1 from layer and normalizing.
     */
    public Decimal log10() {
        if (this.sign <= 0) {
            return dNaN;
        } else if (this.layer > 0) {
            return fromComponents(Math.signum(this.mag), this.layer - 1, Math.abs(this.mag));
        } else {
            return fromComponents(this.sign, 0, StrictMath.log10(this.mag));
        }
    }

    /**
     * Logarithms are one of the inverses of exponentiation: this function finds the
     * Decimal X such that base^X = this.
     */
    public Decimal log(Decimal base) {
        if (this.sign <= 0) {
            return dNaN;
        }
        if (base.sign <= 0) {
            return dNaN;
        }
        if (base.sign == 1 && base.layer == 0 && base.mag == 1) {
            return dNaN;
        } else if (this.layer == 0 && base.layer == 0) {
            return fromComponents(this.sign, 0, StrictMath.log(this.mag) / StrictMath.log(base.mag));
        }

        return this.log10().div(base.log10());
    }

    /** {@link #log(Decimal)} for a double. */
    public Decimal log(double base) {
        return this.log(fromNumber(base));
    }

    /** {@link #log(Decimal)} for a string. */
    public Decimal log(String base) {
        return this.log(fromString(base));
    }

    /** Base-2 logarithm: returns the Decimal X such that 2^X = this. */
    public Decimal log2() {
        if (this.sign <= 0) {
            return dNaN;
        } else if (this.layer == 0) {
            return fromComponents(this.sign, 0, JsMath.log2(this.mag));
        } else if (this.layer == 1) {
            // log2(10)
            return fromComponents(Math.signum(this.mag), 0, Math.abs(this.mag) * 3.321928094887362);
        } else if (this.layer == 2) {
            // -log10(log10(2))
            return fromComponents(Math.signum(this.mag), 1, Math.abs(this.mag) + 0.5213902276543247);
        } else {
            return fromComponents(Math.signum(this.mag), this.layer - 1, Math.abs(this.mag));
        }
    }

    /**
     * Base-e logarithm, also known as the "natural" logarithm: returns the Decimal X such
     * that e^X = this.
     */
    public Decimal ln() {
        if (this.sign <= 0) {
            return dNaN;
        } else if (this.layer == 0) {
            return fromComponents(this.sign, 0, StrictMath.log(this.mag));
        } else if (this.layer == 1) {
            // ln(10)
            return fromComponents(Math.signum(this.mag), 0, Math.abs(this.mag) * 2.302585092994046);
        } else if (this.layer == 2) {
            // log10(log10(e))
            return fromComponents(
                    Math.signum(this.mag), 1, Math.abs(this.mag) + 0.36221568869946325);
        } else {
            return fromComponents(Math.signum(this.mag), this.layer - 1, Math.abs(this.mag));
        }
    }

    /** {@link #log(Decimal)} (the original's alias). */
    public Decimal logarithm(Decimal base) {
        return this.log(base);
    }

    // --- exponentiation (index.ts:3121-3221) ---

    /**
     * Exponentiation: Returns the result of this ^ value (often written as this ** value
     * in programming languages).
     */
    public Decimal pow(Decimal value) {
        Decimal a = this;
        Decimal b = value;

        // special case: if a is 0, then return 0 (UNLESS b is 0, then return 1)
        if (a.sign == 0) {
            return b.eq(dZero) ? dOne : a;
        }
        // special case: if a is 1, then return 1
        if (a.sign == 1 && a.layer == 0 && a.mag == 1) {
            return a;
        }
        // special case: if b is 0, then return 1
        if (b.sign == 0) {
            return dOne;
        }
        // special case: if b is 1, then return a
        if (b.sign == 1 && b.layer == 0 && b.mag == 1) {
            return a;
        }

        Decimal result = a.absLog10().mul(b).pow10();

        if (this.sign == -1) {
            if (Math.abs(b.toNumber() % 2) % 2 == 1) {
                return result.neg();
            } else if (Math.abs(b.toNumber() % 2) % 2 == 0) {
                return result;
            }
            return dNaN;
        }

        return result;
    }

    /** {@link #pow(Decimal)} for a double. */
    public Decimal pow(double value) {
        return this.pow(fromNumber(value));
    }

    /** {@link #pow(Decimal)} for a string. */
    public Decimal pow(String value) {
        return this.pow(fromString(value));
    }

    /**
     * Raises 10 to the power of this, i.e. (10^this). For positive numbers above 1, this
     * is equivalent to adding 1 to layer and normalizing.
     */
    public Decimal pow10() {
        /*
        There are four cases we need to consider:
        1) positive sign, positive mag (e15, ee15): +1 layer (e.g. 10^15 becomes e15, 10^e15 becomes ee15)
        2) negative sign, positive mag (-e15, -ee15): +1 layer but sign and mag sign are flipped (e.g. 10^-15 becomes e-15, 10^-e15 becomes ee-15)
        3) positive sign, negative mag (e-15, ee-15): layer 0 case would have been handled in the Math.pow check, so just return 1
        4) negative sign, negative mag (-e-15, -ee-15): layer 0 case would have been handled in the Math.pow check, so just return 1
        */

        if (this.eq(dInf)) {
            return dInf;
        }
        if (this.eq(dNegInf)) {
            return dZero;
        }
        if (!Double.isFinite(this.layer) || !Double.isFinite(this.mag)) {
            return dNaN;
        }

        Decimal a = this;

        // handle layer 0 case - if no precision is lost just use Math.pow, else promote one layer
        if (a.layer == 0) {
            double newmag = Math.pow(10, a.sign * a.mag);
            if (Double.isFinite(newmag) && Math.abs(newmag) >= 0.1) {
                return fromComponents(1, 0, newmag);
            } else {
                if (a.sign == 0) {
                    return dOne;
                } else {
                    a = fromComponents_noNormalize(a.sign, a.layer + 1, StrictMath.log10(a.mag));
                }
            }
        }

        // handle all 4 layer 1+ cases individually
        if (a.sign > 0 && a.mag >= 0) {
            return fromComponents(a.sign, a.layer + 1, a.mag);
        }
        if (a.sign < 0 && a.mag >= 0) {
            return fromComponents(-a.sign, a.layer + 1, -a.mag);
        }
        // both the negative mag cases are identical: one +/- rounding error
        return dOne;
    }

    /**
     * Exponentiation: Returns the result of value ^ this (often written as value ** this
     * in programming languages).
     */
    public Decimal pow_base(Decimal value) {
        return value.pow(this);
    }

    /** {@link #pow_base(Decimal)} for a double. */
    public Decimal pow_base(double value) {
        return fromNumber(value).pow(this);
    }

    /** {@link #pow_base(Decimal)} for a string. */
    public Decimal pow_base(String value) {
        return fromString(value).pow(this);
    }

    /**
     * Roots are one of the inverses of exponentiation: this function finds the Decimal X
     * such that X ^ value = this. Equivalent to this ^ (1 / value).
     */
    public Decimal root(Decimal value) {
        if (this.lt(0) && value.mod(2, true).eq(1)) {
            return this.neg().root(value).neg();
        }
        return this.pow(value.recip());
    }

    /** {@link #root(Decimal)} for a double. */
    public Decimal root(double value) {
        return this.root(fromNumber(value));
    }

    /** {@link #root(Decimal)} for a string. */
    public Decimal root(String value) {
        return this.root(fromString(value));
    }

    // --- gamma / factorial / exp (index.ts:1219-1331, 3227-3409) ---

    /**
     * HyperCalc's number-domain gamma function (index.ts:219-259).
     */
    private static double f_gamma(double n) {
        if (!Double.isFinite(n)) {
            return n;
        }
        if (n < -50) {
            if (n == JsNumber.trunc(n)) {
                return Double.NEGATIVE_INFINITY;
            }
            return 0;
        }

        double scal1 = 1;
        while (n < 10) {
            scal1 = scal1 * n;
            ++n;
        }

        n -= 1;
        double l = 0.9189385332046727; // 0.5*Math.log(2*Math.PI)
        l = l + (n + 0.5) * StrictMath.log(n);
        l = l - n;
        double n2 = n * n;
        double np = n;
        l = l + 1 / (12 * np);
        np = np * n2;
        l = l - 1 / (360 * np);
        np = np * n2;
        l = l + 1 / (1260 * np);
        np = np * n2;
        l = l - 1 / (1680 * np);
        np = np * n2;
        l = l + 1 / (1188 * np);
        np = np * n2;
        l = l - 691 / (360360 * np);
        np = np * n2;
        l = l + 7 / (1092 * np);
        np = np * n2;
        l = l - 3617 / (122400 * np);

        return StrictMath.exp(l) / scal1;
    }

    /**
     * For positive integers, X factorial (written as X!) equals
     * X * (X - 1) * (X - 2) *... * 3 * 2 * 1. 0! equals 1. This can be extended to real
     * numbers (except for negative integers) via the gamma function, which is what this
     * function does.
     */
    public Decimal factorial() {
        if (this.mag < 0) {
            return this.add(1).gamma();
        } else if (this.layer == 0) {
            return this.add(1).gamma();
        } else if (this.layer == 1) {
            return exp(this.mul(this.ln().sub(1)));
        } else {
            return exp(this);
        }
    }

    /**
     * The gamma function extends the idea of factorials to non-whole numbers using some
     * calculus. Gamma(x) is defined as the integral of t^(x-1) * e^-t dt from t = 0 to
     * t = infinity, and gamma(x) = (x - 1)! for nonnegative integer x, so the factorial
     * for non-whole numbers is defined using the gamma function.
     *
     * <p>From HyperCalc source code.
     */
    public Decimal gamma() {
        if (this.mag < 0) {
            return this.recip();
        } else if (this.layer == 0) {
            if (this.lt(fromComponents_noNormalize(1, 0, 24))) {
                return fromNumber(f_gamma(this.sign * this.mag));
            }

            double t = this.mag - 1;
            double l = 0.9189385332046727; // 0.5*Math.log(2*Math.PI)
            l = l + (t + 0.5) * StrictMath.log(t);
            l = l - t;
            double n2 = t * t;
            double np = t;
            double lm = 12 * np;
            double adj = 1 / lm;
            double l2 = l + adj;
            if (l2 == l) {
                return exp(l);
            }

            l = l2;
            np = np * n2;
            lm = 360 * np;
            adj = 1 / lm;
            l2 = l - adj;
            if (l2 == l) {
                return exp(l);
            }

            l = l2;
            np = np * n2;
            lm = 1260 * np;
            double lt = 1 / lm;
            l = l + lt;
            np = np * n2;
            lm = 1680 * np;
            lt = 1 / lm;
            l = l - lt;
            return exp(l);
        } else if (this.layer == 1) {
            return exp(this.mul(this.ln().sub(1)));
        } else {
            return exp(this);
        }
    }

    /** Returns the natural logarithm of Gamma(this). */
    public Decimal lngamma() {
        return this.gamma().ln();
    }

    /** Base-e exponentiation: returns e^this. */
    public Decimal exp() {
        if (this.mag < 0) {
            return dOne;
        }
        if (this.layer == 0 && this.mag <= 709.7) {
            return fromNumber(StrictMath.exp(this.sign * this.mag));
        } else if (this.layer == 0) {
            return fromComponents(1, 1, this.sign * StrictMath.log10(Math.E) * this.mag);
        } else if (this.layer == 1) {
            return fromComponents(
                    1, 2, this.sign * (StrictMath.log10(0.4342944819032518) + this.mag));
        } else {
            return fromComponents(1, this.layer + 1, this.sign * this.mag);
        }
    }

    /**
     * Squaring a number means multiplying it by itself, a.k.a. raising it to the second
     * power.
     */
    public Decimal sqr() {
        return this.pow(2);
    }

    /**
     * Square root: finds the Decimal X such that X * X, a.k.a X^2, equals this.
     * Equivalent to X^(1/2).
     */
    public Decimal sqrt() {
        if (this.layer == 0) {
            return fromNumber(Math.sqrt(this.sign * this.mag));
        } else if (this.layer == 1) {
            return fromComponents(1, 2, StrictMath.log10(this.mag) - 0.3010299956639812);
        } else {
            // The original mutates the intermediate result (layer += 1, then normalize).
            Decimal result =
                    fromComponents_noNormalize(this.sign, this.layer - 1, this.mag)
                            .div(fromComponents_noNormalize(1, 0, 2));
            return new Decimal(result.sign, result.layer + 1, result.mag).normalized();
        }
    }

    /**
     * Cubing a number means raising it to the third power.
     */
    public Decimal cube() {
        return this.pow(3);
    }

    /**
     * Cube root: finds the Decimal X such that X^3 equals this. Equivalent to X^(1/3).
     */
    public Decimal cbrt() {
        if (this.lt(0)) {
            return this.neg().pow(1.0 / 3).neg();
        }
        return this.pow(1.0 / 3);
    }

    // --- trigonometric functions (index.ts:4756-4875) ---

    /**
     * The sine function, one of the main two trigonometric functions. Behaves
     * periodically with period 2*pi.
     */
    public Decimal sin() {
        if (this.mag < 0) {
            return this;
        }
        if (this.layer == 0) {
            return fromNumber(StrictMath.sin(this.sign * this.mag));
        }
        return dZero;
    }

    /**
     * The cosine function, one of the main two trigonometric functions. Behaves
     * periodically with period 2*pi.
     */
    public Decimal cos() {
        if (this.mag < 0) {
            return dOne;
        }
        if (this.layer == 0) {
            return fromNumber(StrictMath.cos(this.sign * this.mag));
        }
        return dZero;
    }

    /**
     * The tangent function, equal to sine divided by cosine. Behaves periodically with
     * period pi.
     */
    public Decimal tan() {
        if (this.mag < 0) {
            return this;
        }
        if (this.layer == 0) {
            return fromNumber(StrictMath.tan(this.sign * this.mag));
        }
        return dZero;
    }

    /** The arcsine function, the inverse of the sine function. */
    public Decimal asin() {
        if (this.mag < 0) {
            return this;
        }
        if (this.layer == 0) {
            return fromNumber(StrictMath.asin(this.sign * this.mag));
        }
        return dNaN;
    }

    /** The arccosine function, the inverse of the cosine function. */
    public Decimal acos() {
        if (this.mag < 0) {
            return fromNumber(StrictMath.acos(this.toNumber()));
        }
        if (this.layer == 0) {
            return fromNumber(StrictMath.acos(this.sign * this.mag));
        }
        return dNaN;
    }

    /** The arctangent function, the inverse of the tangent function. */
    public Decimal atan() {
        if (this.mag < 0) {
            return this;
        }
        if (this.layer == 0) {
            return fromNumber(StrictMath.atan(this.sign * this.mag));
        }
        // The original writes 1.8e308, which overflows to Infinity in JS; the Java literal
        // would not compile, and atan(+/-Infinity) is the same +/-pi/2.
        return fromNumber(StrictMath.atan(this.sign * Double.POSITIVE_INFINITY));
    }

    /** Hyperbolic sine: sinh(X) = (e^x - e^-x)/2. */
    public Decimal sinh() {
        return this.exp().sub(this.negate().exp()).div(2);
    }

    /** Hyperbolic cosine: cosh(x) = (e^x + e^-x)/2. */
    public Decimal cosh() {
        return this.exp().add(this.negate().exp()).div(2);
    }

    /** Hyperbolic tangent: tanh(x) = sinh(x)/cosh(x). */
    public Decimal tanh() {
        return this.sinh().div(this.cosh());
    }

    /** Hyperbolic arcsine, the inverse of hyperbolic sine. */
    public Decimal asinh() {
        return this.add(this.sqr().add(1).sqrt()).ln();
    }

    /** Hyperbolic arccosine, the inverse of hyperbolic cosine. */
    public Decimal acosh() {
        return this.add(this.sqr().sub(1).sqrt()).ln();
    }

    /** Hyperbolic arctangent, the inverse of hyperbolic tangent. */
    public Decimal atanh() {
        if (this.abs().gte(1)) {
            return dNaN;
        }
        return this.add(1).div(fromNumber(1).sub(this)).ln().div(2);
    }

    // --- comparison (index.ts:2723-2791) ---

    /**
     * Returns 1 if {@code this > value}, -1 if {@code this < value}, 0 if equal.
     *
     * <p>In JS this returns NaN when either operand is NaN (because NaN comparisons are
     * all false and the result is computed as {@code sign * cmpabs}); an int cannot be
     * NaN, so this port follows {@link Double#compare} instead: NaN sorts above every
     * number, and NaN compares equal to NaN. The boolean predicates
     * ({@link #lt}, {@link #lte}, {@link #gt}, {@link #gte}) do NOT use this rule - they
     * return false whenever either operand is NaN, exactly like the original.
     */
    public int cmp(Decimal value) {
        // Faithful port: the sign comparisons are all false when a sign is NaN, so the
        // result falls through to this.sign * cmpabs(value). cmpabs itself yields 0 when
        // a mag is NaN (every comparison inside it is false). The product is therefore
        // NaN only when this.sign is NaN, exactly as in JS; that is the case the int
        // return type cannot represent.
        if (this.sign > value.sign) {
            return 1;
        }
        if (this.sign < value.sign) {
            return -1;
        }
        double result = this.sign * this.cmpabs(value);
        if (Double.isNaN(result)) {
            return Double.compare(this.toNumber(), value.toNumber());
        }
        return (int) result;
    }

    /**
     * Compares the absolute values of this and value: 1 if {@code |this| > |value|},
     * -1 if smaller, 0 if equal.
     */
    public int cmpabs(Decimal value) {
        double layera = this.mag > 0 ? this.layer : -this.layer;
        double layerb = value.mag > 0 ? value.layer : -value.layer;
        if (layera > layerb) {
            return 1;
        }
        if (layera < layerb) {
            return -1;
        }
        if (this.mag > value.mag) {
            return 1;
        }
        if (this.mag < value.mag) {
            return -1;
        }
        return 0;
    }

    /** Alias of {@link #cmp}. */
    public int compare(Decimal value) {
        return this.cmp(value);
    }

    /** Returns true if this Decimal is a NaN value. (Note: the static form is {@code isNaN}.) */
    public boolean isNan() {
        return Double.isNaN(this.sign) || Double.isNaN(this.layer) || Double.isNaN(this.mag);
    }

    /**
     * Returns true if this Decimal is finite (by Decimal standards, not by floating
     * point standards - a humongous Decimal like 10^^10^100 is still finite!).
     */
    public boolean isFinite() {
        return Double.isFinite(this.sign) && Double.isFinite(this.layer) && Double.isFinite(this.mag);
    }

    /**
     * The Decimal equivalent of ==: true if this and value have equal values
     * (component-wise, like the original - so NaN is not equal to anything, including
     * itself).
     */
    public boolean eq(Decimal value) {
        return this.sign == value.sign && this.layer == value.layer && this.mag == value.mag;
    }

    /** {@link #eq(Decimal)} for a double. */
    public boolean eq(double value) {
        return this.eq(fromNumber(value));
    }

    /** {@link #eq(Decimal)} for a string. */
    public boolean eq(String value) {
        return this.eq(fromString(value));
    }

    /** Alias of {@link #eq}. */
    public boolean equals(Decimal value) {
        return this.eq(value);
    }

    /** Alias of {@link #eq}. */
    public boolean equals(double value) {
        return this.eq(value);
    }

    /** Alias of {@link #eq}. */
    public boolean equals(String value) {
        return this.eq(value);
    }

    /** Returns true if this and value have different values. True when either is NaN, like the original. */
    public boolean neq(Decimal value) {
        return !this.eq(value);
    }

    /** Alias of {@link #neq}. */
    public boolean notEquals(Decimal value) {
        return !this.eq(value);
    }

    /**
     * Returns the original's {@code cmp} result, which is NaN when either operand is NaN
     * (JS compares with all-false semantics and multiplies by the sign at the end).
     * Used by the boolean predicates so that they reproduce the original's NaN
     * behavior exactly, which is: {@code lt}/{@code gt} false, {@code lte}/{@code gte}
     * true, {@code eq} false, {@code neq} true whenever either operand is NaN.
     */
    private double cmpJs(Decimal value) {
        if (this.isNan() || value.isNan()) {
            return Double.NaN;
        }
        return this.cmp(value);
    }

    /** Returns true if this is less than value; false when either operand is NaN. */
    public boolean lt(Decimal value) {
        return this.cmpJs(value) == -1;
    }

    /** Returns true if this is less than or equal to value; TRUE when either operand is NaN (JS quirk). */
    public boolean lte(Decimal value) {
        return !this.gt(value);
    }

    /** Returns true if this is greater than value; false when either operand is NaN. */
    public boolean gt(Decimal value) {
        return this.cmpJs(value) == 1;
    }

    /** Returns true if this is greater than or equal to value; TRUE when either operand is NaN (JS quirk). */
    public boolean gte(Decimal value) {
        return !this.lt(value);
    }

    /** {@link #lt(Decimal)} for a double. */
    public boolean lt(double value) {
        return this.lt(fromNumber(value));
    }

    /** {@link #lt(Decimal)} for a string. */
    public boolean lt(String value) {
        return this.lt(fromString(value));
    }

    /** {@link #lte(Decimal)} for a double. */
    public boolean lte(double value) {
        return this.lte(fromNumber(value));
    }

    /** {@link #lte(Decimal)} for a string. */
    public boolean lte(String value) {
        return this.lte(fromString(value));
    }

    /** {@link #gt(Decimal)} for a double. */
    public boolean gt(double value) {
        return this.gt(fromNumber(value));
    }

    /** {@link #gt(Decimal)} for a string. */
    public boolean gt(String value) {
        return this.gt(fromString(value));
    }

    /** {@link #gte(Decimal)} for a double. */
    public boolean gte(double value) {
        return this.gte(fromNumber(value));
    }

    /** {@link #gte(Decimal)} for a string. */
    public boolean gte(String value) {
        return this.gte(fromString(value));
    }

    /** {@link #eq(Decimal)} for a double via a plain number comparison. */
    public boolean neq(double value) {
        return !this.eq(value);
    }

    /** {@link #neq(Decimal)} for a string. */
    public boolean neq(String value) {
        return !this.eq(value);
    }

    /** Alias of {@link #neq(Decimal)}. */
    public boolean notEquals(double value) {
        return !this.eq(value);
    }

    /** Alias of {@link #neq(Decimal)}. */
    public boolean notEquals(String value) {
        return !this.eq(value);
    }

    /** {@link #cmp(Decimal)} for a double. */
    public int cmp(double value) {
        return this.cmp(fromNumber(value));
    }

    /** {@link #cmp(Decimal)} for a string. */
    public int cmp(String value) {
        return this.cmp(fromString(value));
    }

    /** {@link #cmpabs(Decimal)} for a double. */
    public int cmpabs(double value) {
        return this.cmpabs(fromNumber(value));
    }

    /** {@link #cmpabs(Decimal)} for a string. */
    public int cmpabs(String value) {
        return this.cmpabs(fromString(value));
    }

    /** Alias of {@link #cmp(Decimal)}. */
    public int compare(double value) {
        return this.cmp(value);
    }

    /** Alias of {@link #cmp(Decimal)}. */
    public int compare(String value) {
        return this.cmp(value);
    }

    // --- minimum / maximum (index.ts:2838-2887) ---

    /** Returns whichever of this and value is higher. */
    public Decimal max(Decimal value) {
        return this.lt(value) ? value : this;
    }

    /** {@link #max(Decimal)} for a double. */
    public Decimal max(double value) {
        return this.max(fromNumber(value));
    }

    /** {@link #max(Decimal)} for a string. */
    public Decimal max(String value) {
        return this.max(fromString(value));
    }

    /** Returns whichever of this and value is lower. */
    public Decimal min(Decimal value) {
        return this.gt(value) ? value : this;
    }

    /** {@link #min(Decimal)} for a double. */
    public Decimal min(double value) {
        return this.min(fromNumber(value));
    }

    /** {@link #min(Decimal)} for a string. */
    public Decimal min(String value) {
        return this.min(fromString(value));
    }

    /** Returns whichever of this and value has a larger absolute value. */
    public Decimal maxabs(Decimal value) {
        return this.cmpabs(value) < 0 ? value : this;
    }

    /** {@link #maxabs(Decimal)} for a double. */
    public Decimal maxabs(double value) {
        return this.maxabs(fromNumber(value));
    }

    /** {@link #maxabs(Decimal)} for a string. */
    public Decimal maxabs(String value) {
        return this.maxabs(fromString(value));
    }

    /** Returns whichever of this and value has a smaller absolute value. */
    public Decimal minabs(Decimal value) {
        return this.cmpabs(value) > 0 ? value : this;
    }

    /** {@link #minabs(Decimal)} for a double. */
    public Decimal minabs(double value) {
        return this.minabs(fromNumber(value));
    }

    /** {@link #minabs(Decimal)} for a string. */
    public Decimal minabs(String value) {
        return this.minabs(fromString(value));
    }

    /**
     * A combination of minimum and maximum: the value returned by clamp is normally
     * this, but it won't go below min and it won't go above max.
     */
    public Decimal clamp(Decimal min, Decimal max) {
        return this.max(min).min(max);
    }

    /** {@link #clamp(Decimal, Decimal)} for doubles. */
    public Decimal clamp(double min, double max) {
        return this.max(min).min(max);
    }

    /** {@link #clamp(Decimal, Decimal)} for strings. */
    public Decimal clamp(String min, String max) {
        return this.max(min).min(max);
    }

    /** Returns this, unless this is less than min, in which case min is returned. */
    public Decimal clampMin(Decimal min) {
        return this.max(min);
    }

    /** Returns this, unless this is greater than max, in which case max is returned. */
    public Decimal clampMax(Decimal max) {
        return this.min(max);
    }

    // --- tolerance comparisons (index.ts:2896-3018) ---

    /**
     * Returns 1 if this is greater than value, -1 if less, 0 if equal; where the two
     * Decimals are considered equal if they're approximately equal up to a certain
     * tolerance. Tolerance is a relative tolerance, multiplied by the greater of the
     * magnitudes of the two arguments. For example, if you put in 1e-9, then any number
     * closer to the larger number than (larger number)*1e-9 will be considered equal.
     */
    public int cmp_tolerance(Decimal value, double tolerance) {
        return this.eq_tolerance(value, tolerance) ? 0 : this.cmp(value);
    }

    /** {@link #cmp_tolerance(Decimal, double)} with the default tolerance of 1e-7. */
    public int cmp_tolerance(Decimal value) {
        return this.cmp_tolerance(value, 1e-7);
    }

    /** Alias of {@link #cmp_tolerance(Decimal, double)}. */
    public int compare_tolerance(Decimal value, double tolerance) {
        return this.cmp_tolerance(value, tolerance);
    }

    /** Alias of {@link #cmp_tolerance(Decimal, double)}. */
    public int compare_tolerance(Decimal value) {
        return this.cmp_tolerance(value);
    }

    /**
     * Tests whether two Decimals are approximately equal, up to a certain tolerance
     * (see {@link #cmp_tolerance(Decimal, double)}).
     */
    public boolean eq_tolerance(Decimal value, double tolerance) {
        // Numbers that are too far away are never close.
        if (this.sign != value.sign) {
            return false;
        }
        if (Math.abs(this.layer - value.layer) > 1) {
            return false;
        }
        // return abs(a-b) <= tolerance * max(abs(a), abs(b))
        double magA = this.mag;
        double magB = value.mag;
        if (this.layer > value.layer) {
            magB = f_maglog10(magB);
        }
        if (this.layer < value.layer) {
            magA = f_maglog10(magA);
        }
        return Math.abs(magA - magB) <= tolerance * Math.max(Math.abs(magA), Math.abs(magB));
    }

    /** {@link #eq_tolerance(Decimal, double)} with the default tolerance of 1e-7. */
    public boolean eq_tolerance(Decimal value) {
        return this.eq_tolerance(value, 1e-7);
    }

    /** Alias of {@link #eq_tolerance(Decimal, double)}. */
    public boolean equals_tolerance(Decimal value, double tolerance) {
        return this.eq_tolerance(value, tolerance);
    }

    /** Alias of {@link #eq_tolerance(Decimal, double)}. */
    public boolean equals_tolerance(Decimal value) {
        return this.eq_tolerance(value);
    }

    /** Tests whether two Decimals are not approximately equal. */
    public boolean neq_tolerance(Decimal value, double tolerance) {
        return !this.eq_tolerance(value, tolerance);
    }

    /** {@link #neq_tolerance(Decimal, double)} with the default tolerance of 1e-7. */
    public boolean neq_tolerance(Decimal value) {
        return this.neq_tolerance(value, 1e-7);
    }

    /** Alias of {@link #neq_tolerance(Decimal, double)}. */
    public boolean notEquals_tolerance(Decimal value, double tolerance) {
        return this.neq_tolerance(value, tolerance);
    }

    /** Alias of {@link #neq_tolerance(Decimal, double)}. */
    public boolean notEquals_tolerance(Decimal value) {
        return this.neq_tolerance(value);
    }

    /** Returns true if this is less than value, considering tolerance-equal values equal. */
    public boolean lt_tolerance(Decimal value, double tolerance) {
        return !this.eq_tolerance(value, tolerance) && this.lt(value);
    }

    /** {@link #lt_tolerance(Decimal, double)} with the default tolerance of 1e-7. */
    public boolean lt_tolerance(Decimal value) {
        return this.lt_tolerance(value, 1e-7);
    }

    /** Returns true if this is less than or equal to value, considering tolerance-equal values equal. */
    public boolean lte_tolerance(Decimal value, double tolerance) {
        return this.eq_tolerance(value, tolerance) || this.lt(value);
    }

    /** {@link #lte_tolerance(Decimal, double)} with the default tolerance of 1e-7. */
    public boolean lte_tolerance(Decimal value) {
        return this.lte_tolerance(value, 1e-7);
    }

    /** Returns true if this is greater than value, considering tolerance-equal values equal. */
    public boolean gt_tolerance(Decimal value, double tolerance) {
        return !this.eq_tolerance(value, tolerance) && this.gt(value);
    }

    /** {@link #gt_tolerance(Decimal, double)} with the default tolerance of 1e-7. */
    public boolean gt_tolerance(Decimal value) {
        return this.gt_tolerance(value, 1e-7);
    }

    /** Returns true if this is greater than or equal to value, considering tolerance-equal values equal. */
    public boolean gte_tolerance(Decimal value, double tolerance) {
        return this.eq_tolerance(value, tolerance) || this.gt(value);
    }

    /** {@link #gte_tolerance(Decimal, double)} with the default tolerance of 1e-7. */
    public boolean gte_tolerance(Decimal value) {
        return this.gte_tolerance(value, 1e-7);
    }

    /** Returns true if the value is a NaN value (static form; the instance form is {@code isNan}). */
    public static boolean isNaN(Decimal value) {
        return value.isNan();
    }

    /** Returns true if the value is finite by Decimal standards. */
    public static boolean isFinite(Decimal value) {
        return value.isFinite();
    }

    // --- static wrappers (index.ts:610-1085) ---

    /** Absolute value: returns value if value >= 0, the negative of value if value < 0. */
    public static Decimal abs(Decimal value) {
        return value.abs();
    }

    /** {@link #abs(Decimal)} for a double. */
    public static Decimal abs(double value) {
        return fromNumber(value).abs();
    }

    /** {@link #abs(Decimal)} for a string. */
    public static Decimal abs(String value) {
        return fromString(value).abs();
    }

    /** Negates the value: when given X, returns -X. */
    public static Decimal neg(Decimal value) {
        return value.neg();
    }

    /** {@link #neg(Decimal)} for a double. */
    public static Decimal neg(double value) {
        return fromNumber(value).neg();
    }

    /** {@link #neg(Decimal)} for a string. */
    public static Decimal neg(String value) {
        return fromString(value).neg();
    }

    /** Alias of {@link #neg(Decimal)}. */
    public static Decimal negate(Decimal value) {
        return value.neg();
    }

    /** Alias of {@link #neg(Decimal)}. */
    public static Decimal negate(double value) {
        return fromNumber(value).neg();
    }

    /** Alias of {@link #neg(Decimal)}. */
    public static Decimal negate(String value) {
        return fromString(value).neg();
    }

    /** Alias of {@link #neg(Decimal)}. */
    public static Decimal negated(Decimal value) {
        return value.neg();
    }

    /** Alias of {@link #neg(Decimal)}. */
    public static Decimal negated(double value) {
        return fromNumber(value).neg();
    }

    /** Alias of {@link #neg(Decimal)}. */
    public static Decimal negated(String value) {
        return fromString(value).neg();
    }

    /** Returns the sign of the value. */
    public static double sign(Decimal value) {
        return value.sgn();
    }

    /** {@link #sign(Decimal)} for a double. */
    public static double sign(double value) {
        return Math.signum(value);
    }

    /** {@link #sign(Decimal)} for a string. */
    public static double sign(String value) {
        return fromString(value).sgn();
    }

    /** Alias of {@link #sign(Decimal)}. */
    public static double sgn(Decimal value) {
        return value.sgn();
    }

    /** Alias of {@link #sign(Decimal)}. */
    public static double sgn(double value) {
        return Math.signum(value);
    }

    /** Alias of {@link #sign(Decimal)}. */
    public static double sgn(String value) {
        return fromString(value).sgn();
    }

    /** Rounds the value to the nearest integer. */
    public static Decimal round(Decimal value) {
        return value.round();
    }

    /** {@link #round(Decimal)} for a double. */
    public static Decimal round(double value) {
        return fromNumber(value).round();
    }

    /** {@link #round(Decimal)} for a string. */
    public static Decimal round(String value) {
        return fromString(value).round();
    }

    /** "Rounds" the value to the nearest integer that's less than or equal to it. */
    public static Decimal floor(Decimal value) {
        return value.floor();
    }

    /** {@link #floor(Decimal)} for a double. */
    public static Decimal floor(double value) {
        return fromNumber(value).floor();
    }

    /** {@link #floor(Decimal)} for a string. */
    public static Decimal floor(String value) {
        return fromString(value).floor();
    }

    /** "Rounds" the value to the nearest integer that's greater than or equal to it. */
    public static Decimal ceil(Decimal value) {
        return value.ceil();
    }

    /** {@link #ceil(Decimal)} for a double. */
    public static Decimal ceil(double value) {
        return fromNumber(value).ceil();
    }

    /** {@link #ceil(Decimal)} for a string. */
    public static Decimal ceil(String value) {
        return fromString(value).ceil();
    }

    /** Extracts the integer part of the value. */
    public static Decimal trunc(Decimal value) {
        return value.trunc();
    }

    /** {@link #trunc(Decimal)} for a double. */
    public static Decimal trunc(double value) {
        return fromNumber(value).trunc();
    }

    /** {@link #trunc(Decimal)} for a string. */
    public static Decimal trunc(String value) {
        return fromString(value).trunc();
    }

    public static Decimal add(Decimal value, Decimal other) {
        return value.add(other);
    }

    public static Decimal add(Decimal value, double other) {
        return value.add(other);
    }

    public static Decimal add(double value, Decimal other) {
        return fromNumber(value).add(other);
    }

    public static Decimal add(double value, double other) {
        return fromNumber(value).add(other);
    }

    public static Decimal add(Decimal value, String other) {
        return value.add(other);
    }

    public static Decimal add(String value, Decimal other) {
        return fromString(value).add(other);
    }

    /** Alias of {@link #add(Decimal, Decimal)}. */
    public static Decimal plus(Decimal value, Decimal other) {
        return value.add(other);
    }

    /** Alias of {@link #add(Decimal, Decimal)}. */
    public static Decimal plus(Decimal value, double other) {
        return value.add(other);
    }

    /** Alias of {@link #add(Decimal, Decimal)}. */
    public static Decimal plus(double value, Decimal other) {
        return fromNumber(value).add(other);
    }

    /** Alias of {@link #add(Decimal, Decimal)}. */
    public static Decimal plus(double value, double other) {
        return fromNumber(value).add(other);
    }

    public static Decimal sub(Decimal value, Decimal other) {
        return value.sub(other);
    }

    public static Decimal sub(Decimal value, double other) {
        return value.sub(other);
    }

    public static Decimal sub(double value, Decimal other) {
        return fromNumber(value).sub(other);
    }

    public static Decimal sub(double value, double other) {
        return fromNumber(value).sub(other);
    }

    public static Decimal sub(Decimal value, String other) {
        return value.sub(other);
    }

    public static Decimal sub(String value, Decimal other) {
        return fromString(value).sub(other);
    }

    /** Alias of {@link #sub(Decimal, Decimal)}. */
    public static Decimal subtract(Decimal value, Decimal other) {
        return value.sub(other);
    }

    /** Alias of {@link #sub(Decimal, Decimal)}. */
    public static Decimal subtract(double value, double other) {
        return fromNumber(value).sub(other);
    }

    /** Alias of {@link #sub(Decimal, Decimal)}. */
    public static Decimal minus(Decimal value, Decimal other) {
        return value.sub(other);
    }

    /** Alias of {@link #sub(Decimal, Decimal)}. */
    public static Decimal minus(double value, double other) {
        return fromNumber(value).sub(other);
    }

    public static Decimal mul(Decimal value, Decimal other) {
        return value.mul(other);
    }

    public static Decimal mul(Decimal value, double other) {
        return value.mul(other);
    }

    public static Decimal mul(double value, Decimal other) {
        return fromNumber(value).mul(other);
    }

    public static Decimal mul(double value, double other) {
        return fromNumber(value).mul(other);
    }

    public static Decimal mul(Decimal value, String other) {
        return value.mul(other);
    }

    public static Decimal mul(String value, Decimal other) {
        return fromString(value).mul(other);
    }

    /** Alias of {@link #mul(Decimal, Decimal)}. */
    public static Decimal multiply(Decimal value, Decimal other) {
        return value.mul(other);
    }

    /** Alias of {@link #mul(Decimal, Decimal)}. */
    public static Decimal multiply(double value, double other) {
        return fromNumber(value).mul(other);
    }

    /** Alias of {@link #mul(Decimal, Decimal)}. */
    public static Decimal times(Decimal value, Decimal other) {
        return value.mul(other);
    }

    /** Alias of {@link #mul(Decimal, Decimal)}. */
    public static Decimal times(double value, double other) {
        return fromNumber(value).mul(other);
    }

    public static Decimal div(Decimal value, Decimal other) {
        return value.div(other);
    }

    public static Decimal div(Decimal value, double other) {
        return value.div(other);
    }

    public static Decimal div(double value, Decimal other) {
        return fromNumber(value).div(other);
    }

    public static Decimal div(double value, double other) {
        return fromNumber(value).div(other);
    }

    public static Decimal div(Decimal value, String other) {
        return value.div(other);
    }

    public static Decimal div(String value, Decimal other) {
        return fromString(value).div(other);
    }

    /** Alias of {@link #div(Decimal, Decimal)}. */
    public static Decimal divide(Decimal value, Decimal other) {
        return value.div(other);
    }

    /** Alias of {@link #div(Decimal, Decimal)}. */
    public static Decimal divide(double value, double other) {
        return fromNumber(value).div(other);
    }

    /** Alias of {@link #div(Decimal, Decimal)}. */
    public static Decimal divideBy(Decimal value, Decimal other) {
        return value.div(other);
    }

    /** Alias of {@link #div(Decimal, Decimal)}. */
    public static Decimal divideBy(double value, double other) {
        return fromNumber(value).div(other);
    }

    /** Alias of {@link #div(Decimal, Decimal)}. */
    public static Decimal dividedBy(Decimal value, Decimal other) {
        return value.div(other);
    }

    /** Alias of {@link #div(Decimal, Decimal)}. */
    public static Decimal dividedBy(double value, double other) {
        return fromNumber(value).div(other);
    }

    /** Returns the reciprocal (1 / X) of the value. */
    public static Decimal recip(Decimal value) {
        return value.recip();
    }

    /** {@link #recip(Decimal)} for a double. */
    public static Decimal recip(double value) {
        return fromNumber(value).recip();
    }

    /** {@link #recip(Decimal)} for a string. */
    public static Decimal recip(String value) {
        return fromString(value).recip();
    }

    /** Alias of {@link #recip(Decimal)}. */
    public static Decimal reciprocal(Decimal value) {
        return value.recip();
    }

    /** Alias of {@link #recip(Decimal)}. */
    public static Decimal reciprocal(double value) {
        return fromNumber(value).recip();
    }

    /** Alias of {@link #recip(Decimal)}. */
    public static Decimal reciprocal(String value) {
        return fromString(value).recip();
    }

    /** Alias of {@link #recip(Decimal)}. */
    public static Decimal reciprocate(Decimal value) {
        return value.recip();
    }

    /** Alias of {@link #recip(Decimal)}. */
    public static Decimal reciprocate(double value) {
        return fromNumber(value).recip();
    }

    /** Alias of {@link #recip(Decimal)}. */
    public static Decimal reciprocate(String value) {
        return fromString(value).recip();
    }

    /** See {@link #mod(Decimal, boolean)}. */
    public static Decimal mod(Decimal value, Decimal other, boolean floored) {
        return value.mod(other, floored);
    }

    /** {@link #mod(Decimal, Decimal, boolean)} with floored = false. */
    public static Decimal mod(Decimal value, Decimal other) {
        return value.mod(other, false);
    }

    /** {@link #mod(Decimal, Decimal, boolean)} for doubles. */
    public static Decimal mod(double value, double other, boolean floored) {
        return fromNumber(value).mod(other, floored);
    }

    /** {@link #mod(Decimal, Decimal, boolean)} for doubles, floored = false. */
    public static Decimal mod(double value, double other) {
        return fromNumber(value).mod(other, false);
    }

    /** Alias of {@link #mod(Decimal, Decimal, boolean)}. */
    public static Decimal modulo(Decimal value, Decimal other, boolean floored) {
        return value.mod(other, floored);
    }

    /** Alias of {@link #mod(Decimal, Decimal, boolean)}. */
    public static Decimal modulo(Decimal value, Decimal other) {
        return value.mod(other, false);
    }

    /** Alias of {@link #mod(Decimal, Decimal, boolean)}. */
    public static Decimal modular(Decimal value, Decimal other, boolean floored) {
        return value.mod(other, floored);
    }

    /** Alias of {@link #mod(Decimal, Decimal, boolean)}. */
    public static Decimal modular(Decimal value, Decimal other) {
        return value.mod(other, false);
    }

    /** See {@link #cmp(Decimal)}. */
    public static int cmp(Decimal value, Decimal other) {
        return value.cmp(other);
    }

    /** {@link #cmp(Decimal, Decimal)} for doubles. */
    public static int cmp(double value, double other) {
        return fromNumber(value).cmp(other);
    }

    /** See {@link #cmpabs(Decimal)}. */
    public static int cmpabs(Decimal value, Decimal other) {
        return value.cmpabs(other);
    }

    /** {@link #cmpabs(Decimal, Decimal)} for doubles. */
    public static int cmpabs(double value, double other) {
        return fromNumber(value).cmpabs(other);
    }

    /** Alias of {@link #cmp(Decimal, Decimal)}. */
    public static int compare(Decimal value, Decimal other) {
        return value.cmp(other);
    }

    /** Alias of {@link #cmp(Decimal, Decimal)}. */
    public static int compare(double value, double other) {
        return fromNumber(value).cmp(other);
    }

    /** The Decimal equivalent of ==. */
    public static boolean eq(Decimal value, Decimal other) {
        return value.eq(other);
    }

    /** {@link #eq(Decimal, Decimal)} for doubles. */
    public static boolean eq(double value, double other) {
        return fromNumber(value).eq(other);
    }

    /** Alias of {@link #eq(Decimal, Decimal)}. */
    public static boolean equals(Decimal value, Decimal other) {
        return value.eq(other);
    }

    /** Alias of {@link #eq(Decimal, Decimal)}. */
    public static boolean equals(double value, double other) {
        return fromNumber(value).eq(other);
    }

    /** The Decimal equivalent of !=. */
    public static boolean neq(Decimal value, Decimal other) {
        return value.neq(other);
    }

    /** {@link #neq(Decimal, Decimal)} for doubles. */
    public static boolean neq(double value, double other) {
        return fromNumber(value).neq(other);
    }

    /** Alias of {@link #neq(Decimal, Decimal)}. */
    public static boolean notEquals(Decimal value, Decimal other) {
        return value.neq(other);
    }

    /** Alias of {@link #neq(Decimal, Decimal)}. */
    public static boolean notEquals(double value, double other) {
        return fromNumber(value).neq(other);
    }

    /** The Decimal equivalent of <. */
    public static boolean lt(Decimal value, Decimal other) {
        return value.lt(other);
    }

    /** {@link #lt(Decimal, Decimal)} for doubles. */
    public static boolean lt(double value, double other) {
        return fromNumber(value).lt(other);
    }

    /** The Decimal equivalent of <=. */
    public static boolean lte(Decimal value, Decimal other) {
        return value.lte(other);
    }

    /** {@link #lte(Decimal, Decimal)} for doubles. */
    public static boolean lte(double value, double other) {
        return fromNumber(value).lte(other);
    }

    /** The Decimal equivalent of >. */
    public static boolean gt(Decimal value, Decimal other) {
        return value.gt(other);
    }

    /** {@link #gt(Decimal, Decimal)} for doubles. */
    public static boolean gt(double value, double other) {
        return fromNumber(value).gt(other);
    }

    /** The Decimal equivalent of >=. */
    public static boolean gte(Decimal value, Decimal other) {
        return value.gte(other);
    }

    /** {@link #gte(Decimal, Decimal)} for doubles. */
    public static boolean gte(double value, double other) {
        return fromNumber(value).gte(other);
    }

    /** Returns whichever of value and other is higher. */
    public static Decimal max(Decimal value, Decimal other) {
        return value.max(other);
    }

    /** {@link #max(Decimal, Decimal)} for doubles. */
    public static Decimal max(double value, double other) {
        return fromNumber(value).max(other);
    }

    /** Returns whichever of value and other is lower. */
    public static Decimal min(Decimal value, Decimal other) {
        return value.min(other);
    }

    /** {@link #min(Decimal, Decimal)} for doubles. */
    public static Decimal min(double value, double other) {
        return fromNumber(value).min(other);
    }

    /** Returns whichever of value and other has the larger absolute value. */
    public static Decimal maxabs(Decimal value, Decimal other) {
        return value.maxabs(other);
    }

    /** {@link #maxabs(Decimal, Decimal)} for doubles. */
    public static Decimal maxabs(double value, double other) {
        return fromNumber(value).maxabs(other);
    }

    /** Returns whichever of value and other has the smaller absolute value. */
    public static Decimal minabs(Decimal value, Decimal other) {
        return value.minabs(other);
    }

    /** {@link #minabs(Decimal, Decimal)} for doubles. */
    public static Decimal minabs(double value, double other) {
        return fromNumber(value).minabs(other);
    }

    /** See {@link #clamp(Decimal, Decimal)}. */
    public static Decimal clamp(Decimal value, Decimal min, Decimal max) {
        return value.clamp(min, max);
    }

    /** {@link #clamp(Decimal, Decimal, Decimal)} for doubles. */
    public static Decimal clamp(double value, double min, double max) {
        return fromNumber(value).clamp(min, max);
    }

    /** See {@link #clampMin(Decimal)}. */
    public static Decimal clampMin(Decimal value, Decimal min) {
        return value.max(min);
    }

    /** {@link #clampMin(Decimal, Decimal)} for doubles. */
    public static Decimal clampMin(double value, double min) {
        return fromNumber(value).max(min);
    }

    /** See {@link #clampMax(Decimal)}. */
    public static Decimal clampMax(Decimal value, Decimal max) {
        return value.min(max);
    }

    /** {@link #clampMax(Decimal, Decimal)} for doubles. */
    public static Decimal clampMax(double value, double max) {
        return fromNumber(value).min(max);
    }

    /** See {@link #cmp_tolerance(Decimal, double)}. */
    public static int cmp_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.cmp_tolerance(other, tolerance);
    }

    /** {@link #cmp_tolerance(Decimal, Decimal, double)} with the default tolerance of 1e-7. */
    public static int cmp_tolerance(Decimal value, Decimal other) {
        return value.cmp_tolerance(other);
    }

    /** Alias of {@link #cmp_tolerance(Decimal, Decimal, double)}. */
    public static int compare_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.cmp_tolerance(other, tolerance);
    }

    /** Alias of {@link #cmp_tolerance(Decimal, Decimal, double)}. */
    public static int compare_tolerance(Decimal value, Decimal other) {
        return value.cmp_tolerance(other);
    }

    /** See {@link #eq_tolerance(Decimal, double)}. */
    public static boolean eq_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.eq_tolerance(other, tolerance);
    }

    /** {@link #eq_tolerance(Decimal, Decimal, double)} with the default tolerance of 1e-7. */
    public static boolean eq_tolerance(Decimal value, Decimal other) {
        return value.eq_tolerance(other);
    }

    /** Alias of {@link #eq_tolerance(Decimal, Decimal, double)}. */
    public static boolean equals_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.eq_tolerance(other, tolerance);
    }

    /** Alias of {@link #eq_tolerance(Decimal, Decimal, double)}. */
    public static boolean equals_tolerance(Decimal value, Decimal other) {
        return value.eq_tolerance(other);
    }

    /** See {@link #neq_tolerance(Decimal, double)}. */
    public static boolean neq_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.neq_tolerance(other, tolerance);
    }

    /** {@link #neq_tolerance(Decimal, Decimal, double)} with the default tolerance of 1e-7. */
    public static boolean neq_tolerance(Decimal value, Decimal other) {
        return value.neq_tolerance(other);
    }

    /** Alias of {@link #neq_tolerance(Decimal, Decimal, double)}. */
    public static boolean notEquals_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.neq_tolerance(other, tolerance);
    }

    /** Alias of {@link #neq_tolerance(Decimal, Decimal, double)}. */
    public static boolean notEquals_tolerance(Decimal value, Decimal other) {
        return value.neq_tolerance(other);
    }

    /** See {@link #lt_tolerance(Decimal, double)}. */
    public static boolean lt_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.lt_tolerance(other, tolerance);
    }

    /** {@link #lt_tolerance(Decimal, Decimal, double)} with the default tolerance of 1e-7. */
    public static boolean lt_tolerance(Decimal value, Decimal other) {
        return value.lt_tolerance(other);
    }

    /** See {@link #lte_tolerance(Decimal, double)}. */
    public static boolean lte_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.lte_tolerance(other, tolerance);
    }

    /** {@link #lte_tolerance(Decimal, Decimal, double)} with the default tolerance of 1e-7. */
    public static boolean lte_tolerance(Decimal value, Decimal other) {
        return value.lte_tolerance(other);
    }

    /** See {@link #gt_tolerance(Decimal, double)}. */
    public static boolean gt_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.gt_tolerance(other, tolerance);
    }

    /** {@link #gt_tolerance(Decimal, Decimal, double)} with the default tolerance of 1e-7. */
    public static boolean gt_tolerance(Decimal value, Decimal other) {
        return value.gt_tolerance(other);
    }

    /** See {@link #gte_tolerance(Decimal, double)}. */
    public static boolean gte_tolerance(Decimal value, Decimal other, double tolerance) {
        return value.gte_tolerance(other, tolerance);
    }

    /** {@link #gte_tolerance(Decimal, Decimal, double)} with the default tolerance of 1e-7. */
    public static boolean gte_tolerance(Decimal value, Decimal other) {
        return value.gte_tolerance(other);
    }

    // --- tetration (index.ts:3367-3496) ---

    /**
     * Tetration: The result of exponentiating 'this' to 'height' height times, i.e.
     * this^^height. https://en.wikipedia.org/wiki/Tetration
     *
     * <p>If payload != 1, then this is 'iterated exponentiation', the result of exping
     * 'payload' to base 'this' 'height' times.
     *
     * <p>Works with negative and positive real heights. Tetration for non-integer heights
     * does not have a single agreed-upon definition, so this library uses an analytic
     * approximation for bases <= 10, but it reverts to the linear approximation for
     * bases > 10. If you want to use the linear approximation even for bases <= 10, set
     * the linear parameter to true.
     */
    public Decimal tetrate(double height, Decimal payload, boolean linear) {
        // x^^1 == x
        if (height == 1) {
            return this.pow(payload);
        }
        // x^^0 == 1
        if (height == 0) {
            return payload;
        }
        // 1^^x == 1
        if (this.eq(dOne)) {
            return dOne;
        }
        // -1^^x == -1
        if (this.eq(-1)) {
            return this.pow(payload);
        }

        if (height == Double.POSITIVE_INFINITY) {
            double this_num = this.toNumber();
            // within the convergence range?
            if (this_num <= 1.44466786100976613366 && this_num >= 0.06598803584531253708) {
                Decimal negln = this.ln().neg();
                // For bases above 1, b^x = x has two solutions. The lower solution is a
                // stable equilibrium, the upper solution is an unstable equilibrium.
                Decimal lower = negln.lambertw().div(negln);
                // However, if the base is below 1, there's only the stable equilibrium solution.
                if (this_num < 1) {
                    return lower;
                }
                Decimal upper = negln.lambertw(false).div(negln);
                // hotfix for the very edge of the number range not being handled properly
                if (this_num > 1.444667861009099) {
                    lower = fromNumber(Math.E);
                    upper = lower;
                }
                if (payload.eq(upper)) {
                    return upper;
                } else if (payload.lt(upper)) {
                    return lower;
                } else {
                    return dInf;
                }
            } else if (this_num > 1.44466786100976613366) {
                // explodes to infinity
                return dInf;
            } else {
                // 0.06598803584531253708 > this_num >= 0: never converges
                // this_num < 0: quickly becomes a complex number
                return dNaN;
            }
        }

        // 0^^x oscillates if we define 0^0 == 1 (which in javascript land we do), since
        // then 0^^1 is 0, 0^^2 is 1, 0^^3 is 0, etc. payload is ignored.
        // Using the linear approximation for height.
        if (this.eq(dZero)) {
            double result = Math.abs((height + 1) % 2);
            if (result > 1) {
                result = 2 - result;
            }
            return fromNumber(result);
        }

        if (height < 0) {
            return payload.iteratedlog(this, -height, linear);
        }

        double oldheight = height;
        height = JsNumber.trunc(height);
        double fracheight = oldheight - height;

        if (this.gt(dZero)
                && (this.lt(1)
                        || (this.lte(1.44466786100976613366)
                                && payload.lte(
                                        this.ln().neg().lambertw(false).div(this.ln().neg()))))
                && (oldheight > 10000 || !linear)) {
            // Similar to 0^^n, flip-flops between two values, converging slowly (or if
            // it's below 0.06598803584531253708, never). So once again, the fractional
            // part at the beginning will be a linear approximation.
            double limitheight = Math.min(10000, height);
            if (payload.eq(dOne)) {
                payload = this.pow(fracheight);
            } else if (this.lt(1)) {
                payload = payload.pow(1 - fracheight).mul(this.pow(payload).pow(fracheight));
            } else {
                payload = payload.layeradd(fracheight, this);
            }
            for (int i = 0; i < limitheight; ++i) {
                Decimal old_payload = payload;
                payload = this.pow(payload);
                // stop early if we converge
                if (old_payload.eq(payload)) {
                    return payload;
                }
            }
            if (oldheight > 10000 && Math.ceil(oldheight) % 2 == 1) {
                return this.pow(payload);
            }
            return payload;
        }

        if (fracheight != 0) {
            if (payload.eq(dOne)) {
                // If (linear), use linear approximation even for bases <= 10
                if (this.gt(10) || linear) {
                    payload = this.pow(fracheight);
                } else {
                    payload = fromNumber(tetrate_critical(this.toNumber(), fracheight));
                    if (this.lt(2)) {
                        payload = payload.sub(1).mul(this.minus(1)).plus(1);
                    }
                }
            } else {
                if (this.eq(10)) {
                    payload = payload.layeradd10(fracheight, linear);
                } else if (this.lt(1)) {
                    payload = payload.pow(1 - fracheight).mul(this.pow(payload).pow(fracheight));
                } else {
                    payload = payload.layeradd(fracheight, this, linear);
                }
            }
        }

        for (int i = 0; i < height; ++i) {
            payload = this.pow(payload);
            // bail if we're NaN
            if (!Double.isFinite(payload.layer) || !Double.isFinite(payload.mag)) {
                return payload.normalized();
            }
            // shortcut
            if (payload.layer - this.layer > 3) {
                return fromComponents_noNormalize(
                        payload.sign, payload.layer + (height - i - 1), payload.mag);
            }
            // give up after 10000 iterations if nothing is happening
            if (i > 10000) {
                return payload;
            }
        }
        return payload;
    }

    /** {@link #tetrate(double, Decimal, boolean)} with payload 1. */
    public Decimal tetrate(double height, Decimal payload) {
        return this.tetrate(height, payload, false);
    }

    /** {@link #tetrate(double, Decimal, boolean)} with the default payload of 1. */
    public Decimal tetrate(double height) {
        return this.tetrate(height, dOne, false);
    }

    /**
     * Iterated exponentiation, the result of exping 'payload' to base 'this' 'height'
     * times. Identical to {@link #tetrate(double, Decimal, boolean)}.
     */
    public Decimal iteratedexp(double height, Decimal payload, boolean linear) {
        return this.tetrate(height, payload, linear);
    }

    /** {@link #iteratedexp(double, Decimal, boolean)} with payload 1. */
    public Decimal iteratedexp(double height, Decimal payload) {
        return this.tetrate(height, payload, false);
    }

    /** {@link #iteratedexp(double, Decimal, boolean)} with the default payload of 1. */
    public Decimal iteratedexp(double height) {
        return this.tetrate(height, dOne, false);
    }

    // --- iterated logarithm (index.ts:3521-3559) ---

    /**
     * Iterated log/repeated log: The result of applying log(base) 'times' times in a
     * row. Approximately equal to subtracting 'times' from the number's slog
     * representation. Equivalent to tetrating to a negative height.
     */
    public Decimal iteratedlog(Decimal base, double times, boolean linear) {
        if (times < 0) {
            return base.tetrate(-times, this, linear);
        }

        Decimal result = this;
        double fulltimes = times;
        times = JsNumber.trunc(times);
        double fraction = fulltimes - times;
        if (result.layer - base.layer > 3) {
            double layerloss = Math.min(times, result.layer - base.layer - 3);
            times -= layerloss;
            result = new Decimal(result.sign, result.layer - layerloss, result.mag);
        }

        for (int i = 0; i < times; ++i) {
            result = result.log(base);
            // bail if we're NaN
            if (!Double.isFinite(result.layer) || !Double.isFinite(result.mag)) {
                return result.normalized();
            }
            // give up after 10000 iterations if nothing is happening
            if (i > 10000) {
                return result;
            }
        }

        // handle fractional part
        if (fraction > 0 && fraction < 1) {
            if (base.eq(10)) {
                result = result.layeradd10(-fraction, linear);
            } else {
                result = result.layeradd(-fraction, base, linear);
            }
        }

        return result;
    }

    /** {@link #iteratedlog(Decimal, double, boolean)} with the default base of 10. */
    public Decimal iteratedlog(Decimal base, double times) {
        return this.iteratedlog(base, times, false);
    }

    /** {@link #iteratedlog(Decimal, double, boolean)} with base 10 and times 1. */
    public Decimal iteratedlog(Decimal base) {
        return this.iteratedlog(base, 1, false);
    }

    /** {@link #iteratedlog(Decimal, double, boolean)} with the default base of 10 and times 1. */
    public Decimal iteratedlog() {
        return this.iteratedlog(dTen, 1, false);
    }

    // --- slog (index.ts:3573-3728) ---

    /**
     * Super-logarithm, one of tetration's inverses, tells you what size power tower you'd
     * have to tetrate 'base' to to get 'this'.
     *
     * <p>Accepts a number of iterations (default is 100), and uses binary search to, after
     * making an initial guess, hone in on the true value, assuming tetration as the
     * ground truth.
     */
    public Decimal slog(Decimal base, int iterations, boolean linear) {
        double step_size = 0.001;
        boolean has_changed_directions_once = false;
        boolean previously_rose = false;
        double result = this.slog_internal(base, linear).toNumber();
        for (int i = 1; i < iterations; ++i) {
            Decimal new_decimal = base.tetrate(result, dOne, linear);
            boolean currently_rose = new_decimal.gt(this);
            if (i > 1) {
                if (previously_rose != currently_rose) {
                    has_changed_directions_once = true;
                }
            }
            previously_rose = currently_rose;
            if (has_changed_directions_once) {
                step_size /= 2;
            } else {
                step_size *= 2;
            }
            step_size = Math.abs(step_size) * (currently_rose ? -1 : 1);
            result += step_size;
            if (step_size == 0) {
                break;
            }
        }
        return fromNumber(result);
    }

    /** {@link #slog(Decimal, int, boolean)} with the default base of 10 and 100 iterations. */
    public Decimal slog(Decimal base, int iterations) {
        return this.slog(base, iterations, false);
    }

    /** {@link #slog(Decimal, int, boolean)} with the default 100 iterations. */
    public Decimal slog(Decimal base) {
        return this.slog(base, 100, false);
    }

    /** {@link #slog(Decimal, int, boolean)} with the default base of 10 and 100 iterations. */
    public Decimal slog() {
        return this.slog(dTen, 100, false);
    }

    public Decimal slog_internal(Decimal base, boolean linear) {
        // special cases:
        // slog base 0 or lower is NaN
        if (base.lte(dZero)) {
            return dNaN;
        }
        // slog base 1 is NaN
        if (base.eq(dOne)) {
            return dNaN;
        }
        // need to handle these small, wobbling bases specially
        if (base.lt(dOne)) {
            if (this.eq(dOne)) {
                return dZero;
            }
            if (this.eq(dZero)) {
                return fromComponents_noNormalize(-1, 0, 1);
            }
            // 0 < this < 1: ambiguous (happens multiple times)
            // this < 0: impossible (as far as I can tell)
            // this > 1: partially complex
            return dNaN;
        }
        // slog_n(0) is -1
        if (this.mag < 0 || this.eq(dZero)) {
            return fromComponents_noNormalize(-1, 0, 1);
        }
        if (base.lt(1.44466786100976613366)) {
            Decimal negln = base.ln().neg();
            Decimal infTower = negln.lambertw().div(negln);
            if (this.eq(infTower)) {
                return dInf;
            }
            if (this.gt(infTower)) {
                return dNaN;
            }
        }

        double result = 0;
        Decimal copy = this;
        if (copy.layer - base.layer > 3) {
            double layerloss = copy.layer - base.layer - 3;
            result += layerloss;
            copy = new Decimal(copy.sign, copy.layer - layerloss, copy.mag);
        }

        for (int i = 0; i < 100; ++i) {
            if (copy.lt(dZero)) {
                copy = base.pow(copy);
                result -= 1;
            } else if (copy.lte(dOne)) {
                if (linear) {
                    return fromNumber(result + copy.toNumber() - 1);
                } else {
                    return fromNumber(result + slog_critical(base.toNumber(), copy.toNumber()));
                }
            } else {
                result += 1;
                copy = copy.log(base);
            }
        }
        return fromNumber(result);
    }

    /** {@link #slog_internal(Decimal, boolean)} with the default base of 10. */
    public Decimal slog_internal(Decimal base) {
        return this.slog_internal(base, false);
    }

    /** {@link #slog_internal(Decimal, boolean)} with the default base of 10. */
    public Decimal slog_internal() {
        return this.slog_internal(dTen, false);
    }

    /**
     * The critical-section super-logarithm: for base > 10 this is the linear
     * approximation, otherwise a table lookup with interpolation.
     */
    public static double slog_critical(double base, double height) {
        if (base > 10) {
            return height - 1;
        }
        return critical_section(base, height, CriticalTables.critical_slog_values, false);
    }

    /** The critical-section tetration: a table lookup with interpolation. */
    public static double tetrate_critical(double base, double height) {
        return critical_section(base, height, CriticalTables.critical_tetr_values, false);
    }

    public static double critical_section(
            double base, double height, double[][] grid, boolean linear) {
        // this part is simple at least, since it's just 0.1 to 0.9
        height *= 10;
        if (height < 0) {
            height = 0;
        }
        if (height > 10) {
            height = 10;
        }
        // have to do this complicated song and dance since one of the critical_headers is
        // Math.E, and in the future 1.5 as well
        if (base < 2) {
            base = 2;
        }
        if (base > 10) {
            base = 10;
        }
        double lower = 0;
        double upper = 0;
        // basically, if we're between bases, we interpolate each bases' relevant values
        // together, then we interpolate based on what the fractional height is.
        for (int i = 0; i < CriticalTables.critical_headers.length; ++i) {
            if (CriticalTables.critical_headers[i] == base) {
                // exact match
                lower = grid[i][(int) Math.floor(height)];
                upper = grid[i][(int) Math.ceil(height)];
                break;
            } else if (CriticalTables.critical_headers[i] < base
                    && CriticalTables.critical_headers[i + 1] > base) {
                // interpolate between this and the next
                double basefrac =
                        (base - CriticalTables.critical_headers[i])
                                / (CriticalTables.critical_headers[i + 1]
                                        - CriticalTables.critical_headers[i]);
                lower =
                        grid[i][(int) Math.floor(height)] * (1 - basefrac)
                                + grid[i + 1][(int) Math.floor(height)] * basefrac;
                upper =
                        grid[i][(int) Math.ceil(height)] * (1 - basefrac)
                                + grid[i + 1][(int) Math.ceil(height)] * basefrac;
                break;
            }
        }
        double frac = height - Math.floor(height);
        // improvement - you get more accuracy (especially around 0.9-1.0) by doing log,
        // then frac, then powing the result; also, use old linear for slog (values 0 or
        // less in critical section)
        if (lower <= 0 || upper <= 0) {
            return lower * (1 - frac) + upper * frac;
        } else {
            return Math.pow(
                    base,
                    (StrictMath.log(lower) / StrictMath.log(base)) * (1 - frac)
                            + (StrictMath.log(upper) / StrictMath.log(base)) * frac);
        }
    }

    // --- layeradd (index.ts:3739-3843) ---

    /**
     * layeradd10: like adding 'diff' to the number's slog(base) representation. Very
     * similar to tetrate base 10 and iterated log base 10. Also equivalent to adding a
     * fractional amount to the number's layer in its break_eternity.js representation.
     */
    public Decimal layeradd10(Decimal diffSource, boolean linear) {
        // The original converts diff to a number immediately.
        double diff = diffSource.toNumber();
        double rSign = this.sign;
        double rLayer = this.layer;
        double rMag = this.mag;
        if (diff >= 1) {
            // bug fix: if result is very smol (mag < 0, layer > 0) turn it into 0 first
            if (rMag < 0 && rLayer > 0) {
                rSign = 0;
                rMag = 0;
                rLayer = 0;
            } else if (rSign == -1 && rLayer == 0) {
                // bug fix - for stuff like -3.layeradd10(1) we need to move the sign to the mag
                rSign = 1;
                rMag = -rMag;
            }
            double layeradd = JsNumber.trunc(diff);
            diff -= layeradd;
            rLayer += layeradd;
        }
        if (diff <= -1) {
            double layeradd = JsNumber.trunc(diff);
            diff -= layeradd;
            rLayer += layeradd;
            if (rLayer < 0) {
                for (int i = 0; i < 100; ++i) {
                    rLayer++;
                    rMag = StrictMath.log10(rMag);
                    if (!Double.isFinite(rMag)) {
                        // another bugfix: if we hit -Infinity mag, then we should return
                        // negative infinity, not 0. 0.layeradd10(-1) hits this.
                        if (rSign == 0) {
                            rSign = 1;
                        }
                        // also this, for 0.layeradd10(-2)
                        if (rLayer < 0) {
                            rLayer = 0;
                        }
                        return fromComponents_noNormalize(rSign, rLayer, rMag).normalized();
                    }
                    if (rLayer >= 0) {
                        break;
                    }
                }
            }
        }

        while (rLayer < 0) {
            rLayer++;
            rMag = StrictMath.log10(rMag);
        }
        // bugfix: before we normalize: if we started with 0, we now need to manually fix a layer ourselves!
        if (rSign == 0) {
            rSign = 1;
            if (rMag == 0 && rLayer >= 1) {
                rLayer -= 1;
                rMag = 1;
            }
        }
        Decimal result = fromComponents_noNormalize(rSign, rLayer, rMag).normalized();

        if (diff != 0) {
            // safe, only calls positive height 1 payload tetration, slog and log
            return result.layeradd(diff, dTen, linear);
        }
        return result;
    }

    /** {@link #layeradd10(Decimal, boolean)}. */
    public Decimal layeradd10(Decimal diff) {
        return this.layeradd10(diff, false);
    }

    /** {@link #layeradd10(Decimal, boolean)} for a double. */
    public Decimal layeradd10(double diff, boolean linear) {
        return this.layeradd10(fromNumber(diff), linear);
    }

    /** {@link #layeradd10(Decimal, boolean)} for a double. */
    public Decimal layeradd10(double diff) {
        return this.layeradd10(fromNumber(diff), false);
    }

    /**
     * layeradd: like adding 'diff' to the number's slog(base) representation. Very similar
     * to tetrate base 'base' and iterated log base 'base'.
     */
    public Decimal layeradd(double diff, Decimal base, boolean linear) {
        if (base.gt(1) && base.lte(1.44466786100976613366)) {
            ExcessSlogResult excessSlog = excess_slog(this, base, linear);
            double slogthis = excessSlog.slog().toNumber();
            int range = excessSlog.range();
            double slogdest = slogthis + diff;
            Decimal negln = base.ln().neg();
            Decimal lower = negln.lambertw().div(negln);
            Decimal upper = negln.lambertw(false).div(negln);
            Decimal slogzero = dOne;
            if (range == 1) {
                slogzero = lower.mul(upper).sqrt();
            } else if (range == 2) {
                slogzero = upper.mul(2);
            }
            Decimal slogone = base.pow(slogzero);
            double wholeheight = Math.floor(slogdest);
            double fracheight = slogdest - wholeheight;
            Decimal towertop = slogzero.pow(1 - fracheight).mul(slogone.pow(fracheight));
            // wholeheight is a whole number so this is safe even if it ends up calling iteratedlog
            return base.tetrate(wholeheight, towertop, linear);
        }
        double slogthis = this.slog(base, 100, linear).toNumber();
        double slogdest = slogthis + diff;
        if (slogdest >= 0) {
            return base.tetrate(slogdest, dOne, linear);
        } else if (!Double.isFinite(slogdest)) {
            return dNaN;
        } else if (slogdest >= -1) {
            return base.tetrate(slogdest + 1, dOne, linear).log(base);
        } else {
            return base.tetrate(slogdest + 2, dOne, linear).log(base).log(base);
        }
    }

    /** {@link #layeradd(double, Decimal, boolean)}. */
    public Decimal layeradd(double diff, Decimal base) {
        return this.layeradd(diff, base, false);
    }

    /** {@link #layeradd(double, Decimal, boolean)} for a double base. */
    public Decimal layeradd(double diff, double base, boolean linear) {
        return this.layeradd(diff, fromNumber(base), linear);
    }

    /** {@link #layeradd(double, Decimal, boolean)} for a double base. */
    public Decimal layeradd(double diff, double base) {
        return this.layeradd(diff, fromNumber(base), false);
    }

    /** The result of {@link #excess_slog}: a slog value and the range 0, 1 or 2. */
    record ExcessSlogResult(Decimal slog, int range) {}

    /**
     * A strange version of slog for bases between 1 and e^1/e which can handle values
     * above base^^Infinity. The range indicates what range we're in: 0 means we're below
     * the lower solution of b^x = x (normal slog); 1 means between the two solutions,
     * with the geometric mean of the two solutions arbitrarily chosen as the value with
     * a slog of 0; 2 means above the upper solution, with (upper solution * 2)
     * arbitrarily chosen as the value with a slog of 0.
     */
    static ExcessSlogResult excess_slog(Decimal value, Decimal base, boolean linear) {
        Decimal baseD = base;
        double baseNum = base.toNumber();
        if (baseNum == 1 || baseNum <= 0) {
            return new ExcessSlogResult(dNaN, 0);
        }
        if (baseNum > 1.44466786100976613366) {
            return new ExcessSlogResult(value.slog(base, 100, linear), 0);
        }
        Decimal negln = fromNumber(baseNum).ln().neg();
        Decimal lower = negln.lambertw().div(negln);
        Decimal upper = dInf;
        if (baseNum > 1) {
            upper = negln.lambertw(false).div(negln);
        }
        if (baseNum > 1.444667861009099) {
            lower = fromNumber(Math.E);
            upper = lower;
        }
        if (value.lt(lower)) {
            return new ExcessSlogResult(value.slog(base, 100, linear), 0);
        }
        if (value.eq(lower)) {
            return new ExcessSlogResult(dInf, 0);
        }
        if (value.eq(upper)) {
            return new ExcessSlogResult(dNegInf, 2);
        }
        if (value.gt(upper)) {
            Decimal slogzero = upper.mul(2);
            Decimal slogone = baseD.pow(slogzero);
            double estimate = 0;
            if (value.gte(slogzero) && value.lt(slogone)) {
                estimate = 0;
            } else if (value.gte(slogone)) {
                Decimal payload = slogone;
                estimate = 1;
                while (payload.lt(value)) {
                    payload = baseD.pow(payload);
                    estimate = estimate + 1;
                    if (payload.layer > 3) {
                        double layersleft = Math.floor(value.layer - payload.layer + 1);
                        payload = baseD.iteratedexp(layersleft, payload, linear);
                        estimate = estimate + layersleft;
                    }
                }
                if (payload.gt(value)) {
                    payload = payload.log(baseD);
                    estimate = estimate - 1;
                }
            } else if (value.lt(slogzero)) {
                Decimal payload = slogzero;
                estimate = 0;
                while (payload.gt(value)) {
                    payload = payload.log(baseD);
                    estimate = estimate - 1;
                }
            }
            double fracheight = 0;
            double tested = 0;
            double step_size = 0.5;
            Decimal towertop = slogzero;
            Decimal guess = dZero;
            while (step_size > 1e-16) {
                tested = fracheight + step_size;
                // Weighted geometric average
                towertop = slogzero.pow(1 - tested).mul(slogone.pow(tested));
                guess = baseD.iteratedexp(estimate, towertop, linear);
                if (guess.eq(value)) {
                    return new ExcessSlogResult(fromNumber(estimate + tested), 2);
                } else if (guess.lt(value)) {
                    fracheight += step_size;
                }
                step_size /= 2;
            }
            if (guess.neq_tolerance(value, 1e-7)) {
                return new ExcessSlogResult(dNaN, 0);
            }
            return new ExcessSlogResult(fromNumber(estimate + fracheight), 2);
        }
        if (value.lt(upper) && value.gt(lower)) {
            Decimal slogzero = lower.mul(upper).sqrt(); // Geometric mean of the two solutions
            Decimal slogone = baseD.pow(slogzero);
            double estimate = 0;
            if (value.lte(slogzero) && value.gt(slogone)) {
                estimate = 0;
            } else if (value.lte(slogone)) {
                Decimal payload = slogone;
                estimate = 1;
                while (payload.gt(value)) {
                    payload = baseD.pow(payload);
                    estimate = estimate + 1;
                }
                if (payload.lt(value)) {
                    payload = payload.log(baseD);
                    estimate = estimate - 1;
                }
            } else if (value.gt(slogzero)) {
                Decimal payload = slogzero;
                estimate = 0;
                while (payload.lt(value)) {
                    payload = payload.log(baseD);
                    estimate = estimate - 1;
                }
            }
            double fracheight = 0;
            double tested = 0;
            double step_size = 0.5;
            Decimal towertop = slogzero;
            Decimal guess = dZero;
            while (step_size > 1e-16) {
                tested = fracheight + step_size;
                // Weighted geometric average
                towertop = slogzero.pow(1 - tested).mul(slogone.pow(tested));
                guess = baseD.iteratedexp(estimate, towertop, linear);
                if (guess.eq(value)) {
                    return new ExcessSlogResult(fromNumber(estimate + tested), 1);
                } else if (guess.gt(value)) {
                    fracheight += step_size;
                }
                step_size /= 2;
            }
            if (guess.neq_tolerance(value, 1e-7)) {
                return new ExcessSlogResult(dNaN, 0);
            }
            return new ExcessSlogResult(fromNumber(estimate + fracheight), 1);
        }
        throw new IllegalStateException("Unhandled behavior in excess_slog");
    }

    // --- Lambert W (index.ts:3972-4010) ---

    /**
     * The Lambert W function, also called the omega function or product logarithm, is the
     * solution W(x) = x*e^x. Only two branches matter for real numbers: the "principal
     * branch" W0 and the "non-principal branch" W_-1. W_0 works for any number >= -1/e,
     * but W_-1 only works for nonpositive numbers >= -1/e.
     */
    public Decimal lambertw(boolean principal) {
        if (this.lt(-0.3678794411710499)) {
            return dNaN; // complex
        } else if (principal) {
            if (this.abs().lt(fromString("1e-300"))) {
                return this;
            } else if (this.mag < 0) {
                return fromNumber(f_lambertw(this.toNumber(), 1e-10, true));
            } else if (this.layer == 0) {
                return fromNumber(f_lambertw(this.sign * this.mag, 1e-10, true));
            } else if (this.lt(fromString("eee15"))) {
                return d_lambertw(this, 1e-10, true);
            } else {
                // Numbers this large would sometimes fail to converge using d_lambertw,
                // and at this size this.ln() is close enough
                return this.ln();
            }
        } else {
            if (this.sign == 1) {
                return dNaN; // complex
            }
            if (this.layer == 0) {
                return fromNumber(f_lambertw(this.sign * this.mag, 1e-10, false));
            } else if (this.layer == 1) {
                return d_lambertw(this, 1e-10, false);
            } else {
                return this.neg().recip().lambertw().neg();
            }
        }
    }

    /** {@link #lambertw(boolean)} with the principal branch. */
    public Decimal lambertw() {
        return this.lambertw(true);
    }

    /**
     * The number-domain Lambert W (index.ts:267-309). Evaluates W(x, 0) if principal is
     * true, W(x, -1) if principal is false. Throws if the iteration fails to converge,
     * like the original.
     */
    private static double f_lambertw(double z, double tol, boolean principal) {
        if (!Double.isFinite(z)) {
            return z;
        }
        double w;
        double wn;

        if (principal) {
            if (z == 0) {
                return z;
            }
            if (z == 1) {
                return CriticalTables.OMEGA;
            }
            if (z < 10) {
                w = 0;
            } else {
                w = StrictMath.log(z) - StrictMath.log(StrictMath.log(z));
            }
        } else {
            if (z == 0) {
                return Double.NEGATIVE_INFINITY;
            }
            if (z <= -0.1) {
                w = -2;
            } else {
                w = StrictMath.log(-z) - StrictMath.log(-StrictMath.log(-z));
            }
        }

        for (int i = 0; i < 100; ++i) {
            wn = (z * StrictMath.exp(-w) + w * w) / (w + 1);
            if (Math.abs(wn - w) < tol * Math.abs(wn)) {
                return wn;
            } else {
                w = wn;
            }
        }

        throw new IllegalStateException("Iteration failed to converge: " + z);
    }

    /**
     * The Decimal-domain Lambert W (index.ts:316-357). Throws if the iteration fails to
     * converge, like the original.
     */
    private static Decimal d_lambertw(Decimal z, double tol, boolean principal) {
        if (!Double.isFinite(z.mag)) {
            return z;
        }
        Decimal w;
        if (principal) {
            if (z.eq(dZero)) {
                return fromComponents_noNormalize(0, 0, 0);
            }
            if (z.eq(dOne)) {
                // Split out this case because the asymptotic series blows up
                return fromNumber(CriticalTables.OMEGA);
            }
            // Get an initial guess for Halley's method
            w = z.ln();
        } else {
            if (z.eq(dZero)) {
                return dNegInf;
            }
            // Get an initial guess for Halley's method
            w = z.neg().ln();
        }

        // Halley's method; see 5.9 in [1]
        for (int i = 0; i < 100; ++i) {
            Decimal ew = w.neg().exp();
            Decimal wewz = w.sub(z.mul(ew));
            Decimal wn = w.sub(wewz.div(w.add(1).sub(w.add(2).mul(wewz).div(mul(2, w).add(2)))));
            if (wn.sub(w).abs().lt(wn.abs().mul(tol))) {
                return wn;
            } else {
                w = wn;
            }
        }

        throw new IllegalStateException("Iteration failed to converge: " + z);
    }

    // --- super-roots (index.ts:4007-4324) ---

    /**
     * The super square-root function - what number, tetrated to height 2, equals 'this'?
     * https://en.wikipedia.org/wiki/Tetration#Super-root
     */
    public Decimal ssqrt() {
        return this.linear_sroot(2);
    }

    /**
     * Super-root, one of tetration's inverses - what number, tetrated to height 'degree',
     * equals 'this'?
     *
     * <p>Only works with the linear approximation of tetration, as starting with analytic
     * and then switching to linear would result in inconsistent behavior for super-roots.
     */
    public Decimal linear_sroot(double degree) {
        // 1st-degree super root just returns its input
        if (degree == 1) {
            return this;
        }
        if (this.eq(dInf)) {
            return dInf;
        }
        if (!this.isFinite()) {
            return dNaN;
        }
        // Using linear approximation, x^^n = x^n if 0 < n < 1
        if (degree > 0 && degree < 1) {
            return this.root(degree);
        }
        // Using the linear approximation, there actually is a single solution for super
        // roots with -2 < degree <= -1
        if (degree > -2 && degree < -1) {
            return fromNumber(degree).add(2).pow(this.recip());
        }
        // Super roots with -1 <= degree < 0 have either no solution or infinitely many
        // solutions, and tetration with height <= -2 returns NaN, so super roots of
        // degree <= -2 don't work
        if (degree <= 0) {
            return dNaN;
        }
        // Infinite degree super-root is x^(1/x) between 1/e <= x <= e, undefined otherwise
        if (degree == Double.POSITIVE_INFINITY) {
            double this_num = this.toNumber();
            if (this_num < Math.E && this_num > _EXPN1) {
                return this.pow(this.recip());
            } else {
                return dNaN;
            }
        }
        // Special case: any super-root of 1 is 1
        if (this.eq(1)) {
            return fromComponents_noNormalize(1, 0, 1);
        }
        if (this.lt(0)) {
            return dNaN;
        }
        // Treat all numbers of layer <= -2 as zero, because they effectively are
        if (this.lte(fromString("1ee-16"))) {
            if (degree % 2 == 1) {
                return this;
            } else {
                return dNaN;
            }
        }
        // this > 1
        if (this.gt(1)) {
            // Uses guess-and-check to find the super-root.
            // If this > 10^^(degree), then the answer is under iteratedlog(10, degree - 1):
            // for example, ssqrt(x) < log(x, 10) as long as x > 10^10, and
            // linear_sroot(x, 3) < log(log(x, 10), 10) as long as x > 10^10^10
            // On the other hand, if this < 10^^(degree), then clearly the answer is less than 10
            // Since the answer could be a higher-layered number itself (whereas slog's
            // maximum is 1.8e308), the guess-and-check is scaled to the layer of the upper
            // bound, so the guess is set to the average of some higher-layer exponents of
            // the bounds rather than the bounds itself.
            Decimal upperBound = dTen;
            if (this.gte(tetrate(10, degree, dOne, true))) {
                upperBound = this.iteratedlog(dTen, degree - 1, true);
            }
            if (degree <= 1) {
                upperBound = this.root(degree);
            }
            Decimal lower = dZero;
            double layer = upperBound.layer;
            Decimal upper = upperBound.iteratedlog(dTen, layer, true);
            Decimal previous = upper;
            Decimal guess = upper.div(2);
            boolean loopGoing = true;
            while (loopGoing) {
                guess = lower.add(upper).div(2);
                if (fromComponents_noNormalize(1, 0, 10)
                        .iteratedexp(layer, guess, true)
                        .tetrate(degree, dOne, true)
                        .gt(this)) {
                    upper = guess;
                } else {
                    lower = guess;
                }
                if (guess.eq(previous)) {
                    loopGoing = false;
                } else {
                    previous = guess;
                }
            }
            return fromComponents_noNormalize(1, 0, 10).iteratedexp(layer, guess, true);
        }
        // 0 < this < 1
        else {
            int stage = 1;
            Decimal minimum = fromComponents(1, 10, 1);
            Decimal maximum = fromComponents(1, 10, 1);
            // eeeeeeeee-10, which is effectively 0
            Decimal lower = fromComponents(1, 10, 1);
            Decimal upper = fromComponents(1, 1, -16); // ~ 1 - 1e-16
            Decimal prevspan = dZero;
            Decimal difference = fromComponents(1, 10, 1);
            Decimal upperBound = upper.pow10().recip();
            Decimal distance = dZero;
            Decimal prevPoint = upperBound;
            Decimal nextPoint = upperBound;
            boolean evenDegree = (Math.ceil(degree) % 2 == 0);
            int range = 0;
            Decimal lastValid = fromComponents(1, 10, 1);
            boolean infLoopDetector = false;
            Decimal previousUpper = dZero;
            boolean decreasingFound = false;
            while (stage < 4) {
                if (stage == 2) {
                    // The minimum has been found. If ceiling(degree) is even, there's no
                    // zero range and thus no local maximum, so end the loop here.
                    // Otherwise, begin finding the maximum.
                    if (evenDegree) {
                        break;
                    } else {
                        lower = fromComponents(1, 10, 1);
                        upper = minimum;
                        stage = 3;
                        difference = fromComponents(1, 10, 1);
                        lastValid = fromComponents(1, 10, 1);
                    }
                }
                infLoopDetector = false;
                while (upper.neq(lower)) {
                    previousUpper = upper;
                    if (upper.pow10()
                                    .recip()
                                    .tetrate(degree, dOne, true)
                                    .eq(1)
                            && upper.pow10().recip().lt(0.4)) {
                        upperBound = upper.pow10().recip();
                        prevPoint = upper.pow10().recip();
                        nextPoint = upper.pow10().recip();
                        distance = dZero;
                        range = -1;
                        if (stage == 3) {
                            lastValid = upper;
                        }
                    } else if (upper.pow10()
                                    .recip()
                                    .tetrate(degree, dOne, true)
                                    .eq(upper.pow10().recip())
                            && !evenDegree
                            && upper.pow10().recip().lt(0.4)) {
                        upperBound = upper.pow10().recip();
                        prevPoint = upper.pow10().recip();
                        nextPoint = upper.pow10().recip();
                        distance = dZero;
                        range = 0;
                    } else if (upper.pow10()
                            .recip()
                            .tetrate(degree, dOne, true)
                            .eq(upper.pow10().recip().mul(2).tetrate(degree, dOne, true))) {
                        // If the upper bound is closer to zero than the next point with a
                        // discernable tetration, so surely it's in whichever range is
                        // closest to zero?
                        upperBound = upper.pow10().recip();
                        prevPoint = dZero;
                        nextPoint = upperBound.mul(2);
                        distance = upperBound;
                        if (evenDegree) {
                            range = -1;
                        } else {
                            range = 0;
                        }
                    } else {
                        // We want to use prevspan to find the "previous point" right
                        // before the upper bound and the "next point" right after it, as
                        // that will let us approximate derivatives
                        prevspan = upper.mul(1.2e-16);
                        upperBound = upper.pow10().recip();
                        prevPoint = upper.add(prevspan).pow10().recip();
                        distance = upperBound.sub(prevPoint);
                        nextPoint = upperBound.add(distance);
                        // ...but it's of no use to us while its tetration is equal to
                        // upper's tetration, so widen the difference until it's not
                        // (we add prevspan and subtract nextspan because, since upper is
                        // log10(recip(upper bound)), the upper bound gets smaller as upper
                        // gets larger and vice versa)
                        while (prevPoint.tetrate(degree, dOne, true)
                                            .eq(upperBound.tetrate(degree, dOne, true))
                                    || nextPoint.tetrate(degree, dOne, true)
                                            .eq(upperBound.tetrate(degree, dOne, true))
                                    || prevPoint.gte(upperBound)
                                    || nextPoint.lte(upperBound)) {
                            prevspan = prevspan.mul(2);
                            prevPoint = upper.add(prevspan).pow10().recip();
                            distance = upperBound.sub(prevPoint);
                            nextPoint = upperBound.add(distance);
                        }
                        if (stage == 1
                                        && (nextPoint.tetrate(degree, dOne, true)
                                                        .gt(upperBound.tetrate(degree, dOne, true))
                                                && prevPoint.tetrate(degree, dOne, true)
                                                        .gt(upperBound.tetrate(degree, dOne, true)))
                                || stage == 3
                                        && (nextPoint.tetrate(degree, dOne, true)
                                                        .lt(upperBound.tetrate(degree, dOne, true))
                                                && prevPoint.tetrate(degree, dOne, true)
                                                        .lt(upperBound.tetrate(degree, dOne, true)))) {
                            lastValid = upper;
                        }
                        if (nextPoint.tetrate(degree, dOne, true)
                                .lt(upperBound.tetrate(degree, dOne, true))) {
                            // Derivative is negative, so we're in decreasing range
                            range = -1;
                        } else if (evenDegree) {
                            // No zero range, so we're in increasing range
                            range = 1;
                        } else if (stage == 3 && upper.gt_tolerance(minimum, 1e-8)) {
                            // We're already below the minimum, so we can't be in range 1
                            range = 0;
                        } else {
                            // Number imprecision has left the second derivative somewhat
                            // untrustworthy, so we need to expand the bounds to ensure
                            // it's correct
                            while (prevPoint.tetrate(degree, dOne, true)
                                            .eq_tolerance(
                                                    upperBound.tetrate(degree, dOne, true), 1e-8)
                                    || nextPoint.tetrate(degree, dOne, true)
                                            .eq_tolerance(
                                                    upperBound.tetrate(degree, dOne, true), 1e-8)
                                    || prevPoint.gte(upperBound)
                                    || nextPoint.lte(upperBound)) {
                                prevspan = prevspan.mul(2);
                                prevPoint = upper.add(prevspan).pow10().recip();
                                distance = upperBound.sub(prevPoint);
                                nextPoint = upperBound.add(distance);
                            }
                            if (nextPoint.tetrate(degree, dOne, true)
                                    .sub(upperBound.tetrate(degree, dOne, true))
                                    .lt(upperBound.tetrate(degree, dOne, true)
                                            .sub(prevPoint.tetrate(degree, dOne, true)))) {
                                // Second derivative is negative, so we're in zero range
                                range = 0;
                            } else {
                                // By process of elimination, we're in increasing range
                                range = 1;
                            }
                        }
                    }
                    if (range == -1) {
                        decreasingFound = true;
                    }
                    if ((stage == 1 && range == 1) || (stage == 3 && range != 0)) {
                        // The upper bound is too high
                        if (lower.eq(fromComponents(1, 10, 1))) {
                            upper = upper.mul(2);
                        } else {
                            boolean cutOff = false;
                            // Avoids infinite loops from floating point imprecision
                            if (infLoopDetector
                                    && ((range == 1 && stage == 1)
                                            || (range == -1 && stage == 3))) {
                                cutOff = true;
                            }
                            upper = upper.add(lower).div(2);
                            if (cutOff) {
                                break;
                            }
                        }
                    } else {
                        if (lower.eq(fromComponents(1, 10, 1))) {
                            // We've now found an actual lower bound
                            lower = upper;
                            upper = upper.div(2);
                        } else {
                            // The upper bound is too low, meaning last time we decreased
                            // the upper bound, we should have gone to the other half of
                            // the new range instead
                            boolean cutOff = false;
                            if (infLoopDetector
                                    && ((range == 1 && stage == 1)
                                            || (range == -1 && stage == 3))) {
                                cutOff = true;
                            }
                            lower = lower.sub(difference);
                            upper = upper.sub(difference);
                            if (cutOff) {
                                break;
                            }
                        }
                    }
                    if (lower.sub(upper).div(2).abs().gt(difference.mul(1.5))) {
                        infLoopDetector = true;
                    }
                    difference = lower.sub(upper).div(2).abs();
                    if (upper.gt(fromString("1e18"))) {
                        break;
                    }
                    if (upper.eq(previousUpper)) {
                        break; // Another infinite loop catcher
                    }
                }
                if (upper.gt(fromString("1e18"))) {
                    break;
                }
                if (!decreasingFound) {
                    break; // If there's no decreasing range, then even if an error caused
                    // lastValid to gain a value, the minimum can't exist
                }
                // JS-QUIRK(index.ts:4254): the original writes `lastValid == FC(1, 10, 1)`,
                // which is reference equality between two distinct objects and therefore
                // never true. Replicated as an explicit no-op so behavior matches.
                if (stage == 1) {
                    minimum = lastValid;
                } else if (stage == 3) {
                    maximum = lastValid;
                }
                stage++;
            }
            // Now we have the minimum and maximum, so it's time to calculate the actual
            // super-root. First, check if the root is in the increasing range.
            lower = minimum;
            upper = fromComponents(1, 1, -18);
            Decimal previous = upper;
            Decimal guess = dZero;
            boolean loopGoing = true;
            while (loopGoing) {
                if (lower.eq(fromComponents(1, 10, 1))) {
                    guess = upper.mul(2);
                } else {
                    guess = lower.add(upper).div(2);
                }
                if (pow(10, guess).recip().tetrate(degree, dOne, true).gt(this)) {
                    upper = guess;
                } else {
                    lower = guess;
                }
                if (guess.eq(previous)) {
                    loopGoing = false;
                } else {
                    previous = guess;
                }
                if (upper.gt(fromString("1e18"))) {
                    return dNaN;
                }
            }
            // using guess.neq(minimum) led to imprecision errors, so here's a fixed version
            if (!(guess.eq_tolerance(minimum, 1e-15))) {
                return guess.pow10().recip();
            } else {
                // If guess == minimum, we haven't actually found the super-root, the
                // algorithm just kept going down trying to find a super-root that's not in
                // the increasing range. Check if the root is in the zero range.
                if (maximum.eq(fromComponents(1, 10, 1))) {
                    // There is no zero range, so the super root doesn't exist
                    return dNaN;
                }
                lower = fromComponents(1, 10, 1);
                upper = maximum;
                previous = upper;
                guess = dZero;
                loopGoing = true;
                while (loopGoing) {
                    if (lower.eq(fromComponents(1, 10, 1))) {
                        guess = upper.mul(2);
                    } else {
                        guess = lower.add(upper).div(2);
                    }
                    if (pow(10, guess).recip().tetrate(degree, dOne, true).gt(this)) {
                        upper = guess;
                    } else {
                        lower = guess;
                    }
                    if (guess.eq(previous)) {
                        loopGoing = false;
                    } else {
                        previous = guess;
                    }
                    if (upper.gt(fromString("1e18"))) {
                        return dNaN;
                    }
                }
                return guess.pow10().recip();
            }
        }
    }

    // --- increasingInverse (index.ts:4326-4568) ---

    /**
     * Takes a Decimal => Decimal function and returns a Decimal => Decimal function
     * that's an inverse of the first one, using binary search to find its target.
     *
     * <p>Only intended for continuous, strictly increasing (or with {@code decreasing},
     * strictly decreasing) functions. If the input is out of range, NaN is returned.
     */
    public static Function<Decimal, Decimal> increasingInverse(
            Function<Decimal, Decimal> func,
            boolean decreasing,
            int iterations,
            Decimal minX,
            Decimal maxX,
            Decimal minY,
            Decimal maxY) {
        return (valueSource) -> {
            Decimal value = valueSource;

            if (value.isNan()
                    || maxX.lt(minX)
                    || value.lt(minY)
                    || value.gt(maxY)) {
                return dNaN;
            }

            // Before actually doing the search, let's determine what range we're looking
            // at.
            boolean currentCheck = true; // Checking whether the inverse is positive
            if (maxX.lt(0)) {
                currentCheck = false;
            } else if (minX.gt(0)) {
                currentCheck = true;
            } else {
                Decimal valCheck = func.apply(dZero);
                if (valCheck.eq(value)) {
                    return fromComponents_noNormalize(0, 0, 0);
                }
                currentCheck = value.gt(valCheck);
                if (decreasing) {
                    currentCheck = !currentCheck;
                }
            }
            boolean positive = currentCheck;
            boolean reciprocal;
            Function<Double, Decimal> rangeApply;
            if (currentCheck) {
                // Checking whether the inverse is below 1/9e15
                if (maxX.lt(FIRST_NEG_LAYER)) {
                    currentCheck = true;
                } else if (minX.gt(FIRST_NEG_LAYER)) {
                    currentCheck = false;
                } else {
                    Decimal valCheck = func.apply(fromNumber(FIRST_NEG_LAYER));
                    currentCheck = value.lt(valCheck);
                    if (decreasing) {
                        currentCheck = !currentCheck;
                    }
                }
                if (currentCheck) {
                    reciprocal = true;
                    // Checking whether the inverse is above 1/e9e15
                    Decimal limit = Math.pow(10, EXP_LIMIT) == 0
                            ? dZero
                            : fromNumber(1 / StrictMath.pow(10, EXP_LIMIT));
                    if (maxX.lt(limit)) {
                        currentCheck = false;
                    } else if (minX.gt(limit)) {
                        currentCheck = true;
                    } else {
                        Decimal valCheck = func.apply(limit);
                        currentCheck = value.gt(valCheck);
                        if (decreasing) {
                            currentCheck = !currentCheck;
                        }
                    }
                    // If the inverse is between 1/e9e15 and 1/9e15, search through
                    // layer -1 numbers.
                    if (currentCheck) {
                        rangeApply = (x) -> fromNumber(Math.pow(10, x)).recip();
                    } else {
                        // Checking whether the inverse is above 1/10^^9e15
                        Decimal limit2 = tetrate(10, EXP_LIMIT, dOne, false);
                        if (maxX.lt(limit2)) {
                            currentCheck = false;
                        } else if (minX.gt(limit2)) {
                            currentCheck = true;
                        } else {
                            Decimal valCheck = func.apply(limit2);
                            currentCheck = value.gt(valCheck);
                            if (decreasing) {
                                currentCheck = !currentCheck;
                            }
                        }
                        if (currentCheck) {
                            rangeApply = (x) -> tetrate(10, x, dOne, false).recip();
                        } else {
                            rangeApply =
                                    (x) ->
                                            (x > StrictMath.log10(CriticalTables.MAX_VALUE)
                                                    ? dZero
                                                    : tetrate(
                                                                    10,
                                                                    Math.pow(10, x),
                                                                    dOne,
                                                                    false)
                                                            .recip());
                        }
                    }
                } else {
                    reciprocal = false;
                    // Checking whether the inverse is below 9e15
                    if (maxX.lt(EXP_LIMIT)) {
                        currentCheck = true;
                    } else if (minX.gt(EXP_LIMIT)) {
                        currentCheck = false;
                    } else {
                        Decimal valCheck = func.apply(fromNumber(EXP_LIMIT));
                        currentCheck = value.lt(valCheck);
                        if (decreasing) {
                            currentCheck = !currentCheck;
                        }
                    }
                    // If the inverse is between 1/9e15 and 9e15, search through direct
                    // (layer 0) numbers.
                    if (currentCheck) {
                        rangeApply = (x) -> fromNumber(x);
                    } else {
                        // Checking whether the inverse is below e9e15
                        Decimal limit = fromNumber(Math.pow(10, EXP_LIMIT));
                        if (maxX.lt(limit)) {
                            currentCheck = true;
                        } else if (minX.gt(limit)) {
                            currentCheck = false;
                        } else {
                            Decimal valCheck = func.apply(limit);
                            currentCheck = value.lt(valCheck);
                            if (decreasing) {
                                currentCheck = !currentCheck;
                            }
                        }
                        // If the inverse is between 9e15 and e9e15, search through layer 1
                        // numbers.
                        if (currentCheck) {
                            rangeApply = (x) -> fromNumber(Math.pow(10, x));
                        } else {
                            // Checking whether the inverse is below 10^^9e15
                            Decimal limit2 = tetrate(10, EXP_LIMIT, dOne, false);
                            if (maxX.lt(limit2)) {
                                currentCheck = true;
                            } else if (minX.gt(limit2)) {
                                currentCheck = false;
                            } else {
                                Decimal valCheck = func.apply(limit2);
                                currentCheck = value.lt(valCheck);
                                if (decreasing) {
                                    currentCheck = !currentCheck;
                                }
                            }
                            // If the inverse is between e9e15 and 10^^9e15, search through
                            // layers themselves.
                            if (currentCheck) {
                                rangeApply = (x) -> tetrate(10, x, dOne, false);
                            } else {
                                rangeApply =
                                        (x) ->
                                                (x > StrictMath.log10(CriticalTables.MAX_VALUE)
                                                        ? dInf
                                                        : tetrate(
                                                                10,
                                                                Math.pow(10, x),
                                                                dOne,
                                                                false));
                            }
                        }
                    }
                }
            } else {
                reciprocal = true;
                // Checking whether the inverse is above -1/9e15
                if (maxX.lt(-FIRST_NEG_LAYER)) {
                    currentCheck = false;
                } else if (minX.gt(-FIRST_NEG_LAYER)) {
                    currentCheck = true;
                } else {
                    Decimal valCheck = func.apply(fromNumber(-FIRST_NEG_LAYER));
                    currentCheck = value.gt(valCheck);
                    if (decreasing) {
                        currentCheck = !currentCheck;
                    }
                }
                if (currentCheck) {
                    // Checking whether the inverse is below -1/e9e15
                    Decimal limit = fromNumber(1 / StrictMath.pow(10, EXP_LIMIT)).neg();
                    if (maxX.lt(limit)) {
                        currentCheck = true;
                    } else if (minX.gt(limit)) {
                        currentCheck = false;
                    } else {
                        Decimal valCheck = func.apply(limit);
                        currentCheck = value.lt(valCheck);
                        if (decreasing) {
                            currentCheck = !currentCheck;
                        }
                    }
                    // If the inverse is between -1/e9e15 and -1/9e15, search through
                    // negatives of layer -1 numbers.
                    if (currentCheck) {
                        rangeApply = (x) -> fromNumber(Math.pow(10, x)).recip().neg();
                    } else {
                        // Checking whether the inverse is below -1/10^^9e15
                        Decimal limit2 = tetrate(10, EXP_LIMIT, dOne, false).neg();
                        if (maxX.lt(limit2)) {
                            currentCheck = true;
                        } else if (minX.gt(limit2)) {
                            currentCheck = false;
                        } else {
                            Decimal valCheck = func.apply(limit2);
                            currentCheck = value.lt(valCheck);
                            if (decreasing) {
                                currentCheck = !currentCheck;
                            }
                        }
                        // If the inverse is between -1/10^^9e15 and -1/e9e15, search
                        // through reciprocals of negative layers themselves.
                        if (currentCheck) {
                            rangeApply = (x) -> tetrate(10, x, dOne, false).recip().neg();
                        } else {
                            rangeApply =
                                    (x) ->
                                            (x > StrictMath.log10(CriticalTables.MAX_VALUE)
                                                    ? dZero
                                                    : tetrate(
                                                                    10,
                                                                    Math.pow(10, x),
                                                                    dOne,
                                                                    false)
                                                            .recip()
                                                            .neg());
                        }
                    }
                } else {
                    reciprocal = false;
                    // Checking whether the inverse is above -9e15
                    if (maxX.lt(-EXP_LIMIT)) {
                        currentCheck = false;
                    } else if (minX.gt(-EXP_LIMIT)) {
                        currentCheck = true;
                    } else {
                        Decimal valCheck = func.apply(fromNumber(-EXP_LIMIT));
                        currentCheck = value.gt(valCheck);
                        if (decreasing) {
                            currentCheck = !currentCheck;
                        }
                    }
                    // If the inverse is between -1/9e15 and -9e15, search through
                    // negatives of direct (layer 0) numbers.
                    if (currentCheck) {
                        rangeApply = (x) -> fromNumber(-x);
                    } else {
                        // Checking whether the inverse is above -e9e15
                        Decimal limit = fromNumber(Math.pow(10, EXP_LIMIT)).neg();
                        if (maxX.lt(limit)) {
                            currentCheck = false;
                        } else if (minX.gt(limit)) {
                            currentCheck = true;
                        } else {
                            Decimal valCheck = func.apply(limit);
                            currentCheck = value.gt(valCheck);
                            if (decreasing) {
                                currentCheck = !currentCheck;
                            }
                        }
                        // If the inverse is between -9e15 and -e9e15, search through
                        // negatives of layer 1 numbers.
                        if (currentCheck) {
                            rangeApply = (x) -> fromNumber(Math.pow(10, x)).neg();
                        } else {
                            // Checking whether the inverse is above -10^^9e15
                            Decimal limit2 = tetrate(10, EXP_LIMIT, dOne, false).neg();
                            if (maxX.lt(limit2)) {
                                currentCheck = false;
                            } else if (minX.gt(limit2)) {
                                currentCheck = true;
                            } else {
                                Decimal valCheck = func.apply(limit2);
                                currentCheck = value.gt(valCheck);
                                if (decreasing) {
                                    currentCheck = !currentCheck;
                                }
                            }
                            // If the inverse is between e9e15 and 10^^9e15, search through
                            // negatives of layers themselves.
                            if (currentCheck) {
                                rangeApply = (x) -> tetrate(10, x, dOne, false).neg();
                            } else {
                                rangeApply =
                                        (x) ->
                                                (x > StrictMath.log10(CriticalTables.MAX_VALUE)
                                                        ? dNegInf
                                                        : tetrate(
                                                                        10,
                                                                        Math.pow(10, x),
                                                                        dOne,
                                                                        false)
                                                                .neg());
                            }
                        }
                    }
                }
            }
            boolean searchIncreasing = ((positive != reciprocal) != decreasing);
            DecimalBinaryPredicate comparative =
                    searchIncreasing
                            ? (a, b) -> gt(a, b)
                            : (a, b) -> lt(a, b);

            double step_size = 0.001;
            boolean has_changed_directions_once = false;
            boolean previously_rose = false;
            double result = 1;
            Decimal appliedResult = dOne;
            double oldresult = 0;
            boolean critical = false;
            for (int i = 1; i < iterations; ++i) {
                critical = false;
                oldresult = result;
                appliedResult = rangeApply.apply(result);
                // Under no circumstances should we be calling func on something outside its domain.
                if (appliedResult.gt(maxX)) {
                    appliedResult = maxX;
                    critical = true;
                }
                if (appliedResult.lt(minX)) {
                    appliedResult = minX;
                    critical = true;
                }
                Decimal new_decimal = func.apply(appliedResult);
                if (new_decimal.eq(value) && !critical) {
                    break;
                }
                boolean currently_rose = comparative.test(new_decimal, value);
                if (i > 1) {
                    if (previously_rose != currently_rose) {
                        has_changed_directions_once = true;
                    }
                }
                previously_rose = currently_rose;
                if (has_changed_directions_once) {
                    step_size /= 2;
                } else {
                    step_size *= 2;
                }
                // If the inverse is trying to increase when it's already at maxX, or it's
                // trying to decrease when it's already at minX, it's going outside the
                // domain, so return NaN.
                if ((currently_rose != searchIncreasing && appliedResult.eq(maxX))
                        || (currently_rose == searchIncreasing && appliedResult.eq(minX))) {
                    return dNaN;
                }
                step_size = Math.abs(step_size) * (currently_rose ? -1 : 1);
                result += step_size;
                if (step_size == 0 || oldresult == result) {
                    break;
                }
            }
            return rangeApply.apply(result);
        };
    }

    /** A two-argument Decimal predicate, for the inverse search's comparison direction. */
    private interface DecimalBinaryPredicate {
        boolean test(Decimal a, Decimal b);
    }

    /** {@link #increasingInverse} with the original's defaults. */
    public static Function<Decimal, Decimal> increasingInverse(
            Function<Decimal, Decimal> func, boolean decreasing) {
        return increasingInverse(
                func,
                decreasing,
                120,
                dLayerMax.neg(),
                dLayerMax,
                dLayerMax.neg(),
                dLayerMax);
    }

    /** {@link #increasingInverse} with the original's defaults. */
    public static Function<Decimal, Decimal> increasingInverse(Function<Decimal, Decimal> func) {
        return increasingInverse(func, false);
    }

    // --- pentation (index.ts:4581-4750) ---

    /**
     * Pentation/pentate: The result of tetrating 'height' times in a row. An absurdly
     * strong operator - pentate(2, 4.28) and pentate(10, 2.37) are already too huge for
     * this library! https://en.wikipedia.org/wiki/Pentation
     *
     * <p>For non-whole pentation heights, the linear approximation of pentation is always
     * used, as there is no defined analytic approximation of pentation.
     */
    public Decimal pentate(double height, Decimal payload, boolean linear) {
        double oldheight = height;
        height = Math.floor(height);
        double fracheight = oldheight - height;
        Decimal prevpayload = dZero;
        Decimal prevtwopayload = dZero;

        // Linear approximation. It is monotonic and continuous.
        if (fracheight != 0) {
            if (payload.eq(dOne)) {
                ++height;
                payload = fromNumber(fracheight);
            } else {
                // Despite calling penta_log, this is safe, as penta_log only calls
                // pentation with payload 1.
                return this.pentate(
                        payload.penta_log(this, 100, linear).plus(height).toNumber(), dOne, linear);
            }
        }

        if (height > 0) {
            for (int i = 0; i < height; ) {
                prevtwopayload = prevpayload;
                prevpayload = payload;
                payload = this.tetrate(payload.toNumber(), dOne, linear);
                ++i;
                // Under the linear approximation of pentation, if p is between 0 and 1,
                // x^^^p == x^^p (which just equals x^p if tetration is also using the
                // linear approximation) ...and once both the base and payload are between
                // 0 and 1, they'll both stay that way.
                if (this.gt(0)
                        && this.lte(1)
                        && payload.gt(0)
                        && payload.lte(1)) {
                    return this.tetrate(height - i, payload, linear);
                }
                // End early if it's settled on a limit. Bases close to 0 alternate
                // between two possible values.
                if (payload.eq(prevpayload)
                        || (payload.eq(prevtwopayload) && (i % 2 == height % 2))) {
                    return payload.normalized();
                }
                // bail if we're NaN
                if (!Double.isFinite(payload.layer) || !Double.isFinite(payload.mag)) {
                    return payload.normalized();
                }
                // give up after 10000 iterations if nothing is happening
                if (i > 10000) {
                    return payload;
                }
            }
        } else {
            // Repeated slog is sloooow, but that's what negative pentation height means.
            for (int i = 0; i < -height; ++i) {
                prevpayload = payload;
                payload = payload.slog(this, 100, linear);
                // End early if it's settled on a limit
                if (payload.eq(prevpayload)) {
                    return payload.normalized();
                }
                // bail if we're NaN
                if (!Double.isFinite(payload.layer) || !Double.isFinite(payload.mag)) {
                    return payload.normalized();
                }
                // give up after 100 iterations if nothing is happening
                if (i > 100) {
                    return payload;
                }
            }
        }

        return payload;
    }

    /** {@link #pentate(double, Decimal, boolean)} with payload 1. */
    public Decimal pentate(double height, Decimal payload) {
        return this.pentate(height, payload, false);
    }

    /** {@link #pentate(double, Decimal, boolean)} with the default payload of 1. */
    public Decimal pentate(double height) {
        return this.pentate(height, dOne, false);
    }

    /**
     * Penta-logarithm, one of pentation's inverses, tells you what height you'd have to
     * pentate 'base' to to get 'this'. Grows incredibly slowly.
     *
     * <p>INCREDIBLY slow on numbers <= -1. Probably don't call it on those.
     */
    public Decimal penta_log(Decimal base, int iterations, boolean linear) {
        // Bases below 1 oscillate, so the logarithm doesn't make sense
        if (base.lte(1)) {
            return dNaN;
        }
        if (this.eq(1)) {
            return dZero;
        }
        if (this.eq(dInf)) {
            return dInf;
        }
        Decimal value = fromNumber(1);
        double result = 0;
        double step_size = 1;

        // There's some x between -1 and -2, depending on the base, where base^^x == x.
        // This x is base^^^(-Infinity), and we shouldn't bother with numbers less than
        // that limit.
        if (this.lt(-1)) {
            if (this.lte(-2)) {
                return dNaN;
            }
            Decimal limitcheck = base.tetrate(this.toNumber(), dOne, linear);
            if (this.eq(limitcheck)) {
                return dNegInf;
            }
            if (this.gt(limitcheck)) {
                return dNaN;
            }
        }

        // pentate runs through each tetration iteration anyway, so while we're narrowing
        // down on the nearest integer it's faster to just check them one-by-one than to
        // run through pentate every time
        if (this.gt(1)) {
            while (value.lt(this)) {
                result++;
                value = base.tetrate(value.toNumber(), dOne, linear);
                if (result > 1000) {
                    // Probably reached a limit by this point.
                    return dNaN;
                }
            }
        } else {
            while (value.gt(this)) {
                result--;
                value = slog(value, base, linear);
                if (result > 100) {
                    // Probably reached the limit by this point.
                    return dNaN;
                }
            }
        }
        for (int i = 1; i < iterations; ++i) {
            Decimal new_decimal = base.pentate(result, dOne, linear);
            if (new_decimal.eq(this)) {
                break;
            }
            boolean currently_rose = new_decimal.gt(this);
            step_size = Math.abs(step_size) * (currently_rose ? -1 : 1);
            result += step_size;
            step_size /= 2;
            if (step_size == 0) {
                break;
            }
        }
        return fromNumber(result);
    }

    /** {@link #penta_log(Decimal, int, boolean)} with the default base of 10 and 100 iterations. */
    public Decimal penta_log(Decimal base, int iterations) {
        return this.penta_log(base, iterations, false);
    }

    /** {@link #penta_log(Decimal, int, boolean)} with the default 100 iterations. */
    public Decimal penta_log(Decimal base) {
        return this.penta_log(base, 100, false);
    }

    /** {@link #penta_log(Decimal, int, boolean)} with the default base of 10. */
    public Decimal penta_log() {
        return this.penta_log(dTen, 100, false);
    }

    /**
     * Penta-root, one of pentation's inverses - what number, pentated to height 'degree',
     * equals 'this'?
     *
     * <p>Only works with the linear approximation of tetration, as starting with analytic
     * and then switching to linear would result in inconsistent behavior for super-roots.
     */
    public Decimal linear_penta_root(double degree) {
        // 1st-degree super root just returns its input
        if (degree == 1) {
            return this;
        }
        if (degree < 0) {
            return dNaN;
        }
        if (this.eq(dInf)) {
            return dInf;
        }
        if (!this.isFinite()) {
            return dNaN;
        }
        // Using linear approximation, x^^^n = x^n if 0 < n < 1
        if (degree > 0 && degree < 1) {
            return this.root(degree);
        }
        // Special case: any super-root of 1 is 1
        if (this.eq(1)) {
            return fromComponents_noNormalize(1, 0, 1);
        }
        if (this.lt(0)) {
            return dNaN;
        }
        // If 'this' is below 1, then the result is going to be below 1, meaning the
        // original number was a tetration tower where all entries are below 1... and in
        // the linear approximation, tetration with a height between 0 and 1 reduces to
        // exponentiation of that height. Therefore, penta_root and sroot will return the
        // same value.
        if (this.lt(1)) {
            return this.linear_sroot(degree);
        }

        // Unlike with super-root, there's no good upper bound here, so call increasingInverse.
        return decreasingInverseApply(
                increasingInverse(
                        (Decimal value) -> value.pentate(degree, dOne, true),
                        false,
                        120,
                        dLayerMax.neg(),
                        dLayerMax,
                        dLayerMax.neg(),
                        dLayerMax),
                this);
    }

    private static Decimal decreasingInverseApply(Function<Decimal, Decimal> inverse, Decimal value) {
        return inverse.apply(value);
    }

    // --- misc (index.ts:4877-4911) ---

    /** Joke function from Realm Grinder. */
    public Decimal ascensionPenalty(Decimal ascensions) {
        if (ascensions.eq(dZero)) {
            return this;
        }
        return this.root(fromNumber(10).pow(ascensions));
    }

    /** {@link #ascensionPenalty(Decimal)} for a double. */
    public Decimal ascensionPenalty(double ascensions) {
        return this.ascensionPenalty(fromNumber(ascensions));
    }

    /** Joke function from Cookie Clicker. It's 'egg'. */
    public Decimal egg() {
        return this.add(9);
    }

    /**
     * Alias of {@link #lte(Decimal)}, except that the original implements this one as
     * {@code cmp(other) < 1} rather than {@code !gt(other)} - so unlike {@code lte}, it
     * returns false when either operand is NaN. JS-QUIRK(index.ts:4895), replicated.
     */
    public boolean lessThanOrEqualTo(Decimal other) {
        return this.cmpJs(other) < 1;
    }

    /** Alias of {@link #lt(Decimal)} (implemented as {@code cmp(other) < 0}, same result). */
    public boolean lessThan(Decimal other) {
        return this.cmpJs(other) < 0;
    }

    /** Alias of {@link #gte(Decimal)}; false for NaN, unlike {@code gte}. See {@link #lessThanOrEqualTo}. */
    public boolean greaterThanOrEqualTo(Decimal other) {
        return this.cmpJs(other) > -1;
    }

    /** Alias of {@link #gt(Decimal)} (implemented as {@code cmp(other) > 0}, same result). */
    public boolean greaterThan(Decimal other) {
        return this.cmpJs(other) > 0;
    }

    private static final double _EXPN1 = 0.36787944117144232159553; // exp(-1)

    // --- static wrappers for logs, powers, gamma and trig (index.ts:1091-1461) ---

    /** See {@link #pLog10()}. */
    public static Decimal pLog10(Decimal value) {
        return value.pLog10();
    }

    /** {@link #pLog10(Decimal)} for a double. */
    public static Decimal pLog10(double value) {
        return fromNumber(value).pLog10();
    }

    /** {@link #pLog10(Decimal)} for a string. */
    public static Decimal pLog10(String value) {
        return fromString(value).pLog10();
    }

    /** See {@link #absLog10()}. */
    public static Decimal absLog10(Decimal value) {
        return value.absLog10();
    }

    /** {@link #absLog10(Decimal)} for a double. */
    public static Decimal absLog10(double value) {
        return fromNumber(value).absLog10();
    }

    /** {@link #absLog10(Decimal)} for a string. */
    public static Decimal absLog10(String value) {
        return fromString(value).absLog10();
    }

    /** See {@link #log10()}. */
    public static Decimal log10(Decimal value) {
        return value.log10();
    }

    /** {@link #log10(Decimal)} for a double. */
    public static Decimal log10(double value) {
        return fromNumber(value).log10();
    }

    /** {@link #log10(Decimal)} for a string. */
    public static Decimal log10(String value) {
        return fromString(value).log10();
    }

    /** See {@link #log(Decimal)}. */
    public static Decimal log(Decimal value, Decimal base) {
        return value.log(base);
    }

    /** {@link #log(Decimal, Decimal)} with a double base. */
    public static Decimal log(Decimal value, double base) {
        return value.log(base);
    }

    /** {@link #log(Decimal, Decimal)} with a double value. */
    public static Decimal log(double value, Decimal base) {
        return fromNumber(value).log(base);
    }

    /** {@link #log(Decimal, Decimal)} for doubles. */
    public static Decimal log(double value, double base) {
        return fromNumber(value).log(base);
    }

    /** Alias of {@link #log(Decimal, Decimal)}. */
    public static Decimal logarithm(Decimal value, Decimal base) {
        return value.log(base);
    }

    /** Alias of {@link #log(Decimal, Decimal)}. */
    public static Decimal logarithm(double value, double base) {
        return fromNumber(value).log(base);
    }

    /** See {@link #log2()}. */
    public static Decimal log2(Decimal value) {
        return value.log2();
    }

    /** {@link #log2(Decimal)} for a double. */
    public static Decimal log2(double value) {
        return fromNumber(value).log2();
    }

    /** {@link #log2(Decimal)} for a string. */
    public static Decimal log2(String value) {
        return fromString(value).log2();
    }

    /** See {@link #ln()}. */
    public static Decimal ln(Decimal value) {
        return value.ln();
    }

    /** {@link #ln(Decimal)} for a double. */
    public static Decimal ln(double value) {
        return fromNumber(value).ln();
    }

    /** {@link #ln(Decimal)} for a string. */
    public static Decimal ln(String value) {
        return fromString(value).ln();
    }

    /** See {@link #pow(Decimal)}. */
    public static Decimal pow(Decimal value, Decimal other) {
        return value.pow(other);
    }

    /** {@link #pow(Decimal, Decimal)} with a double exponent. */
    public static Decimal pow(Decimal value, double other) {
        return value.pow(other);
    }

    /** {@link #pow(Decimal, Decimal)} with a double base. */
    public static Decimal pow(double value, Decimal other) {
        return fromNumber(value).pow(other);
    }

    /** {@link #pow(Decimal, Decimal)} for doubles. */
    public static Decimal pow(double value, double other) {
        return fromNumber(value).pow(other);
    }

    /** {@link #pow(Decimal, Decimal)} with a string exponent. */
    public static Decimal pow(Decimal value, String other) {
        return value.pow(other);
    }

    /** {@link #pow(Decimal, Decimal)} with a string base. */
    public static Decimal pow(String value, Decimal other) {
        return fromString(value).pow(other);
    }

    /** See {@link #pow10()}. */
    public static Decimal pow10(Decimal value) {
        return value.pow10();
    }

    /** {@link #pow10(Decimal)} for a double. */
    public static Decimal pow10(double value) {
        return fromNumber(value).pow10();
    }

    /** {@link #pow10(Decimal)} for a string. */
    public static Decimal pow10(String value) {
        return fromString(value).pow10();
    }

    /** See {@link #root(Decimal)}. */
    public static Decimal root(Decimal value, Decimal other) {
        return value.root(other);
    }

    /** {@link #root(Decimal, Decimal)} for doubles. */
    public static Decimal root(double value, double other) {
        return fromNumber(value).root(other);
    }

    /** See {@link #factorial()}. */
    public static Decimal factorial(Decimal value) {
        return value.factorial();
    }

    /** {@link #factorial(Decimal)} for a double. */
    public static Decimal factorial(double value) {
        return fromNumber(value).factorial();
    }

    /** {@link #factorial(Decimal)} for a string. */
    public static Decimal factorial(String value) {
        return fromString(value).factorial();
    }

    /** See {@link #gamma()}. */
    public static Decimal gamma(Decimal value) {
        return value.gamma();
    }

    /** {@link #gamma(Decimal)} for a double. */
    public static Decimal gamma(double value) {
        return fromNumber(value).gamma();
    }

    /** {@link #gamma(Decimal)} for a string. */
    public static Decimal gamma(String value) {
        return fromString(value).gamma();
    }

    /** See {@link #lngamma()}. */
    public static Decimal lngamma(Decimal value) {
        return value.lngamma();
    }

    /** {@link #lngamma(Decimal)} for a double. */
    public static Decimal lngamma(double value) {
        return fromNumber(value).lngamma();
    }

    /** {@link #lngamma(Decimal)} for a string. */
    public static Decimal lngamma(String value) {
        return fromString(value).lngamma();
    }

    /** See {@link #exp()}. */
    public static Decimal exp(Decimal value) {
        return value.exp();
    }

    /** {@link #exp(Decimal)} for a double. */
    public static Decimal exp(double value) {
        return fromNumber(value).exp();
    }

    /** {@link #exp(Decimal)} for a string. */
    public static Decimal exp(String value) {
        return fromString(value).exp();
    }

    /** See {@link #sqr()}. */
    public static Decimal sqr(Decimal value) {
        return value.sqr();
    }

    /** {@link #sqr(Decimal)} for a double. */
    public static Decimal sqr(double value) {
        return fromNumber(value).sqr();
    }

    /** {@link #sqr(Decimal)} for a string. */
    public static Decimal sqr(String value) {
        return fromString(value).sqr();
    }

    /** See {@link #sqrt()}. */
    public static Decimal sqrt(Decimal value) {
        return value.sqrt();
    }

    /** {@link #sqrt(Decimal)} for a double. */
    public static Decimal sqrt(double value) {
        return fromNumber(value).sqrt();
    }

    /** {@link #sqrt(Decimal)} for a string. */
    public static Decimal sqrt(String value) {
        return fromString(value).sqrt();
    }

    /** See {@link #cube()}. */
    public static Decimal cube(Decimal value) {
        return value.cube();
    }

    /** {@link #cube(Decimal)} for a double. */
    public static Decimal cube(double value) {
        return fromNumber(value).cube();
    }

    /** {@link #cube(Decimal)} for a string. */
    public static Decimal cube(String value) {
        return fromString(value).cube();
    }

    /** See {@link #cbrt()}. */
    public static Decimal cbrt(Decimal value) {
        return value.cbrt();
    }

    /** {@link #cbrt(Decimal)} for a double. */
    public static Decimal cbrt(double value) {
        return fromNumber(value).cbrt();
    }

    /** {@link #cbrt(Decimal)} for a string. */
    public static Decimal cbrt(String value) {
        return fromString(value).cbrt();
    }

    /** See {@link #sin()}. */
    public static Decimal sin(Decimal value) {
        return value.sin();
    }

    /** {@link #sin(Decimal)} for a double. */
    public static Decimal sin(double value) {
        return fromNumber(value).sin();
    }

    /** {@link #sin(Decimal)} for a string. */
    public static Decimal sin(String value) {
        return fromString(value).sin();
    }

    /** See {@link #cos()}. */
    public static Decimal cos(Decimal value) {
        return value.cos();
    }

    /** {@link #cos(Decimal)} for a double. */
    public static Decimal cos(double value) {
        return fromNumber(value).cos();
    }

    /** {@link #cos(Decimal)} for a string. */
    public static Decimal cos(String value) {
        return fromString(value).cos();
    }

    /** See {@link #tan()}. */
    public static Decimal tan(Decimal value) {
        return value.tan();
    }

    /** {@link #tan(Decimal)} for a double. */
    public static Decimal tan(double value) {
        return fromNumber(value).tan();
    }

    /** {@link #tan(Decimal)} for a string. */
    public static Decimal tan(String value) {
        return fromString(value).tan();
    }

    /** See {@link #asin()}. */
    public static Decimal asin(Decimal value) {
        return value.asin();
    }

    /** {@link #asin(Decimal)} for a double. */
    public static Decimal asin(double value) {
        return fromNumber(value).asin();
    }

    /** {@link #asin(Decimal)} for a string. */
    public static Decimal asin(String value) {
        return fromString(value).asin();
    }

    /** See {@link #acos()}. */
    public static Decimal acos(Decimal value) {
        return value.acos();
    }

    /** {@link #acos(Decimal)} for a double. */
    public static Decimal acos(double value) {
        return fromNumber(value).acos();
    }

    /** {@link #acos(Decimal)} for a string. */
    public static Decimal acos(String value) {
        return fromString(value).acos();
    }

    /** See {@link #atan()}. */
    public static Decimal atan(Decimal value) {
        return value.atan();
    }

    /** {@link #atan(Decimal)} for a double. */
    public static Decimal atan(double value) {
        return fromNumber(value).atan();
    }

    /** {@link #atan(Decimal)} for a string. */
    public static Decimal atan(String value) {
        return fromString(value).atan();
    }

    /** See {@link #sinh()}. */
    public static Decimal sinh(Decimal value) {
        return value.sinh();
    }

    /** {@link #sinh(Decimal)} for a double. */
    public static Decimal sinh(double value) {
        return fromNumber(value).sinh();
    }

    /** {@link #sinh(Decimal)} for a string. */
    public static Decimal sinh(String value) {
        return fromString(value).sinh();
    }

    /** See {@link #cosh()}. */
    public static Decimal cosh(Decimal value) {
        return value.cosh();
    }

    /** {@link #cosh(Decimal)} for a double. */
    public static Decimal cosh(double value) {
        return fromNumber(value).cosh();
    }

    /** {@link #cosh(Decimal)} for a string. */
    public static Decimal cosh(String value) {
        return fromString(value).cosh();
    }

    /** See {@link #tanh()}. */
    public static Decimal tanh(Decimal value) {
        return value.tanh();
    }

    /** {@link #tanh(Decimal)} for a double. */
    public static Decimal tanh(double value) {
        return fromNumber(value).tanh();
    }

    /** {@link #tanh(Decimal)} for a string. */
    public static Decimal tanh(String value) {
        return fromString(value).tanh();
    }

    /** See {@link #asinh()}. */
    public static Decimal asinh(Decimal value) {
        return value.asinh();
    }

    /** {@link #asinh(Decimal)} for a double. */
    public static Decimal asinh(double value) {
        return fromNumber(value).asinh();
    }

    /** {@link #asinh(Decimal)} for a string. */
    public static Decimal asinh(String value) {
        return fromString(value).asinh();
    }

    /** See {@link #acosh()}. */
    public static Decimal acosh(Decimal value) {
        return value.acosh();
    }

    /** {@link #acosh(Decimal)} for a double. */
    public static Decimal acosh(double value) {
        return fromNumber(value).acosh();
    }

    /** {@link #acosh(Decimal)} for a string. */
    public static Decimal acosh(String value) {
        return fromString(value).acosh();
    }

    /** See {@link #atanh()}. */
    public static Decimal atanh(Decimal value) {
        return value.atanh();
    }

    /** {@link #atanh(Decimal)} for a double. */
    public static Decimal atanh(double value) {
        return fromNumber(value).atanh();
    }

    /** {@link #atanh(Decimal)} for a string. */
    public static Decimal atanh(String value) {
        return fromString(value).atanh();
    }

    // --- static wrappers for hyperoperators (index.ts:1230-1383) ---

    /** See {@link #tetrate(double, Decimal, boolean)}. */
    public static Decimal tetrate(Decimal value, double height, Decimal payload, boolean linear) {
        return value.tetrate(height, payload, linear);
    }

    /** {@link #tetrate(Decimal, double, Decimal, boolean)} with payload 1. */
    public static Decimal tetrate(Decimal value, double height, Decimal payload) {
        return value.tetrate(height, payload, false);
    }

    /** {@link #tetrate(Decimal, double, Decimal, boolean)} with the default payload of 1. */
    public static Decimal tetrate(Decimal value, double height) {
        return value.tetrate(height, dOne, false);
    }

    /** {@link #tetrate(Decimal, double, Decimal, boolean)} for doubles. */
    public static Decimal tetrate(double value, double height) {
        return fromNumber(value).tetrate(height, dOne, false);
    }

    /** {@link #tetrate(Decimal, double, Decimal, boolean)} for a double value and payload. */
    public static Decimal tetrate(double value, double height, Decimal payload, boolean linear) {
        return fromNumber(value).tetrate(height, payload, linear);
    }

    /** {@link #tetrate(Decimal, double, Decimal, boolean)} for a double value. */
    public static Decimal tetrate(double value, double height, Decimal payload) {
        return fromNumber(value).tetrate(height, payload, false);
    }

    /** See {@link #iteratedexp(double, Decimal, boolean)}. */
    public static Decimal iteratedexp(Decimal value, double height, Decimal payload, boolean linear) {
        return value.tetrate(height, payload, linear);
    }

    /** {@link #iteratedexp(Decimal, double, Decimal, boolean)} with payload 1. */
    public static Decimal iteratedexp(Decimal value, double height, Decimal payload) {
        return value.tetrate(height, payload, false);
    }

    /** {@link #iteratedexp(Decimal, double, Decimal, boolean)} with the default payload of 1. */
    public static Decimal iteratedexp(Decimal value, double height) {
        return value.tetrate(height, dOne, false);
    }

    /** See {@link #iteratedlog(Decimal, double, boolean)}. */
    public static Decimal iteratedlog(Decimal value, Decimal base, double times, boolean linear) {
        return value.iteratedlog(base, times, linear);
    }

    /** {@link #iteratedlog(Decimal, Decimal, double, boolean)} with the default times of 1. */
    public static Decimal iteratedlog(Decimal value, Decimal base, double times) {
        return value.iteratedlog(base, times, false);
    }

    /** {@link #iteratedlog(Decimal, Decimal, double, boolean)} with the default base of 10. */
    public static Decimal iteratedlog(Decimal value, Decimal base) {
        return value.iteratedlog(base, 1, false);
    }


    /** {@link #iteratedlog(Decimal, Decimal, double, boolean)} for doubles. */
    public static Decimal iteratedlog(double value, double base, double times) {
        return fromNumber(value).iteratedlog(fromNumber(base), times, false);
    }

    /** See {@link #layeradd10(Decimal, boolean)}. */
    public static Decimal layeradd10(Decimal value, Decimal diff, boolean linear) {
        return value.layeradd10(diff, linear);
    }

    /** {@link #layeradd10(Decimal, Decimal, boolean)}. */
    public static Decimal layeradd10(Decimal value, Decimal diff) {
        return value.layeradd10(diff, false);
    }

    /** {@link #layeradd10(Decimal, Decimal, boolean)} for a double diff. */
    public static Decimal layeradd10(Decimal value, double diff) {
        return value.layeradd10(diff, false);
    }

    /** See {@link #layeradd(double, Decimal, boolean)}. */
    public static Decimal layeradd(Decimal value, double diff, Decimal base, boolean linear) {
        return value.layeradd(diff, base, linear);
    }

    /** {@link #layeradd(Decimal, double, Decimal, boolean)} with the default base of 10. */
    public static Decimal layeradd(Decimal value, double diff, Decimal base) {
        return value.layeradd(diff, base, false);
    }

    /** {@link #layeradd(Decimal, double, Decimal, boolean)}. */
    public static Decimal layeradd(Decimal value, double diff) {
        return value.layeradd(diff, dTen, false);
    }

    /** See {@link #slog(Decimal, int, boolean)}. */
    public static Decimal slog(Decimal value, Decimal base, boolean linear) {
        return value.slog(base, 100, linear);
    }

    /** {@link #slog(Decimal, Decimal, boolean)}. */
    public static Decimal slog(Decimal value, Decimal base) {
        return value.slog(base, 100, false);
    }


    /** {@link #slog(Decimal, Decimal, boolean)} with 100 iterations. */
    public static Decimal slog(Decimal value, Decimal base, int iterations) {
        return value.slog(base, iterations, false);
    }

    /** See {@link #lambertw(boolean)}. */
    public static Decimal lambertw(Decimal value, boolean principal) {
        return value.lambertw(principal);
    }

    /** {@link #lambertw(Decimal, boolean)} with the principal branch. */
    public static Decimal lambertw(Decimal value) {
        return value.lambertw(true);
    }

    /** {@link #lambertw(Decimal, boolean)} for a double. */
    public static Decimal lambertw(double value, boolean principal) {
        return fromNumber(value).lambertw(principal);
    }

    /** See {@link #ssqrt()}. */
    public static Decimal ssqrt(Decimal value) {
        return value.ssqrt();
    }

    /** {@link #ssqrt(Decimal)} for a double. */
    public static Decimal ssqrt(double value) {
        return fromNumber(value).ssqrt();
    }

    /** See {@link #linear_sroot(double)}. */
    public static Decimal linear_sroot(Decimal value, double degree) {
        return value.linear_sroot(degree);
    }

    /** {@link #linear_sroot(Decimal, double)} for a double. */
    public static Decimal linear_sroot(double value, double degree) {
        return fromNumber(value).linear_sroot(degree);
    }

    /** See {@link #pentate(double, Decimal, boolean)}. */
    public static Decimal pentate(Decimal value, double height, Decimal payload, boolean linear) {
        return value.pentate(height, payload, linear);
    }

    /** {@link #pentate(Decimal, double, Decimal, boolean)} with payload 1. */
    public static Decimal pentate(Decimal value, double height, Decimal payload) {
        return value.pentate(height, payload, false);
    }

    /** {@link #pentate(Decimal, double, Decimal, boolean)} with the default payload of 1. */
    public static Decimal pentate(Decimal value, double height) {
        return value.pentate(height, dOne, false);
    }

    /** See {@link #penta_log(Decimal, int, boolean)}. */
    public static Decimal penta_log(Decimal value, Decimal base, boolean linear) {
        return value.penta_log(base, 100, linear);
    }

    /** {@link #penta_log(Decimal, Decimal, boolean)}. */
    public static Decimal penta_log(Decimal value, Decimal base) {
        return value.penta_log(base, 100, false);
    }


    /** See {@link #linear_penta_root(double)}. */
    public static Decimal linear_penta_root(Decimal value, double degree) {
        return value.linear_penta_root(degree);
    }

    /** {@link #linear_penta_root(Decimal, double)} for a double. */
    public static Decimal linear_penta_root(double value, double degree) {
        return fromNumber(value).linear_penta_root(degree);
    }

    // --- testing helper and game helpers (index.ts:1472-1641) ---

    /**
     * Returns a Decimal with a random sign, layer and mag. NOTE: This doesn't follow any
     * kind of sane random distribution, so use this for testing purposes only.
     */
    public static Decimal randomDecimalForTesting(int maxLayers) {
        return randomDecimalForTesting(maxLayers, java.util.concurrent.ThreadLocalRandom.current());
    }

    /** {@link #randomDecimalForTesting(int)} with an explicit RNG, for deterministic tests. */
    static Decimal randomDecimalForTesting(int maxLayers, java.util.Random random) {
        // 5% of the time, return 0
        if (random.nextDouble() * 20 < 1) {
            return fromComponents_noNormalize(0, 0, 0);
        }

        double randomsign = random.nextDouble() > 0.5 ? 1 : -1;

        // 5% of the time, return 1 or -1
        if (random.nextDouble() * 20 < 1) {
            return fromComponents_noNormalize(randomsign, 0, 1);
        }

        // pick a random layer
        double layer = Math.floor(random.nextDouble() * (maxLayers + 1));

        double randomexp = layer == 0 ? random.nextDouble() * 616 - 308 : random.nextDouble() * 16;
        // 10% of the time, make it a simple power of 10
        if (random.nextDouble() > 0.9) {
            randomexp = JsNumber.trunc(randomexp);
        }
        double randommag = Math.pow(10, randomexp);
        // 10% of the time, trunc mag
        if (random.nextDouble() > 0.9) {
            randommag = JsNumber.trunc(randommag);
        }
        return fromComponents(randomsign, layer, randommag);
    }

    /** See {@link #affordGeometricSeries_core}. */
    public static Decimal affordGeometricSeries(
            Decimal resourcesAvailable, Decimal priceStart, Decimal priceRatio, Decimal currentOwned) {
        return affordGeometricSeries_core(resourcesAvailable, priceStart, priceRatio, currentOwned);
    }

    /** {@link #affordGeometricSeries} for doubles. */
    public static Decimal affordGeometricSeries(
            double resourcesAvailable, double priceStart, double priceRatio, double currentOwned) {
        return affordGeometricSeries_core(
                fromNumber(resourcesAvailable),
                fromNumber(priceStart),
                fromNumber(priceRatio),
                fromNumber(currentOwned));
    }

    /**
     * Returns how many items you can afford with the given geometric price progression.
     */
    public static Decimal affordGeometricSeries_core(
            Decimal resourcesAvailable, Decimal priceStart, Decimal priceRatio, Decimal currentOwned) {
        Decimal actualStart = priceStart.mul(priceRatio.pow(currentOwned));
        return floor(
                resourcesAvailable
                        .div(actualStart)
                        .mul(priceRatio.sub(1))
                        .add(1)
                        .log10()
                        .div(priceRatio.log10()));
    }

    /** See {@link #sumGeometricSeries_core}. */
    public static Decimal sumGeometricSeries(
            Decimal numItems, Decimal priceStart, Decimal priceRatio, Decimal currentOwned) {
        return sumGeometricSeries_core(numItems, priceStart, priceRatio, currentOwned);
    }

    /** {@link #sumGeometricSeries} for doubles. */
    public static Decimal sumGeometricSeries(
            double numItems, double priceStart, double priceRatio, double currentOwned) {
        return sumGeometricSeries_core(
                fromNumber(numItems),
                fromNumber(priceStart),
                fromNumber(priceRatio),
                fromNumber(currentOwned));
    }

    /** Returns the total cost of the given number of items in a geometric price progression. */
    public static Decimal sumGeometricSeries_core(
            Decimal numItems, Decimal priceStart, Decimal priceRatio, Decimal currentOwned) {
        return priceStart
                .mul(priceRatio.pow(currentOwned))
                .mul(sub(1, priceRatio.pow(numItems)))
                .div(sub(1, priceRatio));
    }

    /** See {@link #affordArithmeticSeries_core}. */
    public static Decimal affordArithmeticSeries(
            Decimal resourcesAvailable, Decimal priceStart, Decimal priceAdd, Decimal currentOwned) {
        return affordArithmeticSeries_core(
                resourcesAvailable, priceStart, priceAdd, currentOwned);
    }

    /** {@link #affordArithmeticSeries} for doubles. */
    public static Decimal affordArithmeticSeries(
            double resourcesAvailable, double priceStart, double priceAdd, double currentOwned) {
        return affordArithmeticSeries_core(
                fromNumber(resourcesAvailable),
                fromNumber(priceStart),
                fromNumber(priceAdd),
                fromNumber(currentOwned));
    }

    /** Returns how many items you can afford with the given arithmetic price progression. */
    public static Decimal affordArithmeticSeries_core(
            Decimal resourcesAvailable, Decimal priceStart, Decimal priceAdd, Decimal currentOwned) {
        // n = (-(a-d/2) + sqrt((a-d/2)^2+2dS))/d
        // where a is actualStart, d is priceAdd and S is resourcesAvailable
        // then floor it and you're done!
        Decimal actualStart = priceStart.add(currentOwned.mul(priceAdd));
        Decimal b = actualStart.sub(priceAdd.div(2));
        Decimal b2 = b.pow(2);
        return b.neg()
                .add(b2.add(priceAdd.mul(resourcesAvailable).mul(2)).sqrt())
                .div(priceAdd)
                .floor();
    }

    /** See {@link #sumArithmeticSeries_core}. */
    public static Decimal sumArithmeticSeries(
            Decimal numItems, Decimal priceStart, Decimal priceAdd, Decimal currentOwned) {
        return sumArithmeticSeries_core(numItems, priceStart, priceAdd, currentOwned);
    }

    /** {@link #sumArithmeticSeries} for doubles. */
    public static Decimal sumArithmeticSeries(
            double numItems, double priceStart, double priceAdd, double currentOwned) {
        return sumArithmeticSeries_core(
                fromNumber(numItems),
                fromNumber(priceStart),
                fromNumber(priceAdd),
                fromNumber(currentOwned));
    }

    /** Returns the total cost of the given number of items in an arithmetic price progression. */
    public static Decimal sumArithmeticSeries_core(
            Decimal numItems, Decimal priceStart, Decimal priceAdd, Decimal currentOwned) {
        Decimal actualStart = priceStart.add(currentOwned.mul(priceAdd)); // (n/2)*(2*a+(n-1)*d)

        return numItems.div(2).mul(actualStart.mul(2).plus(numItems.sub(1).mul(priceAdd)));
    }

    /** See {@link #efficiencyOfPurchase_core}. */
    public static Decimal efficiencyOfPurchase(Decimal cost, Decimal currentRpS, Decimal deltaRpS) {
        return efficiencyOfPurchase_core(cost, currentRpS, deltaRpS);
    }

    /** {@link #efficiencyOfPurchase} for doubles. */
    public static Decimal efficiencyOfPurchase(double cost, double currentRpS, double deltaRpS) {
        return efficiencyOfPurchase_core(fromNumber(cost), fromNumber(currentRpS), fromNumber(deltaRpS));
    }

    /**
     * Returns the time to afford a purchase, approximated as the sum of the time at the
     * current resource rate and the time at the increased rate.
     */
    public static Decimal efficiencyOfPurchase_core(Decimal cost, Decimal currentRpS, Decimal deltaRpS) {
        return cost.div(currentRpS).add(cost.div(deltaRpS));
    }

    // --- Object contract ---

    /**
     * Component-wise equality, so {@code HashSet}/{@code HashMap} behave. This differs
     * from {@link #eq(Decimal)} (and the original) only in that NaN equals NaN and
     * -0.0 does not equal 0.0, per the {@link Object#equals} contract; normalized
     * Decimals always have canonical zero, so only the NaN case is observable.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Decimal)) {
            return false;
        }
        Decimal d = (Decimal) other;
        return Double.compare(this.sign, d.sign) == 0
                && Double.compare(this.layer, d.layer) == 0
                && Double.compare(this.mag, d.mag) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(this.sign) * 961 + Double.hashCode(this.layer) * 31
                + Double.hashCode(this.mag);
    }
}
