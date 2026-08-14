package zoo.basic;

import zoo.Case;

public final class ArrayEdgeBasicCase implements Case {
    @Override
    public String name() {
        return "ArrayEdgeBasicCase";
    }

    @Override
    public String run() {
        boolean[] flags = {true, false, true};
        byte[] bytes = {1, -2};
        short[] shorts = {300, -40};
        char[] chars = {'J', '\u03bb'};
        int[] ints = {3, 5, 8};
        long[] longs = {13L, 21L};
        float[] floats = {1.25f, -0.0f};
        double[] doubles = {2.5d, Double.longBitsToDouble(0x7ff8_0000_0000_0042L)};
        String[][] words = {{"a"}, {"b", "c"}};
        int[][] matrix = makeMatrix(2, 3);
        return flip(flags) + ":" + bytes[1] + ":" + shorts[0] + ":" + (int) chars[1]
                + ":" + sum(ints, longs) + ":" + Float.floatToRawIntBits(floats[1])
                + ":" + Double.doubleToRawLongBits(doubles[1]) + ":" + matrix[1][2]
                + ":" + words[1][1] + ":" + exceptionCodes(ints, words);
    }

    public static boolean flip(boolean[] values) {
        values[1] = !values[0];
        return values[0] && !values[1] && values.length == 3;
    }

    public static long sum(int[] ints, long[] longs) {
        return ints[0] + ints[1] + ints[2] + longs[0] + longs[1];
    }

    public static int[][] makeMatrix(int rows, int columns) {
        int[][] result = new int[rows][columns];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                result[row][column] = (row + 1) * 10 + column;
            }
        }
        return result;
    }

    public static String exceptionCodes(int[] ints, Object[] references) {
        StringBuilder result = new StringBuilder();
        try {
            int ignored = ints[-1];
        } catch (ArrayIndexOutOfBoundsException expected) {
            result.append("oob");
        }
        try {
            int ignored = ((int[]) null).length;
        } catch (NullPointerException expected) {
            result.append("-npe");
        }
        try {
            String[] strings = (String[]) references;
            strings[0] = "bad";
        } catch (ClassCastException expected) {
            result.append("-cast");
        }
        try {
            String[] strings = new String[1];
            Object[] objects = strings;
            objects[0] = Integer.valueOf(1);
        } catch (ArrayStoreException expected) {
            result.append("-store");
        }
        try {
            int[] ignored = new int[-2];
        } catch (NegativeArraySizeException expected) {
            result.append("-negative");
        }
        return result.toString();
    }
}
