package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
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

    @Test
    void splittingParameterizedBlockKeepsPhiInDominatingPrefix() {
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue merged = new IrValue("%merged", IrType.I32);
        IrValue two = new IrValue("%two", IrType.I32);
        IrValue sum = new IrValue("%sum", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Protected",
                "merge",
                "()I",
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.constInt(one, 1)),
                                IrTerminator.gotoBlock("merge", List.of(one))),
                        new IrBlock(
                                "merge",
                                List.of(merged),
                                List.of(
                                        IrInstruction.constInt(two, 2),
                                        IrInstruction.binary(sum, IrOpcode.ADD_I32, merged, two)),
                                IrTerminator.returnValue(sum))));
        ProtectionPipeline pipeline = new ProtectionPipeline(List.of(new BasicBlockSplittingPass()));
        ProtectionConfig config = new ProtectionConfig(true, 17, false, false, false, true, false, false);

        ProtectionPipelineResult protectedResult = pipeline.runDetailed(method, config);
        String llvm = new LlvmTextEmitter().emit(new LlvmModuleLowerer()
                .lowerClass(new IrClass(method.owner(), List.of(protectedResult.method()))));

        assertTrue(protectedResult.diagnostics().isEmpty(), protectedResult.diagnostics().toString());
        assertTrue(protectedResult.reports().stream()
                .anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                        && report.status().equals("RAN")));
        assertTrue(protectedResult.method().blocks().stream()
                .anyMatch(block -> block.name().startsWith("split_")));
        assertTrue(llvm.contains("%merged = phi i32 [ %one, %entry ]"), llvm);
        assertTrue(llvm.contains("add i32 %merged, %two"), llvm);
    }

    @Test
    void ownedReferenceMethodSkipsFlatteningAndStillReachesValidatedLlvm() {
        IrValue array = new IrValue("%p0", IrType.REFERENCE);
        IrValue length = new IrValue("%length", IrType.I32);
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue positive = new IrValue("%positive", IrType.I1);
        IrValue nil = new IrValue("%nil", IrType.REFERENCE);
        IrInstruction arrayLength = IrInstruction.operation(
                        Optional.of(length),
                        IrOpcode.ARRAY_LENGTH,
                        List.of(array),
                        "arrayLength")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        Optional.of(pending)));
        IrMethod method = new IrMethod(
                "pkg/Protected",
                "nonEmptyOrNull",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(array),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        arrayLength,
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(
                                                positive,
                                                IrOpcode.CMP_GT_I32,
                                                length,
                                                zero)),
                                IrTerminator.branch(positive, "nonEmpty", "empty")),
                        new IrBlock(
                                "nonEmpty",
                                List.of(),
                                IrTerminator.returnValue(array)),
                        new IrBlock(
                                "empty",
                                List.of(IrInstruction.constNull(nil)),
                                IrTerminator.returnValue(nil))));
        ProtectionPipeline pipeline =
                new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()));
        ProtectionConfig config =
                new ProtectionConfig(true, 17, true, false, false, false, false, false);

        ProtectionPipelineResult protectedResult = pipeline.runDetailed(method, config);
        String llvm = new LlvmTextEmitter().emit(new LlvmModuleLowerer()
                .lowerClass(new IrClass(method.owner(), List.of(protectedResult.method()))));

        assertTrue(protectedResult.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.decision().equals(
                        "CONTROL_FLOW_FLATTENING_OWNED_LOCAL_REFERENCE")),
                protectedResult.diagnostics().toString());
        assertTrue(protectedResult.reports().stream()
                .anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                        && report.status().equals("SKIPPED")
                        && report.reasonCode().equals(
                                "CONTROL_FLOW_FLATTENING_OWNED_LOCAL_REFERENCE")));
        assertFalse(llvm.contains("switch i32"), llvm);
        assertTrue(llvm.contains("call i32 @j2ll_rt_array_length_i32("), llvm);
        assertTrue(llvm.contains("call ptr @j2ll_rt_pending_exception("), llvm);
        assertFalse(llvm.contains("call void @j2ll_rt_clear_exception("), llvm);
    }
}
