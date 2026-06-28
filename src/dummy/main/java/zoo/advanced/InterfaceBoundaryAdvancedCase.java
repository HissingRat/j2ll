package zoo.advanced;

import zoo.Case;

public final class InterfaceBoundaryAdvancedCase implements Case {
    @Override
    public String name() {
        return "InterfaceBoundaryAdvancedCase";
    }

    @Override
    public String run() {
        Diamond diamond = new Diamond();
        SuperCall superCall = new SuperCall();
        return diamond.name() + ":" + superCall.call();
    }

    private interface Left {
        default String name() {
            return "L";
        }
    }

    private interface Right {
        default String name() {
            return "R";
        }
    }

    private static final class Diamond implements Left, Right {
        @Override
        public String name() {
            return Left.super.name() + Right.super.name();
        }
    }

    private static final class SuperCall implements Left {
        String call() {
            return Left.super.name();
        }
    }
}
