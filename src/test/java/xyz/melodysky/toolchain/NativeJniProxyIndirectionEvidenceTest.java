package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig;

/** Final-gate integration for pre-proxy TABLE call-indirection evidence. */
class NativeJniProxyIndirectionEvidenceTest {
    private final NativeJniEntryLlvmVerifier verifier =
            new NativeJniEntryLlvmVerifier();

    @Test
    void enabledTableNativeCallerCompilesWithStructuredBodyAddressEvidence()
            throws Exception {
        Fixture fixture = fixture();

        assertFalse(fixture.compilation().modules().get(0)
                .llvmCallIndirection()
                .dispatcherSymbols()
                .isEmpty());
        assertEquals(
                List.of(),
                verifier.validate(
                        fixture.source().implementationPlan(),
                        fixture.compilation()));
    }

    @Test
    void unknownGlobalAddressReferenceStillFailsClosed() throws Exception {
        Fixture fixture = fixture();
        ArrayList<LlvmGlobal> globals = new ArrayList<>(fixture.module().globals());
        globals.add(new LlvmGlobal(
                "unknown_body_address",
                "internal constant ptr @" + fixture.calleeBody()));
        LlvmModule mutation = new LlvmModule(
                fixture.module().identifier(),
                fixture.module().declarations(),
                globals,
                fixture.module().functions());

        assertIssue(
                fixture,
                replaceFinalModule(fixture.compilation(), mutation),
                "LLVM_JNI_PROXY_GLOBAL_ADDRESS_SURFACE");
    }

    @Test
    void tableizedAuthorizedCallerCannotReintroduceDirectShortcut()
            throws Exception {
        Fixture fixture = fixture();
        LlvmModule mutation = replaceFunction(
                fixture.module(),
                fixture.callerBody(),
                function -> appendInstruction(
                        function,
                        LlvmInstruction.rawProvenNoNativeUnwind(
                                Optional.empty(),
                                "call i32 @"
                                        + fixture.calleeBody()
                                        + "(i32 "
                                        + function.parameters().stream()
                                                .filter(parameter -> parameter.type()
                                                        == LlvmType.I32)
                                                .findFirst()
                                                .orElseThrow()
                                                .name()
                                        + ")")));

        assertIssue(
                fixture,
                replaceFinalModule(fixture.compilation(), mutation),
                "LLVM_JNI_PROXY_SEMANTIC_CALLER_STATE_MISMATCH");
    }

    private Fixture fixture() throws Exception {
        var source = DirectJniEntryTestFixture.fixture(
                DirectJniEntryTestFixture.ineligibleClass(),
                List.of("callee", "caller"));
        NativeLlvmCompilation compilation = new NativeLlvmCompiler(
                        new LlvmModuleLowerer(),
                        new LlvmTextEmitter())
                .compile(
                        source.implementationPlan(),
                        source.irMethods(),
                        LlvmProtectionConfig.selected(
                                0x6b6bL,
                                false,
                                false,
                                false,
                                true,
                                false));
        NativeMethodImplementation callee =
                DirectJniEntryTestFixture.implementation(source, "callee");
        NativeMethodImplementation caller =
                DirectJniEntryTestFixture.implementation(source, "caller");
        LlvmModule module = compilation.modules().get(0).module();
        return new Fixture(
                source,
                compilation,
                module,
                callee.llvmFunctionSymbol().orElseThrow(),
                caller.llvmFunctionSymbol().orElseThrow());
    }

    private NativeLlvmCompilation replaceFinalModule(
            NativeLlvmCompilation compilation,
            LlvmModule module) {
        NativeLlvmModuleCompilation old = compilation.modules().get(0);
        NativeLlvmModuleCompilation replacement =
                new NativeLlvmModuleCompilation(
                        old.owner(),
                        old.registeredMethods(),
                        old.compiledMethods(),
                        old.blockLayout(),
                        old.opaquePredicates(),
                        old.irCallIndirection(),
                        old.llvmCallIndirection(),
                        new LlvmGlobalLayoutResult(
                                module,
                                old.globalLayout().affectedGlobals(),
                                old.globalLayout().validationIssues()),
                        "");
        return new NativeLlvmCompilation(
                compilation.inputKey(),
                List.of(replacement));
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

    private LlvmFunction appendInstruction(
            LlvmFunction function,
            LlvmInstruction instruction) {
        ArrayList<LlvmBasicBlock> blocks = new ArrayList<>(function.blocks());
        LlvmBasicBlock old = blocks.get(0);
        ArrayList<LlvmInstruction> instructions =
                new ArrayList<>(old.instructions());
        instructions.add(instruction);
        blocks.set(0, new LlvmBasicBlock(
                old.name(),
                instructions,
                old.terminator()));
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
            NativeLlvmCompilation compilation,
            String reasonCode) {
        List<String> issues = verifier.validate(
                fixture.source().implementationPlan(),
                compilation);
        String methodKey = DirectJniEntryTestFixture
                .implementation(fixture.source(), "callee")
                .methodKey();
        assertTrue(
                issues.contains(methodKey + ":" + reasonCode),
                issues.toString());
    }

    private record Fixture(
            DirectJniEntryTestFixture.Fixture source,
            NativeLlvmCompilation compilation,
            LlvmModule module,
            String calleeBody,
            String callerBody) {}
}
