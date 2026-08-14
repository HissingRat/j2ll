package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmCallArgument;
import xyz.melodysky.backend.llvm.model.LlvmDirectCallRef;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmFunctionAttribute;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmNativeUnwindSemantics;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

/** Builds the bounded proxy/bridge functions for one approved JNI entry. */
final class NativeJniProxyFunctionFactory {
    private final NativeJniProxyLocalNameMapper names =
            new NativeJniProxyLocalNameMapper();

    List<LlvmFunction> create(
            NativeJniEntryPlan entryPlan,
            NativeJniProxyAbiProjection projection,
            LlvmFunction body) {
        NativeJniEntryTopology topology = entryPlan.topology().orElseThrow();
        List<LlvmParameter> physical = physicalParameters(
                entryPlan.functionSymbol(),
                projection.physicalParameterTypes());
        List<LlvmParameter> canonical = projection
                .semanticFromPhysicalIndices()
                .stream()
                .map(physical::get)
                .toList();
        if (body.returnType() != projection.returnType()
                || !body.parameters().stream()
                        .map(LlvmParameter::type)
                        .toList()
                        .equals(projection.semanticParameterTypes())) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy projection does not match semantic body");
        }
        ArrayList<LlvmFunction> functions = new ArrayList<>();
        if (topology.shape() == NativeJniEntryTopology.Shape
                .SINGLE_PERMUTING_BRIDGE
                || topology.shape() == NativeJniEntryTopology.Shape
                        .DOUBLE_PERMUTING_BRIDGE) {
            addLinearBridges(functions, topology, body, canonical);
        } else if (topology.shape().branched()) {
            addBranchedBridges(functions, topology, body, canonical);
        }
        functions.add(proxy(
                entryPlan.functionSymbol(),
                body.returnType(),
                physical,
                canonical,
                body.name(),
                topology));
        return List.copyOf(functions);
    }

    private void addLinearBridges(
            List<LlvmFunction> functions,
            NativeJniEntryTopology topology,
            LlvmFunction body,
            List<LlvmParameter> canonical) {
        for (int bridge = topology.bridgeSymbols().size() - 1;
                bridge >= 0;
                bridge--) {
            boolean last = bridge == topology.bridgeSymbols().size() - 1;
            functions.add(bridge(
                    topology.bridgeSymbols().get(bridge),
                    body.returnType(),
                    canonical,
                    topology.parameterOrders().get(bridge),
                    last ? body.name() : topology.bridgeSymbols().get(bridge + 1),
                    last
                            ? canonicalOrder(canonical.size())
                            : topology.parameterOrders().get(bridge + 1)));
        }
    }

    private void addBranchedBridges(
            List<LlvmFunction> functions,
            NativeJniEntryTopology topology,
            LlvmFunction body,
            List<LlvmParameter> canonical) {
        functions.add(bridge(
                topology.bridgeSymbols().get(2),
                body.returnType(),
                canonical,
                topology.parameterOrders().get(2),
                body.name(),
                canonicalOrder(canonical.size())));
        functions.add(bridge(
                topology.bridgeSymbols().get(1),
                body.returnType(),
                canonical,
                topology.parameterOrders().get(1),
                topology.bridgeSymbols().get(2),
                topology.parameterOrders().get(2)));
        functions.add(bridge(
                topology.bridgeSymbols().get(0),
                body.returnType(),
                canonical,
                topology.parameterOrders().get(0),
                body.name(),
                canonicalOrder(canonical.size())));
    }

    private LlvmFunction proxy(
            String symbol,
            LlvmType returnType,
            List<LlvmParameter> physical,
            List<LlvmParameter> canonical,
            String bodySymbol,
            NativeJniEntryTopology topology) {
        List<LlvmBasicBlock> blocks = topology.shape().branched()
                ? branchedProxyBlocks(symbol, returnType, canonical, topology)
                : List.of(callAndReturnBlock(
                        names.block(symbol, "entry"),
                        symbol,
                        returnType,
                        topology.bridgeSymbols().isEmpty()
                                ? bodySymbol
                                : topology.bridgeSymbols().get(0),
                        orderedArguments(
                                canonical,
                                topology.parameterOrders().isEmpty()
                                        ? canonicalOrder(canonical.size())
                                        : topology.parameterOrders().get(0))));
        return function(
                symbol,
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                returnType,
                physical,
                blocks);
    }

    private List<LlvmBasicBlock> branchedProxyBlocks(
            String symbol,
            LlvmType returnType,
            List<LlvmParameter> canonical,
            NativeJniEntryTopology topology) {
        String first = names.block(symbol, "route:0");
        String second = names.block(symbol, "route:1");
        String slot = names.value(symbol, "branch:slot");
        String address = names.value(symbol, "branch:address");
        String mixed = names.value(symbol, "branch:mixed");
        String materialized = names.value(symbol, "branch:materialized");
        String shifted = names.value(symbol, "branch:shifted");
        String condition = names.value(symbol, "branch:condition");
        int bit = 8 + ((topology.branchSalt() >>> 24) & 7);
        LlvmBasicBlock entry = new LlvmBasicBlock(
                names.block(symbol, "entry"),
                List.of(
                        raw(slot, "alloca i64, align 8"),
                        raw(address, "ptrtoint ptr " + slot + " to i64"),
                        raw(mixed, "xor i64 " + address + ", "
                                + Integer.toUnsignedLong(topology.branchSalt())),
                        raw(null, "store volatile i64 " + mixed + ", ptr "
                                + slot + ", align 8"),
                        raw(materialized, "load volatile i64, ptr "
                                + slot + ", align 8"),
                        raw(shifted, "lshr i64 " + materialized + ", " + bit),
                        raw(condition, "trunc i64 " + shifted + " to i1")),
                LlvmTerminator.branch(condition, first, second));
        return List.of(
                entry,
                callAndReturnBlock(
                        first,
                        symbol + ":route:0",
                        returnType,
                        topology.bridgeSymbols().get(0),
                        orderedArguments(canonical, topology.parameterOrders().get(0))),
                callAndReturnBlock(
                        second,
                        symbol + ":route:1",
                        returnType,
                        topology.bridgeSymbols().get(1),
                        orderedArguments(canonical, topology.parameterOrders().get(1))));
    }

    private LlvmInstruction raw(String result, String text) {
        return LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.ofNullable(result),
                text);
    }

    private LlvmFunction bridge(
            String symbol,
            LlvmType returnType,
            List<LlvmParameter> canonical,
            List<Integer> incomingOrder,
            String target,
            List<Integer> targetOrder) {
        return function(
                symbol,
                LlvmLinkage.INTERNAL,
                LlvmVisibility.DEFAULT,
                returnType,
                incomingOrder.stream().map(canonical::get).toList(),
                List.of(callAndReturnBlock(
                        names.block(symbol, "entry"),
                        symbol,
                        returnType,
                        target,
                        orderedArguments(canonical, targetOrder))));
    }

    private LlvmFunction function(
            String symbol,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            LlvmType returnType,
            List<LlvmParameter> parameters,
            List<LlvmBasicBlock> blocks) {
        return new LlvmFunction(
                symbol,
                linkage,
                visibility,
                returnType,
                parameters,
                blocks,
                LlvmNativeUnwindSemantics.PROVEN_ABSENT,
                List.of(LlvmFunctionAttribute.NOINLINE));
    }

    private LlvmBasicBlock callAndReturnBlock(
            String block,
            String valueDomain,
            LlvmType returnType,
            String target,
            List<LlvmParameter> arguments) {
        Optional<String> result = returnType == LlvmType.VOID
                ? Optional.empty()
                : Optional.of(names.value(valueDomain, "result"));
        LlvmInstruction call = LlvmInstruction.directCallProvenNoNativeUnwind(
                result,
                new LlvmDirectCallRef(
                        target,
                        returnType,
                        arguments.stream()
                                .map(parameter -> new LlvmCallArgument(
                                        parameter.type(),
                                        parameter.name()))
                                .toList()));
        return new LlvmBasicBlock(
                block,
                List.of(call),
                new LlvmTerminator(returnType, result));
    }

    private List<LlvmParameter> physicalParameters(
            String proxySymbol,
            List<LlvmType> parameterTypes) {
        ArrayList<LlvmParameter> parameters = new ArrayList<>();
        for (int index = 0; index < parameterTypes.size(); index++) {
            parameters.add(new LlvmParameter(
                    parameterTypes.get(index),
                    names.value(proxySymbol, "physical:" + index)));
        }
        return List.copyOf(parameters);
    }

    private List<LlvmParameter> orderedArguments(
            List<LlvmParameter> canonical,
            List<Integer> order) {
        return order.stream().map(canonical::get).toList();
    }

    private List<Integer> canonicalOrder(int size) {
        return java.util.stream.IntStream.range(0, size).boxed().toList();
    }
}
