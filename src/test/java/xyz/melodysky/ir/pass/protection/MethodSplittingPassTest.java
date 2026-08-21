package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;

class MethodSplittingPassTest {
    private final MethodSplittingPass pass = new MethodSplittingPass();

    @Test
    void outlinesScalarSuffixWithExplicitLiveInAndLiveOutAbi() {
        IrMethod original = scalarMethod();

        MethodSplittingResult result = pass.run(original, 41L);

        assertEquals(MethodSplittingStatus.RAN, result.status());
        assertEquals(MethodSplittingPass.NAME, result.reasonCode());
        OutlinedMethodHelper helper = result.helper().orElseThrow();
        IrValue input = original.parameters().get(0);
        IrValue two = original.blocks().get(0).instructions().get(0).result().orElseThrow();
        IrValue product = original.blocks().get(0).instructions().get(2).result().orElseThrow();

        assertEquals(List.of(input, two), helper.plan().liveIns());
        assertEquals(List.of(product), helper.plan().liveOuts());
        assertEquals("(II)I", helper.plan().helperDescriptor());
        assertTrue(helper.plan().nativeSymbol().matches("j2ll_oh_[0-9a-f]{24}"));
        assertTrue(helper.emittedFunctionSymbol(new LlvmNameMangler()::functionName)
                .matches("j2ll_f_[0-9a-f]{32}"));
        assertEquals(2, helper.body().blocks().get(0).instructions().size());
        assertEquals(product, helper.body().blocks().get(0).terminator().value().orElseThrow());

        List<IrInstruction> callerInstructions = result.caller().blocks().get(0).instructions();
        assertEquals(2, callerInstructions.size());
        IrInstruction outlinedCall = callerInstructions.get(1);
        assertEquals(IrOpcode.CALL_STATIC, outlinedCall.opcode());
        assertEquals(helper.methodKey(), outlinedCall.symbol().orElseThrow());
        assertEquals(List.of(input, two), outlinedCall.operands());
        assertEquals(product, outlinedCall.result().orElseThrow());
        assertTrue(new IrMethodValidator().validate(result.caller()).isEmpty());
        assertTrue(new IrMethodValidator().validate(helper.body()).isEmpty());
        assertTrue(result.validationErrors().isEmpty());
    }

    @Test
    void keepsMultipleSuccessorTerminatorInCaller() {
        IrMethod original = branchingMethod();
        IrTerminator originalTerminator = original.blocks().get(0).terminator();

        MethodSplittingResult result = pass.run(original, 7L);

        assertEquals(MethodSplittingStatus.RAN, result.status());
        OutlinedMethodHelper helper = result.helper().orElseThrow();
        assertEquals(List.of("positive", "negative"), helper.plan().successorBlocks());
        assertEquals("(II)Z", helper.plan().helperDescriptor());
        assertEquals(originalTerminator, result.caller().blocks().get(0).terminator());
        assertEquals(
                result.caller().blocks().get(0).instructions().get(1).result().orElseThrow(),
                result.caller().blocks().get(0).terminator().condition().orElseThrow());
        assertTrue(new IrMethodValidator().validate(result.caller()).isEmpty());
        assertTrue(new IrMethodValidator().validate(helper.body()).isEmpty());
    }

    @Test
    void callerCallUsesExistingDirectLlvmCallConvention() {
        MethodSplittingResult result = pass.run(scalarMethod(), 43L);
        OutlinedMethodHelper helper = result.helper().orElseThrow();
        LlvmNameMangler nameMangler = LlvmNameMangler.obfuscating(43L);

        var module = new LlvmModuleLowerer(
                nameMangler,
                xyz.melodysky.testsupport.TestProtectionMaterials
                        .businessStringSymbols(),
                xyz.melodysky.testsupport.TestProtectionMaterials
                        .runtimeTokens()).lowerClass(
                new IrClass(result.caller().owner(), List.of(result.caller(), helper.body())),
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                Set.of(helper.methodKey()));
        String llvm = new LlvmTextEmitter().emit(module);
        String backendSymbol = helper.emittedFunctionSymbol(nameMangler::functionName);

        assertTrue(
                llvm.contains("%product = call i32 @" + backendSymbol + "(i32 %input, i32 %two)"),
                llvm);
        assertTrue(
                llvm.contains("define internal hidden i32 @" + backendSymbol + "(i32 %input, i32 %two)"),
                llvm);
        assertEquals(
                backendSymbol,
                module.functions().stream()
                        .filter(function -> function.name().equals(backendSymbol))
                        .findFirst()
                        .orElseThrow()
                        .name());
        assertEquals(
                LlvmLinkage.INTERNAL,
                module.functions().stream()
                        .filter(function -> function.name().equals(backendSymbol))
                        .findFirst()
                        .orElseThrow()
                        .linkage());
        assertEquals(
                LlvmVisibility.HIDDEN,
                module.functions().stream()
                        .filter(function -> function.name().equals(backendSymbol))
                        .findFirst()
                        .orElseThrow()
                        .visibility());
        assertFalse(llvm.contains("call i32 @j2ll_rt_call_static_i32_a"), llvm);
    }

    @Test
    void seedAndDisabledModeAreDeterministic() {
        IrMethod original = scalarMethod();

        MethodSplittingResult first = pass.run(original, 101L);
        MethodSplittingResult repeated = pass.run(original, 101L);
        MethodSplittingResult otherSeed = pass.run(original, 102L);
        MethodSplittingResult disabled = pass.run(original, 101L, false);

        assertEquals(first, repeated);
        assertNotEquals(
                first.helper().orElseThrow().emittedFunctionSymbol(new LlvmNameMangler()::functionName),
                otherSeed.helper().orElseThrow().emittedFunctionSymbol(new LlvmNameMangler()::functionName));
        assertEquals(MethodSplittingStatus.SKIPPED, disabled.status());
        assertEquals(MethodSplittingPass.DISABLED, disabled.reasonCode());
        assertEquals(original, disabled.caller());
        assertTrue(disabled.helpers().isEmpty());
    }

    @Test
    void rejectsHelperSensitiveMethodWithoutPartialRewrite() {
        IrValue input = new IrValue("%input", IrType.I32);
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue resultValue = new IrValue("%result", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Sensitive",
                "calculate",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(one, 1),
                                IrInstruction.call(
                                        Optional.empty(),
                                        IrOpcode.CALL_RUNTIME_HELPER,
                                        List.of(),
                                        "j2ll_rt_sensitive"),
                                IrInstruction.binary(resultValue, IrOpcode.ADD_I32, input, one)),
                        IrTerminator.returnValue(resultValue))));

        MethodSplittingResult result = pass.run(method, 19L);

        assertEquals(MethodSplittingStatus.SKIPPED, result.status());
        assertEquals(MethodSplitPlanner.HELPER_SENSITIVE, result.reasonCode());
        assertEquals(method, result.caller());
        assertTrue(result.helpers().isEmpty());
    }

    @Test
    void rejectsMultipleLiveOutRegion() {
        IrValue input = new IrValue("%input", IrType.I32);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue left = new IrValue("%left", IrType.I32);
        IrValue right = new IrValue("%right", IrType.I32);
        IrValue leftParameter = new IrValue("%leftParameter", IrType.I32);
        IrValue rightParameter = new IrValue("%rightParameter", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/MultiOut",
                "pair",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(left, IrOpcode.ADD_I32, input, zero),
                                        IrInstruction.binary(right, IrOpcode.SUB_I32, input, zero)),
                                IrTerminator.gotoBlock("exit", List.of(left, right))),
                        new IrBlock(
                                "exit",
                                List.of(leftParameter, rightParameter),
                                List.of(),
                                IrTerminator.returnValue(leftParameter))));

        MethodSplittingResult result = pass.run(method, 23L);

        assertEquals(MethodSplittingStatus.SKIPPED, result.status());
        assertEquals(MethodSplitPlanner.UNSUPPORTED_LIVE_OUT_ARITY, result.reasonCode());
        assertEquals(method, result.caller());
        assertTrue(result.helpers().isEmpty());
    }

    @Test
    void rejectsStubBackedAndInvalidMethods() {
        IrMethod scalar = scalarMethod();
        IrMethod constructor = new IrMethod(
                scalar.owner(),
                "<init>",
                scalar.descriptor(),
                scalar.returnType(),
                scalar.parameters(),
                scalar.blocks());
        IrMethod invalid = new IrMethod(
                "pkg/Invalid",
                "empty",
                "()V",
                IrType.VOID,
                List.of(),
                List.of());

        MethodSplittingResult constructorResult = pass.run(constructor, 3L);
        MethodSplittingResult invalidResult = pass.run(invalid, 3L);

        assertEquals(MethodSplittingStatus.SKIPPED, constructorResult.status());
        assertEquals(MethodSplitPlanner.STUB_BACKED, constructorResult.reasonCode());
        assertEquals(MethodSplittingStatus.FAILED, invalidResult.status());
        assertEquals(MethodSplittingPass.INPUT_INVALID, invalidResult.reasonCode());
        assertFalse(invalidResult.validationErrors().isEmpty());
    }

    private IrMethod scalarMethod() {
        IrValue input = new IrValue("%input", IrType.I32);
        IrValue two = new IrValue("%two", IrType.I32);
        IrValue sum = new IrValue("%sum", IrType.I32);
        IrValue product = new IrValue("%product", IrType.I32);
        return new IrMethod(
                "pkg/Scalar",
                "calculate",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(two, 2),
                                IrInstruction.binary(sum, IrOpcode.ADD_I32, input, two),
                                IrInstruction.binary(product, IrOpcode.MUL_I32, sum, two)),
                        IrTerminator.returnValue(product))));
    }

    private IrMethod branchingMethod() {
        IrValue input = new IrValue("%input", IrType.I32);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue padding = new IrValue("%padding", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue positive = new IrValue("%positive", IrType.I32);
        IrValue negative = new IrValue("%negative", IrType.I32);
        return new IrMethod(
                "pkg/Branch",
                "choose",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.constInt(padding, 1),
                                        IrInstruction.binary(condition, IrOpcode.CMP_GE_I32, input, zero)),
                                IrTerminator.branch(condition, "positive", "negative")),
                        new IrBlock(
                                "positive",
                                List.of(IrInstruction.constInt(positive, 1)),
                                IrTerminator.returnValue(positive)),
                        new IrBlock(
                                "negative",
                                List.of(IrInstruction.constInt(negative, -1)),
                                IrTerminator.returnValue(negative))));
    }
}
