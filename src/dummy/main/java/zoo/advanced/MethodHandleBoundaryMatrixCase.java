package zoo.advanced;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import zoo.Case;

public final class MethodHandleBoundaryMatrixCase implements Case {
    @Override
    public String name() {
        return "MethodHandleBoundaryMatrixCase";
    }

    @Override
    public String run() throws Exception {
        try {
            return chainAdapter() + ":" + permuteAdapter() + ":" + filterAdapter()
                    + ":" + foldAdapter() + ":" + collectorAdapter();
        } catch (Throwable throwable) {
            if (throwable instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(throwable);
        }
    }

    public static String chainAdapter() throws Throwable {
        MethodHandle target = lookup("target", MethodType.methodType(int.class, String.class, int.class));
        MethodHandle bound = target.bindTo("abc");
        int exact = (int) bound.invokeExact(4);
        Object converted = bound.asType(MethodType.methodType(Object.class, Object.class))
                .invoke(Integer.valueOf(5));
        MethodHandle dropped = MethodHandles.dropArguments(bound, 0, String.class);
        int ignored = (int) dropped.invokeExact("ignored", 6);
        return exact + "." + converted + "." + ignored;
    }

    public static int permuteAdapter() throws Throwable {
        MethodHandle handle = MethodHandles.permuteArguments(
                lookup("combine", MethodType.methodType(int.class, int.class, int.class)),
                MethodType.methodType(int.class, int.class, int.class),
                1,
                0);
        return (int) handle.invokeExact(2, 7);
    }

    public static int filterAdapter() throws Throwable {
        MethodHandle handle = MethodHandles.filterArguments(
                lookup("combine", MethodType.methodType(int.class, int.class, int.class)),
                0,
                lookup("twice", MethodType.methodType(int.class, int.class)));
        return (int) handle.invokeExact(3, 4);
    }

    public static int foldAdapter() throws Throwable {
        MethodHandle handle = MethodHandles.foldArguments(
                lookup("combine", MethodType.methodType(int.class, int.class, int.class)),
                lookup("seed", MethodType.methodType(int.class)));
        return (int) handle.invokeExact(9);
    }

    public static int collectorAdapter() throws Throwable {
        MethodHandle handle = lookup("sumArray", MethodType.methodType(int.class, int[].class))
                .asCollector(int[].class, 3);
        return (int) handle.invokeExact(1, 2, 3);
    }

    private static MethodHandle lookup(String name, MethodType type) throws ReflectiveOperationException {
        return MethodHandles.lookup().findStatic(MethodHandleBoundaryMatrixCase.class, name, type);
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
        int result = 0;
        for (int value : values) {
            result += value;
        }
        return result;
    }
}
