package xyz.melodysky.testsupport;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Child-JVM fixture for checking that generated native static state remains
 * isolated by the defining class loader.
 */
public final class IsolatedStaticStateHarness {
    private IsolatedStaticStateHarness() {
    }

    public static void main(String[] args) throws Exception {
        URL jar = Path.of(args[0]).toUri().toURL();
        try (URLClassLoader firstLoader = loader(jar);
                URLClassLoader secondLoader = loader(jar)) {
            Class<?> first = load(firstLoader);
            Class<?> second = load(secondLoader);

            exerciseConcurrently(first, 6, 100);
            System.out.println("first=" + counter(first) + "/" + total(first));
            System.out.println("second-before=" + counter(second) + "/" + total(second));
            second.getMethod("add", int.class).invoke(null, 7);
            second.getMethod("addLong", long.class).invoke(null, 11L);
            System.out.println("second-after=" + counter(second) + "/" + total(second));
            System.out.println("narrow="
                    + invokeInt(first, "setByte", 255) + "/"
                    + invokeInt(first, "setShort", 65535) + "/"
                    + invokeInt(first, "setChar", -1) + "/"
                    + invokeInt(first, "setBoolean", 2) + "/"
                    + invokeInt(first, "setBoolean", 3));
            float nan = Float.intBitsToFloat(0x7fa12345);
            float negativeZero = -0.0f;
            System.out.println("float-bits="
                    + Integer.toHexString(Float.floatToRawIntBits(
                            (float) first.getMethod("setFloat", float.class).invoke(null, nan)))
                    + "/"
                    + Integer.toHexString(Float.floatToRawIntBits(
                            (float) first.getMethod("setFloat", float.class).invoke(null, negativeZero))));
            double doubleNan = Double.longBitsToDouble(0x7ff123456789abcdL);
            double doubleNegativeZero = -0.0d;
            System.out.println("double-bits="
                    + Long.toHexString(Double.doubleToRawLongBits(
                            (double) first.getMethod("setDouble", double.class).invoke(null, doubleNan)))
                    + "/"
                    + Long.toHexString(Double.doubleToRawLongBits(
                            (double) first.getMethod("setDouble", double.class).invoke(null, doubleNegativeZero))));
            Object marker = new Object();
            WeakReference<Object> weak = new WeakReference<>(marker);
            Object returned = first.getMethod("setObject", Object.class).invoke(null, marker);
            boolean same = returned == marker;
            returned = null;
            marker = null;
            forceGc();
            Object retained = first.getMethod("getObject").invoke(null);
            boolean held = retained != null && retained == weak.get();
            first.getMethod("setObject", Object.class).invoke(null, new Object[] {null});
            boolean cleared = first.getMethod("getObject").invoke(null) == null;
            System.out.println("object=" + same + "/" + held + "/" + cleared);
            exerciseInstanceByteArray(first, firstLoader);
            first.getMethod("setObject", Object.class).invoke(null, "llvm-value");
            String skippedRead =
                    (String) first.getMethod("skippedRead").invoke(null);
            String skippedWrite = (String) first.getMethod(
                            "skippedWrite",
                            String.class)
                    .invoke(null, "skipped-value");
            Object llvmRead = first.getMethod("getObject").invoke(null);
            System.out.println("skipped-shared="
                    + skippedRead
                    + "/"
                    + skippedWrite
                    + "/"
                    + llvmRead);
            System.out.println("second-types="
                    + invokeInt(second, "setByte", 0) + "/"
                    + invokeInt(second, "setShort", 0) + "/"
                    + invokeInt(second, "setChar", 0) + "/"
                    + invokeInt(second, "setBoolean", 0) + "/"
                    + Integer.toHexString(Float.floatToRawIntBits(
                            (float) second.getMethod("setFloat", float.class).invoke(null, 0.0f)))
                    + "/"
                    + Long.toHexString(Double.doubleToRawLongBits(
                            (double) second.getMethod("setDouble", double.class).invoke(null, 0.0d)))
                    + "/"
                    + second.getMethod("getObject").invoke(null)
                    + "/"
                    + second.getMethod("getInstanceByteArray").invoke(null));
        }
    }

    private static URLClassLoader loader(URL jar) {
        return new URLClassLoader(new URL[] {jar}, ClassLoader.getPlatformClassLoader());
    }

    private static Class<?> load(URLClassLoader loader) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return Class.forName("pkg.NativeState", true, loader);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void exerciseConcurrently(Class<?> type, int workers, int iterations) throws Exception {
        Method add = type.getMethod("add", int.class);
        Method addLong = type.getMethod("addLong", long.class);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        add.invoke(null, 1);
                        addLong.invoke(null, 2L);
                    }
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private static int counter(Class<?> type) throws Exception {
        return (int) type.getMethod("getCounter").invoke(null);
    }

    private static long total(Class<?> type) throws Exception {
        return (long) type.getMethod("getTotal").invoke(null);
    }

    private static int invokeInt(Class<?> type, String method, int value) throws Exception {
        return (int) type.getMethod(method, int.class).invoke(null, value);
    }

    private static void exerciseInstanceByteArray(Class<?> type, ClassLoader loader) throws Exception {
        Class<?> childType = Class.forName("pkg.NativeStateChild", true, loader);
        Object base = type.getConstructor().newInstance();
        Object child = childType.getConstructor().newInstance();
        Method set = type.getMethod("setInstanceByteArray", byte[].class);
        Method get = type.getMethod("getInstanceByteArray");

        byte[] baseValue = {1, 2, 3};
        set.invoke(base, (Object) baseValue);
        byte[] afterBase = (byte[]) get.invoke(null);

        byte[] childValue = {4, 5, 6};
        set.invoke(child, (Object) childValue);
        byte[] afterChild = (byte[]) get.invoke(null);
        System.out.println("instance-array="
                + (afterBase == baseValue) + "/" + sum(afterBase) + "/"
                + (afterChild == childValue) + "/" + sum(afterChild));
    }

    private static int sum(byte[] values) {
        int total = 0;
        for (byte value : values) {
            total += value;
        }
        return total;
    }

    private static void forceGc() throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            Thread.sleep(10L);
        }
    }
}
