package xyz.melodysky.ir.ssa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.cfg.MethodCfgResult;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiDomain;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiPlan;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiPlanner;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.ExceptionFlowAsmFixtures;
import xyz.melodysky.toolchain.NativeExceptionFlowSupport;

class BytecodeToSsaLowererTest {
    @Test
    void lowersStraightLineIntAddToThreeAddressIr() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithAddMethod("pkg/Mathy"),
                "add");

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.status());
        var method = result.irMethod().orElseThrow();
        assertEquals(2, method.parameters().size());
        assertEquals(1, method.blocks().size());
        assertEquals(IrOpcode.ADD_I32, method.blocks().get(0).instructions().get(0).opcode());
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
    }

    @Test
    void lowersIntegerConstantReturn() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithIntMethod("pkg/Consty", "answer", 42),
                "answer");

        var method = result.irMethod().orElseThrow();
        assertEquals(IrOpcode.CONST_INT, method.blocks().get(0).instructions().get(0).opcode());
        assertEquals(42, method.blocks().get(0).instructions().get(0).intLiteral().orElseThrow());
    }

    @Test
    void lowersConditionalBranchWithoutMerge() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithConditionalMethod("pkg/Branch"),
                "choose");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status());
        var method = result.artifact().orElseThrow().irMethod().orElseThrow();
        assertEquals(3, method.blocks().size());
        assertTrue(llvm(method).contains("icmp eq i32 %p0"));
        assertTrue(llvm(method).contains("br i1"));
    }

    @Test
    void lowersDiamondLocalMergeWithBlockParameter() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithIfMergeMethod("pkg/Merge"),
                "merged");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status());
        var method = result.artifact().orElseThrow().irMethod().orElseThrow();
        assertTrue(method.blocks().stream().anyMatch(block -> !block.parameters().isEmpty()));
        assertTrue(llvm(method).contains(" phi i32 "));
    }

    @Test
    void lowersStackMergeWithBlockParameter() {
        var method = lower(
                AsmFixtureBuilder.classWithStackMergeMethod("pkg/StackMerge"),
                "stackMerged").irMethod().orElseThrow();

        assertTrue(method.blocks().stream().anyMatch(block -> !block.parameters().isEmpty()));
        assertTrue(llvm(method).contains(" phi i32 "));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
    }

    @Test
    void lowersSimpleLoopCounterWithBlockParameter() {
        var method = lower(
                AsmFixtureBuilder.classWithSimpleLoopCounter("pkg/LoopMerge"),
                "count").irMethod().orElseThrow();

        assertTrue(method.blocks().stream().anyMatch(block -> !block.parameters().isEmpty()));
        String llvm = llvm(method);
        assertTrue(llvm.contains(" phi i32 "));
        assertTrue(llvm.contains("br label %"));
    }

    @Test
    void lowersSwitchMergeWithBlockParameter() {
        var method = lower(
                AsmFixtureBuilder.classWithSwitchStackMergeMethod("pkg/SwitchMerge"),
                "selectMerged").irMethod().orElseThrow();

        assertTrue(method.blocks().stream().anyMatch(block -> !block.parameters().isEmpty()));
        String llvm = llvm(method);
        assertTrue(llvm.contains("switch i32 %p0"));
        assertTrue(llvm.contains(" phi i32 "));
    }

    @Test
    void mergeMismatchProducesSpecificSkipDiagnostics() {
        assertMergeSkipped(
                AsmFixtureBuilder.classWithBadStackHeightMerge("pkg/BadStackHeight"),
                "badStack",
                LoweringDiagnostics.SSA_MERGE_STACK_HEIGHT_MISMATCH);
        assertMergeSkipped(
                AsmFixtureBuilder.classWithBadStackTypeMerge("pkg/BadStackType"),
                "badType",
                LoweringDiagnostics.SSA_MERGE_TYPE_MISMATCH);
        assertMergeSkipped(
                classWithLiveUndefinedLocal("pkg/BadLocal"),
                "liveUndefined",
                LoweringDiagnostics.SSA_MERGE_LOCAL_SLOT_MISMATCH);
        assertMergeSkipped(
                classWithLiveLocalTypeMismatch("pkg/BadLocalType"),
                "liveTypeMismatch",
                LoweringDiagnostics.SSA_MERGE_TYPE_MISMATCH);
    }

    @Test
    void ignoresLocalDefinedOnOnlyOneIncomingEdgeWhenDeadAtJoin() {
        var method = lower(
                classWithDeadPartialLocal("pkg/DeadPartialLocal"),
                "deadPartial").irMethod().orElseThrow();

        assertTrue(method.blocks().stream().allMatch(block -> block.parameters().isEmpty()));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
    }

    @Test
    void allowsDeadLocalSlotToHaveDifferentTypesAcrossIncomingEdges() {
        var method = lower(
                classWithDeadLocalTypeReuse("pkg/DeadLocalTypeReuse"),
                "deadTypeReuse").irMethod().orElseThrow();

        assertTrue(method.blocks().stream().allMatch(block -> block.parameters().isEmpty()));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
    }

    @Test
    void lowersLongLoadAddAndReturn() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithLongAddMethod("pkg/LongMath"),
                "addLong");

        var method = result.irMethod().orElseThrow();
        assertEquals(IrType.I64, method.returnType());
        assertEquals(IrOpcode.ADD_I64, method.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(method).contains("add i64 %p0, %p1"));
    }

    @Test
    void lowersFloatNegationAndReturn() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithFloatNegMethod("pkg/FloatMath"),
                "negFloat");

        var method = result.irMethod().orElseThrow();
        assertEquals(IrOpcode.NEG_F32, method.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(method).contains("fsub float -0.0, %p0"));
    }

    @Test
    void lowersDoubleLdcConstant() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithDoubleLdcMethod("pkg/DoubleConst"),
                "doubleConst");

        var instruction = result.irMethod().orElseThrow().blocks().get(0).instructions().get(0);
        assertEquals(IrOpcode.CONST_DOUBLE, instruction.opcode());
        assertEquals(2.5D, instruction.doubleLiteral().orElseThrow());
        assertTrue(llvm(result.irMethod().orElseThrow()).contains(
                "bitcast i64 4612811918334230528 to double"));
    }

    @Test
    void lowersIntRemainderAndNegation() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithIntRemainderNegMethod("pkg/IntMath"),
                "remNeg");

        var instructions = result.irMethod().orElseThrow().blocks().get(0).instructions();
        assertEquals(IrOpcode.REM_I32, instructions.get(0).opcode());
        assertEquals(IrOpcode.NEG_I32, instructions.get(1).opcode());
        String llvm = llvm(result.irMethod().orElseThrow());
        assertTrue(llvm.contains("call i32 @j2ll_rt_rem_i32(ptr %j2ll_env, i32 %p0, i32 %p1)"));
        assertFalse(llvm.contains("srem i32 %p0, %p1"));
    }

    @Test
    void lowersWideLocalIinc() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithWideLocalIincMethod("pkg/WideLocals"),
                "wideIinc");

        var instructions = result.irMethod().orElseThrow().blocks().get(0).instructions();
        assertEquals(IrOpcode.CONST_INT, instructions.get(0).opcode());
        assertEquals(40, instructions.get(0).intLiteral().orElseThrow());
        assertEquals(IrOpcode.CONST_INT, instructions.get(1).opcode());
        assertEquals(2, instructions.get(1).intLiteral().orElseThrow());
        assertEquals(IrOpcode.ADD_I32, instructions.get(2).opcode());
        assertTrue(llvm(result.irMethod().orElseThrow()).contains("add i32"));
    }

    @Test
    void lowersStackPermutationOpcodes() {
        byte[] classBytes = AsmFixtureBuilder.classWithStackPermutationMethods("pkg/StackOps");

        assertEquals(IrOpcode.SUB_I32, lower(classBytes, "stackInt")
                .irMethod().orElseThrow().blocks().get(0).instructions().get(0).opcode());
        assertEquals(3, lower(classBytes, "dup2Int")
                .irMethod().orElseThrow().blocks().get(0).instructions().size());
        assertEquals(LoweringStatus.NATIVE_LOWERED, lower(classBytes, "dupX2Long").status());
        assertEquals(LoweringStatus.NATIVE_LOWERED, lower(classBytes, "dup2X1Int").status());
        assertEquals(LoweringStatus.NATIVE_LOWERED, lower(classBytes, "dup2X2Int").status());
    }

    @Test
    void lowersBitwiseAndMaskedShiftOpcodes() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithBitwiseShiftMethod("pkg/BitMath"),
                "bitShift");

        var method = result.irMethod().orElseThrow();
        var instructions = method.blocks().get(0).instructions();
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.AND_I32));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.SHL_I32));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.XOR_I32));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.USHR_I32));
        String llvm = llvm(method);
        assertTrue(llvm.contains("and i32"));
        assertTrue(llvm.contains("shl i32"));
        assertTrue(llvm.contains("lshr i32"));
    }

    @Test
    void lowersLongBitwiseAndMaskedShiftOpcodes() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithLongBitwiseShiftMethod("pkg/LongBitMath"),
                "longBitShift");

        var method = result.irMethod().orElseThrow();
        var instructions = method.blocks().get(0).instructions();
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.SHR_I64));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.OR_I64));
        String llvm = llvm(method);
        assertTrue(llvm.contains("sext i32"));
        assertTrue(llvm.contains("ashr i64"));
        assertTrue(llvm.contains("or i64"));
    }

    @Test
    void lowersI2DConversion() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithI2DMethod("pkg/Convert"),
                "toDouble");

        var method = result.irMethod().orElseThrow();
        assertEquals(IrOpcode.I2D, method.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(method).contains("sitofp i32 %p0 to double"));
    }

    @Test
    void lowersPrimitiveConversions() {
        byte[] classBytes = AsmFixtureBuilder.classWithPrimitiveConversionMethods("pkg/ConvertMore");

        var narrow = lower(classBytes, "narrow").irMethod().orElseThrow();
        assertEquals(IrOpcode.L2I, narrow.blocks().get(0).instructions().get(0).opcode());
        assertEquals(IrOpcode.I2B, narrow.blocks().get(0).instructions().get(1).opcode());
        assertTrue(llvm(narrow).contains("trunc i64 %p0 to i32"));
        assertTrue(llvm(narrow).contains("call i32 @j2ll_rt_i2b"));

        var floatToInt = lower(classBytes, "floatToInt").irMethod().orElseThrow();
        assertEquals(IrOpcode.F2I, floatToInt.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(floatToInt).contains("call i32 @j2ll_rt_f2i"));

        var floatToDouble = lower(classBytes, "floatToDouble").irMethod().orElseThrow();
        assertEquals(IrOpcode.F2D, floatToDouble.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(floatToDouble).contains("fpext float %p0 to double"));
    }

    @Test
    void lowersJvmComparisonOpcodesToHelpers() {
        byte[] classBytes = AsmFixtureBuilder.classWithJvmComparisonMethods("pkg/CompareMore");

        var longCmp = lower(classBytes, "longCmp").irMethod().orElseThrow();
        assertEquals(IrOpcode.LCMP, longCmp.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(longCmp).contains("call i32 @j2ll_rt_lcmp(i64 %p0, i64 %p1)"));

        var floatCmp = lower(classBytes, "floatCmp").irMethod().orElseThrow();
        assertEquals(IrOpcode.FCMPL, floatCmp.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(floatCmp).contains("call i32 @j2ll_rt_fcmpl(float %p0, float %p1)"));
    }

    @Test
    void safeOpcodeCoverageMatrixHasNoSilentUnsupportedGaps() {
        assertEquals(LoweringStatus.NATIVE_LOWERED,
                lower(AsmFixtureBuilder.classWithStackPermutationMethods("pkg/StackOps"), "dup2X2Int").status());
        assertEquals(LoweringStatus.NATIVE_LOWERED,
                lower(AsmFixtureBuilder.classWithBitwiseShiftMethod("pkg/BitMath"), "bitShift").status());
        assertEquals(LoweringStatus.NATIVE_LOWERED,
                lower(AsmFixtureBuilder.classWithLongBitwiseShiftMethod("pkg/LongBitMath"), "longBitShift").status());
        assertEquals(LoweringStatus.NATIVE_LOWERED,
                lower(AsmFixtureBuilder.classWithPrimitiveConversionMethods("pkg/ConvertMore"), "narrow").status());
        assertEquals(LoweringStatus.NATIVE_LOWERED,
                lower(AsmFixtureBuilder.classWithJvmComparisonMethods("pkg/CompareMore"), "longCmp").status());
        assertEquals(LoweringStatus.NATIVE_LOWERED,
                lower(AsmFixtureBuilder.classWithTableSwitchMethod("pkg/TableSwitch"), "select").status());
        assertEquals(LoweringStatus.NATIVE_LOWERED,
                lower(AsmFixtureBuilder.classWithLookupSwitchMethod("pkg/LookupSwitch"), "lookup").status());
    }

    @Test
    void lowersReferenceConditionalBranchesWithoutMerge() {
        byte[] classBytes = AsmFixtureBuilder.classWithReferenceBranchMethods("pkg/RefBranches");

        var same = lower(classBytes, "same").irMethod().orElseThrow();
        assertEquals(IrOpcode.CMP_EQ_REF, same.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(same).contains("icmp eq ptr %p0, %p1"));

        var isNull = lower(classBytes, "isNull").irMethod().orElseThrow();
        assertEquals(IrOpcode.CONST_NULL, isNull.blocks().get(0).instructions().get(0).opcode());
        assertEquals(IrOpcode.CMP_EQ_REF, isNull.blocks().get(0).instructions().get(1).opcode());
        assertTrue(llvm(isNull).contains("icmp eq ptr %p0"));
    }

    @Test
    void lowersTableAndLookupSwitchTerminatorsWithoutMerge() {
        var table = lower(
                AsmFixtureBuilder.classWithTableSwitchMethod("pkg/TableSwitch"),
                "select").irMethod().orElseThrow();
        assertEquals(IrTerminatorKind.SWITCH, table.blocks().get(0).terminator().kind());
        assertEquals(2, table.blocks().get(0).terminator().switchCases().size());
        assertTrue(llvm(table).contains("switch i32 %p0"));
        assertTrue(llvm(table).contains("i32 0, label %"));
        assertTrue(llvm(table).contains("i32 1, label %"));

        var lookup = lower(
                AsmFixtureBuilder.classWithLookupSwitchMethod("pkg/LookupSwitch"),
                "lookup").irMethod().orElseThrow();
        assertEquals(IrTerminatorKind.SWITCH, lookup.blocks().get(0).terminator().kind());
        assertEquals(2, lookup.blocks().get(0).terminator().switchCases().size());
        assertTrue(llvm(lookup).contains("i32 10, label %"));
        assertTrue(llvm(lookup).contains("i32 20, label %"));
    }

    @Test
    void prunesTypedCatchHandlerWhenProtectedRegionCannotThrow() {
        var method = lower(
                AsmFixtureBuilder.classWithTryCatchMethod("pkg/TryCatch"),
                "guarded").irMethod().orElseThrow();

        assertFalse(method.blocks().stream().anyMatch(block -> block.isExceptionHandler()));
        assertFalse(method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.exceptionSites().stream())
                .anyMatch(site -> !site.handlers().isEmpty()));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
    }

    @Test
    void safeFinallyCleanupShapeLowersNatively() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithFinallyCleanupShape("pkg/SafeFinally"),
                "withCleanup");

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.status());
        assertTrue(result.irMethod().isPresent());
        assertNull(result.reasonCode());
        assertNull(result.reason());
    }

    @Test
    void lowersAthrowToRethrowHelperAndPendingExceptionReturn() {
        var method = lower(
                AsmFixtureBuilder.classWithAthrowMethod("pkg/Raise"),
                "raise").irMethod().orElseThrow();

        assertEquals(IrTerminatorKind.THROW, method.blocks().get(0).terminator().kind());
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_rethrow(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains("ret void"));
    }

    @Test
    void marksImplicitExceptionSites() {
        var division = lower(
                AsmFixtureBuilder.classWithIntDivideMethod("pkg/Divide"),
                "divide").irMethod().orElseThrow();
        assertTrue(hasExceptionSite(division, IrExceptionSiteKind.DIVISION_BY_ZERO));

        var arrays = AsmFixtureBuilder.classWithArrayOperationMethods("pkg/Arrays");
        assertTrue(hasExceptionSite(lower(arrays, "firstPlusLength").irMethod().orElseThrow(), IrExceptionSiteKind.NULL_CHECK));
        assertTrue(hasExceptionSite(lower(arrays, "firstPlusLength").irMethod().orElseThrow(), IrExceptionSiteKind.ARRAY_BOUNDS));
        assertTrue(hasExceptionSite(lower(arrays, "putRef").irMethod().orElseThrow(), IrExceptionSiteKind.ARRAY_STORE));

        var cast = lower(
                AsmFixtureBuilder.classWithTypeOperationMethods("pkg/TypeOps"),
                "castString").irMethod().orElseThrow();
        assertTrue(hasExceptionSite(cast, IrExceptionSiteKind.CLASS_CAST));
    }

    @Test
    void prunesUnreachableCatchAllRethrowAndExceptionalFinallyCleanup() {
        var rethrow = lower(
                AsmFixtureBuilder.classWithCatchAllFinallyShape("pkg/FinallyShape"),
                "cleanup").irMethod().orElseThrow();
        assertFalse(rethrow.blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.THROW));

        var cleanup = lower(
                AsmFixtureBuilder.classWithFinallyCleanupShape("pkg/FinallyCleanup"),
                "withCleanup").irMethod().orElseThrow();
        long cleanupCalls = cleanup.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC)
                .filter(instruction -> instruction.symbol().orElse("").equals("pkg/FinallyCleanup#cleanupMarker!()V"))
                .count();
        assertEquals(1, cleanupCalls);
        assertFalse(cleanup.blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.THROW));
    }

    @Test
    void lowersMultiExitFinallyShapeWithValidExceptionState() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedMultiExitFinallyShape("pkg/FinallyShape"),
                "badFinally");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status());
        var method = result.artifact().orElseThrow().irMethod().orElseThrow();
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void lowersMonitorFinallyInteractionWithValidExceptionState() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedMonitorFinallyInteraction("pkg/MonitorFinallyShape"),
                "badMonitorFinally");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status());
        var method = result.artifact().orElseThrow().irMethod().orElseThrow();
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
        assertTrue(hasOpcode(method, IrOpcode.MONITOR_EXIT));
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void lowersNestedFinallyWithValidExceptionState() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedNestedFinallyShape("pkg/NestedFinallyShape"),
                "badNestedFinally");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status());
        var method = result.artifact().orElseThrow().irMethod().orElseThrow();
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void lowersMonitorEnterExitToRuntimeHelpers() {
        var method = lower(
                AsmFixtureBuilder.classWithMonitorBlockMethod("pkg/Locks"),
                "locked").irMethod().orElseThrow();

        assertTrue(method.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_ENTER));
        assertTrue(method.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_EXIT));
        assertTrue(method.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_HAPPENS_BEFORE));
        assertTrue(hasExceptionSite(method, IrExceptionSiteKind.NULL_CHECK));
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_enter(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains("fence acquire"));
        assertTrue(llvm.contains("fence release"));
    }

    @Test
    void lowersSynchronizedInstanceMethodWithThisLockAndCleanupHandler() {
        var method = lower(
                AsmFixtureBuilder.classWithSynchronizedInstanceMethod("pkg/SyncInstance"),
                "syncInstance").irMethod().orElseThrow();

        IrValue thisValue = method.parameters().get(0);
        assertTrue(method.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_ENTER
                        && instruction.operands().equals(java.util.List.of(thisValue))));
        assertTrue(method.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_EXIT
                        && instruction.operands().equals(java.util.List.of(thisValue))));
        assertTrue(method.blocks().stream().anyMatch(block -> block.isExceptionHandler()
                && hasOpcode(block, IrOpcode.MONITOR_EXIT_ON_EXCEPTION)
                && block.terminator().kind() == IrTerminatorKind.THROW));
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_enter(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit_on_exception(ptr %j2ll_env, ptr %p0)"));
    }

    @Test
    void lowersSynchronizedStaticMethodWithClassObjectLock() {
        var method = lower(
                AsmFixtureBuilder.classWithSynchronizedMethod("pkg/SyncMethod"),
                "sync").irMethod().orElseThrow();

        var entryInstructions = method.blocks().get(0).instructions();
        assertEquals(IrOpcode.CONST_LONG, entryInstructions.get(0).opcode());
        assertEquals(IrOpcode.CLASS_OBJECT, entryInstructions.get(1).opcode());
        assertEquals("class:Lpkg/SyncMethod;", entryInstructions.get(1).symbol().orElseThrow());
        IrValue classObject = entryInstructions.get(1).result().orElseThrow();
        assertTrue(entryInstructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_ENTER
                && instruction.operands().equals(java.util.List.of(classObject))));
        assertTrue(entryInstructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_EXIT
                && instruction.operands().equals(java.util.List.of(classObject))));
        assertTrue(llvm(method).contains(
                "call ptr @" + classObjectHelper(method) + "(ptr %j2ll_env)"));
        assertTrue(llvm(method).contains("call void @j2ll_rt_monitor_enter(ptr %j2ll_env, ptr "));
    }

    @Test
    void lowersSynchronizedMethodExplicitThrowWithExceptionalUnlock() {
        var method = lower(
                AsmFixtureBuilder.classWithSynchronizedThrowMethod("pkg/SyncThrow"),
                "syncThrow").irMethod().orElseThrow();

        IrBlock throwing = method.blocks().stream()
                .filter(block -> block.terminator().kind() == IrTerminatorKind.THROW)
                .filter(block -> !block.isExceptionHandler())
                .findFirst()
                .orElseThrow();
        assertFalse(hasOpcode(throwing, IrOpcode.MONITOR_EXIT_ON_EXCEPTION));
        assertEquals(List.of("<any>"), throwing.exceptionEdges().stream()
                .map(IrExceptionEdge::catchType)
                .toList());
        IrBlock cleanup = method.blocks().stream()
                .filter(block -> block.name().equals(throwing.exceptionEdges().get(0).target()))
                .findFirst()
                .orElseThrow();
        assertTrue(hasOpcode(cleanup, IrOpcode.MONITOR_EXIT_ON_EXCEPTION));
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit_on_exception(ptr %j2ll_env, ptr %p0)"));
    }

    @Test
    void explicitThrowTriesUserHandlerBeforeSynchronizedCleanup() {
        IrMethod method = lower(
                ExceptionFlowAsmFixtures.classWithSynchronizedCaughtExplicitThrow(
                        "pkg/SyncCaughtThrow"),
                "caughtThrow").irMethod().orElseThrow();

        IrBlock throwing = method.blocks().stream()
                .filter(block -> block.terminator().kind() == IrTerminatorKind.THROW)
                .filter(block -> !block.isExceptionHandler())
                .findFirst()
                .orElseThrow();
        assertFalse(hasOpcode(throwing, IrOpcode.MONITOR_EXIT_ON_EXCEPTION));
        assertEquals(
                List.of("java/lang/RuntimeException", "<any>"),
                throwing.exceptionEdges().stream()
                        .map(IrExceptionEdge::catchType)
                        .toList());
        IrBlock userHandler = method.blocks().stream()
                .filter(block -> block.name().equals(throwing.exceptionEdges().get(0).target()))
                .findFirst()
                .orElseThrow();
        IrBlock cleanup = method.blocks().stream()
                .filter(block -> block.name().equals(throwing.exceptionEdges().get(1).target()))
                .findFirst()
                .orElseThrow();
        assertTrue(userHandler.exceptionCatchTypes().contains("java/lang/RuntimeException"));
        assertFalse(hasOpcode(userHandler, IrOpcode.MONITOR_EXIT_ON_EXCEPTION));
        assertTrue(hasOpcode(userHandler, IrOpcode.MONITOR_EXIT));
        assertTrue(hasOpcode(cleanup, IrOpcode.MONITOR_EXIT_ON_EXCEPTION));
    }

    @Test
    void prunesUnreachableSynchronizedBlockExceptionalUnlockHandler() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithSynchronizedExceptionalUnlockShape("pkg/SyncExceptional"),
                "lockedExceptional");

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.status());
        var method = result.irMethod().orElseThrow();
        assertFalse(hasOpcode(method, IrOpcode.MONITOR_EXIT_ON_EXCEPTION));
        assertEquals(1, countOpcode(method, IrOpcode.MONITOR_EXIT));
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit(ptr %j2ll_env, ptr %p0)"));
        assertFalse(llvm.contains("call void @j2ll_rt_rethrow(ptr %j2ll_env, "));
    }

    @Test
    void lowersNestedMonitorBlockSmoke() {
        var method = lower(
                AsmFixtureBuilder.classWithNestedMonitorBlockMethod("pkg/NestedLocks"),
                "lockedNested").irMethod().orElseThrow();

        assertEquals(2, countOpcode(method, IrOpcode.MONITOR_ENTER));
        assertEquals(2, countOpcode(method, IrOpcode.MONITOR_EXIT));
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_enter(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_enter(ptr %j2ll_env, ptr %p1)"));
    }

    @Test
    void lowersVolatileFieldAccessWithJmmBarriers() {
        byte[] classBytes = AsmFixtureBuilder.classWithVolatileFieldMethods("pkg/VolatileFields");

        var read = lower(classBytes, "read").irMethod().orElseThrow();
        assertTrue(hasOpcode(read, IrOpcode.VOLATILE_READ_BARRIER));
        assertTrue(llvm(read).contains("fence acquire"));

        var write = lower(classBytes, "write").irMethod().orElseThrow();
        assertTrue(hasOpcode(write, IrOpcode.VOLATILE_WRITE_BARRIER));
        assertTrue(llvm(write).contains("fence release"));
    }

    @Test
    void marksFinalFieldPublicationInConstructor() {
        var method = lower(
                AsmFixtureBuilder.classWithFinalFieldConstructor("pkg/FinalFields"),
                "<init>").irMethod().orElseThrow();

        assertTrue(hasOpcode(method, IrOpcode.FINAL_FIELD_PUBLICATION));
        assertTrue(llvm(method).contains("fence release"));
    }

    @Test
    void threadStartJoinIsSkippedWithoutPartialIr() {
        var result = lower(
                AsmFixtureBuilder.classWithThreadStartJoinMethod("pkg/Threads"),
                "runThread");

        assertEquals(LoweringStatus.SKIPPED, result.status());
        assertTrue(result.irMethod().isEmpty());
        assertEquals(DiagnosticCode.JVM_HELPER_UNSUPPORTED.value(), result.reasonCode());
    }

    @Test
    void waitNotifyIsSkippedAtJvmHelperBoundary() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithWaitNotifyMethod("pkg/WaitNotify"),
                "waitNotify");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.SKIPPED, result.artifact().orElseThrow().status());
        assertTrue(result.artifact().orElseThrow().irMethod().isEmpty());
        assertEquals(DiagnosticCode.JVM_HELPER_UNSUPPORTED, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("WAIT_NOTIFY_UNSUPPORTED"));
    }

    @Test
    void lowersJdkStringMethodsToRuntimeHelpers() {
        var method = lower(
                AsmFixtureBuilder.classWithJdkStringMethods("pkg/JdkStrings"),
                "stringOps").irMethod().orElseThrow();

        assertEquals(4, countOpcode(method, IrOpcode.CALL_RUNTIME_HELPER));
        String llvm = llvm(method);
        assertTrue(llvm.contains("call i32 @j2ll_rt_string_length(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_string_is_empty(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_string_char_at(ptr %j2ll_env, ptr %p0"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_string_equals(ptr %j2ll_env, ptr %p0, ptr %p1)"));
    }

    @Test
    void lowersJdkStringBuilderMethodsToRuntimeHelpers() {
        var method = lower(
                AsmFixtureBuilder.classWithJdkStringBuilderMethods("pkg/JdkStringBuilder"),
                "build").irMethod().orElseThrow();

        assertTrue(hasOpcode(method, IrOpcode.NEW_OBJECT));
        assertTrue(hasOpcode(method, IrOpcode.CALL_RUNTIME_HELPER));
        String llvm = llvm(method);
        assertTrue(llvm.contains("@j2ll_rt_string_builder_init("));
        assertTrue(llvm.contains("@j2ll_rt_string_builder_append_ref("));
        assertTrue(llvm.contains("@j2ll_rt_string_builder_append_i32("));
        assertTrue(llvm.contains("@j2ll_rt_string_builder_to_string("));
    }

    @Test
    void lowersSystemArraycopyToRuntimeHelper() {
        var method = lower(
                AsmFixtureBuilder.classWithJdkSystemArraycopy("pkg/JdkArraycopy"),
                "copy").irMethod().orElseThrow();

        assertTrue(hasOpcode(method, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(hasOpcode(method, IrOpcode.CALL_RUNTIME_HELPER));
        assertTrue(llvm(method).contains("call void @j2ll_rt_system_arraycopy(ptr %j2ll_env, ptr %p0, i32 "));
    }

    @Test
    void lowersMathAndBoxingJdkMethodsToRuntimeHelpers() {
        byte[] classBytes = AsmFixtureBuilder.classWithJdkMathAndBoxing("pkg/JdkMathBoxing");

        var math = lower(classBytes, "math").irMethod().orElseThrow();
        assertTrue(llvm(math).contains("@j2ll_rt_math_abs_i32("));
        assertTrue(llvm(math).contains("@j2ll_rt_math_max_i32("));

        var box = lower(classBytes, "boxInt").irMethod().orElseThrow();
        assertTrue(llvm(box).contains("@j2ll_rt_integer_value_of("));

        var unbox = lower(classBytes, "unboxLong").irMethod().orElseThrow();
        assertTrue(llvm(unbox).contains("@j2ll_rt_long_long_value("));
    }

    @Test
    void unsupportedJdkCallIsSkippedWithoutPartialIr() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedJdkStringCall("pkg/JdkUnsupported"),
                "substring");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.SKIPPED, result.artifact().orElseThrow().status());
        assertEquals(DiagnosticCode.JVM_HELPER_UNSUPPORTED, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("java/lang/String#substring"));
        assertTrue(result.artifact().orElseThrow().irMethod().isEmpty());
    }

    @Test
    void lowersStringConcatMakeConcatToStringBuilderHelpers() {
        var method = lower(
                AsmFixtureBuilder.classWithStringConcatMakeConcat("pkg/StringConcat"),
                "concat").irMethod().orElseThrow();

        assertFalse(hasOpcode(method, IrOpcode.CALL_DYNAMIC));
        assertEquals(4, countOpcode(method, IrOpcode.CALL_RUNTIME_HELPER));
        String llvm = llvm(method);
        assertTrue(llvm.contains("@j2ll_rt_string_builder_new(ptr %j2ll_env)"));
        assertTrue(llvm.contains("@j2ll_rt_string_builder_append_ref("));
        assertTrue(llvm.contains("@j2ll_rt_string_builder_to_string("));
    }

    @Test
    void lowersStringConcatWithConstantsRecipeAndPrimitiveOperand() {
        var method = lower(
                AsmFixtureBuilder.classWithStringConcatWithConstants("pkg/StringConcatRecipe"),
                "concatRecipe").irMethod().orElseThrow();

        assertFalse(hasOpcode(method, IrOpcode.CONST_STRING));
        assertTrue(hasHelper(method, "j2ll_rt_string_constant"));
        String llvm = llvm(method);
        String localizedHelper = method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> xyz.melodysky.ir.model.BusinessStringConstantRef
                        .fromInstruction(instruction)
                        .stream())
                .map(xyz.melodysky.ir.model.BusinessStringConstantRef::helperSymbol)
                .findFirst()
                .orElseThrow();
        assertTrue(llvm.contains("@" + localizedHelper + "(ptr %j2ll_env)"));
        assertFalse(llvm.contains("@j2ll_rt_string_constant(ptr %j2ll_env, i64"));
        assertTrue(llvm.contains("@j2ll_rt_string_builder_append_ref("));
        assertTrue(llvm.contains("@j2ll_rt_string_builder_append_i32("));
        assertTrue(llvm.contains("@j2ll_rt_string_builder_to_string("));
    }

    @Test
    void lowersObjectStringConcatThroughReferenceAppendForNullSemantics() {
        var method = lower(
                AsmFixtureBuilder.classWithObjectStringConcat("pkg/StringConcatObject"),
                "concatObject").irMethod().orElseThrow();

        assertTrue(llvm(method).contains("@j2ll_rt_string_builder_append_ref("));
        assertFalse(hasOpcode(method, IrOpcode.CALL_DYNAMIC));
    }

    @Test
    void unsupportedStringConcatRecipeIsSkippedWithoutPartialIr() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedStringConcatRecipe("pkg/StringConcatUnsupported"),
                "concatUnsupported");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.SKIPPED, result.artifact().orElseThrow().status());
        assertEquals(DiagnosticCode.JVM_HELPER_UNSUPPORTED, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("unsupported recipe constant"));
        assertTrue(result.artifact().orElseThrow().irMethod().isEmpty());
    }

    @Test
    void lowersCommonLambdaMetafactoryShapesToRuntimeHelper() {
        byte[] classBytes = AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/LambdaShapes");

        var nonCapturing = lower(classBytes, "nonCapturing").irMethod().orElseThrow();
        assertTrue(llvm(nonCapturing).matches(
                "(?s).*call ptr @j2ll_h_[0-9a-f]{16}\\(ptr %j2ll_env, ptr .*"));
        assertFalse(hasOpcode(nonCapturing, IrOpcode.CALL_DYNAMIC));

        var capturing = lower(classBytes, "capturing").irMethod().orElseThrow();
        assertTrue(llvm(capturing).matches(
                "(?s).*call ptr @j2ll_h_[0-9a-f]{16}\\(ptr %j2ll_env, ptr %p0\\).*"));
        assertTrue(llvm(capturing).contains("ptr %p0"));

        var staticReference = lower(classBytes, "staticReference").irMethod().orElseThrow();
        assertTrue(llvm(staticReference).matches(
                "(?s).*@j2ll_h_[0-9a-f]{16}.*"));

        var instanceReference = lower(classBytes, "instanceReference").irMethod().orElseThrow();
        assertTrue(llvm(instanceReference).contains("ptr %p0"));

        var constructorReference = lower(classBytes, "constructorReference").irMethod().orElseThrow();
        assertTrue(llvm(constructorReference).matches(
                "(?s).*@j2ll_h_[0-9a-f]{16}.*"));
    }

    @Test
    void unsupportedLambdaMetafactoryShapeIsSkippedWithoutPartialIr() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/LambdaUnsupported"),
                "alt");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.SKIPPED, result.artifact().orElseThrow().status());
        assertEquals(DiagnosticCode.JVM_HELPER_UNSUPPORTED, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("altMetafactory"));
        assertTrue(result.artifact().orElseThrow().irMethod().isEmpty());
    }

    @Test
    void skipsClassInitGuardForSelfStaticFieldRead() {
        var method = lower(
                AsmFixtureBuilder.classWithStaticFieldRead("pkg/ClassInitRead"),
                "getValue").irMethod().orElseThrow();

        assertFalse(hasOpcode(method, IrOpcode.CLASS_OBJECT));
        assertFalse(hasOpcode(method, IrOpcode.CLASS_INIT_GUARD));
        assertFalse(hasOpcode(method, IrOpcode.CLASS_INIT_HAPPENS_BEFORE));
        assertTrue(hasOpcode(method, IrOpcode.GET_STATIC));
    }

    @Test
    void insertsClassInitGuardForCrossOwnerStaticFieldRead() {
        var method = lower(
                AsmFixtureBuilder.classWithExternalStaticFieldRead("pkg/ClassInitRead", "pkg/OtherFields"),
                "getValue").irMethod().orElseThrow();

        assertTrue(hasOpcode(method, IrOpcode.CLASS_OBJECT));
        assertTrue(hasOpcode(method, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(hasOpcode(method, IrOpcode.CLASS_INIT_HAPPENS_BEFORE));
        assertTrue(method.blocks().get(0).instructions().stream()
                .filter(instruction -> instruction.opcode() == IrOpcode.CLASS_INIT_GUARD)
                .allMatch(instruction -> instruction.symbol().orElseThrow().contains("superBeforeSubclass")));
        String llvm = llvm(method);
        assertTrue(llvm.contains(
                "call ptr @" + classObjectHelper(method) + "(ptr %j2ll_env)"));
        assertTrue(llvm.contains("call void @j2ll_rt_class_init_guard(ptr %j2ll_env, ptr "));
        assertTrue(llvm.contains("fence acquire"));
    }

    @Test
    void skipsClassInitGuardForSelfStaticFieldWrite() {
        var method = lower(
                AsmFixtureBuilder.classWithStaticFieldWrite("pkg/ClassInitWrite"),
                "setValue").irMethod().orElseThrow();

        assertFalse(hasOpcode(method, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(hasOpcode(method, IrOpcode.PUT_STATIC));
    }

    @Test
    void insertsClassInitGuardForCrossOwnerStaticFieldWrite() {
        var method = lower(
                AsmFixtureBuilder.classWithExternalStaticFieldWrite("pkg/ClassInitWrite", "pkg/OtherFields"),
                "setValue").irMethod().orElseThrow();

        assertTrue(hasOpcode(method, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(hasOpcode(method, IrOpcode.PUT_STATIC));
        assertTrue(llvm(method).contains("call void @j2ll_rt_class_init_guard(ptr %j2ll_env, ptr "));
    }

    @Test
    void skipsClassInitGuardForSelfStaticInvokeAndGuardsNew() {
        var call = lower(
                AsmFixtureBuilder.classWithStaticCall("pkg/ClassInitCall"),
                "call").irMethod().orElseThrow();
        assertFalse(hasOpcode(call, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(hasOpcode(call, IrOpcode.CALL_STATIC));

        var allocation = lower(
                AsmFixtureBuilder.classWithAllocation("pkg/ClassInitAlloc", "pkg/Allocated"),
                "make").irMethod().orElseThrow();
        assertTrue(hasOpcode(allocation, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(hasOpcode(allocation, IrOpcode.NEW_OBJECT));
    }

    @Test
    void insertsClassInitGuardForCrossOwnerStaticInvoke() {
        var call = lower(
                AsmFixtureBuilder.classWithExternalStaticCall("pkg/ClassInitCall", "pkg/OtherCalls"),
                "call").irMethod().orElseThrow();

        assertTrue(hasOpcode(call, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(hasOpcode(call, IrOpcode.CALL_STATIC));
        assertTrue(llvm(call).contains("call void @j2ll_rt_class_init_guard(ptr %j2ll_env, ptr "));
    }

    @Test
    void classInitializerUsesBeginEndAndDoesNotRecursivelyGuardSelf() {
        var method = lower(
                AsmFixtureBuilder.classWithSelfStaticWriteInClassInitializer("pkg/ClassInitSelf"),
                "<clinit>").irMethod().orElseThrow();

        assertTrue(hasOpcode(method, IrOpcode.CLASS_INIT_BEGIN));
        assertTrue(hasOpcode(method, IrOpcode.CLASS_INIT_END));
        assertTrue(hasOpcode(method, IrOpcode.CLASS_INIT_HAPPENS_BEFORE));
        assertFalse(hasOpcode(method, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(llvm(method).contains("call void @j2ll_rt_class_init_begin(ptr %j2ll_env, ptr "));
        assertTrue(llvm(method).contains("call void @j2ll_rt_class_init_end(ptr %j2ll_env, ptr "));
        assertTrue(llvm(method).contains("fence release"));
    }

    @Test
    void unsupportedClassInitializerFailurePathIsSkippedWithoutPartialIr() {
        var result = lower(
                AsmFixtureBuilder.classWithThrowingClassInitializer("pkg/ClassInitFail"),
                "<clinit>");

        assertEquals(LoweringStatus.SKIPPED, result.status());
        assertTrue(result.irMethod().isEmpty());
        assertEquals(DiagnosticCode.JVM_HELPER_UNSUPPORTED.value(), result.reasonCode());
    }

    @Test
    void lowersNullReferenceReturn() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithNullReturnMethod("pkg/Refs"),
                "nil");

        var method = result.irMethod().orElseThrow();
        assertEquals(IrType.REFERENCE, method.returnType());
        assertEquals(IrOpcode.CONST_NULL, method.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(method).contains("inttoptr i64 0 to ptr"));
    }

    @Test
    void lowersSymbolicLdcConstants() {
        byte[] classBytes = AsmFixtureBuilder.classWithSymbolicLdcMethods("pkg/SymbolicConstants");

        var stringConst = lower(classBytes, "stringConst").irMethod().orElseThrow();
        var stringInstruction = stringConst.blocks().get(0).instructions().get(0);
        assertEquals(IrOpcode.CONST_STRING, stringInstruction.opcode());
        assertEquals("string:secret-value", stringInstruction.symbol().orElseThrow());
        String localizedHelper = xyz.melodysky.ir.model.BusinessStringConstantRef
                .fromInstruction(stringInstruction)
                .orElseThrow()
                .helperSymbol();
        assertTrue(llvm(stringConst).contains(
                "call ptr @" + localizedHelper + "(ptr %j2ll_env)"));
        assertFalse(llvm(stringConst).contains(
                "@j2ll_rt_string_constant(ptr %j2ll_env, i64"));
        assertFalse(llvm(stringConst).contains("secret-value"));

        var classConst = lower(classBytes, "classConst").irMethod().orElseThrow();
        var classInstruction = classConst.blocks().get(0).instructions().get(0);
        assertEquals(IrOpcode.CONST_CLASS, classInstruction.opcode());
        assertEquals("class:Ljava/lang/String;", classInstruction.symbol().orElseThrow());
        assertTrue(llvm(classConst).contains(
                "call ptr @" + classObjectHelper(classConst) + "(ptr %j2ll_env)"));

        var methodTypeConst = lower(classBytes, "methodTypeConst").irMethod().orElseThrow();
        var methodTypeInstruction = methodTypeConst.blocks().get(0).instructions().get(0);
        assertEquals(IrOpcode.CONST_METHOD_TYPE, methodTypeInstruction.opcode());
        assertEquals("methodType:(I)Ljava/lang/String;", methodTypeInstruction.symbol().orElseThrow());
        assertTrue(llvm(methodTypeConst).contains("call ptr @j2ll_const_method_type_"));

        var methodHandleConst = lower(classBytes, "methodHandleConst").irMethod().orElseThrow();
        var methodHandleInstruction = methodHandleConst.blocks().get(0).instructions().get(0);
        assertEquals(IrOpcode.CONST_METHOD_HANDLE, methodHandleInstruction.opcode());
        assertTrue(methodHandleInstruction.symbol().orElseThrow().contains("pkg/SymbolicConstants#target!()I"));
        assertTrue(llvm(methodHandleConst).contains("call ptr @j2ll_const_method_handle_"));
    }

    @Test
    void lowersStaticFieldReadSkeleton() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithStaticFieldRead("pkg/Fields"),
                "getValue");

        var method = result.irMethod().orElseThrow();
        assertFalse(hasOpcode(method, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(method.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.GET_STATIC
                        && instruction.symbol().orElseThrow().contains("VALUE")));
        String identity = localizedIdentity(method, IrOpcode.GET_STATIC);
        assertTrue(llvm(method).contains(
                "@" + localizedHelper(
                        method,
                        IrOpcode.GET_STATIC,
                        RuntimeTokenDomain.FIELD_RUNTIME,
                        "field_get_static_i32")
                        + "("
                        + localAbiCall(
                                RuntimeLocalAbiDomain.FIELD,
                                "field_get_static_i32",
                                identity,
                                List.of(
                                        "ptr %j2ll_env",
                                        "ptr %j2ll_owner"))
                        + ")"));
    }

    @Test
    void lowersInstanceFieldReadSkeleton() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithInstanceFieldRead("pkg/Fields"),
                "read");

        var method = result.irMethod().orElseThrow();
        assertEquals(IrOpcode.GET_FIELD, method.blocks().get(0).instructions().get(0).opcode());
        String identity = localizedIdentity(method, IrOpcode.GET_FIELD);
        assertTrue(llvm(method).contains(
                "@" + localizedHelper(
                        method,
                        IrOpcode.GET_FIELD,
                        RuntimeTokenDomain.FIELD_RUNTIME,
                        "field_get_instance_i32")
                        + "("
                        + localAbiCall(
                                RuntimeLocalAbiDomain.FIELD,
                                "field_get_instance_i32",
                                identity,
                                List.of(
                                        "ptr %j2ll_env",
                                        "ptr %p0"))
                        + ")"));
    }

    @Test
    void lowersStaticCallSkeleton() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithStaticCall("pkg/Calls"),
                "call");

        var method = result.irMethod().orElseThrow();
        assertFalse(hasOpcode(method, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(hasOpcode(method, IrOpcode.CALL_STATIC));
        assertTrue(llvm(method).contains("@j2ll_call_pkg_Calls_value"));
    }

    @Test
    void lowersSpecialCallSkeleton() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithSpecialCall("pkg/Special"),
                "callPrivate");

        var method = result.irMethod().orElseThrow();
        assertEquals(IrOpcode.CALL_SPECIAL, method.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(method).contains("@j2ll_call_pkg_Special_value"));
    }

    @Test
    void lowersObjectAndReferenceArrayAllocationSkeletons() {
        var allocation = lower(
                AsmFixtureBuilder.classWithAllocation("pkg/Alloc", "pkg/Thing"),
                "make").irMethod().orElseThrow();

        assertTrue(allocation.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.NEW_OBJECT));
        assertTrue(allocation.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.CALL_SPECIAL));
        assertTrue(llvm(allocation).contains(
                "call ptr @" + localizedHelper(
                        allocation,
                        IrOpcode.NEW_OBJECT,
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        "alloc_object")
                        + "(ptr %j2ll_env)"));
        String constructorIdentity = localizedIdentity(
                allocation,
                IrOpcode.CALL_SPECIAL);
        String constructorHelper = localizedHelper(
                allocation,
                IrOpcode.CALL_SPECIAL,
                RuntimeTokenDomain.DISPATCH_METHOD,
                "constructor_call");
        assertLocalAbiInvocation(
                llvm(allocation),
                "call void @" + constructorHelper + "(",
                RuntimeLocalAbiDomain.DISPATCH,
                "constructor_call",
                constructorIdentity,
                List.of(
                        "ptr %j2ll_env",
                        "ptr ",
                        "ptr "));

        var refArray = lower(
                AsmFixtureBuilder.classWithReferenceArrayAllocation("pkg/RefArrays", "java/lang/String"),
                "array").irMethod().orElseThrow();
        assertEquals(IrOpcode.NEW_ARRAY, refArray.blocks().get(0).instructions().get(1).opcode());
        assertTrue(llvm(refArray).contains(
                "call ptr @" + localizedHelper(
                        refArray,
                        IrOpcode.NEW_ARRAY,
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        "new_object_array")
                        + "(ptr %j2ll_env, i32 "));
    }

    @Test
    void javaVisibleAllocationUsesJvmHelperBackedBackendOnly() {
        var objectAllocation = lower(
                AsmFixtureBuilder.classWithAllocation("pkg/AllocGuard", "pkg/Thing"),
                "make").irMethod().orElseThrow();
        var referenceArray = lower(
                AsmFixtureBuilder.classWithReferenceArrayAllocation("pkg/ArrayGuard", "java/lang/String"),
                "array").irMethod().orElseThrow();
        byte[] arrayFixture = AsmFixtureBuilder.classWithArrayOperationMethods("pkg/ArrayOpsGuard");
        var primitiveArray = lower(arrayFixture, "makeInts").irMethod().orElseThrow();
        var multiArray = lower(arrayFixture, "multi").irMethod().orElseThrow();
        var unsafeAllocate = lower(
                AsmFixtureBuilder.classWithUnsafeMethods("pkg/UnsafeAllocGuard"),
                "allocate").irMethod().orElseThrow();
        var lambdaAllocation = lower(
                AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/LambdaAllocGuard"),
                "nonCapturing").irMethod().orElseThrow();
        var stringBuilderAllocation = lower(
                AsmFixtureBuilder.classWithStringConcatMakeConcat("pkg/StringBuilderAllocGuard"),
                "concat").irMethod().orElseThrow();

        assertTrue(llvm(objectAllocation).contains(
                "call ptr @" + localizedHelper(
                        objectAllocation,
                        IrOpcode.NEW_OBJECT,
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        "alloc_object")
                        + "(ptr %j2ll_env)"));
        assertTrue(llvm(referenceArray).contains(
                "call ptr @" + localizedHelper(
                        referenceArray,
                        IrOpcode.NEW_ARRAY,
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        "new_object_array")
                        + "(ptr %j2ll_env, i32 "));
        assertTrue(llvm(primitiveArray).contains("call ptr @j2ll_rt_new_int_array(ptr %j2ll_env, i32 "));
        assertTrue(llvm(multiArray).contains("call ptr @j2ll_rt_new_multi_array_"));
        assertTrue(hasHelper(unsafeAllocate, "j2ll_rt_unsafe_allocate_instance"));
        assertTrue(hasHelper(lambdaAllocation, "j2ll_rt_lambda_new"));
        assertTrue(hasHelper(stringBuilderAllocation, "j2ll_rt_string_builder_new"));
        assertNoNativeHeapJavaAllocation(objectAllocation);
        assertNoNativeHeapJavaAllocation(referenceArray);
        assertNoNativeHeapJavaAllocation(primitiveArray);
        assertNoNativeHeapJavaAllocation(multiArray);
        assertNoNativeHeapJavaAllocation(unsafeAllocate);
        assertNoNativeHeapJavaAllocation(lambdaAllocation);
        assertNoNativeHeapJavaAllocation(stringBuilderAllocation);
    }

    @Test
    void lowersArrayOperationSkeletons() {
        byte[] classBytes = AsmFixtureBuilder.classWithArrayOperationMethods("pkg/Arrays");

        var makeInts = lower(classBytes, "makeInts").irMethod().orElseThrow();
        assertEquals(IrOpcode.NEW_ARRAY, makeInts.blocks().get(0).instructions().get(0).opcode());
        assertTrue(makeInts.blocks().get(0).instructions().get(0).symbol().orElseThrow().contains("primitiveArray:int"));

        var firstPlusLength = lower(classBytes, "firstPlusLength").irMethod().orElseThrow();
        assertEquals(IrOpcode.ARRAY_LENGTH, firstPlusLength.blocks().get(0).instructions().get(0).opcode());
        assertEquals(IrOpcode.ARRAY_LOAD_I32, firstPlusLength.blocks().get(0).instructions().get(2).opcode());
        assertTrue(llvm(firstPlusLength).contains("call i32 @j2ll_rt_array_length_i32(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm(firstPlusLength).contains("call i32 @j2ll_rt_array_load_i32(ptr %j2ll_env, ptr %p0"));

        var putRef = lower(classBytes, "putRef").irMethod().orElseThrow();
        assertEquals(IrOpcode.ARRAY_STORE_REF, putRef.blocks().get(0).instructions().get(1).opcode());
        assertTrue(llvm(putRef).contains("call void @j2ll_rt_array_store_ref(ptr %j2ll_env"));

        var multi = lower(classBytes, "multi").irMethod().orElseThrow();
        assertEquals(IrOpcode.NEW_MULTI_ARRAY, multi.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(multi).contains("call ptr @j2ll_rt_new_multi_array_"));
    }

    @Test
    void lowersTypeOperationSkeletons() {
        byte[] classBytes = AsmFixtureBuilder.classWithTypeOperationMethods("pkg/TypeOps");

        var cast = lower(classBytes, "castString").irMethod().orElseThrow();
        assertEquals(IrOpcode.CHECKCAST, cast.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(cast).contains(
                "call ptr @" + localizedHelper(
                        cast,
                        IrOpcode.CHECKCAST,
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        "checkcast")
                        + "(ptr %j2ll_env"));

        var instanceOf = lower(classBytes, "isString").irMethod().orElseThrow();
        assertEquals(IrOpcode.INSTANCEOF, instanceOf.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(instanceOf).contains(
                "call i32 @" + localizedHelper(
                        instanceOf,
                        IrOpcode.INSTANCEOF,
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        "instanceof")
                        + "(ptr %j2ll_env"));
    }

    @Test
    void lowersVirtualInterfaceAndDynamicCallSkeletons() {
        var virtualCall = lower(
                AsmFixtureBuilder.classWithVirtualCall("pkg/VirtualCalls", "pkg/RunnableThing"),
                "call").irMethod().orElseThrow();
        assertEquals(IrOpcode.CALL_VIRTUAL, virtualCall.blocks().get(0).instructions().get(0).opcode());
        String virtualIdentity = localizedIdentity(
                virtualCall,
                IrOpcode.CALL_VIRTUAL);
        String virtualHelper = localizedHelper(
                virtualCall,
                IrOpcode.CALL_VIRTUAL,
                RuntimeTokenDomain.DISPATCH_METHOD,
                "virtual_dispatch_void");
        assertLocalAbiInvocation(
                llvm(virtualCall),
                "call void @" + virtualHelper + "(",
                RuntimeLocalAbiDomain.DISPATCH,
                "virtual_dispatch_void",
                virtualIdentity,
                List.of(
                        "ptr %j2ll_env",
                        "ptr ",
                        "ptr "));

        var interfaceCall = lower(
                AsmFixtureBuilder.classWithInterfaceCall("pkg/InterfaceCalls", "pkg/Task"),
                "call").irMethod().orElseThrow();
        assertEquals(IrOpcode.CALL_INTERFACE, interfaceCall.blocks().get(0).instructions().get(0).opcode());
        String interfaceIdentity = localizedIdentity(
                interfaceCall,
                IrOpcode.CALL_INTERFACE);
        String interfaceHelper = localizedHelper(
                interfaceCall,
                IrOpcode.CALL_INTERFACE,
                RuntimeTokenDomain.DISPATCH_METHOD,
                "interface_dispatch_void");
        assertLocalAbiInvocation(
                llvm(interfaceCall),
                "call void @" + interfaceHelper + "(",
                RuntimeLocalAbiDomain.DISPATCH,
                "interface_dispatch_void",
                interfaceIdentity,
                List.of(
                        "ptr %j2ll_env",
                        "ptr ",
                        "ptr "));

        var dynamicCall = lower(
                AsmFixtureBuilder.classWithInvokeDynamic("pkg/DynamicCalls"),
                "dynamic").irMethod().orElseThrow();
        assertEquals(IrOpcode.CALL_DYNAMIC, dynamicCall.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(dynamicCall).contains("@j2ll_call_dynamic_"));
    }

    @Test
    void lowersStaticReflectionCallsToRuntimeMetadataHelpers() {
        byte[] classBytes = AsmFixtureBuilder.classWithStaticReflectionMethods(
                "pkg/ReflectCaller",
                "pkg/ReflectTarget");

        assertTrue(hasHelper(lower(classBytes, "forName").irMethod().orElseThrow(), "j2ll_rt_class_for_name_static"));
        assertTrue(hasHelper(lower(classBytes, "declaredMethod").irMethod().orElseThrow(), "j2ll_rt_get_declared_method"));
        assertTrue(hasHelper(lower(classBytes, "declaredField").irMethod().orElseThrow(), "j2ll_rt_get_declared_field"));
        assertTrue(hasHelper(lower(classBytes, "declaredConstructor").irMethod().orElseThrow(), "j2ll_rt_get_declared_constructor"));
        assertTrue(hasHelper(
                lower(classBytes, "primitiveDeclaredMethod").irMethod().orElseThrow(),
                "j2ll_rt_get_declared_method"));
        assertTrue(hasHelper(
                lower(classBytes, "primitiveDeclaredConstructor").irMethod().orElseThrow(),
                "j2ll_rt_get_declared_constructor"));

        var reflectiveInvoke = lower(classBytes, "reflectiveInvoke").irMethod().orElseThrow();
        assertTrue(hasHelper(reflectiveInvoke, "j2ll_rt_get_declared_method"));
        assertTrue(hasHelper(reflectiveInvoke, "j2ll_rt_reflect_invoke"));
    }

    @Test
    void reflectionPeepholePreservesProtectedPendingExceptionTransfer() {
        byte[] classBytes = AsmFixtureBuilder.classWithStaticReflectionMethods(
                "pkg/ReflectCaller",
                "pkg/ReflectTarget");

        IrMethod method = lower(classBytes, "protectedDeclaredField")
                .irMethod()
                .orElseThrow();

        var lookup = method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.symbol()
                        .orElse("")
                        .startsWith("j2ll_rt_get_declared_field|"))
                .findFirst()
                .orElseThrow();
        var site = lookup.exceptionSites().get(0);
        IrValue exception = site.exceptionValue().orElseThrow();
        assertEquals(List.of("java/lang/Exception"), site.handlers().stream()
                .map(IrExceptionEdge::catchType)
                .toList());
        assertEquals(exception, site.handlers().get(0).arguments().get(0));
        assertFalse(hasOpcode(method, IrOpcode.CONST_CLASS));
        assertFalse(hasOpcode(method, IrOpcode.CONST_STRING));
        assertFalse(new NativeExceptionFlowSupport().hasUnsupportedJvmFlow(method));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
    }

    @Test
    void dynamicReflectionStringUsesJvmBridge() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithStaticReflectionMethods("pkg/ReflectCaller", "pkg/ReflectTarget"),
                "dynamicForName");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(hasOpcode(result.artifact().orElseThrow().irMethod().orElseThrow(), IrOpcode.CALL_STATIC));
    }

    @Test
    void reflectionMemberScanUsesJvmBridge() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithStaticReflectionMethods("pkg/ReflectCaller", "pkg/ReflectTarget"),
                "declaredMethods");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(hasOpcode(result.artifact().orElseThrow().irMethod().orElseThrow(), IrOpcode.CALL_VIRTUAL));
    }

    @Test
    void lowersAltMetafactoryCommonFlagsToLambdaHelper() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithAltMetafactoryLambda("pkg/AltLambda"),
                "altCommon");

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.status());
        assertTrue(hasHelper(result.irMethod().orElseThrow(), "j2ll_rt_lambda_new"));
    }

    @Test
    void lowersMethodHandleInvokeExactDirectTargetAndDynamicReceiverBridge() {
        byte[] classBytes = AsmFixtureBuilder.classWithMethodHandleInvokeExact("pkg/Handles");

        var direct = lower(classBytes, "direct").irMethod().orElseThrow();
        assertTrue(direct.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC
                        && instruction.symbol().orElse("").equals("pkg/Handles#target!()I")));

        ParsedMethod parsedMethod = parseMethod(classBytes, "dynamic");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();
        var result = new BytecodeToSsaLowerer().lower(cfg);
        assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status());
        assertTrue(result.diagnostics().isEmpty());
        var dynamic = result.artifact().orElseThrow().irMethod().orElseThrow();
        assertTrue(hasOpcode(dynamic, IrOpcode.NEW_ARRAY));
        assertTrue(hasHelper(dynamic, "j2ll_rt_integer_int_value"));
        assertTrue(hasOpcode(dynamic, IrOpcode.CALL_VIRTUAL));
        String dispatchIdentity = localizedIdentity(
                dynamic,
                IrOpcode.CALL_VIRTUAL);
        String dispatchHelper = localizedHelper(
                dynamic,
                IrOpcode.CALL_VIRTUAL,
                RuntimeTokenDomain.DISPATCH_METHOD,
                "virtual_dispatch_ref");
        assertLocalAbiInvocation(
                llvm(dynamic),
                "call ptr @" + dispatchHelper + "(",
                RuntimeLocalAbiDomain.DISPATCH,
                "virtual_dispatch_ref",
                dispatchIdentity,
                List.of(
                        "ptr %j2ll_env",
                        "ptr ",
                        "ptr "));
    }

    @Test
    void lowersSupportedConstantDynamicAndSkipsUnsupportedBootstrap() {
        byte[] classBytes = AsmFixtureBuilder.classWithConstantDynamicMethods("pkg/Condy");

        var supported = lower(classBytes, "supported").irMethod().orElseThrow();
        assertTrue(hasHelper(supported, "j2ll_rt_constant_dynamic"));

        ParsedMethod parsedMethod = parseMethod(classBytes, "unsupported");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();
        var result = new BytecodeToSsaLowerer().lower(cfg);
        assertEquals(LoweringStatus.SKIPPED, result.artifact().orElseThrow().status());
        assertTrue(result.diagnostics().get(0).message().contains("unsupported ConstantDynamic bootstrap"));
        assertTrue(result.artifact().orElseThrow().irMethod().isEmpty());
    }

    @Test
    void lowersUnsafeSubsetToRuntimeHelpersAndPreservesJmmMarkers() {
        byte[] classBytes = AsmFixtureBuilder.classWithUnsafeMethods("pkg/UnsafeOps");

        assertTrue(hasHelper(lower(classBytes, "objectOffset").irMethod().orElseThrow(), "j2ll_rt_unsafe_object_field_offset"));
        assertTrue(hasHelper(lower(classBytes, "staticOffset").irMethod().orElseThrow(), "j2ll_rt_unsafe_static_field_offset"));
        assertTrue(hasHelper(lower(classBytes, "arrayBase").irMethod().orElseThrow(), "j2ll_rt_unsafe_array_base_offset"));
        assertTrue(hasHelper(lower(classBytes, "arrayScale").irMethod().orElseThrow(), "j2ll_rt_unsafe_array_index_scale"));
        assertTrue(hasHelper(lower(classBytes, "getInt").irMethod().orElseThrow(), "j2ll_rt_unsafe_get_int"));
        assertTrue(hasHelper(lower(classBytes, "putObject").irMethod().orElseThrow(), "j2ll_rt_unsafe_put"));
        assertTrue(hasHelper(lower(classBytes, "allocate").irMethod().orElseThrow(), "j2ll_rt_unsafe_allocate_instance"));

        var volatileGet = lower(classBytes, "getVolatile").irMethod().orElseThrow();
        assertTrue(hasHelper(volatileGet, "j2ll_rt_unsafe_get_volatile"));
        assertTrue(hasOpcode(volatileGet, IrOpcode.VOLATILE_READ_BARRIER));

        var cas = lower(classBytes, "cas").irMethod().orElseThrow();
        assertTrue(hasHelper(cas, "j2ll_rt_unsafe_compare_and_swap_int"));
        assertTrue(hasOpcode(cas, IrOpcode.VOLATILE_READ_BARRIER));
        assertTrue(hasOpcode(cas, IrOpcode.VOLATILE_WRITE_BARRIER));
    }

    @Test
    void unsupportedUnsafeApiIsSkippedWithJvmHelperDiagnostic() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsafeMethods("pkg/UnsafeOps"),
                "unsupported");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.SKIPPED, result.artifact().orElseThrow().status());
        assertEquals(DiagnosticCode.UNSAFE_RAW_MEMORY_UNSUPPORTED, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("UNSAFE_RAW_MEMORY_UNSUPPORTED"));
        assertTrue(result.artifact().orElseThrow().irMethod().isEmpty());
    }

    @Test
    void lowersVarHandleCommonShapesToUnsafeHelpers() {
        byte[] classBytes = AsmFixtureBuilder.classWithVarHandleMethods("pkg/VarHandles");

        assertTrue(hasHelper(lower(classBytes, "get").irMethod().orElseThrow(), "j2ll_rt_unsafe_get"));
        assertTrue(hasHelper(lower(classBytes, "set").irMethod().orElseThrow(), "j2ll_rt_unsafe_put"));
        var volatileGet = lower(classBytes, "getVolatile").irMethod().orElseThrow();
        assertTrue(hasHelper(volatileGet, "j2ll_rt_unsafe_get_volatile"));
        assertTrue(hasOpcode(volatileGet, IrOpcode.VOLATILE_READ_BARRIER));
        var cas = lower(classBytes, "compareAndSet").irMethod().orElseThrow();
        assertTrue(hasHelper(cas, "j2ll_rt_unsafe_compare_and_swap"));
        assertTrue(hasOpcode(cas, IrOpcode.VOLATILE_WRITE_BARRIER));
    }

    @Test
    void lowersTypedIntVarHandleShapesToJvmVarHandleHelpers() {
        byte[] classBytes = AsmFixtureBuilder.classWithTypedIntVarHandleMethods("pkg/TypedVarHandles", "pkg/Target");

        assertTrue(hasHelper(lower(classBytes, "getInt").irMethod().orElseThrow(), "j2ll_rt_var_handle_get_int"));
        assertTrue(hasHelper(lower(classBytes, "setInt").irMethod().orElseThrow(), "j2ll_rt_var_handle_set_int"));
        var volatileGet = lower(classBytes, "getVolatileInt").irMethod().orElseThrow();
        assertTrue(hasHelper(volatileGet, "j2ll_rt_var_handle_get_volatile_int"));
        assertTrue(hasOpcode(volatileGet, IrOpcode.VOLATILE_READ_BARRIER));
        var volatileSet = lower(classBytes, "setVolatileInt").irMethod().orElseThrow();
        assertTrue(hasHelper(volatileSet, "j2ll_rt_var_handle_set_volatile_int"));
        assertTrue(hasOpcode(volatileSet, IrOpcode.VOLATILE_WRITE_BARRIER));
        var cas = lower(classBytes, "compareAndSetInt").irMethod().orElseThrow();
        assertTrue(hasHelper(cas, "j2ll_rt_var_handle_compare_and_set_int"));
        assertTrue(hasOpcode(cas, IrOpcode.VOLATILE_WRITE_BARRIER));
    }

    private SsaMethodResult lower(byte[] classBytes, String methodName) {
        ParsedMethod parsedMethod = parseMethod(classBytes, methodName);
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();
        var result = new BytecodeToSsaLowerer().lower(cfg);
        assertFalse(result.hasErrors());
        SsaMethodResult artifact = result.artifact().orElseThrow();
        artifact.irMethod().ifPresent(method -> assertTrue(new IrMethodValidator().validate(method).isEmpty()));
        return artifact;
    }

    private byte[] classWithDeadPartialLocal(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "deadPartial",
                "(I)I",
                null,
                null);
        Label join = new Label();
        method.visitCode();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, join);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, 1);
        method.visitLabel(join);
        method.visitIntInsn(Opcodes.BIPUSH, 7);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] classWithDeadLocalTypeReuse(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "deadTypeReuse",
                "(I)I",
                null,
                null);
        Label integerBranch = new Label();
        Label join = new Label();
        method.visitCode();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, integerBranch);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitJumpInsn(Opcodes.GOTO, join);
        method.visitLabel(integerBranch);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, 1);
        method.visitLabel(join);
        method.visitIntInsn(Opcodes.BIPUSH, 7);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] classWithLiveUndefinedLocal(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "liveUndefined",
                "(I)I",
                null,
                null);
        Label undefinedBranch = new Label();
        Label join = new Label();
        method.visitCode();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, undefinedBranch);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, 1);
        method.visitJumpInsn(Opcodes.GOTO, join);
        method.visitLabel(undefinedBranch);
        method.visitLabel(join);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] classWithLiveLocalTypeMismatch(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "liveTypeMismatch",
                "(I)Ljava/lang/Object;",
                null,
                null);
        Label integerBranch = new Label();
        Label join = new Label();
        method.visitCode();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, integerBranch);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitJumpInsn(Opcodes.GOTO, join);
        method.visitLabel(integerBranch);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, 1);
        method.visitLabel(join);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void assertMergeSkipped(byte[] classBytes, String methodName, DiagnosticCode expectedCode) {
        ParsedMethod parsedMethod = parseMethod(classBytes, methodName);
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.SKIPPED, result.artifact().orElseThrow().status());
        assertTrue(result.artifact().orElseThrow().irMethod().isEmpty());
        assertEquals(expectedCode, result.diagnostics().get(0).code());
    }

    private boolean hasExceptionSite(xyz.melodysky.ir.model.IrMethod method, IrExceptionSiteKind kind) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.exceptionSites().stream())
                .anyMatch(site -> site.kind() == kind);
    }

    private boolean hasOpcode(xyz.melodysky.ir.model.IrMethod method, IrOpcode opcode) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == opcode);
    }

    private boolean hasOpcode(xyz.melodysky.ir.model.IrBlock block, IrOpcode opcode) {
        return block.instructions().stream().anyMatch(instruction -> instruction.opcode() == opcode);
    }

    private long countOpcode(xyz.melodysky.ir.model.IrMethod method, IrOpcode opcode) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == opcode)
                .count();
    }

    private boolean hasHelper(xyz.melodysky.ir.model.IrMethod method, String helper) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                        && instruction.symbol().orElse("").split("\\|", 2)[0].equals(helper));
    }

    private void assertNoNativeHeapJavaAllocation(xyz.melodysky.ir.model.IrMethod method) {
        String llvm = llvm(method);
        assertFalse(llvm.contains("@malloc("), llvm);
        assertFalse(llvm.contains("@calloc("), llvm);
        assertFalse(llvm.contains(" = alloca "), llvm);
    }

    private ParsedMethod parseMethod(byte[] classBytes, String methodName) {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry("fixture.class", classBytes, "fixture"))
                .artifact()
                .orElseThrow();
        return parsedClass.methods().stream()
                .filter(method -> method.name().equals(methodName))
                .findFirst()
                .orElseThrow();
    }

    private String llvm(xyz.melodysky.ir.model.IrMethod method) {
        return new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(new IrClass(method.owner(), java.util.List.of(method))));
    }

    private String localizedHelper(
            IrMethod method,
            IrOpcode opcode,
            RuntimeTokenDomain domain,
            String operation) {
        return RuntimeTokenMapper.compatibility()
                .helperSymbol(
                        domain,
                        operation,
                        localizedIdentity(method, opcode));
    }

    private String localizedIdentity(
            IrMethod method,
            IrOpcode opcode) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == opcode)
                .map(instruction -> instruction.symbol().orElseThrow())
                .findFirst()
                .orElseThrow();
    }

    private String localAbiCall(
            RuntimeLocalAbiDomain domain,
            String operation,
            String identity,
            List<String> logicalArguments) {
        RuntimeLocalAbiPlan plan = new RuntimeLocalAbiPlanner().plan(
                RuntimeTokenMapper.compatibility(),
                domain,
                operation,
                identity,
                logicalArguments.size());
        return String.join(
                ", ",
                plan.arrange(logicalArguments));
    }

    private void assertLocalAbiInvocation(
            String llvm,
            String callPrefix,
            RuntimeLocalAbiDomain domain,
            String operation,
            String identity,
            List<String> logicalArgumentPrefixes) {
        RuntimeLocalAbiPlan plan = new RuntimeLocalAbiPlanner().plan(
                RuntimeTokenMapper.compatibility(),
                domain,
                operation,
                identity,
                logicalArgumentPrefixes.size());
        int start = llvm.indexOf(callPrefix);
        assertTrue(start >= 0, llvm);
        int argumentsStart = start + callPrefix.length();
        int end = llvm.indexOf(')', argumentsStart);
        assertTrue(end >= argumentsStart, llvm);
        List<String> actual = List.of(
                llvm.substring(argumentsStart, end).split(", "));
        assertEquals(plan.physicalSlots().size(), actual.size(), llvm);
        for (int physical = 0;
                physical < plan.physicalSlots().size();
                physical++) {
            int logical = plan.physicalSlots().get(physical);
            assertTrue(
                    actual.get(physical).startsWith(
                            logicalArgumentPrefixes.get(logical)),
                    llvm);
        }
    }

    private String classObjectHelper(IrMethod method) {
        String symbol = method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CLASS_OBJECT
                        || instruction.opcode() == IrOpcode.CONST_CLASS)
                .map(instruction -> instruction.symbol().orElseThrow())
                .findFirst()
                .orElseThrow();
        String identity = symbol.startsWith("class:")
                ? symbol.substring("class:".length())
                : symbol;
        if (!identity.startsWith("[")
                && !(identity.startsWith("L") && identity.endsWith(";"))) {
            identity = "L" + identity + ";";
        }
        return RuntimeTokenMapper.compatibility().helperSymbol(
                RuntimeTokenDomain.CLASS_OBJECT,
                "class_object",
                identity);
    }
}
