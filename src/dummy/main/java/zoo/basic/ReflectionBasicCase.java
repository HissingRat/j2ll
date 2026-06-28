package zoo.basic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import zoo.Case;

public final class ReflectionBasicCase implements Case {
    @Override
    public String name() {
        return "ReflectionBasicCase";
    }

    @Override
    public String run() throws Exception {
        Class<?> type = Class.forName("zoo.basic.ReflectionBasicCase$Target");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class);
        constructor.setAccessible(true);
        Object target = constructor.newInstance("r", 12);
        Method method = type.getDeclaredMethod("combine", int.class, String[].class);
        method.setAccessible(true);
        Field field = type.getDeclaredField("value");
        field.setAccessible(true);
        field.setInt(target, 15);
        Object result = method.invoke(target, 4, new String[] {"a", "b"});
        return Target.class.getSimpleName() + ":" + field.getInt(target) + ":" + result;
    }

    private static final class Target {
        private final String prefix;
        private int value;

        private Target(String prefix, int value) {
            this.prefix = prefix;
            this.value = value;
        }

        private String combine(int extra, String[] parts) {
            return prefix + ":" + (value + extra) + ":" + parts.length;
        }
    }
}
