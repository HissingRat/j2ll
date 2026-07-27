package xyz.melodysky.runtime.unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import xyz.melodysky.runtime.RuntimeHelperKind;

class UnsafePolicyTest {
    @Test
    void recognizesSupportedUnsafeSubset() {
        UnsafePolicy policy = new UnsafePolicy();

        assertEquals(RuntimeHelperKind.UNSAFE_OBJECT_FIELD_OFFSET,
                policy.plan("sun/misc/Unsafe", "objectFieldOffset", "(Ljava/lang/reflect/Field;)J").helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.UNSAFE_STATIC_FIELD_OFFSET,
                policy.plan("sun/misc/Unsafe", "staticFieldOffset", "(Ljava/lang/reflect/Field;)J").helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.UNSAFE_ARRAY_BASE_OFFSET,
                policy.plan("sun/misc/Unsafe", "arrayBaseOffset", "(Ljava/lang/Class;)I").helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.UNSAFE_GET_INT,
                policy.plan("sun/misc/Unsafe", "getInt", "(Ljava/lang/Object;J)I").helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.UNSAFE_PUT_INT,
                policy.plan("sun/misc/Unsafe", "putInt", "(Ljava/lang/Object;JI)V").helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP_INT,
                policy.plan("sun/misc/Unsafe", "compareAndSwapInt", "(Ljava/lang/Object;JII)Z").helperKind().orElseThrow());
        assertTrue(policy.plan("sun/misc/Unsafe", "allocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;").supported());
    }

    @Test
    void marksVolatileAndCasJmmSensitiveOperations() {
        UnsafePolicy policy = new UnsafePolicy();

        UnsafePlan volatileGet = policy.plan("sun/misc/Unsafe", "getIntVolatile", "(Ljava/lang/Object;J)I");
        UnsafePlan cas = policy.plan("sun/misc/Unsafe", "compareAndSwapObject", "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z");

        assertTrue(volatileGet.volatileAccess());
        assertFalse(volatileGet.compareAndSwap());
        assertTrue(cas.volatileAccess());
        assertTrue(cas.compareAndSwap());
    }

    @Test
    void recognizesVarHandleCommonShapesAndUnsupportedUnsafeApis() {
        UnsafePolicy policy = new UnsafePolicy();

        assertEquals(RuntimeHelperKind.UNSAFE_GET,
                policy.plan("java/lang/invoke/VarHandle", "get", "(Ljava/lang/Object;)Ljava/lang/Object;")
                        .helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.VAR_HANDLE_GET_INT,
                policy.plan("java/lang/invoke/VarHandle", "get", "(Lpkg/Target;)I")
                        .helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.VAR_HANDLE_SET_INT,
                policy.plan("java/lang/invoke/VarHandle", "set", "(Lpkg/Target;I)V")
                        .helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.VAR_HANDLE_GET_VOLATILE_INT,
                policy.plan("java/lang/invoke/VarHandle", "getVolatile", "(Lpkg/Target;)I")
                        .helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.VAR_HANDLE_SET_VOLATILE_INT,
                policy.plan("java/lang/invoke/VarHandle", "setVolatile", "(Lpkg/Target;I)V")
                        .helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.VAR_HANDLE_COMPARE_AND_SET_INT,
                policy.plan("java/lang/invoke/VarHandle", "compareAndSet", "(Lpkg/Target;II)Z")
                        .helperKind().orElseThrow());
        assertEquals(RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP,
                policy.plan("java/lang/invoke/VarHandle", "compareAndSet", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z")
                        .helperKind().orElseThrow());
        UnsafePlan unsupported = policy.plan("sun/misc/Unsafe", "getByte", "(J)B");
        assertTrue(unsupported.unsafeOrVarHandleCall());
        assertFalse(unsupported.supported());
        assertTrue(unsupported.reason().contains("UNSAFE_RAW_MEMORY_UNSUPPORTED"));
        UnsafePlan dynamicVarHandle = policy.plan("java/lang/invoke/VarHandle", "withInvokeExactBehavior", "()Ljava/lang/invoke/VarHandle;");
        assertTrue(dynamicVarHandle.reason().contains("VAR_HANDLE_DYNAMIC_UNSUPPORTED"));
    }

    @Test
    void rawOffHeapUnsafeMemoryApisAreSkippedBoundaries() {
        UnsafePolicy policy = new UnsafePolicy();

        for (UnsafePlan plan : java.util.List.of(
                policy.plan("sun/misc/Unsafe", "allocateMemory", "(J)J"),
                policy.plan("sun/misc/Unsafe", "freeMemory", "(J)V"),
                policy.plan("sun/misc/Unsafe", "getLong", "(J)J"),
                policy.plan("sun/misc/Unsafe", "putLong", "(JJ)V"),
                policy.plan("sun/misc/Unsafe", "copyMemory", "(JJJ)V"),
                policy.plan("sun/misc/Unsafe", "park", "(ZJ)V"),
                policy.plan("sun/misc/Unsafe", "unpark", "(Ljava/lang/Object;)V"))) {
            assertTrue(plan.unsafeOrVarHandleCall());
            assertFalse(plan.supported());
            assertEquals(UnsafeOperationKind.UNSUPPORTED, plan.kind());
            assertTrue(plan.reason().contains("UNSAFE_RAW_MEMORY_UNSUPPORTED"));
        }
    }
}
