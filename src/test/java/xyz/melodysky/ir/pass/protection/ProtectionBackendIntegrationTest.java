package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

class ProtectionBackendIntegrationTest {
    @Test
    void independentSplitAndFakeBranchPassesReachValidatedLlvm() {
        IrValue input = new IrValue("%p0", IrType.I32);
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue sum = new IrValue("%sum", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Protected",
                "calculate",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(one, 1),
                                IrInstruction.binary(sum, IrOpcode.ADD_I32, input, one)),
                        IrTerminator.returnValue(sum))));
        ProtectionPipeline pipeline =
                new ProtectionPipeline(List.of(new BasicBlockSplittingPass(), new FakeBranchesPass()));
        ProtectionConfig config = new ProtectionConfig(true, 17, false, false, false, true, true, false);

        ProtectionPipelineResult protectedResult = pipeline.runDetailed(method, config);
        String llvm = new LlvmTextEmitter().emit(new LlvmModuleLowerer()
                .lowerClass(new IrClass(method.owner(), List.of(protectedResult.method()))));

        assertTrue(protectedResult.diagnostics().isEmpty(), protectedResult.diagnostics().toString());
        assertTrue(protectedResult.reports().stream()
                .anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                        && report.status().equals("RAN")));
        assertTrue(protectedResult.reports().stream()
                .anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                        && report.status().equals("RAN")));
        assertTrue(llvm.contains("define external hidden i32"));
        assertTrue(llvm.contains("br i1"));
        assertTrue(llvm.contains("br label"));
    }
}
