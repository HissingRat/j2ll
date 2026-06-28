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
        return total + ":" + table(2) + ":" + lookup(21);
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
}
