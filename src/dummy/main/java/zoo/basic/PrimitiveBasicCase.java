package zoo.basic;

import zoo.Case;

public final class PrimitiveBasicCase implements Case {
    @Override
    public String name() {
        return "PrimitiveBasicCase";
    }

    @Override
    public String run() {
        int i = simpleInt(19, 23);
        long l = longMath(1234567890123L, 3L);
        byte b = (byte) 260;
        short s = (short) (32000 + 12);
        char c = (char) ('A' + 2);
        boolean cmp = lessThan(90, i) && Long.compare(l, 411522630042L) == 0;
        int divZero = divZeroCode();
        float f = floatValue();
        double d = doubleValue();
        return i + ":" + l + ":" + b + ":" + s + ":" + c + ":" + cmp + ":" + divZero
                + ":" + Float.floatToRawIntBits(f) + ":" + Double.doubleToRawLongBits(d)
                + ":" + Double.isInfinite(1.0d / 0.0d);
    }

    public static int simpleInt(int left, int right) {
        return ((left + right) * 3) - (7 << 2);
    }

    public static long longMath(long value, long divisor) {
        return (value / divisor) + (99L % 7L);
    }

    public static boolean lessThan(int left, int right) {
        return left < right;
    }

    public static int divZeroCode() {
        try {
            int zero = 0;
            return 10 / zero;
        } catch (ArithmeticException expected) {
            return 31;
        }
    }

    public static float floatValue() {
        return Float.NaN;
    }

    public static double doubleValue() {
        return -0.0d;
    }
}
