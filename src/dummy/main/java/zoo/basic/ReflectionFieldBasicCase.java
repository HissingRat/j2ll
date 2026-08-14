package zoo.basic;

import java.lang.reflect.Field;
import zoo.Case;

public final class ReflectionFieldBasicCase implements Case {
    @Override
    public String name() {
        return "ReflectionFieldBasicCase";
    }

    @Override
    public String run() throws Exception {
        Target target = new Target();
        String primitiveState = mutatePrimitives(target);
        String referenceState = mutateReferences(target);
        return primitiveState + ":" + referenceState;
    }

    public static String mutatePrimitives(Target target) throws Exception {
        Field publicInt = declared("publicInt");
        Field privateBoolean = declared("privateBoolean");
        Field privateLong = declared("privateLong");
        Field privateDouble = declared("privateDouble");

        publicInt.setInt(target, -71);
        privateBoolean.setBoolean(target, true);
        privateLong.setLong(target, 0x1_0000_0007L);
        privateDouble.setDouble(target, -0.0d);
        return publicInt.getInt(target) + ":" + privateBoolean.getBoolean(target) + ":"
                + privateLong.getLong(target) + ":"
                + Double.doubleToRawLongBits(privateDouble.getDouble(target));
    }

    public static String mutateReferences(Target target) throws Exception {
        Field nullable = declared("nullable");
        Field shared = declared("shared");
        boolean initiallyNull = nullable.get(target) == null;
        Object token = new Object();
        nullable.set(target, token);
        shared.set(null, token);
        Object instanceValue = nullable.get(target);
        Object staticValue = shared.get(null);
        return initiallyNull + ":" + (instanceValue == token) + ":" + (staticValue == token)
                + ":" + (instanceValue == staticValue);
    }

    private static Field declared(String name) throws Exception {
        Field field = Target.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public static final class Target {
        public int publicInt;
        public Object nullable;
        private boolean privateBoolean;
        private long privateLong;
        private double privateDouble;
        private static Object shared;
    }
}
