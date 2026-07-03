package xyz.melodysky.ir.ssa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class BytecodeToSsaLowererTest {
    @Test
    void lowersStraightLineIntAddToThreeAddressIr() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithAddMethod("pkg/Mathy"),
                "add");

        assertEquals(LoweringStatus.LOWERED, result.status());
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

        assertEquals(LoweringStatus.LOWERED, result.artifact().orElseThrow().status());
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

        assertEquals(LoweringStatus.LOWERED, result.artifact().orElseThrow().status());
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
    void mergeMismatchProducesSpecificFrontendSkippedDiagnostics() {
        assertMergeSkipped(
                AsmFixtureBuilder.classWithBadStackHeightMerge("pkg/BadStackHeight"),
                "badStack",
                LoweringDiagnostics.SSA_MERGE_STACK_HEIGHT_MISMATCH);
        assertMergeSkipped(
                AsmFixtureBuilder.classWithBadStackTypeMerge("pkg/BadStackType"),
                "badType",
                LoweringDiagnostics.SSA_MERGE_TYPE_MISMATCH);
        assertMergeSkipped(
                AsmFixtureBuilder.classWithBadLocalSlotMerge("pkg/BadLocal"),
                "badLocal",
                LoweringDiagnostics.SSA_MERGE_LOCAL_SLOT_MISMATCH);
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
        assertTrue(llvm(result.irMethod().orElseThrow()).contains("fadd double 0.0, 2.5"));
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
        assertEquals(LoweringStatus.LOWERED, lower(classBytes, "dupX2Long").status());
        assertEquals(LoweringStatus.LOWERED, lower(classBytes, "dup2X1Int").status());
        assertEquals(LoweringStatus.LOWERED, lower(classBytes, "dup2X2Int").status());
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
        assertEquals(LoweringStatus.LOWERED,
                lower(AsmFixtureBuilder.classWithStackPermutationMethods("pkg/StackOps"), "dup2X2Int").status());
        assertEquals(LoweringStatus.LOWERED,
                lower(AsmFixtureBuilder.classWithBitwiseShiftMethod("pkg/BitMath"), "bitShift").status());
        assertEquals(LoweringStatus.LOWERED,
                lower(AsmFixtureBuilder.classWithLongBitwiseShiftMethod("pkg/LongBitMath"), "longBitShift").status());
        assertEquals(LoweringStatus.LOWERED,
                lower(AsmFixtureBuilder.classWithPrimitiveConversionMethods("pkg/ConvertMore"), "narrow").status());
        assertEquals(LoweringStatus.LOWERED,
                lower(AsmFixtureBuilder.classWithJvmComparisonMethods("pkg/CompareMore"), "longCmp").status());
        assertEquals(LoweringStatus.LOWERED,
                lower(AsmFixtureBuilder.classWithTableSwitchMethod("pkg/TableSwitch"), "select").status());
        assertEquals(LoweringStatus.LOWERED,
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
    void lowersTypedCatchHandlerWithExceptionParameter() {
        var method = lower(
                AsmFixtureBuilder.classWithTryCatchMethod("pkg/TryCatch"),
                "guarded").irMethod().orElseThrow();

        var handler = method.blocks().stream()
                .filter(block -> block.isExceptionHandler())
                .findFirst()
                .orElseThrow();
        assertEquals(java.util.List.of("java/lang/RuntimeException"), handler.exceptionCatchTypes());
        assertEquals(IrType.REFERENCE, handler.parameters().get(0).type());
        assertTrue(method.blocks().stream().anyMatch(block -> block.exceptionEdges().stream()
                .anyMatch(edge -> edge.target().equals(handler.name())
                        && edge.catchType().equals("java/lang/RuntimeException"))));
    }

    @Test
    void safeFinallyCleanupShapeLowersWithoutFallbackOrFrontendSkip() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithFinallyCleanupShape("pkg/SafeFinally"),
                "withCleanup");

        assertEquals(LoweringStatus.LOWERED, result.status());
        assertTrue(result.irMethod().isPresent());
        assertNull(result.reasonCode());
        assertNull(result.reason());
    }

    @Test
    void lowersAthrowToThrowTerminatorAndRuntimeHelperCall() {
        var method = lower(
                AsmFixtureBuilder.classWithAthrowMethod("pkg/Raise"),
                "raise").irMethod().orElseThrow();

        assertEquals(IrTerminatorKind.THROW, method.blocks().get(0).terminator().kind());
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_throw(ptr %j2ll_env, ptr %p0)"));
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
    void lowersCatchAllRethrowAndFinallyCleanupShapes() {
        var rethrow = lower(
                AsmFixtureBuilder.classWithCatchAllFinallyShape("pkg/FinallyShape"),
                "cleanup").irMethod().orElseThrow();
        assertTrue(rethrow.blocks().stream().anyMatch(block -> block.terminator().kind() == IrTerminatorKind.THROW));

        var cleanup = lower(
                AsmFixtureBuilder.classWithFinallyCleanupShape("pkg/FinallyCleanup"),
                "withCleanup").irMethod().orElseThrow();
        long cleanupCalls = cleanup.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC)
                .filter(instruction -> instruction.symbol().orElse("").equals("pkg/FinallyCleanup#cleanupMarker!()V"))
                .count();
        assertEquals(2, cleanupCalls);
        assertTrue(cleanup.blocks().stream().anyMatch(block -> block.terminator().kind() == IrTerminatorKind.THROW));
    }

    @Test
    void unsupportedMultiExitFinallyShapeProducesPreciseDiagnostic() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedMultiExitFinallyShape("pkg/FinallyShape"),
                "badFinally");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.FRONTEND_SKIPPED, result.artifact().orElseThrow().status());
        assertEquals(LoweringDiagnostics.UNSUPPORTED_MULTI_EXIT_FINALLY, result.diagnostics().get(0).code());
    }

    @Test
    void unsupportedMonitorFinallyInteractionProducesPreciseDiagnostic() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedMonitorFinallyInteraction("pkg/MonitorFinallyShape"),
                "badMonitorFinally");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.FRONTEND_SKIPPED, result.artifact().orElseThrow().status());
        assertEquals(LoweringDiagnostics.UNSUPPORTED_MONITOR_FINALLY_INTERACTION, result.diagnostics().get(0).code());
    }

    @Test
    void unsupportedNestedFinallyProducesPreciseDiagnostic() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedNestedFinallyShape("pkg/NestedFinallyShape"),
                "badNestedFinally");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.FRONTEND_SKIPPED, result.artifact().orElseThrow().status());
        assertEquals(LoweringDiagnostics.UNSUPPORTED_NESTED_FINALLY, result.diagnostics().get(0).code());
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
        assertTrue(llvm(method).contains("call ptr @j2ll_rt_class_object(ptr %j2ll_env, i64 "));
        assertTrue(llvm(method).contains("call void @j2ll_rt_monitor_enter(ptr %j2ll_env, ptr "));
    }

    @Test
    void lowersSynchronizedMethodExplicitThrowWithExceptionalUnlock() {
        var method = lower(
                AsmFixtureBuilder.classWithSynchronizedThrowMethod("pkg/SyncThrow"),
                "syncThrow").irMethod().orElseThrow();

        assertTrue(method.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_EXIT_ON_EXCEPTION));
        assertEquals(IrTerminatorKind.THROW, method.blocks().get(0).terminator().kind());
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit_on_exception(ptr %j2ll_env, ptr %p0)"));
    }

    @Test
    void lowersSynchronizedBlockExceptionalUnlockShape() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithSynchronizedExceptionalUnlockShape("pkg/SyncExceptional"),
                "lockedExceptional");

        assertEquals(LoweringStatus.LOWERED, result.status());
        var method = result.irMethod().orElseThrow();
        assertTrue(hasOpcode(method, IrOpcode.MONITOR_EXIT_ON_EXCEPTION));
        assertTrue(method.blocks().stream().anyMatch(block -> block.isExceptionHandler()
                && hasOpcode(block, IrOpcode.MONITOR_EXIT_ON_EXCEPTION)
                && block.terminator().kind() == IrTerminatorKind.THROW));
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit_on_exception(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains("call void @j2ll_rt_throw(ptr %j2ll_env, "));
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
    void marksThreadStartJoinHappensBefore() {
        var method = lower(
                AsmFixtureBuilder.classWithThreadStartJoinMethod("pkg/Threads"),
                "runThread").irMethod().orElseThrow();

        assertTrue(hasOpcode(method, IrOpcode.THREAD_START_HAPPENS_BEFORE));
        assertTrue(hasOpcode(method, IrOpcode.THREAD_JOIN_HAPPENS_BEFORE));
        String llvm = llvm(method);
        assertTrue(llvm.contains("call void @j2ll_rt_thread_start_happens_before(ptr %p0)"));
        assertTrue(llvm.contains("call void @j2ll_rt_thread_join_happens_before(ptr %p0)"));
    }

    @Test
    void waitNotifyUsesJvmHelperFallbackBoundary() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithWaitNotifyMethod("pkg/WaitNotify"),
                "waitNotify");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.HALF_LOWERED, result.artifact().orElseThrow().status());
        assertEquals(DiagnosticCode.JVM_HELPER_FALLBACK, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("WAIT_NOTIFY_FALLBACK"));
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
    void unsupportedJdkCallBecomesHalfLoweredFallback() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedJdkStringCall("pkg/JdkFallback"),
                "substring");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.HALF_LOWERED, result.artifact().orElseThrow().status());
        assertEquals(DiagnosticCode.JVM_HELPER_FALLBACK, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("java/lang/String#substring"));
        var method = result.artifact().orElseThrow().irMethod().orElseThrow();
        assertTrue(hasOpcode(method, IrOpcode.CALL_VIRTUAL));
        assertTrue(llvm(method).contains("@j2ll_rt_call_virtual_ref_a(ptr %j2ll_env"));
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
        assertTrue(llvm.contains("@j2ll_rt_string_constant(ptr %j2ll_env"));
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
    void unsupportedStringConcatRecipeBecomesHalfLoweredFallback() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsupportedStringConcatRecipe("pkg/StringConcatUnsupported"),
                "concatUnsupported");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.HALF_LOWERED, result.artifact().orElseThrow().status());
        assertEquals(DiagnosticCode.JVM_HELPER_FALLBACK, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("unsupported recipe constant"));
        assertTrue(hasOpcode(result.artifact().orElseThrow().irMethod().orElseThrow(), IrOpcode.CALL_DYNAMIC));
    }

    @Test
    void lowersCommonLambdaMetafactoryShapesToRuntimeHelper() {
        byte[] classBytes = AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/LambdaShapes");

        var nonCapturing = lower(classBytes, "nonCapturing").irMethod().orElseThrow();
        assertTrue(llvm(nonCapturing).contains("call ptr @j2ll_rt_lambda_new(ptr %j2ll_env, i64 "));
        assertFalse(hasOpcode(nonCapturing, IrOpcode.CALL_DYNAMIC));

        var capturing = lower(classBytes, "capturing").irMethod().orElseThrow();
        assertTrue(llvm(capturing).contains("call ptr @j2ll_rt_lambda_new(ptr %j2ll_env, i64 "));
        assertTrue(llvm(capturing).contains("ptr %p0"));

        var staticReference = lower(classBytes, "staticReference").irMethod().orElseThrow();
        assertTrue(llvm(staticReference).contains("@j2ll_rt_lambda_new"));

        var instanceReference = lower(classBytes, "instanceReference").irMethod().orElseThrow();
        assertTrue(llvm(instanceReference).contains("ptr %p0"));

        var constructorReference = lower(classBytes, "constructorReference").irMethod().orElseThrow();
        assertTrue(llvm(constructorReference).contains("@j2ll_rt_lambda_new"));
    }

    @Test
    void unsupportedLambdaMetafactoryShapeBecomesHalfLoweredFallback() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/LambdaUnsupported"),
                "alt");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.HALF_LOWERED, result.artifact().orElseThrow().status());
        assertEquals(DiagnosticCode.JVM_HELPER_FALLBACK, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("altMetafactory"));
        assertTrue(hasOpcode(result.artifact().orElseThrow().irMethod().orElseThrow(), IrOpcode.CALL_DYNAMIC));
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
        assertTrue(llvm.contains("call ptr @j2ll_rt_class_object(ptr %j2ll_env, i64 "));
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
    void classInitializerFailurePathMarksFailedState() {
        var method = lower(
                AsmFixtureBuilder.classWithThrowingClassInitializer("pkg/ClassInitFail"),
                "<clinit>").irMethod().orElseThrow();

        assertTrue(method.blocks().stream().anyMatch(block -> block.isExceptionHandler()
                && hasOpcode(block, IrOpcode.CLASS_INIT_FAILED)
                && block.terminator().kind() == IrTerminatorKind.THROW));
        assertTrue(hasOpcode(method, IrOpcode.CLASS_INIT_FAILED));
        assertTrue(llvm(method).contains("call void @j2ll_rt_class_init_failed(ptr %j2ll_env, ptr "));
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
        assertTrue(llvm(stringConst).contains("call ptr @j2ll_rt_string_constant(ptr %j2ll_env, i64 "));
        assertFalse(llvm(stringConst).contains("secret-value"));

        var classConst = lower(classBytes, "classConst").irMethod().orElseThrow();
        var classInstruction = classConst.blocks().get(0).instructions().get(0);
        assertEquals(IrOpcode.CONST_CLASS, classInstruction.opcode());
        assertEquals("class:Ljava/lang/String;", classInstruction.symbol().orElseThrow());
        assertTrue(llvm(classConst).contains("call ptr @j2ll_rt_class_object(ptr %j2ll_env, i64 "));

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
        assertTrue(llvm(method).contains("@j2ll_rt_field_get_static_i32(ptr %j2ll_env, ptr %j2ll_owner, i64 "));
    }

    @Test
    void lowersInstanceFieldReadSkeleton() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithInstanceFieldRead("pkg/Fields"),
                "read");

        var method = result.irMethod().orElseThrow();
        assertEquals(IrOpcode.GET_FIELD, method.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(method).contains("@j2ll_rt_field_get_field_i32(ptr %j2ll_env, ptr %p0, i64 "));
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
        assertTrue(llvm(allocation).contains("call ptr @j2ll_rt_alloc_object(ptr %j2ll_env, i64 "));
        assertTrue(llvm(allocation).contains("call void @j2ll_rt_call_constructor_void(ptr %j2ll_env"));

        var refArray = lower(
                AsmFixtureBuilder.classWithReferenceArrayAllocation("pkg/RefArrays", "java/lang/String"),
                "array").irMethod().orElseThrow();
        assertEquals(IrOpcode.NEW_ARRAY, refArray.blocks().get(0).instructions().get(1).opcode());
        assertTrue(llvm(refArray).contains("call ptr @j2ll_rt_new_object_array(ptr %j2ll_env, i64 "));
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

        assertTrue(llvm(objectAllocation).contains("call ptr @j2ll_rt_alloc_object(ptr %j2ll_env, i64 "));
        assertTrue(llvm(referenceArray).contains("call ptr @j2ll_rt_new_object_array(ptr %j2ll_env, i64 "));
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
        assertTrue(llvm(cast).contains("call ptr @j2ll_rt_checkcast(ptr %j2ll_env"));

        var instanceOf = lower(classBytes, "isString").irMethod().orElseThrow();
        assertEquals(IrOpcode.INSTANCEOF, instanceOf.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(instanceOf).contains("call i32 @j2ll_rt_instanceof(ptr %j2ll_env"));
    }

    @Test
    void lowersVirtualInterfaceAndDynamicCallSkeletons() {
        var virtualCall = lower(
                AsmFixtureBuilder.classWithVirtualCall("pkg/VirtualCalls", "pkg/RunnableThing"),
                "call").irMethod().orElseThrow();
        assertEquals(IrOpcode.CALL_VIRTUAL, virtualCall.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(virtualCall).contains("@j2ll_rt_call_virtual_void_a(ptr %j2ll_env"));

        var interfaceCall = lower(
                AsmFixtureBuilder.classWithInterfaceCall("pkg/InterfaceCalls", "pkg/Task"),
                "call").irMethod().orElseThrow();
        assertEquals(IrOpcode.CALL_INTERFACE, interfaceCall.blocks().get(0).instructions().get(0).opcode());
        assertTrue(llvm(interfaceCall).contains("@j2ll_rt_call_interface_void_a(ptr %j2ll_env"));

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
    void dynamicReflectionStringUsesJvmBridge() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithStaticReflectionMethods("pkg/ReflectCaller", "pkg/ReflectTarget"),
                "dynamicForName");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.LOWERED, result.artifact().orElseThrow().status());
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

        assertEquals(LoweringStatus.LOWERED, result.artifact().orElseThrow().status());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(hasOpcode(result.artifact().orElseThrow().irMethod().orElseThrow(), IrOpcode.CALL_VIRTUAL));
    }

    @Test
    void lowersAltMetafactoryCommonFlagsToLambdaHelper() {
        SsaMethodResult result = lower(
                AsmFixtureBuilder.classWithAltMetafactoryLambda("pkg/AltLambda"),
                "altCommon");

        assertEquals(LoweringStatus.LOWERED, result.status());
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
        assertEquals(LoweringStatus.LOWERED, result.artifact().orElseThrow().status());
        assertTrue(result.diagnostics().isEmpty());
        var dynamic = result.artifact().orElseThrow().irMethod().orElseThrow();
        assertTrue(hasOpcode(dynamic, IrOpcode.NEW_ARRAY));
        assertTrue(hasHelper(dynamic, "j2ll_rt_integer_int_value"));
        assertTrue(hasOpcode(dynamic, IrOpcode.CALL_VIRTUAL));
        assertTrue(llvm(dynamic).contains("@j2ll_rt_call_virtual_ref_a(ptr %j2ll_env"));
    }

    @Test
    void lowersSupportedConstantDynamicAndFallbacksUnsupportedBootstrap() {
        byte[] classBytes = AsmFixtureBuilder.classWithConstantDynamicMethods("pkg/Condy");

        var supported = lower(classBytes, "supported").irMethod().orElseThrow();
        assertTrue(hasHelper(supported, "j2ll_rt_constant_dynamic"));

        ParsedMethod parsedMethod = parseMethod(classBytes, "unsupported");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();
        var result = new BytecodeToSsaLowerer().lower(cfg);
        assertEquals(LoweringStatus.HALF_LOWERED, result.artifact().orElseThrow().status());
        assertTrue(result.diagnostics().get(0).message().contains("unsupported ConstantDynamic bootstrap"));
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
    void unsupportedUnsafeApiUsesJvmHelperFallbackReport() {
        ParsedMethod parsedMethod = parseMethod(
                AsmFixtureBuilder.classWithUnsafeMethods("pkg/UnsafeOps"),
                "unsupported");
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.HALF_LOWERED, result.artifact().orElseThrow().status());
        assertEquals(DiagnosticCode.UNSAFE_RAW_MEMORY_FALLBACK, result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains("UNSAFE_RAW_MEMORY_FALLBACK"));
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

    private void assertMergeSkipped(byte[] classBytes, String methodName, DiagnosticCode expectedCode) {
        ParsedMethod parsedMethod = parseMethod(classBytes, methodName);
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();

        var result = new BytecodeToSsaLowerer().lower(cfg);

        assertEquals(LoweringStatus.FRONTEND_SKIPPED, result.artifact().orElseThrow().status());
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
}
