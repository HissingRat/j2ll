package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmCallArgument;
import xyz.melodysky.backend.llvm.model.LlvmDirectCallRef;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmParameter;

class NativeJniEntryLlvmTopologyMutationTest {
    private final NativeJniEntryLlvmVerifier verifier =
            new NativeJniEntryLlvmVerifier();

    @Test
    void rejectsWrongBridgeDepthAndAnExtraBodyShortcut() {
        NativeJniEntryTestFixture.Fixture fixture = fixture(
                NativeJniEntryTopology.Shape.DOUBLE_PERMUTING_BRIDGE);
        NativeJniEntryPlan entry = entry(fixture);
        List<String> bridges = entry.topology()
                .orElseThrow()
                .bridgeSymbols();
        LlvmModule wrongDepth = mutateCall(
                module(fixture),
                entry.functionSymbol(),
                call -> new LlvmDirectCallRef(
                        bridges.get(1),
                        call.returnType(),
                        call.arguments()));
        assertIssues(
                fixture,
                wrongDepth,
                "LLVM_JNI_PROXY_SCHEMA_MISMATCH",
                "LLVM_JNI_PROXY_CALL_EDGE_MISMATCH",
                "LLVM_JNI_PROXY_CALLER_CLOSURE_MISMATCH");

        LlvmModule shortcut = replaceFunction(
                module(fixture),
                entry.functionSymbol(),
                function -> appendInstruction(
                        function,
                        0,
                        LlvmInstruction.directCallProvenNoNativeUnwind(
                                Optional.of("%shortcut"),
                                new LlvmDirectCallRef(
                                        entry.semanticBodySymbol()
                                                .orElseThrow(),
                                        function.returnType(),
                                        semanticArguments(function, 2)))));
        assertIssues(
                fixture,
                shortcut,
                "LLVM_JNI_PROXY_SCHEMA_MISMATCH",
                "LLVM_JNI_PROXY_CALL_EDGE_MISMATCH",
                "LLVM_JNI_PROXY_CALLER_CLOSURE_MISMATCH");
    }

    @Test
    void rejectsCorrectTargetWithWrongOrderedSsaArguments() {
        NativeJniEntryTestFixture.Fixture fixture = fixture(
                NativeJniEntryTopology.Shape.SINGLE_PERMUTING_BRIDGE);
        NativeJniEntryPlan entry = entry(fixture);
        LlvmModule wrongArguments = mutateCall(
                module(fixture),
                entry.functionSymbol(),
                call -> {
                    ArrayList<LlvmCallArgument> arguments =
                            new ArrayList<>(call.arguments());
                    Collections.swap(arguments, 0, 1);
                    return new LlvmDirectCallRef(
                            call.target(),
                            call.returnType(),
                            arguments);
                });

        assertIssues(
                fixture,
                wrongArguments,
                "LLVM_JNI_PROXY_SCHEMA_MISMATCH");
    }

    @Test
    void rejectsBridgeParameterOrderExtraInstructionAndSecondBlock() {
        NativeJniEntryTestFixture.Fixture fixture = fixture(
                NativeJniEntryTopology.Shape.SINGLE_PERMUTING_BRIDGE);
        String bridge = entry(fixture)
                .topology()
                .orElseThrow()
                .bridgeSymbols()
                .get(0);
        LlvmModule wrongParameters = replaceFunction(
                module(fixture),
                bridge,
                function -> {
                    ArrayList<LlvmParameter> parameters =
                            new ArrayList<>(function.parameters());
                    LlvmParameter first = parameters.get(0);
                    parameters.set(
                            0,
                            new LlvmParameter(
                                    first.type(),
                                    first.name() + "_wrong"));
                    return copy(function, parameters, function.blocks());
                });
        assertIssues(
                fixture,
                wrongParameters,
                "LLVM_JNI_PROXY_BRIDGE_PARAMETER_ORDER_MISMATCH");

        LlvmModule extraInstruction = replaceFunction(
                module(fixture),
                bridge,
                function -> appendInstruction(
                        function,
                        0,
                        LlvmInstruction.rawProvenNoNativeUnwind(
                                Optional.empty(),
                                "call void @jni_unplanned_helper()")));
        assertIssues(
                fixture,
                extraInstruction,
                "LLVM_JNI_PROXY_BRIDGE_SCHEMA_MISMATCH");

        LlvmModule secondBlock = replaceFunction(
                module(fixture),
                bridge,
                function -> {
                    ArrayList<LlvmBasicBlock> blocks =
                            new ArrayList<>(function.blocks());
                    blocks.add(function.blocks().get(0));
                    return copy(function, function.parameters(), blocks);
                });
        assertIssues(
                fixture,
                secondBlock,
                "LLVM_JNI_PROXY_BRIDGE_SCHEMA_MISMATCH");
    }

    @Test
    void rejectsNonBranchedProxyArithmeticAndBranchedPredicateDrift() {
        NativeJniEntryTestFixture.Fixture direct = fixture(
                NativeJniEntryTopology.Shape.DIRECT_CANONICAL);
        LlvmModule arithmetic = replaceFunction(
                module(direct),
                entry(direct).functionSymbol(),
                function -> appendInstruction(
                        function,
                        0,
                        LlvmInstruction.rawProvenNoNativeUnwind(
                                Optional.of("%noise"),
                                "add i32 1, 2")));
        assertIssues(
                direct,
                arithmetic,
                "LLVM_JNI_PROXY_SCHEMA_MISMATCH");

        NativeJniEntryTestFixture.Fixture branched = fixture(
                NativeJniEntryTopology.Shape.BRANCHED_PERMUTING_BRIDGE);
        LlvmModule predicate = replaceFunction(
                module(branched),
                entry(branched).functionSymbol(),
                function -> replaceInstruction(
                        function,
                        0,
                        3,
                        instruction -> LlvmInstruction
                                .rawProvenNoNativeUnwind(
                                        instruction.result(),
                                        instruction.rawText()
                                                .orElseThrow()
                                                .replace(
                                                        "store volatile",
                                                        "store"))));
        assertIssues(
                branched,
                predicate,
                "LLVM_JNI_PROXY_BRANCH_SCHEMA_MISMATCH");
    }

    @Test
    void rejectsBranchedRouteArgumentDrift() {
        NativeJniEntryTestFixture.Fixture fixture = fixture(
                NativeJniEntryTopology.Shape.BRANCHED_PERMUTING_BRIDGE);
        NativeJniEntryPlan entry = entry(fixture);
        LlvmModule mutation = mutateCall(
                module(fixture),
                entry.functionSymbol(),
                1,
                call -> {
                    ArrayList<LlvmCallArgument> arguments =
                            new ArrayList<>(call.arguments());
                    Collections.rotate(arguments, 1);
                    return new LlvmDirectCallRef(
                            call.target(),
                            call.returnType(),
                            arguments);
                });

        assertIssues(
                fixture,
                mutation,
                "LLVM_JNI_PROXY_BRANCH_ROUTE_SCHEMA_MISMATCH");
    }

    private NativeJniEntryTestFixture.Fixture fixture(
            NativeJniEntryTopology.Shape shape) {
        return NativeJniEntryTestFixture.plannedProxy(shape);
    }

    private NativeJniEntryPlan entry(
            NativeJniEntryTestFixture.Fixture fixture) {
        return fixture.plan().jniEntryPlanFor(fixture.method().methodKey());
    }

    private LlvmModule module(NativeJniEntryTestFixture.Fixture fixture) {
        return NativeJniEntryTestFixture.synthesizedModule(fixture);
    }

    private LlvmModule mutateCall(
            LlvmModule module,
            String symbol,
            UnaryOperator<LlvmDirectCallRef> mutation) {
        return mutateCall(module, symbol, 0, mutation);
    }

    private LlvmModule mutateCall(
            LlvmModule module,
            String symbol,
            int blockIndex,
            UnaryOperator<LlvmDirectCallRef> mutation) {
        return replaceFunction(
                module,
                symbol,
                function -> replaceInstruction(
                        function,
                        blockIndex,
                        0,
                        instruction -> LlvmInstruction
                                .directCallProvenNoNativeUnwind(
                                        instruction.result(),
                                        mutation.apply(instruction
                                                .directCall()
                                                .orElseThrow()))));
    }

    private LlvmFunction appendInstruction(
            LlvmFunction function,
            int blockIndex,
            LlvmInstruction extra) {
        ArrayList<LlvmBasicBlock> blocks =
                new ArrayList<>(function.blocks());
        LlvmBasicBlock old = blocks.get(blockIndex);
        ArrayList<LlvmInstruction> instructions =
                new ArrayList<>(old.instructions());
        instructions.add(extra);
        blocks.set(
                blockIndex,
                new LlvmBasicBlock(
                        old.name(),
                        instructions,
                        old.terminator()));
        return copy(function, function.parameters(), blocks);
    }

    private LlvmFunction replaceInstruction(
            LlvmFunction function,
            int blockIndex,
            int instructionIndex,
            UnaryOperator<LlvmInstruction> replacement) {
        ArrayList<LlvmBasicBlock> blocks =
                new ArrayList<>(function.blocks());
        LlvmBasicBlock old = blocks.get(blockIndex);
        ArrayList<LlvmInstruction> instructions =
                new ArrayList<>(old.instructions());
        instructions.set(
                instructionIndex,
                replacement.apply(instructions.get(instructionIndex)));
        blocks.set(
                blockIndex,
                new LlvmBasicBlock(
                        old.name(),
                        instructions,
                        old.terminator()));
        return copy(function, function.parameters(), blocks);
    }

    private LlvmModule replaceFunction(
            LlvmModule module,
            String symbol,
            UnaryOperator<LlvmFunction> replacement) {
        ArrayList<LlvmFunction> functions = new ArrayList<>();
        boolean found = false;
        for (LlvmFunction function : module.functions()) {
            if (function.name().equals(symbol)) {
                functions.add(replacement.apply(function));
                found = true;
            } else {
                functions.add(function);
            }
        }
        assertTrue(found, "missing function " + symbol);
        return new LlvmModule(
                module.identifier(),
                module.declarations(),
                module.globals(),
                functions);
    }

    private LlvmFunction copy(
            LlvmFunction function,
            List<LlvmParameter> parameters,
            List<LlvmBasicBlock> blocks) {
        return new LlvmFunction(
                function.name(),
                function.linkage(),
                function.visibility(),
                function.returnType(),
                parameters,
                blocks,
                function.nativeUnwindSemantics(),
                function.attributes());
    }

    private List<LlvmCallArgument> semanticArguments(
            LlvmFunction proxy,
            int offset) {
        return proxy.parameters().subList(offset, proxy.parameters().size())
                .stream()
                .map(parameter -> new LlvmCallArgument(
                        parameter.type(),
                        parameter.name()))
                .toList();
    }

    private void assertIssues(
            NativeJniEntryTestFixture.Fixture fixture,
            LlvmModule module,
            String... reasonCodes) {
        List<String> issues = verifier.validate(
                fixture.plan(),
                NativeJniEntryTestFixture.compilation(
                        NativeJniEntryTestFixture.OWNER,
                        List.of(fixture.method()),
                        module));
        for (String reasonCode : reasonCodes) {
            assertTrue(
                    issues.contains(fixture.method().methodKey()
                            + ":"
                            + reasonCode),
                    issues.toString());
        }
    }
}
