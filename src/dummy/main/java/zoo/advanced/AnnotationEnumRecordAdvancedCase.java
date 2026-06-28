package zoo.advanced;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import zoo.Case;

@AnnotationEnumRecordAdvancedCase.Marker("case")
public final class AnnotationEnumRecordAdvancedCase implements Case {
    @Override
    public String name() {
        return "AnnotationEnumRecordAdvancedCase";
    }

    @Override
    public String run() {
        Marker marker = AnnotationEnumRecordAdvancedCase.class.getAnnotation(Marker.class);
        GenericBox<String> box = new GenericBox<>("g");
        Pair pair = new Pair("p", 9);
        Outer outer = new Outer("o");
        Object anonymous = new Runnable() {
            @Override
            public void run() {}

            @Override
            public String toString() {
                return "anon";
            }
        };
        return marker.value() + ":" + ZooEnum.BETA.name() + ":" + ZooEnum.valueOf("ALPHA").code
                + ":" + box.value() + ":" + pair.name() + pair.count()
                + ":" + Outer.StaticInner.value() + ":" + outer.new Inner().value() + ":" + anonymous;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker {
        String value();
    }

    private enum ZooEnum {
        ALPHA(1),
        BETA(2);

        private final int code;

        ZooEnum(int code) {
            this.code = code;
        }
    }

    private static final class GenericBox<T> {
        private final T value;

        private GenericBox(T value) {
            this.value = value;
        }

        private T value() {
            return value;
        }
    }

    private record Pair(String name, int count) {}

    private static final class Outer {
        private final String prefix;

        private Outer(String prefix) {
            this.prefix = prefix;
        }

        private static final class StaticInner {
            private static String value() {
                return "static-inner";
            }
        }

        private final class Inner {
            private String value() {
                return prefix + "-inner";
            }
        }
    }
}
