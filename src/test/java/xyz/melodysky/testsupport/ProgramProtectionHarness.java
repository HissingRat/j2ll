package xyz.melodysky.testsupport;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Child-JVM runner for the program/LLVM protection integration fixture. */
public final class ProgramProtectionHarness {
    private ProgramProtectionHarness() {
    }

    public static void main(String[] args) throws Exception {
        URL jar = Path.of(args[0]).toUri().toURL();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {jar}, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = load(loader);
            System.out.println(invoke(type, "inlineCaller", new Class<?>[] {int.class}, 5));
            System.out.println(invoke(type, "indirectIntCaller", new Class<?>[] {int.class}, 5));
            System.out.println(invoke(type, "indirectLongCaller", new Class<?>[] {long.class}, 4L));
            System.out.println(invoke(
                    type,
                    "splitCandidate",
                    new Class<?>[] {int.class, int.class},
                    3,
                    4));
            System.out.println(invoke(type, "branchCandidate", new Class<?>[] {int.class}, 5));
            System.out.println(invoke(type, "branchCandidate", new Class<?>[] {int.class}, -5));
            try {
                invoke(type, "indirectIntCaller", new Class<?>[] {int.class}, 0);
                System.out.println("missing-error");
            } catch (InvocationTargetException exception) {
                System.out.println(exception.getCause().getClass().getSimpleName());
            }
        }
    }

    private static Class<?> load(URLClassLoader loader) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return Class.forName("pkg.PassOps", true, loader);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static Object invoke(
            Class<?> type,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        return method.invoke(null, arguments);
    }
}
