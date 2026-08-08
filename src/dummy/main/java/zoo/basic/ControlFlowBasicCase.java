package zoo.basic;

import zoo.Case;

public final class ControlFlowBasicCase implements Case {
    @Override
    public String name() {
        return "ControlFlowBasicCase";
    }

    @Override
    public String run() {
        int total = 0;
        for (int index = -2; index <= 4; index++) {
            if (index < 0) {
                total += negate(index);
            } else if (index == 0) {
                total += table(index);
            } else {
                total += lookup(index * 7);
            }
        }
        String ownedNegative = regionAroundOwnedBoundary(-1, new String[] {"unused"});
        String ownedZero = regionAroundOwnedBoundary(0, new String[] {"zero"});
        String ownedPositive = regionAroundOwnedBoundary(1, new String[] {"first", "last"});
        int typedNegative = regionAroundTypedCatch(-1, 5);
        int typedZero = regionAroundTypedCatch(0, 5);
        int typedNormal = regionAroundTypedCatch(1, 5);
        int typedException = regionAroundTypedCatch(1, 0);
        return total + ":" + table(2) + ":" + lookup(21)
                + ":" + ownedNegative + ":" + ownedZero + ":" + ownedPositive
                + ":" + typedNegative + ":" + typedZero + ":" + typedNormal + ":" + typedException;
    }

    public static int negate(int value) {
        return -value;
    }

    public static int table(int value) {
        return switch (value) {
            case 0 -> 3;
            case 1 -> 5;
            case 2 -> 7;
            default -> 11;
        };
    }

    public static int lookup(int value) {
        return switch (value) {
            case 7 -> 13;
            case 21 -> 17;
            case 28 -> 19;
            default -> -1;
        };
    }

    public static String regionAroundOwnedBoundary(int selector, String[] values) {
        if (selector < 0) {
            return "fallback";
        }
        if (selector == 0) {
            return values[0];
        }
        return values[values.length - 1];
    }

    public static int regionAroundTypedCatch(int selector, int divisor) {
        if (selector < 0) {
            return -11;
        }
        if (selector == 0) {
            return 13;
        }
        try {
            return 120 / divisor;
        } catch (ArithmeticException expected) {
            return 17;
        }
    }
}
