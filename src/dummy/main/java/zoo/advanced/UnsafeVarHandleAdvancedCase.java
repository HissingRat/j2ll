package zoo.advanced;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import zoo.Case;

public final class UnsafeVarHandleAdvancedCase implements Case {
    private int value = 11;
    private volatile int volatileValue = 17;

    @Override
    public String name() {
        return "UnsafeVarHandleAdvancedCase";
    }

    @Override
    public String run() throws Exception {
        String unsafe = unsafeSmoke();
        VarHandle valueHandle = MethodHandles.lookup()
                .findVarHandle(UnsafeVarHandleAdvancedCase.class, "value", int.class);
        VarHandle volatileHandle = MethodHandles.lookup()
                .findVarHandle(UnsafeVarHandleAdvancedCase.class, "volatileValue", int.class);
        valueHandle.set(this, 23);
        volatileHandle.setVolatile(this, 29);
        boolean cas = valueHandle.compareAndSet(this, 23, 31);
        return unsafe + ":" + valueHandle.get(this) + ":" + volatileHandle.getVolatile(this) + ":" + cas;
    }

    private String unsafeSmoke() throws Exception {
        Class<?> unsafeClass;
        try {
            unsafeClass = Class.forName("sun.misc.Unsafe");
        } catch (ClassNotFoundException exception) {
            return "JDK_FEATURE_UNAVAILABLE";
        }
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Field valueField = UnsafeVarHandleAdvancedCase.class.getDeclaredField("value");
        Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
        Method getInt = unsafeClass.getMethod("getInt", Object.class, long.class);
        Method putInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
        Method compareAndSwapInt = unsafeClass.getMethod("compareAndSwapInt", Object.class, long.class, int.class, int.class);
        long offset = ((Long) objectFieldOffset.invoke(unsafe, valueField)).longValue();
        putInt.invoke(unsafe, this, offset, 19);
        boolean cas = ((Boolean) compareAndSwapInt.invoke(unsafe, this, offset, 19, 21)).booleanValue();
        int read = ((Integer) getInt.invoke(unsafe, this, offset)).intValue();
        Method allocateMemory = unsafeClass.getMethod("allocateMemory", long.class);
        Method putLong = unsafeClass.getMethod("putLong", long.class, long.class);
        Method getLong = unsafeClass.getMethod("getLong", long.class);
        Method freeMemory = unsafeClass.getMethod("freeMemory", long.class);
        long address = ((Long) allocateMemory.invoke(unsafe, 8L)).longValue();
        try {
            putLong.invoke(unsafe, address, 123L);
            long raw = ((Long) getLong.invoke(unsafe, address)).longValue();
            return read + ":" + cas + ":" + raw;
        } finally {
            freeMemory.invoke(unsafe, address);
        }
    }
}
