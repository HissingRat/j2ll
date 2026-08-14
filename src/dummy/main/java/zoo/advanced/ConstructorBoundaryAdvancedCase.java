package zoo.advanced;

import zoo.Case;

public final class ConstructorBoundaryAdvancedCase implements Case {
    @Override
    public String name() {
        return "ConstructorBoundaryAdvancedCase";
    }

    @Override
    public String run() {
        return Integer.toString(new GuardedBox(7).value());
    }

    public static final class GuardedBox {
        private final int value;

        public GuardedBox(int candidate) {
            int resolved;
            try {
                resolved = 84 / candidate;
            } catch (ArithmeticException overflow) {
                resolved = -1;
            }
            value = resolved;
        }

        public int value() {
            return value;
        }
    }
}
