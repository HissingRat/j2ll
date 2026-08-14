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

/** Mutation gates for semantic proxy argument and JNI-free closure proofs. */
class SemanticJniProxyMutationTest {
    private final NativeJniEntryLlvmVerifier verifier =
            new NativeJniEntryLlvmVerifier();

    @Test
    void rejectsCorrectRouteTargetWithWrongProjectedSsaArguments()
            throws Exception {
        Fixture fixture = fixture();
        LlvmModule mutation = replaceInstruction(
                fixture.module(),
                fixture.entry().functionSymbol(),
                1,
                0,
                instruction -> {
                    LlvmDirectCallRef call = instruction
                            .directCall()
                            .orElseThrow();
                    ArrayList<LlvmCallArgument> arguments =
                            new ArrayList<>(call.arguments());
                    Collections.swap(arguments, 0, 1);
                    return LlvmInstruction.directCallProvenNoNativeUnwind(
                            instruction.result(),
                            new LlvmDirectCallRef(
                                    call.target(),
                                    call.returnType(),
                                    arguments));
                });

        assertIssue(
                fixture,
                mutation,
                "LLVM_JNI_PROXY_BRANCH_ROUTE_SCHEMA_MISMATCH");
    }

    @Test
    void rejectsJniLocalReferenceAndExceptionWorkInProxyOrBridge()
            throws Exception {
        for (String raw : List.of(
                "call i32 @JNIEnv_ExceptionCheck(ptr null)",
                "call void @JNIEnv_DeleteLocalRef(ptr null, ptr null)",
                "call void @JNIEnv_ExceptionClear(ptr null)")) {
            Fixture proxyFixture = fixture();
            LlvmModule proxyMutation = appendInstruction(
                    proxyFixture.module(),
                    proxyFixture.entry().functionSymbol(),
                    0,
                    LlvmInstruction.rawProvenNoNativeUnwind(
                            Optional.empty(),
                            raw));
            assertIssue(
                    proxyFixture,
                    proxyMutation,
                    "LLVM_JNI_PROXY_BRANCH_SCHEMA_MISMATCH");

            Fixture bridgeFixture = fixture();
            String bridge = bridgeFixture.entry()
                    .topology()
                    .orElseThrow()
                    .bridgeSymbols()
                    .get(0);
            LlvmModule bridgeMutation = appendInstruction(
                    bridgeFixture.module(),
                    bridge,
                    0,
                    LlvmInstruction.rawProvenNoNativeUnwind(
                            Optional.empty(),
                            raw));
            assertIssue(
                    bridgeFixture,
                    bridgeMutation,
                    "LLVM_JNI_PROXY_BRIDGE_SCHEMA_MISMATCH");
        }
    }

    private Fixture fixture() throws Exception {
        var source = DirectJniEntryTestFixture.fixture(
                DirectJniEntryTestFixture.semanticClass(),
                List.of("readStaticField"));
        NativeMethodImplementation implementation =
                DirectJniEntryTestFixture.implementation(
                        source,
                        "readStaticField");
        NativeJniEntryPlan entry = source.implementationPlan()
                .jniEntryPlanFor(implementation.methodKey());
        LlvmModule module = DirectJniEntryTestFixture.compileModel(source)
                .modulesByOwner()
                .get(SemanticJniProxyBytecodeFixture.OWNER);
        return new Fixture(source, implementation, entry, module);
    }

    private LlvmModule appendInstruction(
            LlvmModule module,
            String symbol,
            int blockIndex,
            LlvmInstruction extra) {
        return replaceFunction(module, symbol, function -> {
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
            return copy(function, blocks);
        });
    }

    private LlvmModule replaceInstruction(
            LlvmModule module,
            String symbol,
            int blockIndex,
            int instructionIndex,
            UnaryOperator<LlvmInstruction> replacement) {
        return replaceFunction(module, symbol, function -> {
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
            return copy(function, blocks);
        });
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
            List<LlvmBasicBlock> blocks) {
        return new LlvmFunction(
                function.name(),
                function.linkage(),
                function.visibility(),
                function.returnType(),
                function.parameters(),
                blocks,
                function.nativeUnwindSemantics(),
                function.attributes());
    }

    private void assertIssue(
            Fixture fixture,
            LlvmModule module,
            String reasonCode) {
        List<String> issues = verifier.validate(
                fixture.source().implementationPlan(),
                NativeJniEntryTestFixture.compilation(
                        SemanticJniProxyBytecodeFixture.OWNER,
                        List.copyOf(fixture.source().irMethods().values()),
                        module));
        assertTrue(
                issues.contains(fixture.implementation().methodKey()
                        + ":"
                        + reasonCode),
                issues.toString());
    }

    private record Fixture(
            DirectJniEntryTestFixture.Fixture source,
            NativeMethodImplementation implementation,
            NativeJniEntryPlan entry,
            LlvmModule module) {}
}
