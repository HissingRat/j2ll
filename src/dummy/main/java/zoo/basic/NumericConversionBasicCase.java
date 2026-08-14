package zoo.basic;

import zoo.Case;

public final class NumericConversionBasicCase implements Case {
    @Override
    public String name() {
        return "NumericConversionBasicCase";
    }

    @Override
    public String run() {
        byte b = byteMath((byte) 120, (byte) 17);
        short s = shortMath((short) 30_000, (short) 1_234);
        char c = charMath('A', 31);
        int i = intMath(-19, 7);
        long l = longMath(0x1_0000_0000L, -9L);
        float f = floatMath(7, 4L);
        double d = doubleMath(-13.5f, 4);
        boolean z = booleanMath(b, s, c);
        return z + ":" + b + ":" + s + ":" + (int) c + ":" + i + ":" + l
                + ":" + Float.floatToRawIntBits(f)
                + ":" + Double.doubleToRawLongBits(d)
                + ":" + Float.floatToRawIntBits(-0.0f)
                + ":" + Double.doubleToRawLongBits(-0.0d);
    }

    public static boolean booleanMath(byte b, short s, char c) {
        return b < 0 && s > 0 && c == 96;
    }

    public static byte byteMath(byte left, byte right) {
        return (byte) (left + right);
    }

    public static short shortMath(short left, short right) {
        return (short) (left + right);
    }

    public static char charMath(char value, int delta) {
        return (char) (value + delta);
    }

    public static int intMath(int value, int divisor) {
        return ((value * divisor) / 3) + (value % divisor);
    }

    public static long longMath(long value, long delta) {
        return (value >>> 3) + (delta << 4);
    }

    public static float floatMath(int numerator, long denominator) {
        return ((float) numerator / (float) denominator) + (byte) 2;
    }

    public static double doubleMath(float value, int divisor) {
        return ((double) value / (double) divisor) - (short) 3;
    }
}
