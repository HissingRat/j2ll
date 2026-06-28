package zoo.basic;

import java.util.Arrays;
import zoo.Case;

public final class ArrayBasicCase implements Case {
    @Override
    public String name() {
        return "ArrayBasicCase";
    }

    @Override
    public String run() {
        byte[] bytes = {1, 2, 3};
        short[] shorts = {4, 5};
        char[] chars = {'x', 'y'};
        int[] ints = {1, 2, 3, 4};
        long[] longs = {8L, 13L};
        float[] floats = {1.5f, 2.5f};
        double[] doubles = {3.5d, 4.5d};
        String[] strings = {"a", "b", "c"};
        Object[] objects = strings;
        int[] copied = new int[4];
        System.arraycopy(ints, 0, copied, 0, ints.length);
        System.arraycopy(strings, 0, strings, 1, 2);
        String npe = catchName(() -> {
            int ignored = ((int[]) null).length;
        });
        String oob = catchName(() -> {
            int ignored = ints[9];
        });
        String ase = catchName(() -> objects[0] = new Object());
        return bytes[2] + ":" + shorts[1] + ":" + chars[0] + ":" + longs[1] + ":"
                + Float.floatToRawIntBits(floats[0]) + ":" + Double.doubleToRawLongBits(doubles[1])
                + ":" + Arrays.equals(ints, copied) + ":" + strings[1] + strings[2]
                + ":" + npe + ":" + oob + ":" + ase;
    }

    private static String catchName(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return "none";
        } catch (Throwable throwable) {
            return throwable.getClass().getSimpleName();
        }
    }

    private interface ThrowingRunnable {
        void run();
    }
}
