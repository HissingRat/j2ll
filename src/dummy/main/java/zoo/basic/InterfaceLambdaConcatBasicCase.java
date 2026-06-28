package zoo.basic;

import java.util.function.Function;
import java.util.function.IntSupplier;
import zoo.Case;

public final class InterfaceLambdaConcatBasicCase implements Case {
    @Override
    public String name() {
        return "InterfaceLambdaConcatBasicCase";
    }

    @Override
    public String run() {
        MathOp op = new AddOp();
        MathOp defaultOp = new MathOp() {};
        IntSupplier noCapture = () -> 5;
        int captured = 7;
        IntSupplier singleCapture = () -> captured + 1;
        Function<String, String> staticRef = InterfaceLambdaConcatBasicCase::tag;
        Function<String, Box> ctorRef = Box::new;
        Box box = ctorRef.apply("box");
        Function<String, String> instanceRef = box::join;
        String concat = "concat-" + op.apply(2, 3) + "-" + singleCapture.getAsInt();
        return op.apply(2, 3) + ":" + defaultOp.identity(9) + ":" + noCapture.getAsInt()
                + ":" + singleCapture.getAsInt() + ":" + staticRef.apply("s")
                + ":" + instanceRef.apply("i") + ":" + concat;
    }

    private static String tag(String value) {
        return "tag-" + value;
    }

    private interface MathOp {
        default int identity(int value) {
            return value;
        }

        default int apply(int left, int right) {
            return left * right;
        }
    }

    private static final class AddOp implements MathOp {
        @Override
        public int apply(int left, int right) {
            return left + right;
        }
    }

    private static final class Box {
        private final String value;

        Box(String value) {
            this.value = value;
        }

        String join(String suffix) {
            return value + "-" + suffix;
        }
    }
}
