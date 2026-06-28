package zoo.advanced;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;
import zoo.Case;

public final class ReflectionAdvancedCase implements Case {
    @Override
    public String name() {
        return "ReflectionAdvancedCase";
    }

    @Override
    public String run() throws Exception {
        String className = "zoo.advanced.ReflectionAdvancedCase$Target";
        String methodName = "echo";
        Class<?>[] parameters = new Class<?>[] {String.class};
        Class<?> type = Class.forName(className);
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object target = constructor.newInstance();
        Method method = type.getDeclaredMethod(methodName, parameters);
        method.setAccessible(true);
        String scans = Arrays.stream(type.getDeclaredMethods())
                .map(Method::getName)
                .sorted()
                .collect(Collectors.joining("+"));
        return method.invoke(target, "dyn") + ":" + scans + ":" + type.getDeclaredFields().length
                + ":" + type.getDeclaredConstructors().length;
    }

    private static final class Target {
        private int value = 3;

        private Target() {}

        private String echo(String input) {
            return input + value;
        }
    }
}
