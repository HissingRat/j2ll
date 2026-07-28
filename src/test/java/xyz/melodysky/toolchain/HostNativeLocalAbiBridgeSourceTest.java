package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.HostNativeLocalAbiBridgeSource.Parameter;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class HostNativeLocalAbiBridgeSourceTest {
    private static final String METHOD =
            "pkg/Owner#method!(ILjava/lang/Object;)I";
    private static final String LLVM_SYMBOL =
            "j2ll_f_0123456789abcdef";
    private static final List<Parameter> PARAMETERS = List.of(
            new Parameter("JNIEnv*", "env", "env"),
            new Parameter("jclass", "owner", "owner"),
            new Parameter("jint", "arg0", "(jint)arg0"),
            new Parameter("jobject", "arg1", "arg1"));

    @Test
    void plannerIsBuildScopedStableAndCanProduceEverySafeShape() {
        NativeLocalAbiPlanner planner = new NativeLocalAbiPlanner();
        Map<NativeLocalAbiPlan.Shape, NativeTextBuildKey> keys =
                keysForEveryShape();

        for (Map.Entry<NativeLocalAbiPlan.Shape, NativeTextBuildKey> entry
                : keys.entrySet()) {
            NativeLocalAbiPlan first =
                    planner.plan(entry.getValue(), METHOD, 7);
            NativeLocalAbiPlan repeated =
                    planner.plan(entry.getValue(), METHOD, 7);

            assertEquals(first, repeated);
            assertEquals(entry.getKey(), first.shape());
            assertEquals(
                    first.shape().bridgeCount(),
                    first.bridgeSymbols().size());
            assertEquals(
                    first.shape().bridgeCount(),
                    first.parameterOrders().size());
            for (List<Integer> order : first.parameterOrders()) {
                assertEquals(7, new HashSet<>(order).size());
                assertTrue(order.stream()
                        .allMatch(index -> index >= 0 && index < 7));
            }
        }

        NativeLocalAbiPlan direct = planner.plan(
                keys.get(NativeLocalAbiPlan.Shape.DIRECT_CANONICAL),
                METHOD,
                7);
        NativeLocalAbiPlan single = planner.plan(
                keys.get(
                        NativeLocalAbiPlan.Shape
                                .SINGLE_PERMUTING_BRIDGE),
                METHOD,
                7);
        NativeLocalAbiPlan dual = planner.plan(
                keys.get(
                        NativeLocalAbiPlan.Shape
                                .DOUBLE_PERMUTING_BRIDGE),
                METHOD,
                7);
        assertNotEquals(direct, single);
        assertNotEquals(single, dual);
    }

    @Test
    void emitterUsesFourBoundedTopologiesWithoutDataSlots() {
        HostNativeLocalAbiBridgeSource source =
                new HostNativeLocalAbiBridgeSource();
        Map<NativeLocalAbiPlan.Shape, NativeTextBuildKey> keys =
                keysForEveryShape();
        EnumMap<NativeLocalAbiPlan.Shape, String> generated =
                new EnumMap<>(NativeLocalAbiPlan.Shape.class);

        for (Map.Entry<NativeLocalAbiPlan.Shape, NativeTextBuildKey> entry
                : keys.entrySet()) {
            HostNativeLocalAbiBridgeSource.Emission emission = source.emit(
                    entry.getValue(),
                    METHOD,
                    "jint",
                    LLVM_SYMBOL,
                    PARAMETERS);
            generated.put(
                    entry.getKey(),
                    emission.source()
                            + "\nCALL="
                            + emission.wrapperInvocation());
            assertEquals(entry.getKey(), emission.plan().shape());
            if (entry.getKey().branched()) {
                assertTrue(emission.wrapperPrelude().contains(
                        "volatile uintptr_t"));
            } else {
                assertEquals("", emission.wrapperPrelude());
                assertFalse(emission.source().contains(" volatile "));
            }
            assertFalse(emission.source().contains("j2ll_lab_slot_"));
            assertFalse(emission.source().contains("typedef"));
            assertFalse(emission.source().contains("(*env)->"));
            assertFalse(emission.source().contains("ExceptionCheck"));
            assertFalse(emission.source().contains("DeleteLocalRef"));
            assertFalse(emission.source().contains("malloc"));
        }

        HostNativeLocalAbiBridgeSource.Emission direct = source.emit(
                keys.get(NativeLocalAbiPlan.Shape.DIRECT_CANONICAL),
                METHOD,
                "jint",
                LLVM_SYMBOL,
                PARAMETERS);
        assertEquals("", direct.source());
        assertEquals(
                LLVM_SYMBOL + "(env, owner, (jint)arg0, arg1)",
                direct.wrapperInvocation());

        HostNativeLocalAbiBridgeSource.Emission single = source.emit(
                keys.get(
                        NativeLocalAbiPlan.Shape
                                .SINGLE_PERMUTING_BRIDGE),
                METHOD,
                "jint",
                LLVM_SYMBOL,
                PARAMETERS);
        assertEquals(1, single.plan().bridgeSymbols().size());
        assertTrue(single.source().contains(
                "return "
                        + LLVM_SYMBOL
                        + "(env, owner, arg0, arg1);"));
        assertTrue(single.wrapperInvocation().startsWith(
                single.plan().bridgeSymbols().get(0) + "("));

        HostNativeLocalAbiBridgeSource.Emission dual = source.emit(
                keys.get(
                        NativeLocalAbiPlan.Shape
                                .DOUBLE_PERMUTING_BRIDGE),
                METHOD,
                "jint",
                LLVM_SYMBOL,
                PARAMETERS);
        assertEquals(2, dual.plan().bridgeSymbols().size());
        assertTrue(dual.source().contains(
                "return "
                        + LLVM_SYMBOL
                        + "(env, owner, arg0, arg1);"));
        assertTrue(dual.source().contains(
                "return "
                        + dual.plan().bridgeSymbols().get(1)
                        + "("));
        assertTrue(dual.wrapperInvocation().startsWith(
                dual.plan().bridgeSymbols().get(0) + "("));

        HostNativeLocalAbiBridgeSource.Emission branched = source.emit(
                keys.get(
                        NativeLocalAbiPlan.Shape
                                .BRANCHED_PERMUTING_BRIDGE),
                METHOD,
                "jint",
                LLVM_SYMBOL,
                PARAMETERS);
        assertEquals(3, branched.plan().bridgeSymbols().size());
        assertTrue(branched.source().contains(
                "__attribute__((noinline, optnone, used))"));
        assertTrue(branched.wrapperPrelude().contains(
                "volatile uintptr_t"));
        assertTrue(branched.wrapperPrelude().contains(
                "(uintptr_t)(void*)&"));
        assertTrue(branched.wrapperInvocation().contains(" ? "));
        assertTrue(branched.wrapperInvocation().contains(" : "));
        assertTrue(branched.wrapperInvocation().contains(
                branched.plan().bridgeSymbols().get(0) + "("));
        assertTrue(branched.wrapperInvocation().contains(
                branched.plan().bridgeSymbols().get(1) + "("));
        assertTrue(branched.source().contains(
                branched.plan().bridgeSymbols().get(2) + "("));
        assertTrue(branched.source().contains(
                LLVM_SYMBOL + "(env, owner, arg0, arg1);"));
        assertNotEquals(
                branched.plan().parameterOrders().get(0),
                branched.plan().parameterOrders().get(1));
        assertTrue(
                branched.source().length()
                        <= (dual.source().length() * 2) + 1024,
                "branched source must remain bounded");

        assertEquals(4, new HashSet<>(generated.values()).size());
    }

    @Test
    void branchedShapeUsesApproximatelyOneQuarterHashGate() {
        NativeLocalAbiPlanner planner = new NativeLocalAbiPlanner();
        int samples = 4096;
        int branched = 0;
        for (int index = 0; index < samples; index++) {
            NativeLocalAbiPlan plan = planner.plan(
                    NativeTextBuildKey.fromUtf8(
                            "local-abi-distribution-" + index),
                    METHOD,
                    PARAMETERS.size());
            if (plan.shape().branched()) {
                branched++;
            }
        }

        assertTrue(branched >= samples / 5, "branched=" + branched);
        assertTrue(branched <= (samples * 3) / 10, "branched=" + branched);
    }

    @Test
    void zeroParameterVoidMethodsRemainValidInEveryShape() {
        HostNativeLocalAbiBridgeSource source =
                new HostNativeLocalAbiBridgeSource();
        for (NativeTextBuildKey key : keysForEveryShape().values()) {
            HostNativeLocalAbiBridgeSource.Emission emission = source.emit(
                    key,
                    METHOD,
                    "void",
                    "j2ll_f_abcdef0123456789",
                    List.of());

            if (emission.plan().shape().branched()) {
                assertTrue(emission.wrapperPrelude().contains(
                        "volatile uintptr_t"));
                assertTrue(emission.wrapperInvocation().contains("()"));
            } else {
                assertTrue(emission.wrapperInvocation().endsWith("()"));
            }
            if (emission.plan().shape()
                    == NativeLocalAbiPlan.Shape.DIRECT_CANONICAL) {
                assertEquals("", emission.source());
            } else {
                assertTrue(emission.source().contains("(void) {\n"));
                assertTrue(emission.source().contains(
                        "j2ll_f_abcdef0123456789();"));
            }
        }
    }

    @Test
    void plannerAndEmitterRejectMalformedAbiShapes() {
        NativeLocalAbiPlanner planner = new NativeLocalAbiPlanner();
        NativeTextBuildKey key =
                NativeTextBuildKey.fromUtf8("invalid-shape");

        assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(key, "method", -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HostNativeLocalAbiBridgeSource().emit(
                        key,
                        "method",
                        "jint",
                        "not-a-c-symbol",
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HostNativeLocalAbiBridgeSource().emit(
                        key,
                        "method",
                        "jint",
                        "j2ll_target",
                        List.of(
                                new Parameter(
                                        "jint",
                                        "arg0",
                                        "arg0"),
                                new Parameter(
                                        "jint",
                                        "arg0",
                                        "arg1"))));
    }

    private Map<NativeLocalAbiPlan.Shape, NativeTextBuildKey>
            keysForEveryShape() {
        EnumMap<NativeLocalAbiPlan.Shape, NativeTextBuildKey> result =
                new EnumMap<>(NativeLocalAbiPlan.Shape.class);
        NativeLocalAbiPlanner planner = new NativeLocalAbiPlanner();
        for (int index = 0;
                index < 4096
                        && result.size()
                                < NativeLocalAbiPlan.Shape.values().length;
                index++) {
            NativeTextBuildKey key = NativeTextBuildKey.fromUtf8(
                    "local-abi-shape-" + index);
            result.putIfAbsent(
                    planner.plan(key, METHOD, PARAMETERS.size()).shape(),
                    key);
        }
        assertEquals(
                NativeLocalAbiPlan.Shape.values().length,
                result.size());
        return Map.copyOf(result);
    }
}
