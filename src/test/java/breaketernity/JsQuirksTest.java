package breaketernity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks in the original's quirks and suspected bugs, which the port replicates
 * deliberately (see the JS-QUIRK comments in Decimal.java). Each expected value here was
 * verified against the original library running under Node.
 */
class JsQuirksTest {

    /** mul's Infinity*0 check tests `this` twice, so the result depends on operand order. */
    @Test
    void mulInfinityZeroAsymmetry() {
        assertEquals("Infinity", new Decimal(0).mul(Decimal.dInf).toString());
        assertEquals("NaN", Decimal.dInf.mul(new Decimal(0)).toString());
    }

    /**
     * lt/gt are {@code cmp === +/-1} and lte/gte are their negations, so lte/gte are TRUE
     * for NaN operands; but the lessThan and greaterThan aliases use raw cmp comparisons
     * and are therefore false.
     */
    @Test
    void nanComparisonTruthTable() {
        assertFalse(Decimal.dNaN.gt(new Decimal(1)));
        assertFalse(Decimal.dNaN.lt(new Decimal(1)));
        assertTrue(Decimal.dNaN.lte(new Decimal(1)));
        assertTrue(Decimal.dNaN.gte(new Decimal(1)));
        assertFalse(Decimal.dNaN.greaterThan(new Decimal(1)));
        assertFalse(Decimal.dNaN.lessThan(new Decimal(1)));
        assertFalse(Decimal.dNaN.greaterThanOrEqualTo(new Decimal(1)));
        assertFalse(Decimal.dNaN.lessThanOrEqualTo(new Decimal(1)));
        assertFalse(Decimal.dNaN.eq(Decimal.dNaN));
        assertTrue(Decimal.dNaN.neq(new Decimal(1)));
    }

    /** powerOf10 returns undefined outside its table, which makes the m getter NaN. */
    @Test
    void powerOfTenOutOfRangeIsNaN() {
        Decimal weird = Decimal.fromComponents_noNormalize(1, 0, 0);
        assertTrue(Double.isNaN(weird.m()));
        assertTrue(weird.e() == Double.NEGATIVE_INFINITY);
    }

    /** fromString removes only the first comma (JS String.replace with a string pattern). */
    @Test
    void onlyFirstCommaRemoved() {
        assertEquals("12", new Decimal("1,2,3.5").toString());
    }

    /**
     * The fromString cache key ignores linearhyper4, so the second call returns the
     * cached (non-linear) value.
     */
    @Test
    void fromStringCacheIgnoresLinearHyper4() {
        Decimal nonLinear = Decimal.fromString("10^^8.5");
        Decimal linearRequested = Decimal.fromString("10^^8.5", true);
        assertEquals(nonLinear.sign, linearRequested.sign);
        assertEquals(nonLinear.layer, linearRequested.layer);
        assertEquals(nonLinear.mag, linearRequested.mag);
    }

    /**
     * The smallest subnormal formats like V8 does, not like Java's Double.toString
     * ("4.9E-324"). Note that as a Decimal it normalizes to layer 1, whose toString is
     * the mantissa/exponent form (verified against the original under Node).
     */
    @Test
    void subnormalFormatting() {
        assertEquals("4.940656458412399e-324", Decimal.dNumberMin.toString());
        assertEquals("5e-324", JsNumber.toString(Double.MIN_VALUE));
    }

    /** fromMantissaExponent_noNormalize normalizes anyway, despite its name. */
    @Test
    void meNoNormalizeStillNormalizes() {
        Decimal d = Decimal.fromMantissaExponent_noNormalize(1, 500);
        assertEquals(1, d.layer);
        assertEquals(500.0, d.mag);
    }

    /** add/mul short-circuit on non-finite layers, returning the operand unchanged. */
    @Test
    void nonFiniteOperandShortCircuits() {
        assertEquals("NaN", Decimal.dNaN.add(new Decimal(5)).toString());
        assertEquals("Infinity", Decimal.dInf.add(new Decimal(5)).toString());
        assertEquals("NaN", Decimal.dNaN.mul(new Decimal(5)).toString());
        // Infinity + -Infinity is NaN
        assertEquals("NaN", Decimal.dInf.add(Decimal.dNegInf).toString());
        // -Infinity * -Infinity is Infinity
        assertEquals("Infinity", Decimal.dNegInf.mul(Decimal.dNegInf).toString());
    }

    /** withM on a layered value keeps layer/mag and replaces the sign; withM(0) zeroes the value. */
    @Test
    void setterEquivalents() {
        Decimal layered = new Decimal("(e^10)100");
        Decimal signFlipped = layered.withM(-1);
        assertEquals(-1, signFlipped.sign);
        assertEquals(layered.layer, signFlipped.layer);
        assertEquals(layered.mag, signFlipped.mag);
        assertEquals("0", layered.withM(0).toString());
        assertEquals("0", layered.withS(0).toString());
    }

    /** The tan/sin/asin instance methods return the receiver for negative layer-0 mags. */
    @Test
    void smallNumberTrigReturnsSelf() {
        Decimal tiny = new Decimal("1e-300");
        assertTrue(tiny.sin() == tiny || tiny.sin().eq(tiny));
        assertTrue(tiny.tan() == tiny || tiny.tan().eq(tiny));
        assertEquals("1", tiny.cos().toString());
    }
}
