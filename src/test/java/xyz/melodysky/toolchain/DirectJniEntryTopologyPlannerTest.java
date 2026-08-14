package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Build-scoped planner determinism contract for all bounded proxy shapes. */
class DirectJniEntryTopologyPlannerTest {
    @Test
    void proxyTopologyReusesTheExistingBuildScopedPlannerForEveryShape() {
        ParsedClass parsedClass = DirectJniEntryTestFixture.eligibleClass();
        ParsedMethod method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals("staticInt"))
                .findFirst()
                .orElseThrow();
        Map<NativeLocalAbiPlan.Shape, NativeTextBuildKey> keys =
                keysForEveryShape(method.methodKey(), 1);

        for (Map.Entry<NativeLocalAbiPlan.Shape, NativeTextBuildKey> entry
                : keys.entrySet()) {
            DirectJniEntryTestFixture.Fixture first =
                    DirectJniEntryTestFixture.fixture(
                            parsedClass,
                            List.of("staticInt"),
                            entry.getValue());
            DirectJniEntryTestFixture.Fixture repeated =
                    DirectJniEntryTestFixture.fixture(
                            parsedClass,
                            List.of("staticInt"),
                            entry.getValue());
            NativeJniEntryPlan firstEntry = first.implementationPlan()
                    .jniEntryPlanFor(method.methodKey());
            NativeJniEntryPlan repeatedEntry = repeated.implementationPlan()
                    .jniEntryPlanFor(method.methodKey());
            NativeLocalAbiPlan expected = new NativeLocalAbiPlanner().plan(
                    entry.getValue(),
                    method.methodKey(),
                    1,
                    NativeLocalAbiProfile.COMPACT_DIVERSE);
            NativeJniEntryTopology actual = firstEntry.topology()
                    .orElseThrow();

            assertEquals(firstEntry, repeatedEntry);
            assertEquals(entry.getKey().name(), actual.shape().name());
            assertEquals(expected.parameterCount(), actual.parameterCount());
            assertEquals(expected.parameterOrders(), actual.parameterOrders());
            assertEquals(expected.branchSalt(), actual.branchSalt());
            assertEquals(
                    expected.bridgeSymbols().size(),
                    actual.bridgeSymbols().size());
            assertEquals(
                    actual.bridgeSymbols().size(),
                    new HashSet<>(actual.bridgeSymbols()).size());
            assertTrue(actual.bridgeSymbols().stream()
                    .allMatch(symbol -> symbol.matches("[a-p]{32}")));
            assertTrue(firstEntry.functionSymbol().matches("[a-p]{32}"));
        }
    }

    private Map<NativeLocalAbiPlan.Shape, NativeTextBuildKey>
            keysForEveryShape(
                    String methodKey,
                    int parameterCount) {
        EnumMap<NativeLocalAbiPlan.Shape, NativeTextBuildKey> result =
                new EnumMap<>(NativeLocalAbiPlan.Shape.class);
        NativeLocalAbiPlanner planner = new NativeLocalAbiPlanner();
        for (int index = 0;
                index < 4096
                        && result.size()
                                < NativeLocalAbiPlan.Shape.values().length;
                index++) {
            NativeTextBuildKey key = NativeTextBuildKey.fromUtf8(
                    "llvm-jni-proxy-shape-" + index);
            result.putIfAbsent(
                    planner.plan(
                                    key,
                                    methodKey,
                                    parameterCount,
                                    NativeLocalAbiProfile.COMPACT_DIVERSE)
                            .shape(),
                    key);
        }
        assertEquals(
                NativeLocalAbiPlan.Shape.values().length,
                result.size());
        return Map.copyOf(result);
    }
}
