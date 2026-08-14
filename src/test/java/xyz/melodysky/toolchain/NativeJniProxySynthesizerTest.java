package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmFunctionAttribute;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

class NativeJniProxySynthesizerTest {
    @Test
    void synthesizesEveryBoundedTopologyWithoutMergingProxyAndBody() {
        for (NativeTextBuildKey key : keysForEveryShape().values()) {
            NativeJniEntryTestFixture.Fixture fixture =
                    NativeJniEntryTestFixture.plannedProxy(key);
            NativeJniEntryPlan entry = fixture.plan()
                    .jniEntryPlanFor(fixture.method().methodKey());
            NativeJniEntryTopology topology = entry.topology().orElseThrow();
            LlvmModule module = NativeJniEntryTestFixture
                    .synthesizedModule(fixture);
            Map<String, LlvmFunction> functions = functions(module);
            String proxySymbol = entry.functionSymbol();
            String bodySymbol = entry.semanticBodySymbol().orElseThrow();

            assertNotEquals(proxySymbol, bodySymbol);
            assertEquals(2 + topology.bridgeSymbols().size(), functions.size());
            assertEquals(proxySignature(), parameterTypes(functions.get(proxySymbol)));
            assertEquals(semanticSignature(), parameterTypes(functions.get(bodySymbol)));
            assertSurface(
                    functions.get(proxySymbol),
                    LlvmLinkage.EXTERNAL,
                    LlvmVisibility.HIDDEN);
            assertSurface(
                    functions.get(bodySymbol),
                    LlvmLinkage.EXTERNAL,
                    LlvmVisibility.HIDDEN);
            for (String bridge : topology.bridgeSymbols()) {
                assertSurface(
                        functions.get(bridge),
                        LlvmLinkage.INTERNAL,
                        LlvmVisibility.DEFAULT);
            }
            assertEquals(
                    expectedEdges(entry),
                    actualEdges(functions, entry));
        }
    }

    @Test
    void everyProxyHopUsesThePlannedPermutationAndTargetAbi() {
        for (NativeTextBuildKey key : keysForEveryShape().values()) {
            NativeJniEntryTestFixture.Fixture fixture =
                    NativeJniEntryTestFixture.plannedProxy(key);
            NativeJniEntryPlan entry = fixture.plan()
                    .jniEntryPlanFor(fixture.method().methodKey());
            NativeJniEntryTopology topology = entry.topology().orElseThrow();
            Map<String, LlvmFunction> functions = functions(
                    NativeJniEntryTestFixture.synthesizedModule(fixture));

            for (int index = 0;
                    index < topology.bridgeSymbols().size();
                    index++) {
                assertEquals(
                        orderedTypes(
                                semanticSignature(),
                                topology.parameterOrders().get(index)),
                        parameterTypes(functions.get(
                                topology.bridgeSymbols().get(index))));
            }
            for (LlvmFunction function : functions.values()) {
                for (Call call : calls(function)) {
                    LlvmFunction target = functions.get(call.target());
                    assertTrue(
                            target != null,
                            () -> function.name()
                                    + " has an unexpected external shortcut to "
                                    + call.target());
                    assertEquals(
                            parameterTypes(target),
                            call.argumentTypes(),
                            () -> function.name()
                                    + " does not call "
                                    + target.name()
                                    + " with its exact physical parameter order");
                }
            }
        }
    }

    private Map<NativeLocalAbiPlan.Shape, NativeTextBuildKey>
            keysForEveryShape() {
        EnumMap<NativeLocalAbiPlan.Shape, NativeTextBuildKey> result =
                new EnumMap<>(NativeLocalAbiPlan.Shape.class);
        NativeLocalAbiPlanner planner = new NativeLocalAbiPlanner();
        String methodKey = NativeJniEntryTestFixture.plannedProxy(
                        NativeTextBuildKey.fromUtf8("probe"))
                .method()
                .methodKey();
        for (int index = 0;
                index < 4096
                        && result.size()
                                < NativeLocalAbiPlan.Shape.values().length;
                index++) {
            NativeTextBuildKey key = NativeTextBuildKey.fromUtf8(
                    "llvm-proxy-synth-shape-" + index);
            result.putIfAbsent(
                    planner.plan(
                                    key,
                                    methodKey,
                                    3,
                                    NativeLocalAbiProfile.COMPACT_DIVERSE)
                            .shape(),
                    key);
        }
        assertEquals(
                NativeLocalAbiPlan.Shape.values().length,
                result.size());
        return Map.copyOf(result);
    }

    private Map<String, LlvmFunction> functions(LlvmModule module) {
        LinkedHashMap<String, LlvmFunction> result = new LinkedHashMap<>();
        for (LlvmFunction function : module.functions()) {
            LlvmFunction previous = result.put(function.name(), function);
            assertEquals(null, previous, "duplicate function " + function.name());
        }
        return Map.copyOf(result);
    }

    private void assertSurface(
            LlvmFunction function,
            LlvmLinkage linkage,
            LlvmVisibility visibility) {
        assertEquals(linkage, function.linkage());
        assertEquals(visibility, function.visibility());
        assertEquals(List.of(LlvmFunctionAttribute.NOINLINE), function.attributes());
    }

    private Map<String, Set<String>> expectedEdges(NativeJniEntryPlan entry) {
        NativeJniEntryTopology topology = entry.topology().orElseThrow();
        String proxy = entry.functionSymbol();
        String body = entry.semanticBodySymbol().orElseThrow();
        List<String> bridges = topology.bridgeSymbols();
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        result.put(body, Set.of());
        switch (topology.shape()) {
            case DIRECT_CANONICAL -> result.put(proxy, Set.of(body));
            case SINGLE_PERMUTING_BRIDGE -> {
                result.put(proxy, Set.of(bridges.get(0)));
                result.put(bridges.get(0), Set.of(body));
            }
            case DOUBLE_PERMUTING_BRIDGE -> {
                result.put(proxy, Set.of(bridges.get(0)));
                result.put(bridges.get(0), Set.of(bridges.get(1)));
                result.put(bridges.get(1), Set.of(body));
            }
            case BRANCHED_PERMUTING_BRIDGE -> {
                result.put(proxy, Set.of(bridges.get(0), bridges.get(1)));
                result.put(bridges.get(0), Set.of(body));
                result.put(bridges.get(1), Set.of(bridges.get(2)));
                result.put(bridges.get(2), Set.of(body));
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, Set<String>> actualEdges(
            Map<String, LlvmFunction> functions,
            NativeJniEntryPlan entry) {
        LinkedHashSet<String> relevant = new LinkedHashSet<>();
        relevant.add(entry.functionSymbol());
        relevant.add(entry.semanticBodySymbol().orElseThrow());
        relevant.addAll(entry.topology().orElseThrow().bridgeSymbols());
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        for (String symbol : relevant) {
            result.put(
                    symbol,
                    calls(functions.get(symbol)).stream()
                            .map(Call::target)
                            .collect(java.util.stream.Collectors
                                    .toUnmodifiableSet()));
        }
        return Map.copyOf(result);
    }

    private List<Call> calls(LlvmFunction function) {
        return function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.directCall().stream())
                .map(call -> new Call(
                        call.target(),
                        call.arguments().stream()
                                .map(argument -> argument.type())
                                .toList()))
                .toList();
    }

    private List<LlvmType> parameterTypes(LlvmFunction function) {
        return function.parameters().stream()
                .map(parameter -> parameter.type())
                .toList();
    }

    private List<LlvmType> orderedTypes(
            List<LlvmType> canonical,
            List<Integer> order) {
        return order.stream().map(canonical::get).toList();
    }

    private List<LlvmType> proxySignature() {
        return List.of(
                LlvmType.PTR,
                LlvmType.PTR,
                LlvmType.I32,
                LlvmType.I64,
                LlvmType.F64);
    }

    private List<LlvmType> semanticSignature() {
        return List.of(LlvmType.I32, LlvmType.I64, LlvmType.F64);
    }

    private record Call(String target, List<LlvmType> argumentTypes) {
        private Call {
            argumentTypes = List.copyOf(argumentTypes);
        }
    }
}
