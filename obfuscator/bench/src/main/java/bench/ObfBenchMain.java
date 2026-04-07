package bench;

import java.util.List;

public final class ObfBenchMain {

    private ObfBenchMain() {
    }

    public static void main(String[] args) {
        List<TestCase> testCases = List.of(
                new TestCase("native slice test", () -> assertEquals(23, FeatureScenarios.runNativeSlice())),
                new TestCase("array test", () -> assertEquals(30, FeatureScenarios.runArrayScenario())),
                new TestCase("inner class test", () -> assertEquals(49, FeatureScenarios.runInnerClassScenario())),
                new TestCase("enum test", () -> assertEquals(24, FeatureScenarios.runEnumScenario())),
                new TestCase("lambda test", () -> assertEquals(31, FeatureScenarios.runLambdaScenario())),
                new TestCase("record sealed test", () -> assertEquals(32, FeatureScenarios.runRecordAndSealedScenario())),
                new TestCase("generic test", () -> assertEquals(23, FeatureScenarios.runGenericsScenario())),
                new TestCase("exception test", () -> assertEquals(128, FeatureScenarios.runExceptionScenario())),
                new TestCase("string basic test", () -> assertEquals(59, FeatureScenarios.runStringBasicScenario())),
                new TestCase("string builder test", () -> assertEquals("j2ll-llvm-forge-25", FeatureScenarios.runStringBuilderScenario())),
                new TestCase("string switch test", () -> assertEquals(2, FeatureScenarios.runStringSwitchScenario())),
                new TestCase("text block test", () -> assertEquals("j2ll-obf|llvm-forge|java25", FeatureScenarios.runTextBlockScenario())),
                new TestCase("string unicode test", () -> assertEquals("火箭-llvm-锻造|10", FeatureScenarios.runStringUnicodeScenario())),
                new TestCase("long concat test", () -> assertEquals("Time: 3h 7min 5s", FeatureScenarios.runLongConcatScenario())),
                new TestCase("emoji concat test", () -> assertEquals("§c🎵 ERR§fCaused by java.lang.NoSuchFieldError : default_u", FeatureScenarios.runEmojiConcatScenario())),
                new TestCase("reference equality test", () -> assertEquals(18, FeatureScenarios.runReferenceEqualityScenario())),
                new TestCase("invokespecial test", () -> assertEquals("base-child", FeatureScenarios.runInvokeSpecialScenario())),
                new TestCase("constructor chain test", () -> assertEquals(30, FeatureScenarios.runConstructorChainScenario())),
                new TestCase("constructor lambda test", () -> assertEquals(10, FeatureScenarios.runConstructorLambdaScenario())),
                new TestCase("method reference propagation test", () -> assertEquals(8, FeatureScenarios.runMethodReferencePropagationScenario())),
                new TestCase("try catch callback test", () -> assertEquals(17, FeatureScenarios.runTryCatchCallbackScenario())),
                new TestCase("annotation reflection test", () -> assertEquals("bench|HIGH|FeatureScenarios", FeatureScenarios.runAnnotationReflectionScenario())),
                new TestCase("concurrent native test", () -> assertEquals(3840, FeatureScenarios.runConcurrentNativeScenario())),
                new TestCase("font loader propagation test", () -> assertEquals(8, FeatureScenarios.runFontLoaderPropagationScenario())),
                new TestCase("gui render propagation test", () -> assertEquals(28, FeatureScenarios.runGuiRenderPropagationScenario()))
        );

        int failures = 0;
        for (TestCase testCase : testCases) {
            try {
                testCase.body().run();
                System.out.println(testCase.name() + " -> pass");
            } catch (Throwable throwable) {
                failures++;
                System.out.println(testCase.name() + " -> fail: " + throwable);
                throwable.printStackTrace(System.out);
            }
        }

        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("expected " + expected + " but got " + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private record TestCase(String name, ThrowingRunnable body) {
    }
}
