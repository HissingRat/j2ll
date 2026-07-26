package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.pass.PassDiagnostics;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;

class ProtectionPipelineTest {
    @Test
    void disabledProtectionIsNoOp() {
        IrMethod method = method();

        IrMethod protectedMethod = ProtectionPipeline.defaultPipeline().run(method, ProtectionConfig.disabled(7));

        assertEquals("entry", protectedMethod.blocks().get(0).name());
    }

    @Test
    void blockNamesAreDeterministicBySeed() {
        IrMethod method = method();

        IrMethod first = ProtectionPipeline.defaultPipeline().run(method, ProtectionConfig.enabled(7));
        IrMethod second = ProtectionPipeline.defaultPipeline().run(method, ProtectionConfig.enabled(7));
        IrMethod differentSeed = ProtectionPipeline.defaultPipeline().run(method, ProtectionConfig.enabled(8));

        assertEquals(first.blocks().get(0).name(), second.blocks().get(0).name());
        assertNotEquals(first.blocks().get(0).name(), differentSeed.blocks().get(0).name());
        assertTrue(new IrMethodValidator().validate(first).isEmpty());
    }

    @Test
    void enabledProtectionSkipsMonitorSensitiveMethodWithWarning() {
        IrValue monitor = new IrValue("%p0", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Locky",
                "run",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(monitor),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                java.util.Optional.empty(),
                                IrOpcode.MONITOR_ENTER,
                                List.of(monitor),
                                "monitor")),
                        IrTerminator.returnVoid())));

        var result = ProtectionPipeline.defaultPipeline().runWithDiagnostics(method, ProtectionConfig.enabled(7));

        assertEquals("entry", result.artifact().orElseThrow().blocks().get(0).name());
        assertEquals(PassDiagnostics.PROTECTION_MONITOR_SENSITIVE_SKIP, result.diagnostics().get(0).code());
    }

    @Test
    void stringEncryptionRewritesOnlyStringConstantCarrier() {
        IrValue token = new IrValue("%token", IrType.I64);
        IrValue value = new IrValue("%value", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Strings",
                "recipe",
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constLong(token, 123L),
                                IrInstruction.call(
                                        java.util.Optional.of(value),
                                        IrOpcode.CALL_RUNTIME_HELPER,
                                        List.of(token),
                                        "j2ll_rt_string_constant|string:value=")),
                        IrTerminator.returnValue(value))));

        var result = ProtectionPipeline.defaultPipeline().runDetailed(method, ProtectionConfig.enabled(11));
        String symbol = result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                .findFirst()
                .orElseThrow()
                .symbol()
                .orElseThrow();

        assertTrue(symbol.startsWith("j2ll_rt_string_constant|enc:v1:"));
        assertFalse(symbol.contains("value="));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("STRING_ENCRYPTION")
                && report.status().equals("RAN")));
    }

    @Test
    void stringEncryptionLowersOrdinaryConstStringToEncryptedRuntimeHelper() {
        IrValue value = new IrValue("%value", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Strings",
                "literal",
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.symbolicConstant(value, IrOpcode.CONST_STRING, "string:plain-secret")),
                        IrTerminator.returnValue(value))));

        var result = new ProtectionPipeline(List.of(new StringEncryptionPass()))
                .runDetailed(method, ProtectionConfig.enabled(11));
        List<IrInstruction> instructions = result.method().blocks().get(0).instructions();

        assertEquals(2, instructions.size());
        assertEquals(IrOpcode.CONST_LONG, instructions.get(0).opcode());
        assertEquals(IrOpcode.CALL_RUNTIME_HELPER, instructions.get(1).opcode());
        assertEquals(value, instructions.get(1).result().orElseThrow());
        String symbol = instructions.get(1).symbol().orElseThrow();
        assertTrue(symbol.startsWith("j2ll_rt_string_constant|enc:v1:"));
        assertFalse(symbol.contains("plain-secret"));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void stringEncryptionAcceptsRawConstStringSymbolFromTemplateIr() {
        IrValue value = new IrValue("%value", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Strings",
                "rawLiteral",
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.symbolicConstant(value, IrOpcode.CONST_STRING, "raw-secret")),
                        IrTerminator.returnValue(value))));

        var result = new ProtectionPipeline(List.of(new StringEncryptionPass()))
                .runDetailed(method, ProtectionConfig.enabled(11));
        String symbol = result.method().blocks().get(0).instructions().get(1).symbol().orElseThrow();

        assertTrue(symbol.startsWith("j2ll_rt_string_constant|enc:v1:"));
        assertFalse(symbol.contains("raw-secret"));
    }

    @Test
    void stringEncryptionSkipsReflectionSensitiveConstStringWithReason() {
        IrValue value = new IrValue("%value", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Reflective",
                "literal",
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.symbolicConstant(value, IrOpcode.CONST_STRING, "string:pkg.Reflective"),
                                IrInstruction.call(
                                        java.util.Optional.empty(),
                                        IrOpcode.CALL_RUNTIME_HELPER,
                                        List.of(),
                                        "j2ll_rt_class_for_name_static")),
                        IrTerminator.returnValue(value))));

        var result = new ProtectionPipeline(List.of(new StringEncryptionPass()))
                .runDetailed(method, ProtectionConfig.enabled(11));

        assertEquals(IrOpcode.CONST_STRING, result.method().blocks().get(0).instructions().get(0).opcode());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("STRING_ENCRYPTION")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("STRING_ENCRYPTION_REFLECTION_SENSITIVE")));
    }

    @Test
    void primitiveConstantEncryptionEmitsXorShape() {
        IrValue constant = new IrValue("%c", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Constants",
                "answer",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constInt(constant, 42)),
                        IrTerminator.returnValue(constant))));

        var result = ProtectionPipeline.defaultPipeline().runDetailed(method, ProtectionConfig.enabled(13));

        assertTrue(result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.XOR_I32));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void controlFlowFlatteningBuildsDispatcherSwitchForSafeBranchShape() {
        IrMethod method = branchingMethod(false);

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.SWITCH));
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.parameters().stream().anyMatch(parameter -> parameter.name().contains("_state"))));
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.name().startsWith("cff_true_")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("RAN")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void controlFlowFlatteningSkipsHelperSensitiveShapeWithReason() {
        IrMethod method = branchingMethod(true);

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE")));
    }

    @Test
    void controlFlowFlatteningSkipsExceptionEdgeShapeWithReason() {
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Exceptional",
                "choose",
                "()I",
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                List.of(),
                                List.of(new IrExceptionEdge("handler", "java/lang/Throwable")),
                                List.of(IrInstruction.constInt(zero, 0)),
                                IrTerminator.gotoBlock("exit")),
                        new IrBlock("exit", List.of(), IrTerminator.returnValue(zero)),
                        new IrBlock(
                                "handler",
                                List.of(caught),
                                List.of("java/lang/Throwable"),
                                List.of(),
                                IrTerminator.returnValue(zero))));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE")));
    }

    @Test
    void controlFlowFlatteningSkipsMonitorSensitiveShapeBeforeRewrite() {
        IrValue monitor = new IrValue("%monitor", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Locky",
                "guarded",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(monitor),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.operation(
                                        java.util.Optional.empty(),
                                        IrOpcode.MONITOR_ENTER,
                                        List.of(monitor),
                                        "monitor")),
                                IrTerminator.gotoBlock("exit")),
                        new IrBlock("exit", List.of(), IrTerminator.returnVoid())));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_MONITOR_SENSITIVE_SKIP")));
    }

    @Test
    void controlFlowFlatteningSkipsVolatileJmmShapeWithReason() {
        IrValue input = new IrValue("%p0", IrType.I32);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrMethod method = new IrMethod(
                "pkg/Jmm",
                "guarded",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.operation(
                                                java.util.Optional.empty(),
                                                IrOpcode.VOLATILE_READ_BARRIER,
                                                List.of(),
                                                "volatile"),
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(condition, IrOpcode.CMP_GE_I32, input, zero)),
                                IrTerminator.branch(condition, "yes", "no")),
                        new IrBlock("yes", List.of(), IrTerminator.returnValue(input)),
                        new IrBlock("no", List.of(), IrTerminator.returnValue(zero))));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE")));
    }

    @Test
    void controlFlowFlatteningSkipsReferenceHeavyShapeWithReason() {
        IrValue input = new IrValue("%p0", IrType.REFERENCE);
        IrValue nullValue = new IrValue("%null", IrType.REFERENCE);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrMethod method = new IrMethod(
                "pkg/References",
                "choose",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(condition, IrOpcode.CMP_EQ_I32, zero, zero)),
                                IrTerminator.branch(condition, "value", "nil")),
                        new IrBlock("value", List.of(), IrTerminator.returnValue(input)),
                        new IrBlock("nil", List.of(IrInstruction.constNull(nullValue)), IrTerminator.returnValue(nullValue))));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE")));
    }

    @Test
    void floatConstantEncryptionPreservesBitsThroughIntegerXorAndBitcast() {
        IrValue constant = new IrValue("%c", IrType.F32);
        IrMethod method = new IrMethod(
                "pkg/Constants",
                "floatBits",
                "()F",
                IrType.F32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constFloat(constant, -0.0F)),
                        IrTerminator.returnValue(constant))));

        var result = ProtectionPipeline.defaultPipeline().runDetailed(method, ProtectionConfig.enabled(13));

        List<IrInstruction> instructions = result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .toList();
        assertTrue(instructions.stream().noneMatch(instruction -> instruction.opcode() == IrOpcode.CONST_FLOAT));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.XOR_I32));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.BITCAST_I32_TO_F32
                && instruction.result().orElseThrow().equals(constant)));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONSTANT_ENCRYPTION")
                && report.status().equals("RAN")
                && report.reasonCode().equals("FLOAT_CONSTANT_ENCRYPTION")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void doubleConstantEncryptionPreservesBitsThroughIntegerXorAndBitcast() {
        IrValue constant = new IrValue("%c", IrType.F64);
        IrMethod method = new IrMethod(
                "pkg/Constants",
                "doubleBits",
                "()D",
                IrType.F64,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constDouble(constant, Double.NaN)),
                        IrTerminator.returnValue(constant))));

        var result = ProtectionPipeline.defaultPipeline().runDetailed(method, ProtectionConfig.enabled(13));

        List<IrInstruction> instructions = result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .toList();
        assertTrue(instructions.stream().noneMatch(instruction -> instruction.opcode() == IrOpcode.CONST_DOUBLE));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.XOR_I64));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.BITCAST_I64_TO_F64
                && instruction.result().orElseThrow().equals(constant)));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONSTANT_ENCRYPTION")
                && report.status().equals("RAN")
                && report.reasonCode().equals("DOUBLE_CONSTANT_ENCRYPTION")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void basicBlockSplittingOnlySplitsInstructionsWhenEnabledAlone() {
        IrMethod method = splittableMethod();

        var result = splitAndFakePipeline().runDetailed(method, splitAndFakeConfig(true, false));

        assertEquals(2, result.method().blocks().size());
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.GOTO));
        assertFalse(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.BRANCH));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("RAN")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_PASS_DISABLED")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void fakeBranchesOnlyAddsDetourWhenEnabledAlone() {
        IrMethod method = splittableMethod();

        var result = splitAndFakePipeline().runDetailed(method, splitAndFakeConfig(false, true));

        assertEquals(3, result.method().blocks().size());
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.BRANCH));
        assertTrue(result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.XOR_I32));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_PASS_DISABLED")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                && report.status().equals("RAN")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void basicBlockSplittingAndFakeBranchesCanBothRunOnOneMethod() {
        IrMethod method = splittableMethod();

        var result = splitAndFakePipeline().runDetailed(method, splitAndFakeConfig(true, true));

        assertEquals(4, result.method().blocks().size());
        assertTrue(result.method().blocks().stream().anyMatch(block -> block.name().startsWith("split_")));
        assertTrue(result.method().blocks().stream().anyMatch(block -> block.name().startsWith("fake_detour_")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("RAN")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                && report.status().equals("RAN")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    private ProtectionPipeline splitAndFakePipeline() {
        return new ProtectionPipeline(List.of(new BasicBlockSplittingPass(), new FakeBranchesPass()));
    }

    private ProtectionConfig splitAndFakeConfig(boolean split, boolean fake) {
        return new ProtectionConfig(true, 17, false, false, false, split, fake, false);
    }

    private IrMethod splittableMethod() {
        IrValue input = new IrValue("%p0", IrType.I32);
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue result = new IrValue("%result", IrType.I32);
        return new IrMethod(
                "pkg/Sample",
                "calculate",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(one, 1),
                                IrInstruction.binary(result, IrOpcode.ADD_I32, input, one)),
                        IrTerminator.returnValue(result))));
    }

    private IrMethod method() {
        return new IrMethod(
                "pkg/Sample",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock("entry", List.<IrInstruction>of(), IrTerminator.returnVoid())));
    }

    private IrMethod branchingMethod(boolean helperSensitive) {
        IrValue input = new IrValue("%p0", IrType.I32);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue left = new IrValue("%left", IrType.I32);
        IrValue right = new IrValue("%right", IrType.I32);
        List<IrInstruction> entryInstructions = new java.util.ArrayList<>();
        if (helperSensitive) {
            entryInstructions.add(IrInstruction.call(
                    java.util.Optional.empty(),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    List.of(),
                    "j2ll_rt_sensitive"));
        }
        entryInstructions.add(IrInstruction.constInt(zero, 0));
        entryInstructions.add(IrInstruction.binary(condition, IrOpcode.CMP_GE_I32, input, zero));
        return new IrMethod(
                "pkg/Branches",
                "choose",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                entryInstructions,
                                IrTerminator.branch(condition, "left", "right")),
                        new IrBlock(
                                "left",
                                List.of(IrInstruction.constInt(left, 1)),
                                IrTerminator.returnValue(left)),
                        new IrBlock(
                                "right",
                                List.of(IrInstruction.constInt(right, -1)),
                                IrTerminator.returnValue(right))));
    }
}
