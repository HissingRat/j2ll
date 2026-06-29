package zoo.advanced;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import zoo.Case;

@LanguageSurfaceAdvancedCase.Tag("alpha")
@LanguageSurfaceAdvancedCase.Tag("beta")
public final class LanguageSurfaceAdvancedCase implements Case {
    @Override
    public String name() {
        return "LanguageSurfaceAdvancedCase";
    }

    @Override
    public String run() throws Exception {
        return sealedDispatch()
                + ":" + enumSwitch()
                + ":" + multiArray()
                + ":" + textBlock()
                + ":" + patternMatching()
                + ":" + recordPatternSwitch()
                + ":" + tryWithResources()
                + ":" + annotations()
                + ":" + serialization()
                + ":" + dynamicProxy()
                + ":" + customClassLoader();
    }

    public static String sealedDispatch() {
        Node node = new Leaf(6);
        return node.kind() + node.value();
    }

    public static String enumSwitch() {
        return switch (Mode.FAST) {
            case FAST -> "fast";
            case SAFE -> "safe";
        };
    }

    public static String multiArray() {
        int[][][] cube = new int[2][2][2];
        cube[1][0][1] = 37;
        String[][] words = {{"a", "b"}, {"c", "d"}};
        return cube.length + "." + cube[1].length + "." + cube[1][0][1] + "." + words[1][0];
    }

    public static String textBlock() {
        String text = """
                alpha
                beta
                """;
        return text.strip().replace('\n', '/');
    }

    public static String patternMatching() {
        Object text = "pattern";
        String prefix = text instanceof String value && value.length() > 3
                ? value.substring(0, 3)
                : "none";
        Object number = Integer.valueOf(12);
        String switched = switch (number) {
            case Integer value when value > 10 -> "big" + value;
            case Integer value -> "int" + value;
            case String value -> value;
            default -> "other";
        };
        return prefix + "." + switched;
    }

    public static String recordPatternSwitch() {
        Object command = new Move(new Point(3, 4));
        return switch (command) {
            case Move(Point(int x, int y)) -> "move" + (x + y);
            case Stop(String reason) -> "stop" + reason;
            default -> "other";
        };
    }

    public static String tryWithResources() {
        try (FailingCloseable first = new FailingCloseable("first");
                FailingCloseable second = new FailingCloseable("second")) {
            throw new IOException("body");
        } catch (IOException exception) {
            return exception.getMessage() + "." + exception.getSuppressed().length
                    + "." + exception.getSuppressed()[0].getMessage();
        }
    }

    public static String annotations() throws Exception {
        Tag[] tags = LanguageSurfaceAdvancedCase.class.getAnnotationsByType(Tag.class);
        java.lang.reflect.Field field = AnnotatedField.class.getDeclaredField("value");
        boolean typeUse = field.getAnnotatedType().getAnnotation(TypeUse.class) != null;
        return tags.length + "." + tags[0].value() + "." + tags[1].value() + "."
                + Defaults.class.getDeclaredMethod("count").getDefaultValue() + "." + typeUse;
    }

    public static String serialization() throws Exception {
        Payload payload = new Payload("ser", 12);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(payload);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            Payload restored = (Payload) input.readObject();
            return restored.name + restored.value;
        }
    }

    public static String dynamicProxy() {
        InvocationHandler handler = (proxy, method, args) -> method.getName() + "-" + args[0];
        Greeter greeter = (Greeter) Proxy.newProxyInstance(
                LanguageSurfaceAdvancedCase.class.getClassLoader(),
                new Class<?>[] {Greeter.class},
                handler);
        return greeter.hello("proxy");
    }

    public static String customClassLoader() throws Exception {
        URL location = LanguageSurfaceAdvancedCase.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {location}, null)) {
            Class<?> loaded = Class.forName("zoo.versioned.VersionedFeature", true, loader);
            Object value = loaded.getDeclaredMethod("value").invoke(null);
            return loaded.getClassLoader().getClass().getSimpleName() + "." + value;
        }
    }

    private sealed interface Node permits Leaf {
        String kind();

        int value();
    }

    private record Leaf(int value) implements Node {
        @Override
        public String kind() {
            return "leaf";
        }
    }

    private record Point(int x, int y) {}

    private record Move(Point point) {}

    private record Stop(String reason) {}

    private enum Mode {
        FAST,
        SAFE
    }

    private static final class FailingCloseable implements AutoCloseable {
        private final String name;

        private FailingCloseable(String name) {
            this.name = name;
        }

        @Override
        public void close() throws IOException {
            throw new IOException(name);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Repeatable(Tags.class)
    public @interface Tag {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Tags {
        Tag[] value();
    }

    public @interface Defaults {
        int count() default 7;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface TypeUse {}

    private static final class AnnotatedField {
        private @TypeUse String value = "annotated";
    }

    private static final class Payload implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final int value;

        private Payload(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

    private interface Greeter {
        String hello(String name);
    }
}
