package zoo.basic;

import java.lang.reflect.Field;
import zoo.Case;

public final class ReferenceIdentityBasicCase implements Case {
    private static final int INITIALIZED;
    private static Object shared;

    static {
        INITIALIZED = 37;
        shared = new Payload("static", INITIALIZED);
    }

    private Object value;

    public ReferenceIdentityBasicCase() {
        value = new Payload("instance", 5);
    }

    @Override
    public String name() {
        return "ReferenceIdentityBasicCase";
    }

    @Override
    public String run() throws Exception {
        ReferenceIdentityBasicCase owner = new ReferenceIdentityBasicCase();
        Payload instance = checkedPayload(owner.value);
        Payload replacement = new Payload("replacement", 11);
        owner.value = replacement;

        Field firstHandle = ReferenceIdentityBasicCase.class.getDeclaredField("shared");
        Field secondHandle = ReferenceIdentityBasicCase.class.getDeclaredField("shared");
        firstHandle.setAccessible(true);
        secondHandle.setAccessible(true);
        Object throughFirst = firstHandle.get(null);
        Object throughSecond = secondHandle.get(null);

        return INITIALIZED + ":" + instance.label + ":" + instance.number + ":"
                + same(owner.value, replacement) + ":" + different(instance, replacement) + ":"
                + same(throughFirst, throughSecond) + ":" + (throughFirst instanceof Payload)
                + ":" + checkedPayload(throughSecond).number;
    }

    public static Payload checkedPayload(Object value) {
        boolean payload = value instanceof Payload;
        if (!payload) {
            return null;
        }
        return (Payload) value;
    }

    public static boolean same(Object left, Object right) {
        return left == right;
    }

    public static boolean different(Object left, Object right) {
        return left != right;
    }

    public static final class Payload {
        public final String label;
        public final int number;

        public Payload(String label, int number) {
            this.label = label;
            this.number = number;
        }
    }
}
