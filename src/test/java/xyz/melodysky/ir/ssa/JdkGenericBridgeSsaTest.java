package xyz.melodysky.ir.ssa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.testsupport.JdkGenericBridgeFixture;
import xyz.melodysky.toolchain.NativeExceptionFlowSupport;

class JdkGenericBridgeSsaTest {
    @Test
    void lowersExactV2BridgeSignaturesWithTypedOperandsAndPendingExceptionEvidence() {
        var parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/V2JdkBridges.class",
                        JdkGenericBridgeFixture.classBytes("pkg/V2JdkBridges"),
                        "fixture"))
                .artifact()
                .orElseThrow();

        for (JdkGenericBridgeFixture.CallSpec spec : JdkGenericBridgeFixture.calls()) {
            ParsedMethod parsedMethod = parsedClass.methods().stream()
                    .filter(method -> method.name().equals(spec.wrapperName()))
                    .findFirst()
                    .orElseThrow();
            var result = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer().lower(
                    new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow());
            assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status(), spec.wrapperName());
            assertTrue(result.diagnostics().isEmpty(), () -> spec.wrapperName() + ": " + result.diagnostics());

            IrMethod method = result.artifact().orElseThrow().irMethod().orElseThrow();
            IrInstruction call = method.blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.symbol()
                            .map(spec.targetMethodKey()::equals)
                            .orElse(false))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    spec.invokeOpcode() == Opcodes.INVOKESTATIC ? IrOpcode.CALL_STATIC : IrOpcode.CALL_VIRTUAL,
                    call.opcode(),
                    spec.targetMethodKey());

            ArrayList<IrType> expectedOperands = new ArrayList<>();
            if (spec.invokeOpcode() != Opcodes.INVOKESTATIC) {
                expectedOperands.add(IrType.REFERENCE);
            }
            expectedOperands.addAll(JvmToIrTypes.parameterTypes(spec.targetDescriptor()));
            assertEquals(
                    expectedOperands,
                    call.operands().stream().map(value -> value.type()).toList(),
                    spec.targetMethodKey());

            IrType returnType = JvmToIrTypes.returnType(spec.targetDescriptor());
            if (returnType == IrType.VOID) {
                assertTrue(call.result().isEmpty(), spec.targetMethodKey());
            } else {
                assertEquals(returnType, call.result().orElseThrow().type(), spec.targetMethodKey());
            }

            assertEquals(1, call.exceptionSites().size(), spec.targetMethodKey());
            IrExceptionSite exceptionSite = call.exceptionSites().get(0);
            assertEquals(IrExceptionSiteKind.JVM_PENDING_EXCEPTION, exceptionSite.kind(), spec.targetMethodKey());
            assertTrue(exceptionSite.handlers().isEmpty(), spec.targetMethodKey());
            assertEquals(IrType.REFERENCE, exceptionSite.exceptionValue().orElseThrow().type(), spec.targetMethodKey());
            assertFalse(new NativeExceptionFlowSupport().hasUnsupportedJvmFlow(method), spec.targetMethodKey());
            assertEquals(List.of(), new IrMethodValidator().validate(method), spec.targetMethodKey());
        }
    }
}
