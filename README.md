# break_eternity.java

A Java port of [break_eternity.js](https://github.com/Patashu/break_eternity.js) v2.1.3 —
Patashu's numerical library for incremental games, which represents numbers as
`sign * 10^10^...^mag` with a layer count and covers everything from
`10^-(10^^1e308)` to `10^^1e308`, including tetration, pentation and their inverses.

The port is a faithful translation: method names, static/instance duality and the
`plus`/`times`/`dividedBy` aliases all match the JavaScript API, so game logic can be
translated mechanically. Two deliberate differences:

- **Instances are immutable.** `sign`, `mag` and `layer` are public final fields; the
  original's mutating `from*` methods and property setters became the static factories
  and `with*` copies (see [Migration notes](#migration-notes-from-javascript)).
- **Java has no union types or implicit conversions**, so `DecimalSource` arguments are
  served by overloads (`Decimal`, `double` — which also accepts `int`/`long`/`float` by
  widening — and `String`).

Requires Java 17 or newer. Licensed under MIT, like the original
(see LICENSE for the upstream copyright).

## Usage

```java
import breaketernity.Decimal;

Decimal x = new Decimal(123.4567);
Decimal y = new Decimal("123456.7e-3");
Decimal z = new Decimal(x);
x.eq(y) && y.eq(z);                       // true

x.add(y).mul(9).floor();                  // methods returning Decimals chain
new Decimal(10).tetrate(3.5).toString();  // "ee299.9201235685456"
new Decimal("ee100").slog(new Decimal(10)).toNumber(); // 3.392912401008867

Decimal.add(x, "1e100");                  // static forms exist too
Decimal.max(new Decimal("1e1000"), x);
Decimal.pow10(308.25).toString();         // "1.7782794100389228e308"

new Decimal("(e^9)299.92").log10();       // the original's string forms parse
new Decimal("10^^2.5").toString();        // "8.320004641000848e299"
```

Game-helper statics mirror the original as well:

```java
Decimal.affordGeometricSeries(resources, priceStart, priceRatio, currentOwned);
Decimal.sumArithmeticSeries(numItems, priceStart, priceAdd, currentOwned);
Decimal.efficiencyOfPurchase(cost, currentRpS, deltaRpS);
```

## Building

```bash
./gradlew build              # compiles with --release 17, runs the unit tests, jars
./gradlew test               # unit tests only (no Node needed)
./gradlew differentialTest   # cross-checks against the original JS, needs Node
```

The differential suite looks for the original repository at `../break_eternity.js`;
override it with `-PjsRepo=/path/to/break_eternity.js`.

The wrapper points at the Tencent Cloud mirror of the Gradle distribution because the
canonical `services.gradle.org` CDN measured ~60 KB/s from this machine (~11 MB/s from
the mirror); edit `gradle/wrapper/gradle-wrapper.properties` to use the canonical URL if
you prefer.

Test counts as of the last full run: `./gradlew build` runs 150 tests (104 ported
upstream tests, 35 formatting, 10 quirk, 1 API-surface) and `./gradlew differentialTest`
runs 3 suites covering about 84,000 replayed cases in a few seconds.

## Migration notes (from JavaScript)

| JavaScript | Java |
|---|---|
| `new Decimal(x)` with number/string/Decimal — unchanged | same (constructors exist for all three) |
| `x.m = 5; x.e = 10; x.s = -1` (property setters) | `x = x.withM(5); x = x.withE(10); x = x.withS(-1)` |
| `x.fromNumber(5)` (mutates `x`) | `x = Decimal.fromNumber(5)` (the factories never mutate) |
| `x.normalize()` (mutates) | `x.normalized()` (returns a normalized copy) |
| `Decimal.add(a, b)` with mixed types | `Decimal.add(Decimal, Decimal)` and `(double, double)` / `(Decimal, double)` / `(double, Decimal)` overloads; for strings use `a.add("...")` or `Decimal.fromValue("...")` first |
| instance defaults differ from static defaults (`lambertw`, `layeradd`, `slog`, `penta_log`) | overloads reproduce both, but a few static convenience forms (`Decimal.slog(x)`, `Decimal.iteratedlog(x)`, `Decimal.penta_log(x)`) are omitted because Java cannot overload on static-ness; call `x.slog()` instead |
| `valueOf()`/`toJSON()` return strings | same (String-returning methods), but Java has no operator coercion — use `toString()` |
| `isNaN` (static) vs `isNan` (instance) | kept exactly as-is |
| arrays of `[Decimal, 0\|1\|2]` | `Decimal.ExcessSlogResult` record (package-private, internal) |
| functions as arguments (`increasingInverse`) | `java.util.function.Function<Decimal, Decimal>` |

`Decimal.dZero`, `dOne`, `dNaN`, `dInf`, `dLayerMax` and the other 9 constants exist with
the same names. In JavaScript they are shared mutable objects that a stray assignment can
corrupt; here they are safely immutable.

### cmp and NaN

The original's `cmp` returns `NaN` when an operand is NaN, which fits a JS `-1|0|1` type
only by accident. `cmp` here returns `int` and follows `Double.compare` for NaN operands
(NaN sorts above numbers, NaN equals NaN); this is documented on the method. The boolean
predicates reproduce the original's exact behavior, including its quirks:

- `lt`/`gt` are false when either operand is NaN;
- `lte`/`gte` are **true** when either operand is NaN (they are `!gt`/`!lt` in the
  original);
- the `lessThan*`/`greaterThan*` aliases are **false** for NaN (they compare `cmp`
  directly) — see `JsQuirksTest`.

### Replicated upstream quirks

The port is bug-for-bug faithful, so that behavior matches the original exactly. Sites
marked `JS-QUIRK` in the source, with tests in `JsQuirksTest`:

- `mul`'s Infinity×0 check tests the wrong operand, so `0 * Infinity` is `Infinity` but
  `Infinity * 0` is `NaN`;
- `fromString` removes only the first comma, and its cache key ignores `linearhyper4`;
- the first plain-number string parsed in a session is not cached (a `size` vs `maxSize`
  typo in the original's guard);
- `fromMantissaExponent_noNormalize` normalizes anyway, despite its name;
- `linear_sroot` contains a reference-equality comparison (`lastValid == FC(1, 10, 1)`)
  that can never be true; it is replicated as an explicit no-op.

## Verification

Three layers, all in the repository:

1. **`UpstreamUnitTests`** ports every test in the original `src/unit_tests.js` (28 test
   functions) to JUnit 5. Ground-truth tables are read from fixtures generated directly
   from the original file by `tools/gen-ground-truth.mjs`, so the expected values are the
   original literals; randomized properties use seeded RNGs so they are deterministic.
   The original only logs failures to the console — here they fail the build.

2. **`differentialTest`** compares this port against the original library running under
   Node, over corpora generated by the scripts in `tools/`:
   - `JsNumber` formatting (toString/toFixed/toExponential/parseFloat) over an
     ECMAScript-derived corpus (uniform doubles, random bit patterns, subnormals, specials):
     **240,168 string comparisons, 0 diffs** in the last full run.
   - Construction/parsing: 8,074 inputs — sign/layer/mag and the string outputs are
     bit-identical except for 19 values whose last digit differs by one ulp (see below).
   - Arithmetic/comparison (38,399 cases, including comparison truth tables, tolerance
     variants, mod/min/max/clamp/rounding): **bit-identical, 0 diffs**.
   - Logs/powers/gamma/exp/trig (73,199 cases): **0 real diffs**; 99.69% bit-identical,
     the rest differing in the last ulp.
   - Hyperoperators (tetrate, slog, layeradd, lambertw, super-roots, pentation): compared
     with tolerance against the original — see `tools/hyper-diff.mjs`.

3. **`JsQuirksTest`** locks in the replicated quirks listed above.

### Known divergences

These are last-ulp (relative ~1e-16) differences, unavoidable without porting V8's exact
floating-point routines:

- **`Math.pow`**: V8's `pow` differs from both glibc's and fdlibm's by one ulp on ~0.1%
  of inputs. Java's `Math.pow` (glibc) matches V8 on 99.93% of a 4,000-case corpus,
  `StrictMath.pow` (fdlibm) on 90%. The port uses `Math.pow`, so values derived from it
  (layer-1 magnitudes, `pow10`, `toNumber` for layer >= 1) can differ in the last digit
  from the original on rare inputs.
- **Math functions**: `StrictMath` is used for `log10`, `log`, `exp`, `sin`, `cos`, `tan`,
  `asin`, `acos`, `atan`, `sinh`, `cosh`, `tanh`, `sqrt` — all measured at 99.95-100%
  bit-agreement with V8 on a 4,000-case-per-function corpus. Java has no `log2`, so
  `JsMath.log2` supplies it: exact for powers of two, matching V8 on 72% of random
  inputs otherwise (the library's `log2()` is rarely used, and the difference is one
  ulp). The hyperbolic inverses (`asinh`/`acosh`/`atanh`) are built from Decimal-domain
  `exp`/`ln` in the original and are ported the same way, so they need no number-domain
  implementation.

Thread safety: like the original, the `fromString` LRU cache is unsynchronized, so
Decimals can be shared across threads but string parsing should be externally
synchronized if used concurrently.

## Layout

```
src/main/java/breaketernity/
  Decimal.java       the library (mirrors src/index.ts section order, with line refs)
  JsNumber.java      ECMAScript number <-> string (shortest round-trip, toFixed, parseFloat)
  JsMath.java        the one Math function Java lacks that the library needs (log2)
  CriticalTables.java  generated tables + module constants (tools/gen-tables.mjs)
  LRUCache.java      port of src/lru-cache.ts
src/test/java/breaketernity/   JUnit 5 suites
src/test/resources/            fixtures generated from the original sources
tools/                         differential harnesses and fixture generators
```
