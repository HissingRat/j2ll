package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.pass.PassDiagnostics;
import xyz.melodysky.ir.model.IrBlock;
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
    void basicBlockSplittingAddsOpaqueFakeBranch() {
        IrMethod method = method();

        var result = ProtectionPipeline.defaultPipeline().runDetailed(method, ProtectionConfig.enabled(17));

        assertEquals(3, result.method().blocks().size());
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.GOTO));
        assertTrue(result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.CMP_EQ_I32));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
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
}
