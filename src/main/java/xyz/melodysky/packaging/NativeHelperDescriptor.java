package xyz.melodysky.packaging;

/** Computes the descriptor of the method that is actually registered with JNI. */
public final class NativeHelperDescriptor {
    private NativeHelperDescriptor() {
    }

    public static String forDecision(MethodRewriteDecision decision) {
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            return prependReceiver(decision.method().owner(), decision.method().descriptor(), "V");
        }
        if (decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return "()V";
        }
        if (decision.strategy() == MethodRewriteStrategy.INTERFACE_METHOD_STUB
                && !decision.method().accessFlags().isStatic()) {
            return prependReceiver(
                    decision.method().owner(),
                    decision.method().descriptor(),
                    returnDescriptor(decision.method().descriptor()));
        }
        return decision.method().descriptor();
    }

    private static String prependReceiver(
            String owner,
            String descriptor,
            String returnDescriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0) {
            throw new IllegalArgumentException("invalid method descriptor: " + descriptor);
        }
        return "(L" + owner + ";" + descriptor.substring(1, close) + ")" + returnDescriptor;
    }

    private static String returnDescriptor(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0 || close + 1 >= descriptor.length()) {
            throw new IllegalArgumentException("invalid method descriptor: " + descriptor);
        }
        return descriptor.substring(close + 1);
    }
}
