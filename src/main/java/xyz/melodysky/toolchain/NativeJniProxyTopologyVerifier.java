package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmCallArgument;
import xyz.melodysky.backend.llvm.model.LlvmDirectCallRef;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmTerminatorKind;
import xyz.melodysky.backend.llvm.model.LlvmType;

/** Closed-schema gate for one synthesized proxy/bridge topology. */
final class NativeJniProxyTopologyVerifier {
    List<String> validate(
            String methodKey,
            NativeJniEntryTopology topology,
            LlvmFunction proxy,
            LlvmFunction body,
            List<LlvmParameter> canonical,
            Map<String, LlvmFunction> bridges,
            NativeLlvmSymbolIndex symbols,
            List<String> expectedSemanticCallers) {
        ArrayList<String> issues = new ArrayList<>();
        Map<String, List<String>> expectedTargets = expectedTargets(
                topology,
                proxy.name(),
                body.name());
        verifyClosedSchema(
                methodKey,
                topology,
                proxy,
                body,
                canonical,
                bridges,
                issues);
        expectedTargets.forEach((function, expected) -> {
            LlvmFunction actual = function.equals(proxy.name())
                    ? proxy
                    : bridges.get(function);
            if (actual == null || !callTargets(actual).equals(expected)) {
                add(issues, methodKey, "LLVM_JNI_PROXY_CALL_EDGE_MISMATCH");
            }
        });
        issues.addAll(new NativeJniProxyCallerVerifier().validate(
                methodKey,
                topology,
                proxy.name(),
                body.name(),
                symbols,
                expectedSemanticCallers));
        return issues.stream().distinct().sorted().toList();
    }

    private Map<String, List<String>> expectedTargets(
            NativeJniEntryTopology topology,
            String proxy,
            String body) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        List<String> bridges = topology.bridgeSymbols();
        switch (topology.shape()) {
            case DIRECT_CANONICAL -> result.put(proxy, List.of(body));
            case SINGLE_PERMUTING_BRIDGE -> {
                result.put(proxy, List.of(bridges.get(0)));
                result.put(bridges.get(0), List.of(body));
            }
            case DOUBLE_PERMUTING_BRIDGE -> {
                result.put(proxy, List.of(bridges.get(0)));
                result.put(bridges.get(0), List.of(bridges.get(1)));
                result.put(bridges.get(1), List.of(body));
            }
            case BRANCHED_PERMUTING_BRIDGE -> {
                result.put(proxy, List.of(bridges.get(0), bridges.get(1)));
                result.put(bridges.get(0), List.of(body));
                result.put(bridges.get(1), List.of(bridges.get(2)));
                result.put(bridges.get(2), List.of(body));
            }
        }
        return Map.copyOf(result);
    }

    private void verifyClosedSchema(
            String methodKey,
            NativeJniEntryTopology topology,
            LlvmFunction proxy,
            LlvmFunction body,
            List<LlvmParameter> canonical,
            Map<String, LlvmFunction> bridges,
            List<String> issues) {
        List<Integer> identity = java.util.stream.IntStream
                .range(0, canonical.size())
                .boxed()
                .toList();
        for (int index = 0; index < topology.bridgeSymbols().size(); index++) {
            boolean chained = topology.shape().branched()
                    ? index == 1
                    : index + 1 < topology.bridgeSymbols().size();
            int next = topology.shape().branched() ? 2 : index + 1;
            String target = chained
                    ? topology.bridgeSymbols().get(next)
                    : body.name();
            List<Integer> order = chained
                    ? topology.parameterOrders().get(next)
                    : identity;
            verifyForwardFunction(
                    methodKey,
                    bridges.get(topology.bridgeSymbols().get(index)),
                    target,
                    arguments(canonical, order),
                    "LLVM_JNI_PROXY_BRIDGE_SCHEMA_MISMATCH",
                    issues);
        }
        if (topology.shape().branched()) {
            verifyBranchedProxy(
                    methodKey,
                    proxy,
                    topology,
                    canonical,
                    issues);
        } else {
            String target = topology.bridgeSymbols().isEmpty()
                    ? body.name()
                    : topology.bridgeSymbols().get(0);
            List<Integer> order = topology.parameterOrders().isEmpty()
                    ? identity
                    : topology.parameterOrders().get(0);
            verifyForwardFunction(
                    methodKey,
                    proxy,
                    target,
                    arguments(canonical, order),
                    "LLVM_JNI_PROXY_SCHEMA_MISMATCH",
                    issues);
        }
    }

    private void verifyForwardFunction(
            String methodKey,
            LlvmFunction function,
            String target,
            List<LlvmCallArgument> arguments,
            String reasonCode,
            List<String> issues) {
        if (function == null || function.blocks().size() != 1) {
            add(issues, methodKey, reasonCode);
            return;
        }
        verifyForwardBlock(
                methodKey,
                function.blocks().get(0),
                function.returnType(),
                target,
                arguments,
                reasonCode,
                issues);
    }

    private void verifyForwardBlock(
            String methodKey,
            LlvmBasicBlock block,
            LlvmType returnType,
            String target,
            List<LlvmCallArgument> arguments,
            String reasonCode,
            List<String> issues) {
        if (block.instructions().size() != 1) {
            add(issues, methodKey, reasonCode);
            return;
        }
        LlvmInstruction instruction = block.instructions().get(0);
        Optional<LlvmDirectCallRef> call = instruction.directCall();
        if (call.isEmpty()
                || !call.orElseThrow().equals(new LlvmDirectCallRef(
                        target,
                        returnType,
                        arguments))
                || instruction.rawText().isPresent()
                || block.terminator().kind() != LlvmTerminatorKind.RETURN
                || block.terminator().returnType() != returnType
                || !block.terminator().returnValue().equals(instruction.result())) {
            add(issues, methodKey, reasonCode);
        }
    }

    private void verifyBranchedProxy(
            String methodKey,
            LlvmFunction proxy,
            NativeJniEntryTopology topology,
            List<LlvmParameter> canonical,
            List<String> issues) {
        if (proxy.blocks().size() != 3
                || proxy.blocks().get(0).instructions().size() != 7
                || proxy.blocks().get(0).terminator().kind()
                        != LlvmTerminatorKind.BRANCH) {
            add(issues, methodKey, "LLVM_JNI_PROXY_BRANCH_SCHEMA_MISMATCH");
            return;
        }
        LlvmBasicBlock entry = proxy.blocks().get(0);
        List<LlvmInstruction> instructions = entry.instructions();
        if (instructions.stream().anyMatch(instruction ->
                        instruction.directCall().isPresent()
                                || instruction.rawText().isEmpty())) {
            add(issues, methodKey, "LLVM_JNI_PROXY_BRANCH_SCHEMA_MISMATCH");
            return;
        }
        String slot = result(instructions.get(0));
        String address = result(instructions.get(1));
        String mixed = result(instructions.get(2));
        String materialized = result(instructions.get(4));
        String shifted = result(instructions.get(5));
        String condition = result(instructions.get(6));
        int predicateBit = 8 + ((topology.branchSalt() >>> 24) & 7);
        List<String> expected = List.of(
                "alloca i64, align 8",
                "ptrtoint ptr " + slot + " to i64",
                "xor i64 " + address + ", "
                        + Integer.toUnsignedLong(topology.branchSalt()),
                "store volatile i64 " + mixed + ", ptr " + slot + ", align 8",
                "load volatile i64, ptr " + slot + ", align 8",
                "lshr i64 " + materialized + ", " + predicateBit,
                "trunc i64 " + shifted + " to i1");
        List<String> actual = instructions.stream()
                .map(instruction -> instruction.rawText().orElseThrow())
                .toList();
        if (!actual.equals(expected)
                || !entry.terminator().condition().equals(Optional.of(condition))
                || !entry.terminator().trueTarget()
                        .equals(Optional.of(proxy.blocks().get(1).name()))
                || !entry.terminator().falseTarget()
                        .equals(Optional.of(proxy.blocks().get(2).name()))) {
            add(issues, methodKey, "LLVM_JNI_PROXY_BRANCH_SCHEMA_MISMATCH");
        }
        verifyForwardBlock(
                methodKey,
                proxy.blocks().get(1),
                proxy.returnType(),
                topology.bridgeSymbols().get(0),
                arguments(canonical, topology.parameterOrders().get(0)),
                "LLVM_JNI_PROXY_BRANCH_ROUTE_SCHEMA_MISMATCH",
                issues);
        verifyForwardBlock(
                methodKey,
                proxy.blocks().get(2),
                proxy.returnType(),
                topology.bridgeSymbols().get(1),
                arguments(canonical, topology.parameterOrders().get(1)),
                "LLVM_JNI_PROXY_BRANCH_ROUTE_SCHEMA_MISMATCH",
                issues);
    }

    private List<String> callTargets(LlvmFunction function) {
        return function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.directCall().stream())
                .map(LlvmDirectCallRef::target)
                .toList();
    }

    private List<LlvmCallArgument> arguments(
            List<LlvmParameter> canonical,
            List<Integer> order) {
        return order.stream()
                .map(canonical::get)
                .map(parameter -> new LlvmCallArgument(
                        parameter.type(),
                        parameter.name()))
                .toList();
    }

    private String result(LlvmInstruction instruction) {
        return instruction.result().orElseThrow();
    }

    private void add(List<String> issues, String methodKey, String reasonCode) {
        issues.add(methodKey + ":" + reasonCode);
    }
}
