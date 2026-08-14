package zoo.advanced;

import java.lang.reflect.Field;
import sun.misc.Unsafe;
import zoo.Case;

public final class UnsafeRawMemoryBoundaryCase implements Case {
    @Override
    public String name() {
        return "UnsafeRawMemoryBoundaryCase";
    }

    @Override
    public String run() throws Exception {
        return Long.toString(rawRoundTrip(0x1020_3040_5060_7080L));
    }

    public static long rawRoundTrip(long value) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        long address = unsafe.allocateMemory(Long.BYTES);
        try {
            unsafe.putLong(address, value);
            return unsafe.getLong(address);
        } finally {
            unsafe.freeMemory(address);
        }
    }
}
