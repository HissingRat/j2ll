package bench;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

final class FeatureScenarios {

    private FeatureScenarios() {
    }

    static int runNativeSlice() {
        NativeSlice.setBase(3);
        return NativeSlice.compute(6);
    }

    static int runArrayScenario() {
        int[] values = {2, 4, 6, 8};
        int[][] matrix = {
                {1, 2},
                {3, 4}
        };
        int sum = 0;
        for (int value : values) {
            sum += value;
        }
        for (int[] row : matrix) {
            for (int value : row) {
                sum += value;
            }
        }
        return sum;
    }

    static int runInnerClassScenario() {
        OuterBox box = new OuterBox(10);
        OuterBox.Inner inner = box.new Inner();
        OuterBox.Nested nested = new OuterBox.Nested();
        IntUnaryOperator anonymous = new IntUnaryOperator() {
            @Override
            public int applyAsInt(int operand) {
                return operand + 4;
            }
        };
        class LocalAdder {
            int add(int value) {
                return value + 5;
            }
        }
        return inner.bump(1) + nested.bump(2) + anonymous.applyAsInt(3) + new LocalAdder().add(4);
    }

    static int runEnumScenario() {
        return Mode.ADD.apply(7, 5) + Mode.MUL.apply(3, 4);
    }

    static int runLambdaScenario() {
        List<String> parts = List.of("ja", "va", "25");
        int lengthSum = parts.stream()
                .map(String::toUpperCase)
                .mapToInt(String::length)
                .sum();
        int parsed = parts.stream()
                .filter(part -> part.chars().allMatch(Character::isDigit))
                .mapToInt(Integer::parseInt)
                .sum();
        return lengthSum + parsed;
    }

    static int runRecordAndSealedScenario() {
        Pair pair = new Pair(11, 4);
        Expr expression = new Add(new Value(pair.left()), new Multiply(new Value(pair.right()), new Value(3)));
        return eval(expression) + pair.plusOffset(2);
    }

    static int runGenericsScenario() {
        Box<Integer> box = new Box<>(7);
        Transformer<Integer> transformer = value -> value * 3;
        return transformer.apply(box.get()) + transformer.identity(2);
    }

    static int runExceptionScenario() throws IOException {
        byte[] bytes = "128".getBytes(StandardCharsets.UTF_8);
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            int parsed = Integer.parseInt(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (parsed < 0) {
                throw new IllegalStateException("negative parse");
            }
            return parsed;
        } finally {
            AtomicInteger marker = new AtomicInteger();
            marker.incrementAndGet();
        }
    }

    static int runStringBasicScenario() {
        String text = "j2ll-obf-llvmforge";
        String middle = text.substring(5, 8);
        int score = middle.equals("obf") ? 10 : 0;
        score += text.startsWith("j2ll") ? 3 : 0;
        score += text.endsWith("forge") ? 5 : 0;
        score += text.contains("-llvm-") ? 7 : 0;
        score += text.indexOf("ll") >= 0 ? 11 : 0;
        score += text.charAt(0) == 'j' ? 13 : 0;
        score += "NATIVE".equalsIgnoreCase("native") ? 17 : 0;
        return score;
    }

    static String runStringBuilderScenario() {
        String prefix = "llvm";
        String suffix = "forge";
        return new StringBuilder()
                .append("j2ll")
                .append('-')
                .append(prefix)
                .append('-')
                .append(suffix)
                .append('-')
                .append(25)
                .toString();
    }

    static int runStringSwitchScenario() {
        String mode = "bench";
        return switch (mode) {
            case "warmup" -> 1;
            case "bench" -> 2;
            case "report" -> 3;
            default -> -1;
        };
    }

    static String runTextBlockScenario() {
        String text = """
                j2ll-obf
                llvm-forge
                java25
                """;
        return text.strip().lines()
                .map(line -> line.toLowerCase(Locale.ROOT))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    static String runStringUnicodeScenario() {
        String text = "火箭-IR-锻造";
        String normalized = text.replace("IR", "llvm");
        return normalized + "|" + normalized.codePointCount(0, normalized.length());
    }

    static String runLongConcatScenario() {
        BenchTimer sessionTime = new BenchTimer(11_225_000L);
        long seconds = sessionTime.getTime() / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds = seconds % 60;
        minutes = minutes % 60;
        return "Time: " + hours + "h " + minutes + "min " + seconds + "s";
    }

    static String runEmojiConcatScenario() {
        return "§c🎵 ERR§fCaused by " + NoSuchFieldError.class.getName() + " : default_u";
    }

    static int runReferenceEqualityScenario() {
        Session current = new Session("bench-session");
        Holder holder = new Holder();
        holder.last = current;
        Object alias = current;
        int score = 0;
        if (alias == holder.last) {
            score += 7;
        }
        if (holder.last != new Session("bench-session")) {
            score += 11;
        }
        return score;
    }

    static String runInvokeSpecialScenario() {
        return new DerivedScreen().close();
    }

    static int runConstructorChainScenario() {
        ConstructedTimer timer = new ConstructedTimer("bench-worker", true);
        ConstructedSocket socket = new ConstructedSocket(new BenchUri("wss://bench/native"));
        return (timer.summary().equals("bench-worker:true") ? 10 : 0)
                + (socket.summary().equals("wss://bench/native|ready") ? 20 : 0);
    }

    static int runMethodReferencePropagationScenario() {
        ScreenLike screen = new ScreenLike();
        return screen.render();
    }

    static int runConstructorLambdaScenario() {
        return ConstructorLambdaHolder.SUPPLIER.get().score()
                + ConstructorLambdaHolder.NAMED.apply("forge").score();
    }

    static int runTryCatchCallbackScenario() {
        CallbackBox box = new CallbackBox();
        AtomicInteger callbackCount = new AtomicInteger();
        box.listener = value -> {
            callbackCount.incrementAndGet();
            throw new IllegalStateException("expected callback failure");
        };
        box.setValue("bench");
        return "bench".equals(box.value) && callbackCount.get() == 1 ? 17 : 0;
    }

    static String runAnnotationReflectionScenario() {
        BenchTag tag = AnnotatedFeature.class.getAnnotation(BenchTag.class);
        return tag.name() + "|" + tag.level().name() + "|" + tag.owner().getSimpleName();
    }

    static int runConcurrentNativeScenario() throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> tasks = List.of(
                    () -> repeatNativeCalls(20),
                    () -> repeatNativeCalls(20),
                    () -> repeatNativeCalls(20),
                    () -> repeatNativeCalls(20)
            );
            List<Future<Integer>> futures = executor.invokeAll(tasks);
            int sum = 0;
            for (Future<Integer> future : futures) {
                sum += future.get();
            }
            return sum;
        } finally {
            executor.shutdownNow();
        }
    }

    static int runFontLoaderPropagationScenario() {
        return FontLoaderLike.primary(16).drawStringWithShadow("ab");
    }

    static int runGuiRenderPropagationScenario() {
        return new ScreenShell().render();
    }

    private static int repeatNativeCalls(int iterations) {
        int sum = 0;
        for (int index = 0; index < iterations; index++) {
            sum += runReferenceEqualityScenario();
            sum += runConstructorChainScenario();
        }
        return sum;
    }

    interface Transformer<T> {
        T apply(T value);

        default T identity(T value) {
            return value;
        }
    }

    static final class Box<T> {
        private final T value;

        Box(T value) {
            this.value = value;
        }

        T get() {
            return value;
        }
    }

    static final class OuterBox {
        private final int base;

        OuterBox(int base) {
            this.base = base;
        }

        final class Inner {
            int bump(int delta) {
                return base + delta;
            }
        }

        static final class Nested {
            int bump(int delta) {
                return delta + 20;
            }
        }
    }

    enum Mode {
        ADD {
            @Override
            int apply(int left, int right) {
                return left + right;
            }
        },
        MUL {
            @Override
            int apply(int left, int right) {
                return left * right;
            }
        };

        abstract int apply(int left, int right);
    }

    record Pair(int left, int right) implements HasOffset {
        @Override
        public int offset() {
            return left - right;
        }
    }

    interface HasOffset {
        int offset();

        default int plusOffset(int value) {
            return value + offset();
        }
    }

    sealed interface Expr permits Value, Add, Multiply {
    }

    record Value(int value) implements Expr {
    }

    record Add(Expr left, Expr right) implements Expr {
    }

    record Multiply(Expr left, Expr right) implements Expr {
    }

    static int eval(Expr expression) {
        return switch (expression) {
            case Value(int value) -> value;
            case Add(Expr left, Expr right) -> eval(left) + eval(right);
            case Multiply(Expr left, Expr right) -> eval(left) * eval(right);
        };
    }

    static final class Session {
        private final String id;

        Session(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    static final class Holder {
        private Session last;
    }

    static final class BenchTimer {
        private final long millis;

        BenchTimer(long millis) {
            this.millis = millis;
        }

        long getTime() {
            return millis;
        }
    }

    static class BaseScreen {
        String close() {
            return "base";
        }
    }

    static final class DerivedScreen extends BaseScreen {
        @Override
        String close() {
            return super.close() + "-child";
        }
    }

    static final class BenchUri {
        private final String raw;

        BenchUri(String raw) {
            this.raw = raw;
        }

        String raw() {
            return raw;
        }
    }

    static final class ConstructedTimer {
        private final String name;
        private final boolean daemon;

        ConstructedTimer(String name, boolean daemon) {
            this.name = name;
            this.daemon = daemon;
        }

        String summary() {
            return name + ":" + daemon;
        }
    }

    static final class ConstructedSocket {
        private final BenchUri uri;
        private final String state;

        ConstructedSocket(BenchUri uri) {
            this.uri = uri;
            this.state = "ready";
        }

        String summary() {
            return uri.raw() + "|" + state;
        }
    }

    static final class Glyph {
        private final char value;
        private final int width;

        Glyph(char value, int width) {
            this.value = value;
            this.width = width;
        }

        int width() {
            return width;
        }

        char value() {
            return value;
        }
    }

    static final class FontLoaderLike {
        private FontLoaderLike() {
        }

        static FontLikeRenderer primary(int size) {
            try {
                return new FontLikeRenderer(size);
            } catch (RuntimeException exception) {
                return fallback(size);
            }
        }

        static FontLikeRenderer fallback(int size) {
            try {
                return new FontLikeRenderer(Math.max(1, size - 1));
            } catch (RuntimeException exception) {
                return new FontLikeRenderer(1);
            }
        }
    }

    static final class FontLikeRenderer {
        private final Map<Character, Glyph> glyphs = new HashMap<>();
        private final int scale;

        FontLikeRenderer() {
            this(2);
        }

        FontLikeRenderer(int scale) {
            this.scale = scale;
        }

        private Glyph locateGlyph0(char glyph) {
            String normalized = stripControlCodes(String.valueOf(glyph));
            return normalized.isEmpty() ? null : new Glyph(normalized.charAt(0), normalized.charAt(0) == ' ' ? 1 : Math.max(2, scale / 8));
        }

        private Glyph locateGlyph1(char glyph) {
            Function<Character, Glyph> factory = this::locateGlyph0;
            return Objects.requireNonNull(glyphs.computeIfAbsent(glyph, factory));
        }

        int getStringWidth(String text) {
            int total = 0;
            for (char c : stripControlCodes(text).toCharArray()) {
                total += locateGlyph1(c).width();
            }
            return total;
        }

        int drawStringWithShadow(String text) {
            return getStringWidth(text) + getStringWidth(text);
        }

        int getFontHeight(String text) {
            return getStringWidth(text) + scale;
        }

        private String stripControlCodes(String text) {
            StringBuilder builder = new StringBuilder(text.length());
            char[] chars = text.toCharArray();
            for (int index = 0; index < chars.length; index++) {
                char value = chars[index];
                if (value == '\u00a7' && index + 1 < chars.length) {
                    index++;
                    continue;
                }
                builder.append(value);
            }
            return builder.toString();
        }
    }

    static final class HudLikeModule {
        private final FontLikeRenderer renderer = new FontLikeRenderer();

        int render() {
            return renderer.drawStringWithShadow("ab");
        }
    }

    static final class ScreenLike {
        private final HudLikeModule module = new HudLikeModule();

        int render() {
            return module.render();
        }
    }

    static final class ButtonShell {
        private final FontLikeRenderer renderer;

        ButtonShell(FontLikeRenderer renderer) {
            this.renderer = renderer;
        }

        int drawButton() {
            return renderer.getFontHeight("ab") + renderer.drawStringWithShadow("ab");
        }
    }

    static final class ScreenShell {
        private final ButtonShell button = new ButtonShell(FontLoaderLike.primary(16));

        int render() {
            return button.drawButton();
        }
    }

    static final class CallbackBox {
        private String value;
        private Consumer<String> listener;

        void setValue(String value) {
            this.value = value;
            try {
                if (listener != null) {
                    listener.accept(value);
                }
            } catch (Exception ignored) {
            }
        }
    }

    enum BenchLevel {
        LOW,
        HIGH
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface BenchTag {
        String name() default "bench";

        BenchLevel level() default BenchLevel.HIGH;

        Class<?> owner() default FeatureScenarios.class;
    }

    @BenchTag
    static final class AnnotatedFeature {
    }

    static final class ConstructorProduct {
        private final String text;

        ConstructorProduct() {
            this("bench");
        }

        ConstructorProduct(String text) {
            this.text = text;
        }

        int score() {
            return text.length();
        }
    }

    static final class ConstructorLambdaHolder {
        private static final Supplier<ConstructorProduct> SUPPLIER = ConstructorProduct::new;
        private static final Function<String, ConstructorProduct> NAMED = ConstructorProduct::new;
    }
}
