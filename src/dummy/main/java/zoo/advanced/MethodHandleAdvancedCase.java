package zoo.advanced;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import zoo.Case;

public final class MethodHandleAdvancedCase implements Case {
    @Override
    public String name() {
        return "MethodHandleAdvancedCase";
    }

    @Override
    public String run() throws Exception {
        try {
            return methodHandleBoundary();
        } catch (Throwable throwable) {
            if (throwable instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(throwable);
        }
    }

    public static String methodHandleBoundary() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle target = lookup.findStatic(
                MethodHandleAdvancedCase.class,
                "target",
                MethodType.methodType(int.class, String.class, int.class));
        MethodHandle bound = target.bindTo("abc");
        int bind = (int) bound.invokeExact(4);
        MethodHandle asType = bound.asType(MethodType.methodType(Object.class, Object.class));
        Object converted = asType.invoke(Integer.valueOf(5));
        MethodHandle dropped = MethodHandles.dropArguments(bound, 0, String.class);
        int drop = (int) dropped.invokeExact("ignored", 6);
        MethodHandle combine = lookup.findStatic(
                MethodHandleAdvancedCase.class,
                "combine",
                MethodType.methodType(int.class, int.class, int.class));
        MethodHandle permuted = MethodHandles.permuteArguments(
                combine,
                MethodType.methodType(int.class, int.class, int.class),
                1,
                0);
        int permute = (int) permuted.invokeExact(2, 7);
        MethodHandle twice = lookup.findStatic(
                MethodHandleAdvancedCase.class,
                "twice",
                MethodType.methodType(int.class, int.class));
        MethodHandle filtered = MethodHandles.filterArguments(combine, 0, twice);
        int filter = (int) filtered.invokeExact(3, 4);
        MethodHandle seed = lookup.findStatic(
                MethodHandleAdvancedCase.class,
                "seed",
                MethodType.methodType(int.class));
        MethodHandle folded = MethodHandles.foldArguments(combine, seed);
        int fold = (int) folded.invokeExact(9);
        MethodHandle sumArray = lookup.findStatic(
                MethodHandleAdvancedCase.class,
                "sumArray",
                MethodType.methodType(int.class, int[].class));
        MethodHandle collector = sumArray.asCollector(int[].class, 3);
        int collect = (int) collector.invokeExact(1, 2, 3);
        return bind + ":" + converted + ":" + drop + ":" + permute + ":" + filter + ":" + fold + ":" + collect;
    }

    private static int target(String prefix, int value) {
        return prefix.length() + value;
    }

    private static int combine(int left, int right) {
        return left * 10 + right;
    }

    private static int twice(int value) {
        return value * 2;
    }

    private static int seed() {
        return 4;
    }

    private static int sumArray(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
