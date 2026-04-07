package bench;

final class NativeSlice {

    private static int base = 1;

    private int value;

    NativeSlice() {
        this(40);
    }

    NativeSlice(int seed) {
        value = seed;
    }

    static void setBase(int newBase) {
        base = newBase;
    }

    static int compute(int seed) {
        NativeSlice slice = new NativeSlice(seed);
        slice.setValue(seed + 2);
        return add(slice.getValue(), slice.callHelper(5)) + branch(seed);
    }

    static int add(int left, int right) {
        return left + right;
    }

    static int branch(int value) {
        if (value < 0) {
            return 100 - value;
        }
        if (value == 0) {
            return 7;
        }
        return value + base;
    }

    private int helper(int input) {
        return input + 1;
    }

    int callHelper(int input) {
        return helper(input);
    }

    void setValue(int newValue) {
        value = newValue;
    }

    int getValue() {
        return value;
    }
}
