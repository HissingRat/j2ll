package xyz.melodysky.pipeline;

/** Java 17 sources used by the semantic-surface JNI proxy E2E. */
final class SemanticJniProxyJavaSources {
    private SemanticJniProxyJavaSources() {}

    static String operations() {
        return """
                package pkg;

                public final class SemanticProxyOps {
                    private static int staticState;
                    private int instanceState = 29;

                    static { staticState = 41; }

                    public SemanticProxyOps() {}

                    public static Object staticIdentity(Object value) {
                        return value;
                    }
                    public Object instanceIdentity(Object value) {
                        return value;
                    }
                    public static int[] intArrayIdentity(int[] value) {
                        return value;
                    }
                    public static Object[] objectArrayIdentity(Object[] value) {
                        return value;
                    }
                    public static Object allocateObject() {
                        return new Object();
                    }
                    public static byte[] allocateBytes(int length) {
                        return new byte[length];
                    }
                    public static int readStaticField() {
                        return staticState;
                    }
                    public int readInstanceField() {
                        return instanceState;
                    }
                    public int readStaticFromInstance() {
                        return staticState;
                    }
                    public static int divide(int left, int right) {
                        return left / right;
                    }
                    public static int remainder(int left, int right) {
                        return left % right;
                    }
                    public static String callStringValueOf(Object value) {
                        return String.valueOf(value);
                    }
                    public static int alwaysThrow() {
                        throw new IllegalStateException("semantic-proxy");
                    }
                    public synchronized int synchronizedIdentity(int value) {
                        return value;
                    }
                    public static boolean narrowBoolean(boolean value) {
                        return value;
                    }
                    public static byte narrowByte(byte value) {
                        return value;
                    }
                    public static char narrowChar(char value) {
                        return value;
                    }
                    public static short narrowShort(short value) {
                        return value;
                    }
                }
                """;
    }

    static String main() {
        return """
                package pkg;

                public final class SemanticProxyMain {
                    public static void main(String[] args) {
                        SemanticProxyOps ops = new SemanticProxyOps();
                        Object marker = new Object();
                        int[] ints = { 1, 2, 3 };
                        Object[] objects = { marker, null };

                        System.out.println(SemanticProxyOps.staticIdentity(marker) == marker);
                        System.out.println(SemanticProxyOps.staticIdentity(null) == null);
                        System.out.println(ops.instanceIdentity(marker) == marker);
                        System.out.println(ops.instanceIdentity(null) == null);
                        System.out.println(SemanticProxyOps.intArrayIdentity(ints) == ints);
                        System.out.println(SemanticProxyOps.intArrayIdentity(null) == null);
                        System.out.println(SemanticProxyOps.objectArrayIdentity(objects) == objects);
                        System.out.println(SemanticProxyOps.objectArrayIdentity(null) == null);
                        System.out.println(SemanticProxyOps.allocateObject() != null);
                        System.out.println(SemanticProxyOps.allocateBytes(7).length);
                        System.out.println(SemanticProxyOps.readStaticField());
                        System.out.println(ops.readInstanceField());
                        System.out.println(ops.readStaticFromInstance());
                        System.out.println(SemanticProxyOps.divide(35, 6));
                        System.out.println(SemanticProxyOps.remainder(35, 6));
                        System.out.println(SemanticProxyOps.callStringValueOf(1234));
                        try {
                            SemanticProxyOps.divide(1, 0);
                            System.out.println("missing-divide-exception");
                        } catch (ArithmeticException expected) {
                            System.out.println(expected.getClass().getSimpleName());
                        }
                        try {
                            SemanticProxyOps.alwaysThrow();
                            System.out.println("missing-explicit-exception");
                        } catch (IllegalStateException expected) {
                            System.out.println(expected.getMessage());
                        }
                        System.out.println(ops.synchronizedIdentity(73));
                        System.out.println(
                                SemanticProxyOps.narrowBoolean(true) + "/"
                                        + SemanticProxyOps.narrowByte((byte) -7) + "/"
                                        + (int) SemanticProxyOps.narrowChar((char) 0xffee) + "/"
                                        + SemanticProxyOps.narrowShort((short) -32000));
                    }
                }
                """;
    }

    static String expectedOutput() {
        return """
                true
                true
                true
                true
                true
                true
                true
                true
                true
                7
                41
                29
                41
                5
                5
                1234
                ArithmeticException
                semantic-proxy
                73
                true/-7/65518/-32000
                """;
    }
}
