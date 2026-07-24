package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;

class BlockNameObfuscationPassTest {
    @Test
    void renamesEveryControlFlowAndExceptionHandlerReferenceWithOneMapping() {
        IrMethod original = exceptionalControlFlowMethod();

        var result = new ProtectionPipeline(List.of(new BlockNameObfuscationPass()))
                .runDetailed(original, ProtectionConfig.enabled(29));
        IrMethod protectedMethod = result.method();
        Map<String, String> renamed = Map.of(
                "entry", protectedMethod.blocks().get(0).name(),
                "goto", protectedMethod.blocks().get(1).name(),
                "switch", protectedMethod.blocks().get(2).name(),
                "exit", protectedMethod.blocks().get(3).name(),
                "handler", protectedMethod.blocks().get(4).name());

        assertTrue(renamed.entrySet().stream().allMatch(entry -> !entry.getKey().equals(entry.getValue())));
        IrBlock entry = protectedMethod.blocks().get(0);
        assertEquals(renamed.get("goto"), entry.terminator().trueTarget().orElseThrow());
        assertEquals(renamed.get("switch"), entry.terminator().falseTarget().orElseThrow());
        assertEquals(renamed.get("handler"), entry.exceptionEdges().get(0).target());
        assertEquals(
                renamed.get("handler"),
                entry.instructions().get(2).exceptionSites().get(0).handlers().get(0).target());

        assertEquals(
                renamed.get("exit"),
                protectedMethod.blocks().get(1).terminator().target().orElseThrow());
        IrTerminator switchTerminator = protectedMethod.blocks().get(2).terminator();
        assertEquals(renamed.get("exit"), switchTerminator.defaultTarget().orElseThrow());
        assertEquals(renamed.get("goto"), switchTerminator.switchCases().get(0).target());

        assertFalse(result.reports().stream().anyMatch(report -> report.status().equals("FAILED")));
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(new IrMethodValidator().validate(protectedMethod).isEmpty());
    }

    private IrMethod exceptionalControlFlowMethod() {
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrExceptionEdge handler = new IrExceptionEdge("handler", "java/lang/Throwable");
        IrInstruction mayThrow = IrInstruction.operation(
                        Optional.empty(),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "mayThrow")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.NULL_CHECK,
                        List.of(handler)));
        return new IrMethod(
                "pkg/Exceptional",
                "process",
                "()I",
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                List.of(),
                                List.of(handler),
                                List.of(
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(condition, IrOpcode.CMP_EQ_I32, zero, zero),
                                        mayThrow),
                                IrTerminator.branch(condition, "goto", "switch")),
                        new IrBlock(
                                "goto",
                                List.of(),
                                IrTerminator.gotoBlock("exit")),
                        new IrBlock(
                                "switch",
                                List.of(),
                                IrTerminator.switchOn(
                                        zero,
                                        "exit",
                                        List.of(new IrSwitchCase(0, "goto")))),
                        new IrBlock(
                                "exit",
                                List.of(),
                                IrTerminator.returnValue(zero)),
                        new IrBlock(
                                "handler",
                                List.of(caught),
                                List.of("java/lang/Throwable"),
                                List.of(),
                                IrTerminator.returnValue(zero))));
    }
}
