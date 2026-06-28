package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.testsupport.DifferentialResult;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;

class JvmHostedNativeRuntimeE2eTest implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void primitiveScalarLlvmNativePathMatrixRunsInChildJvm() throws Exception {
        Path inputJar = temp.resolve("llvm-matrix.jar");
        writeJar(inputJar, Map.of(
                "pkg/LlvmOps.class", llvmOpsClass(),
                "pkg/LlvmMain.class", llvmMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/LlvmOps#add!(II)I",
                "pkg/LlvmOps#arithmeticLong!(JJ)J",
                "pkg/LlvmOps#arithmeticDouble!(DD)D",
                "pkg/LlvmOps#lessThan!(II)Z",
                "pkg/LlvmOps#noop!()V"));
        Path workspace = temp.resolve("out/llvm-matrix");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.LlvmMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                42
                42
                10.0
                true
                false
                void
                """, differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(5, countOccurrences(loweringReport, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        Path zigWorkspace = workspace.resolve("native/zig-workspace");
        String source = Files.readString(zigWorkspace.resolve("jni/j2lle2e.c"));
        String llvm = Files.readString(zigWorkspace.resolve("llvm/pkg_LlvmOps.ll"));
        assertTrue(source.matches("(?s).*extern jint j2ll_f_[0-9a-f]{32}\\(.*"));
        assertTrue(source.matches("(?s).*extern jlong j2ll_f_[0-9a-f]{32}\\(.*"));
        assertTrue(source.matches("(?s).*extern jdouble j2ll_f_[0-9a-f]{32}\\(.*"));
        assertTrue(source.contains("JNI_TRUE"));
        assertFalse(source.contains("return arg0 + arg1;"));
        assertTrue(llvm.matches("(?s).*define external hidden i32 @j2ll_f_[0-9a-f]{32}\\(.*"));
        assertTrue(llvm.matches("(?s).*define external hidden i64 @j2ll_f_[0-9a-f]{32}\\(.*"));
        assertTrue(llvm.matches("(?s).*define external hidden double @j2ll_f_[0-9a-f]{32}\\(.*"));
        assertTrue(llvm.contains("icmp sge i32"));
    }

    @Test
    void protectedFloatAndDoubleConstantsRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-protected-floating-constants.jar");
        writeJar(inputJar, Map.of(
                "pkg/FloatingConstantOps.class", floatingConstantOpsClass(),
                "pkg/FloatingConstantMain.class", floatingConstantMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/FloatingConstantOps#floatValue!()F",
                "pkg/FloatingConstantOps#floatNaN!()F",
                "pkg/FloatingConstantOps#negativeZero!()D",
                "pkg/FloatingConstantOps#negativeInfinity!()D"));
        Path workspace = temp.resolve("out/llvm-protected-floating-constants");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.FloatingConstantMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                1069547520
                2143289344
                -9223372036854775808
                -4503599627370496
                """, differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(4, countOccurrences(loweringReport, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        String protectionReport = Files.readString(workspace.resolve("reports/protection-report.json"));
        assertTrue(protectionReport.contains("\"reasonCode\": \"FLOAT_CONSTANT_ENCRYPTION\""));
        assertTrue(protectionReport.contains("\"reasonCode\": \"DOUBLE_CONSTANT_ENCRYPTION\""));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_FloatingConstantOps.ll"));
        assertTrue(llvm.contains("xor i32"));
        assertTrue(llvm.contains("bitcast i32"));
        assertTrue(llvm.contains("xor i64"));
        assertTrue(llvm.contains("bitcast i64"));
        assertFalse(llvm.contains("fadd float 0.0"));
        assertFalse(llvm.contains("fadd double 0.0"));
    }

    @Test
    void branchAndPhiLlvmNativePathRunsInChildJvm() throws Exception {
        Path inputJar = temp.resolve("llvm-branch-phi.jar");
        writeJar(inputJar, Map.of(
                "pkg/BranchPhiOps.class", branchPhiOpsClass(),
                "pkg/BranchPhiMain.class", branchPhiMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/BranchPhiOps#ifElse!(I)I",
                "pkg/BranchPhiOps#nested!(I)I",
                "pkg/BranchPhiOps#isZero!(I)Z",
                "pkg/BranchPhiOps#merge!(I)I"));
        Path workspace = temp.resolve("out/llvm-branch-phi");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.BranchPhiMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                15
                -12
                -1
                0
                1
                true
                false
                10
                -8
                """, differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(4, countOccurrences(loweringReport, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        String protectionReport = Files.readString(workspace.resolve("reports/protection-report.json"));
        assertTrue(protectionReport.contains("\"passName\": \"CONTROL_FLOW_FLATTENING\""));
        assertTrue(protectionReport.contains("\"reasonCode\": \"CONTROL_FLOW_FLATTENING\""));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_BranchPhiOps.ll"));
        assertTrue(llvm.contains("switch i32"));
        assertTrue(llvm.contains("br i1"));
        assertTrue(llvm.contains("icmp"));
        assertTrue(llvm.contains(" phi i32 "));
    }

    @Test
    void switchAndJvmNumericHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-switch-numeric.jar");
        writeJar(inputJar, Map.of(
                "pkg/TableSwitch.class", AsmFixtureBuilder.classWithTableSwitchMethod("pkg/TableSwitch"),
                "pkg/LookupSwitch.class", AsmFixtureBuilder.classWithLookupSwitchMethod("pkg/LookupSwitch"),
                "pkg/ConvertMore.class", AsmFixtureBuilder.classWithPrimitiveConversionMethods("pkg/ConvertMore"),
                "pkg/CompareMore.class", AsmFixtureBuilder.classWithJvmComparisonMethods("pkg/CompareMore"),
                "pkg/SwitchNumericMain.class", switchNumericMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/TableSwitch#select!(I)I",
                "pkg/LookupSwitch#lookup!(I)I",
                "pkg/ConvertMore#narrow!(J)I",
                "pkg/ConvertMore#floatToInt!(F)I",
                "pkg/ConvertMore#floatToDouble!(F)D",
                "pkg/CompareMore#longCmp!(JJ)I",
                "pkg/CompareMore#floatCmp!(FF)I"));
        Path workspace = temp.resolve("out/llvm-switch-numeric");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.SwitchNumericMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                0
                1
                -1
                10
                20
                -1
                -1
                3
                2.5
                1
                -1
                -1
                0
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(7, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertEquals(4, countOccurrences(report, "\"reasonCode\": \"JVM_NUMERIC_HELPER\""));
        String tableLlvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_TableSwitch.ll"));
        String lookupLlvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_LookupSwitch.ll"));
        String convertLlvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ConvertMore.ll"));
        String compareLlvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_CompareMore.ll"));
        assertTrue(tableLlvm.contains("switch i32"));
        assertTrue(lookupLlvm.contains("switch i32"));
        assertTrue(convertLlvm.contains("call i32 @j2ll_rt_i2b"));
        assertTrue(compareLlvm.contains("call i32 @j2ll_rt_lcmp"));
        assertTrue(compareLlvm.contains("call i32 @j2ll_rt_fcmpl"));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("int32_t j2ll_rt_i2b"));
        assertTrue(source.contains("int32_t j2ll_rt_lcmp"));
        assertTrue(source.contains("int32_t j2ll_rt_fcmpl"));
    }

    @Test
    void fieldHelpersInstanceMethodsAndDirectCallsRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-field-call.jar");
        writeJar(inputJar, Map.of(
                "pkg/FieldCallOps.class", fieldCallOpsClass(),
                "pkg/FieldCallMain.class", fieldCallMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/FieldCallOps#readCounter!()I",
                "pkg/FieldCallOps#setCounter!(I)V",
                "pkg/FieldCallOps#addBase!(I)I",
                "pkg/FieldCallOps#setBase!(I)V",
                "pkg/FieldCallOps#getBase!()I",
                "pkg/FieldCallOps#callee!(I)I",
                "pkg/FieldCallOps#caller!(I)I"));
        Path workspace = temp.resolve("out/llvm-field-call");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.FieldCallMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                14
                12
                20
                19
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(7, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"FIELD_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"DIRECT_LLVM_CALL\""));
        assertTrue(report.contains("\"helper\": \"direct:pkg/FieldCallOps#callee!(I)I\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_field_table"));
        assertTrue(source.contains("GetStaticIntField"));
        assertTrue(source.contains("SetStaticIntField"));
        assertTrue(source.contains("GetIntField"));
        assertTrue(source.contains("SetIntField"));
        assertFalse(source.contains("self->"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_FieldCallOps.ll"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_field_get_static_i32"));
        assertTrue(llvm.contains("call void @j2ll_rt_field_put_field_i32"));
        assertTrue(llvm.matches("(?s).*@j2ll_cit_[0-9a-f]{32} = internal constant \\[[0-9]+ x ptr] \\[.*"));
        assertTrue(llvm.matches("(?s).*getelementptr inbounds \\[[0-9]+ x ptr], ptr @j2ll_cit_[0-9a-f]{32}, i32 0, i32 [0-9]+.*"));
        assertTrue(llvm.contains("load ptr, ptr %j2ll_indirect_slot_"));
        assertTrue(llvm.matches("(?s).*%[A-Za-z0-9_]+ = call i32 \\([^)]*\\) %j2ll_indirect_fn_[A-Za-z0-9_]+\\(.*"));
        assertTrue(llvm.matches("(?s).*ptr @j2ll_f_[0-9a-f]{32}.*"));
        assertFalse(llvm.contains("@j2ll_call_pkg_FieldCallOps_callee"));
        String protectionReport = Files.readString(workspace.resolve("reports/protection-report.json"));
        assertTrue(protectionReport.contains("\"passName\": \"CALL_INDIRECTION\""));
        assertTrue(protectionReport.contains("\"reasonCode\": \"CALL_INDIRECTION_TABLE\""));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
        assertTrue(symbolAudit.contains("\"JNI_OnLoad\""));
        assertFalse(symbolAudit.contains("j2ll_cit_"));
        assertFalse(symbolAudit.contains("j2ll_f_"));
        assertFalse(symbolAudit.contains("j2ll_pkg_FieldCallOps_callee_"));
    }

    @Test
    void privateSpecialDirectCallsRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-special-call.jar");
        writeJar(inputJar, Map.of(
                "pkg/SpecialCallOps.class", specialCallOpsClass(),
                "pkg/SpecialCallMain.class", specialCallMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/SpecialCallOps#helper!(I)I",
                "pkg/SpecialCallOps#call!(I)I"));
        Path workspace = temp.resolve("out/llvm-special-call");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.SpecialCallMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("15\n", differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(2, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"DIRECT_LLVM_CALL\""));
        assertTrue(report.contains("\"helper\": \"direct:pkg/SpecialCallOps#helper!(I)I\""));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_SpecialCallOps.ll"));
        assertTrue(llvm.matches("(?s).*@j2ll_cit_[0-9a-f]{32} = internal constant \\[[0-9]+ x ptr] \\[.*"));
        assertTrue(llvm.matches("(?s).*getelementptr inbounds \\[[0-9]+ x ptr], ptr @j2ll_cit_[0-9a-f]{32}, i32 0, i32 [0-9]+.*"));
        assertTrue(llvm.contains("load ptr, ptr %j2ll_indirect_slot_"));
        assertTrue(llvm.matches("(?s).*call i32 \\([^)]*\\) %j2ll_indirect_fn_[A-Za-z0-9_]+\\(.*"));
        assertTrue(llvm.matches("(?s).*ptr @j2ll_f_[0-9a-f]{32}.*"));
        assertTrue(llvm.contains("ptr %p0, i32 %p1"));
        assertFalse(llvm.contains("@j2ll_call_pkg_SpecialCallOps_helper"));
        String protectionReport = Files.readString(workspace.resolve("reports/protection-report.json"));
        assertTrue(protectionReport.contains("\"passName\": \"CALL_INDIRECTION\""));
        assertTrue(protectionReport.contains("\"reasonCode\": \"CALL_INDIRECTION_TABLE\""));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
        assertFalse(symbolAudit.contains("j2ll_cit_"));
        assertFalse(symbolAudit.contains("j2ll_f_"));
        assertFalse(symbolAudit.contains("j2ll_pkg_SpecialCallOps_helper_"));
    }

    @Test
    void methodHandleDirectInvokeExactRunsInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-method-handle.jar");
        writeJar(inputJar, Map.of(
                "pkg/MethodHandleOps.class", AsmFixtureBuilder.classWithMethodHandleInvokeExact("pkg/MethodHandleOps"),
                "pkg/MethodHandleMain.class", methodHandleMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/MethodHandleOps#target!()I",
                "pkg/MethodHandleOps#direct!()I"));
        Path workspace = temp.resolve("out/llvm-method-handle");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.MethodHandleMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("9\n", differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(2, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"DIRECT_LLVM_CALL\""));
        assertTrue(report.contains("\"helper\": \"direct:pkg/MethodHandleOps#target!()I\""));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_MethodHandleOps.ll"));
        assertTrue(llvm.matches("(?s).*@j2ll_cit_[0-9a-f]{32} = internal constant \\[[0-9]+ x ptr] \\[.*"));
        assertTrue(llvm.matches("(?s).*getelementptr inbounds \\[[0-9]+ x ptr], ptr @j2ll_cit_[0-9a-f]{32}, i32 0, i32 [0-9]+.*"));
        assertTrue(llvm.contains("load ptr, ptr %j2ll_indirect_slot_"));
        assertTrue(llvm.matches("(?s).*call i32 \\([^)]*\\) %j2ll_indirect_fn_[A-Za-z0-9_]+\\(.*"));
        assertTrue(llvm.matches("(?s).*ptr @j2ll_f_[0-9a-f]{32}.*"));
        assertFalse(llvm.contains("call ptr @j2ll_rt_method_handle_invoke_exact"));
        String protectionReport = Files.readString(workspace.resolve("reports/protection-report.json"));
        assertTrue(protectionReport.contains("\"passName\": \"CALL_INDIRECTION\""));
        assertTrue(protectionReport.contains("\"reasonCode\": \"CALL_INDIRECTION_TABLE\""));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
        assertFalse(symbolAudit.contains("j2ll_cit_"));
        assertFalse(symbolAudit.contains("j2ll_f_"));
        assertFalse(symbolAudit.contains("j2ll_pkg_MethodHandleOps_target_"));
    }

    @Test
    void volatileFieldJmmFencesRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-volatile-fields.jar");
        writeJar(inputJar, Map.of(
                "pkg/VolatileBox.class", AsmFixtureBuilder.classWithVolatileFieldMethods("pkg/VolatileBox"),
                "pkg/VolatileMain.class", volatileMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/VolatileBox#read!()I",
                "pkg/VolatileBox#write!(I)V"));
        Path workspace = temp.resolve("out/llvm-volatile-fields");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.VolatileMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("42\n", differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(2, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"JMM_FENCE\""));
        assertTrue(report.contains("\"reasonCode\": \"FIELD_HELPER\""));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_VolatileBox.ll"));
        assertTrue(llvm.contains("fence acquire"));
        assertTrue(llvm.contains("fence release"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_field_get_field_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_field_put_field_i32(ptr %j2ll_env"));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("GetIntField"));
        assertTrue(source.contains("SetIntField"));
    }

    @Test
    void synchronizedBlockMonitorHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-monitor.jar");
        writeJar(inputJar, Map.of(
                "pkg/MonitorOps.class", monitorOpsClass(),
                "pkg/MonitorWorker.class", monitorWorkerClass(),
                "pkg/MonitorMain.class", monitorMainClass()));
        ResolvedConfig config = config(inputJar, List.of("pkg/MonitorOps#inc!(Ljava/lang/Object;)V"));
        Path workspace = temp.resolve("out/llvm-monitor");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.MonitorMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("2000\n", differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(report.contains("\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"MONITOR_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"JMM_FENCE\""));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_MonitorOps.ll"));
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_enter(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit(ptr %j2ll_env"));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("MonitorEnter"));
        assertTrue(source.contains("MonitorExit"));
    }

    @Test
    void synchronizedMethodsRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-synchronized-methods.jar");
        writeJar(inputJar, Map.of(
                "pkg/SyncMethodOps.class", syncMethodOpsClass(),
                "pkg/SyncMethodMain.class", syncMethodMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/SyncMethodOps#add!(I)V",
                "pkg/SyncMethodOps#value!()I",
                "pkg/SyncMethodOps#addStatic!(I)V",
                "pkg/SyncMethodOps#staticValue!()I",
                "pkg/SyncMethodOps#fail!(Ljava/lang/RuntimeException;)V"));
        Path workspace = temp.resolve("out/llvm-synchronized-methods");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.SyncMethodMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                7
                11
                boom
                8
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(5, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"SYNCHRONIZED_METHOD_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"MONITOR_HELPER\""));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_SyncMethodOps.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_class_object(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_enter(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_monitor_exit_on_exception(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_throw(ptr %j2ll_env"));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_rt_class_object(JNIEnv* env, int64_t class_token)"));
        assertTrue(source.contains("MonitorEnter"));
        assertTrue(source.contains("MonitorExit"));
        assertTrue(source.contains("(*env)->Throw(env, (jthrowable)throwable)"));
    }

    @Test
    void explicitAthrowRunsInChildJvmThroughExceptionBridge() throws Exception {
        Path inputJar = temp.resolve("llvm-exception-bridge.jar");
        writeJar(inputJar, Map.of(
                "pkg/ExceptionBridgeOps.class", exceptionBridgeOpsClass(),
                "pkg/ExceptionBridgeMain.class", exceptionBridgeMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ExceptionBridgeOps#throwRuntime!()V",
                "pkg/ExceptionBridgeOps#throwIllegal!()V"));
        Path workspace = temp.resolve("out/llvm-exception-bridge");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ExceptionBridgeMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                RuntimeException
                IllegalArgumentException
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(2, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"EXCEPTION_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"ALLOCATION_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"THROWABLE_HELPER\""));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ExceptionBridgeOps.ll"));
        assertTrue(llvm.contains("call void @j2ll_rt_throw(ptr %j2ll_env"));
        assertTrue(llvm.contains("ret void"));
        assertFalse(llvm.contains("unreachable"));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("void j2ll_rt_throw(JNIEnv* env, jobject throwable)"));
        assertTrue(source.contains("(*env)->Throw(env, (jthrowable)throwable)"));
    }

    @Test
    void staticReflectionHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-reflection.jar");
        writeJar(inputJar, Map.of(
                "pkg/ReflectionTarget.class", reflectionTargetClass(),
                "pkg/ReflectionOps.class", reflectionOpsClass(),
                "pkg/ReflectionMain.class", reflectionMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ReflectionOps#forName!()Ljava/lang/Class;",
                "pkg/ReflectionOps#invokeStatic!()Ljava/lang/String;",
                "pkg/ReflectionOps#invokeStaticArg!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#invokeInstanceArg!(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#constructAndInvoke!()Ljava/lang/String;",
                "pkg/ReflectionOps#constructWithArg!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#privateMethodAccessible!(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#privateVoidAccessible!(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#privateConstructorAccessible!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#privatePrimitiveAccessible!(Lpkg/ReflectionTarget;IJ)I",
                "pkg/ReflectionOps#refReturn!(Lpkg/ReflectionTarget;)Ljava/lang/String;",
                "pkg/ReflectionOps#constructPrimitiveAndRef!(ILjava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#arrayArg!([I)Ljava/lang/String;",
                "pkg/ReflectionOps#fieldInt!(Lpkg/ReflectionTarget;)I",
                "pkg/ReflectionOps#fieldRef!(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#fieldBoolean!(Lpkg/ReflectionTarget;)Z",
                "pkg/ReflectionOps#fieldLong!(Lpkg/ReflectionTarget;)J",
                "pkg/ReflectionOps#fieldDouble!(Lpkg/ReflectionTarget;)D",
                "pkg/ReflectionOps#staticLong!()J",
                "pkg/ReflectionOps#staticRef!(Ljava/lang/String;)Ljava/lang/String;"));
        Path workspace = temp.resolve("out/llvm-reflection");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ReflectionMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                pkg.ReflectionTarget
                hello
                static:arg
                target:arg
                target
                made
                private:arg
                null
                hidden
                52
                target
                seven:7
                len=3
                41
                field
                true
                1234567890123
                2.5
                88
                static-ref
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(20, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"REFLECTION_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"REFLECTION_FIELD_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"REFLECTION_METHOD_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"REFLECTION_CONSTRUCTOR_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"REFLECTION_ACCESSIBLE_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"TYPE_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_reflection_method_table"));
        assertTrue(source.contains("j2ll_reflection_field_table"));
        assertTrue(source.contains("j2ll_rt_class_for_name_static"));
        assertTrue(source.contains("j2ll_rt_get_declared_method"));
        assertTrue(source.contains("j2ll_rt_get_declared_field"));
        assertTrue(source.contains("j2ll_rt_get_declared_constructor"));
        assertTrue(source.contains("j2ll_rt_reflect_invoke"));
        assertTrue(source.contains("j2ll_rt_reflect_new_instance"));
        assertTrue(source.contains("j2ll_rt_reflect_set_accessible"));
        assertTrue(source.contains("j2ll_parameter_array_for_descriptor"));
        assertTrue(source.contains("fromMethodDescriptorString"));
        assertTrue(source.contains("j2ll_rt_reflect_field_get_int"));
        assertTrue(source.contains("j2ll_rt_reflect_field_set_int"));
        assertTrue(source.contains("j2ll_rt_reflect_field_get_boolean"));
        assertTrue(source.contains("j2ll_rt_reflect_field_set_boolean"));
        assertTrue(source.contains("j2ll_rt_reflect_field_get_long"));
        assertTrue(source.contains("j2ll_rt_reflect_field_set_long"));
        assertTrue(source.contains("j2ll_rt_reflect_field_get_double"));
        assertTrue(source.contains("j2ll_rt_reflect_field_set_double"));
        assertTrue(source.contains("j2ll_rt_reflect_field_get"));
        assertTrue(source.contains("j2ll_rt_reflect_field_set"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ReflectionOps.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_class_for_name_static(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_get_declared_method(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_get_declared_field(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_get_declared_constructor(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_reflect_invoke(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_reflect_new_instance(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_reflect_set_accessible(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_reflect_field_get_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_reflect_field_set_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_reflect_field_get_boolean(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_reflect_field_set_boolean(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i64 @j2ll_rt_reflect_field_get_long(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_reflect_field_set_long(ptr %j2ll_env"));
        assertTrue(llvm.contains("call double @j2ll_rt_reflect_field_get_double(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_reflect_field_set_double(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_reflect_field_get(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_reflect_field_set(ptr %j2ll_env"));
    }

    @Test
    void unsafeIntFieldAndAllocateInstanceRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-unsafe.jar");
        writeJar(inputJar, Map.of(
                "pkg/UnsafeTarget.class", unsafeTargetClass(),
                "pkg/UnsafeOps.class", unsafeOpsClass(),
                "pkg/UnsafeMain.class", unsafeMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/UnsafeOps#offset!(Lsun/misc/Unsafe;)J",
                "pkg/UnsafeOps#read!(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;J)I",
                "pkg/UnsafeOps#write!(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;JI)V",
                "pkg/UnsafeOps#cas!(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;JII)Z",
                "pkg/UnsafeOps#allocate!(Lsun/misc/Unsafe;)Lpkg/UnsafeTarget;"));
        Path workspace = temp.resolve("out/llvm-unsafe");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.UnsafeMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                7
                11
                true
                13
                false
                13
                0
                21
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(5, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"UNSAFE_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_rt_unsafe_object_field_offset"));
        assertTrue(source.contains("j2ll_rt_unsafe_get_int"));
        assertTrue(source.contains("j2ll_rt_unsafe_put_int"));
        assertTrue(source.contains("j2ll_rt_unsafe_compare_and_swap_int"));
        assertTrue(source.contains("j2ll_rt_unsafe_allocate_instance"));
        assertTrue(source.contains("GetIntField"));
        assertTrue(source.contains("SetIntField"));
        assertTrue(source.contains("MonitorEnter"));
        assertTrue(source.contains("AllocObject"));
        assertFalse(source.contains("(uintptr_t)target"));
        assertFalse(source.contains("target + token"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_UnsafeOps.ll"));
        assertTrue(llvm.contains("call i64 @j2ll_rt_unsafe_object_field_offset(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_unsafe_get_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_unsafe_put_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_unsafe_compare_and_swap_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_unsafe_allocate_instance(ptr %j2ll_env"));
    }

    @Test
    void typedVarHandleIntFieldRunsInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-varhandle.jar");
        writeJar(inputJar, Map.of(
                "pkg/VarHandleTarget.class", varHandleTargetClass(),
                "pkg/VarHandleOps.class", AsmFixtureBuilder.classWithTypedIntVarHandleMethods(
                        "pkg/VarHandleOps",
                        "pkg/VarHandleTarget"),
                "pkg/VarHandleMain.class", varHandleMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/VarHandleOps#getInt!(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;)I",
                "pkg/VarHandleOps#setInt!(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;I)V",
                "pkg/VarHandleOps#getVolatileInt!(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;)I",
                "pkg/VarHandleOps#setVolatileInt!(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;I)V",
                "pkg/VarHandleOps#compareAndSetInt!(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;II)Z"));
        Path workspace = temp.resolve("out/llvm-varhandle");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.VarHandleMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                3
                9
                12
                true
                15
                false
                15
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(5, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"VARHANDLE_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_rt_var_handle_get_int"));
        assertTrue(source.contains("j2ll_rt_var_handle_set_int"));
        assertTrue(source.contains("j2ll_rt_var_handle_get_volatile_int"));
        assertTrue(source.contains("j2ll_rt_var_handle_set_volatile_int"));
        assertTrue(source.contains("j2ll_rt_var_handle_compare_and_set_int"));
        assertTrue(source.contains("NewObjectArray"));
        assertFalse(source.contains("(uintptr_t)target"));
        assertFalse(source.contains("target + token"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_VarHandleOps.ll"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_var_handle_get_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_var_handle_set_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_var_handle_get_volatile_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_var_handle_set_volatile_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_var_handle_compare_and_set_int(ptr %j2ll_env"));
    }

    @Test
    void arithmeticExceptionHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-div-rem.jar");
        writeJar(inputJar, Map.of(
                "pkg/DivRemOps.class", divRemOpsClass(),
                "pkg/DivRemMain.class", divRemMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/DivRemOps#div!(II)I",
                "pkg/DivRemOps#rem!(II)I",
                "pkg/DivRemOps#ldiv!(JJ)J",
                "pkg/DivRemOps#lrem!(JJ)J"));
        Path workspace = temp.resolve("out/llvm-div-rem");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.DivRemMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                7
                1
                5
                2
                / by zero
                / by zero
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(4, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"DIV_REM_EXCEPTION_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_rt_div_i32"));
        assertTrue(source.contains("java/lang/ArithmeticException"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_DivRemOps.ll"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_div_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_rem_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i64 @j2ll_rt_div_i64(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i64 @j2ll_rt_rem_i64(ptr %j2ll_env"));
        assertFalse(llvm.contains("sdiv i32"));
        assertFalse(llvm.contains("srem i32"));
        assertFalse(llvm.contains("sdiv i64"));
        assertFalse(llvm.contains("srem i64"));
    }

    @Test
    void nullReceiverAndReferenceFieldHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-reference-fields.jar");
        writeJar(inputJar, Map.of(
                "pkg/ReferenceFieldOps.class", referenceFieldOpsClass(),
                "pkg/ReferenceFieldMain.class", referenceFieldMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ReferenceFieldOps#readValue!(Lpkg/ReferenceFieldOps;)I",
                "pkg/ReferenceFieldOps#readLabel!(Lpkg/ReferenceFieldOps;)Ljava/lang/String;",
                "pkg/ReferenceFieldOps#setLabel!(Lpkg/ReferenceFieldOps;Ljava/lang/String;)V"));
        Path workspace = temp.resolve("out/llvm-reference-fields");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ReferenceFieldMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                42
                hello
                true
                NPE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(3, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"FIELD_HELPER\""));
        assertTrue(report.contains("Ljava/lang/String;"));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("GetObjectField"));
        assertTrue(source.contains("SetObjectField"));
        assertTrue(source.contains("field receiver is null"));
        assertFalse(source.contains("self->"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ReferenceFieldOps.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_field_get_field_ref(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_field_put_field_ref(ptr %j2ll_env"));
    }

    @Test
    void intArrayHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-arrays.jar");
        writeJar(inputJar, Map.of(
                "pkg/ArrayHelperOps.class", arrayHelperOpsClass(),
                "pkg/ArrayHelperMain.class", arrayHelperMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ArrayHelperOps#firstPlusLength!([I)I",
                "pkg/ArrayHelperOps#setFirst!([II)I"));
        Path workspace = temp.resolve("out/llvm-arrays");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ArrayHelperMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                6
                9
                NPE
                AIOOBE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(2, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"ARRAY_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("GetArrayLength"));
        assertTrue(source.contains("GetIntArrayRegion"));
        assertTrue(source.contains("SetIntArrayRegion"));
        assertTrue(source.contains("ArrayIndexOutOfBoundsException"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ArrayHelperOps.ll"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_array_length_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_array_load_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_array_store_i32(ptr %j2ll_env"));
        assertFalse(llvm.contains("@j2ll_rt_array_load_i32_"));
    }

    @Test
    void byteAndReferenceArrayHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-reference-arrays.jar");
        writeJar(inputJar, Map.of(
                "pkg/ReferenceArrayOps.class", referenceArrayOpsClass(),
                "pkg/ReferenceArrayMain.class", referenceArrayMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ReferenceArrayOps#byteRoundtrip!([B)I",
                "pkg/ReferenceArrayOps#byteAt!([BI)I",
                "pkg/ReferenceArrayOps#stringRoundtrip!([Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReferenceArrayOps#objectRoundtrip!([Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                "pkg/ReferenceArrayOps#newStringArrayRoundtrip!(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReferenceArrayOps#newObjectArrayRoundtrip!(Ljava/lang/Object;)Ljava/lang/Object;",
                "pkg/ReferenceArrayOps#nullElement!([Ljava/lang/String;)Z",
                "pkg/ReferenceArrayOps#wrongStore!([Ljava/lang/String;Ljava/lang/Object;)V"));
        Path workspace = temp.resolve("out/llvm-reference-arrays");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ReferenceArrayMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                7
                alpha
                beta
                second
                gamma
                true
                NPE
                AIOOBE
                ASE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(8, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"ARRAY_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"ALLOCATION_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("GetByteArrayRegion"));
        assertTrue(source.contains("SetByteArrayRegion"));
        assertTrue(source.contains("GetObjectArrayElement"));
        assertTrue(source.contains("SetObjectArrayElement"));
        assertTrue(source.contains("NewObjectArray"));
        assertFalse(source.contains("->elements"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ReferenceArrayOps.ll"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_array_load_i8(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_array_store_i8(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_array_load_ref(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_array_store_ref(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_new_object_array(ptr %j2ll_env"));
    }

    @Test
    void broadPrimitiveArrayHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-broad-primitive-arrays.jar");
        writeJar(inputJar, Map.of(
                "pkg/BroadPrimitiveArrayOps.class", broadPrimitiveArrayOpsClass(),
                "pkg/BroadPrimitiveArrayMain.class", broadPrimitiveArrayMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/BroadPrimitiveArrayOps#byteLength!(I)I",
                "pkg/BroadPrimitiveArrayOps#shortLength!(I)I",
                "pkg/BroadPrimitiveArrayOps#charLength!(I)I",
                "pkg/BroadPrimitiveArrayOps#longLength!(I)I",
                "pkg/BroadPrimitiveArrayOps#floatLength!(I)I",
                "pkg/BroadPrimitiveArrayOps#doubleLength!(I)I",
                "pkg/BroadPrimitiveArrayOps#shortRoundtrip!([S)I",
                "pkg/BroadPrimitiveArrayOps#charRoundtrip!([C)I",
                "pkg/BroadPrimitiveArrayOps#longRoundtrip!([J)J",
                "pkg/BroadPrimitiveArrayOps#floatRoundtrip!([F)F",
                "pkg/BroadPrimitiveArrayOps#doubleRoundtrip!([D)D"));
        Path workspace = temp.resolve("out/llvm-broad-primitive-arrays");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.BroadPrimitiveArrayMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                1
                2
                3
                4
                5
                6
                7
                66
                7
                3.0
                5.0
                NPE
                AIOOBE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(11, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"ARRAY_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"ALLOCATION_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("NewByteArray"));
        assertTrue(source.contains("NewShortArray"));
        assertTrue(source.contains("NewCharArray"));
        assertTrue(source.contains("NewLongArray"));
        assertTrue(source.contains("NewFloatArray"));
        assertTrue(source.contains("NewDoubleArray"));
        assertTrue(source.contains("GetShortArrayRegion"));
        assertTrue(source.contains("SetShortArrayRegion"));
        assertTrue(source.contains("GetCharArrayRegion"));
        assertTrue(source.contains("SetCharArrayRegion"));
        assertTrue(source.contains("GetLongArrayRegion"));
        assertTrue(source.contains("SetLongArrayRegion"));
        assertTrue(source.contains("GetFloatArrayRegion"));
        assertTrue(source.contains("SetFloatArrayRegion"));
        assertTrue(source.contains("GetDoubleArrayRegion"));
        assertTrue(source.contains("SetDoubleArrayRegion"));
        assertFalse(source.contains("malloc(sizeof(jarray"));
        assertFalse(source.contains("->elements"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_BroadPrimitiveArrayOps.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_new_byte_array(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_new_short_array(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_new_char_array(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_new_long_array(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_new_float_array(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_new_double_array(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_array_load_i16(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_array_store_i16(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_array_load_u16(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_array_store_u16(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i64 @j2ll_rt_array_load_i64(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_array_store_i64(ptr %j2ll_env"));
        assertTrue(llvm.contains("call float @j2ll_rt_array_load_f32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_array_store_f32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call double @j2ll_rt_array_load_f64(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_array_store_f64(ptr %j2ll_env"));
    }

    @Test
    void systemArraycopyHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-arraycopy.jar");
        writeJar(inputJar, Map.of(
                "pkg/ArraycopyOps.class", arraycopyOpsClass(),
                "pkg/ArraycopyMain.class", arraycopyMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ArraycopyOps#copyInt!([I[I)V",
                "pkg/ArraycopyOps#copyByte!([B[B)V",
                "pkg/ArraycopyOps#copyLong!([J[J)V",
                "pkg/ArraycopyOps#copyDouble!([D[D)V",
                "pkg/ArraycopyOps#copyObject!([Ljava/lang/Object;[Ljava/lang/Object;)V",
                "pkg/ArraycopyOps#overlap!([I)V",
                "pkg/ArraycopyOps#copyObjectToString!([Ljava/lang/Object;[Ljava/lang/String;)V",
                "pkg/ArraycopyOps#copyNull!([Ljava/lang/Object;)V",
                "pkg/ArraycopyOps#copyOob!([I[I)V"));
        Path workspace = temp.resolve("out/llvm-arraycopy");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ArraycopyMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                3
                8
                13
                2.5
                hi
                1
                3
                NPE
                OOB
                ASE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(9, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"ARRAYCOPY_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("void j2ll_rt_system_arraycopy(JNIEnv* env"));
        assertTrue(source.contains("CallStaticVoidMethod"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ArraycopyOps.ll"));
        assertTrue(llvm.contains("call void @j2ll_rt_system_arraycopy(ptr %j2ll_env"));
    }

    @Test
    void allocationAndStringHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-allocation-string.jar");
        writeJar(inputJar, Map.of(
                "pkg/AllocationStringOps.class", allocationStringOpsClass(),
                "pkg/AllocationStringMain.class", allocationStringMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/AllocationStringOps#intLength!(I)I",
                "pkg/AllocationStringOps#stringArrayLength!(I)I",
                "pkg/AllocationStringOps#length!(Ljava/lang/String;)I",
                "pkg/AllocationStringOps#same!(Ljava/lang/String;Ljava/lang/String;)Z",
                "pkg/AllocationStringOps#empty!(Ljava/lang/String;)Z",
                "pkg/AllocationStringOps#charAt!(Ljava/lang/String;I)I",
                "pkg/AllocationStringOps#starts!(Ljava/lang/String;Ljava/lang/String;)Z",
                "pkg/AllocationStringOps#ends!(Ljava/lang/String;Ljava/lang/String;)Z",
                "pkg/AllocationStringOps#middle!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/AllocationStringOps#builder!(Ljava/lang/String;IJ)Ljava/lang/String;"));
        Path workspace = temp.resolve("out/llvm-allocation-string");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.AllocationStringMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                3
                2
                4
                true
                false
                true
                98
                true
                true
                ell
                x742
                SIOOBE
                NPE
                NASE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(10, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"ALLOCATION_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"STRING_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"STRING_BUILDER_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("NewIntArray"));
        assertTrue(source.contains("NewObjectArray"));
        assertTrue(source.contains("GetStringLength"));
        assertTrue(source.contains("j2ll_rt_string_equals"));
        assertTrue(source.contains("j2ll_rt_string_char_at"));
        assertTrue(source.contains("j2ll_rt_string_substring_range"));
        assertTrue(source.contains("j2ll_rt_string_builder_append_i64"));
        assertFalse(source.contains("malloc(sizeof(jobject"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_AllocationStringOps.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_new_int_array(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_new_object_array(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_string_length(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_string_equals(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_string_char_at(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_string_substring_range(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_string_builder_init(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_string_builder_append_i64(ptr %j2ll_env"));
    }

    @Test
    void mathScalarJdkHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-math-helpers.jar");
        writeJar(inputJar, Map.of(
                "pkg/MathHelperOps.class", mathHelperOpsClass(),
                "pkg/MathHelperMain.class", mathHelperMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/MathHelperOps#ints!(II)I",
                "pkg/MathHelperOps#longs!(JJ)J",
                "pkg/MathHelperOps#floats!(FF)F",
                "pkg/MathHelperOps#doubles!(DD)D"));
        Path workspace = temp.resolve("out/llvm-math-helpers");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.MathHelperMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                4
                5
                5.0
                4.0
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(4, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"JDK_INTRINSIC_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_rt_math_abs_i32"));
        assertTrue(source.contains("j2ll_rt_math_min_i64"));
        assertTrue(source.contains("j2ll_rt_math_max_i64"));
        assertTrue(source.contains("j2ll_rt_math_abs_f32"));
        assertTrue(source.contains("j2ll_rt_math_max_f64"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_MathHelperOps.ll"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_math_abs_i32"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_math_min_i32"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_math_max_i32"));
        assertTrue(llvm.contains("call i64 @j2ll_rt_math_abs_i64"));
        assertTrue(llvm.contains("call i64 @j2ll_rt_math_min_i64"));
        assertTrue(llvm.contains("call i64 @j2ll_rt_math_max_i64"));
        assertTrue(llvm.contains("call float @j2ll_rt_math_abs_f32"));
        assertTrue(llvm.contains("call double @j2ll_rt_math_max_f64"));
    }

    @Test
    void boxingAndObjectsHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-boxing-objects.jar");
        writeJar(inputJar, Map.of(
                "pkg/BoxingObjectsOps.class", boxingObjectsOpsClass(),
                "pkg/BoxingObjectsMain.class", boxingObjectsMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/BoxingObjectsOps#boxedInt!(I)I",
                "pkg/BoxingObjectsOps#boxedLong!(J)J",
                "pkg/BoxingObjectsOps#boxedBoolean!(Z)Z",
                "pkg/BoxingObjectsOps#boxedDouble!(D)D",
                "pkg/BoxingObjectsOps#same!(Ljava/lang/Object;Ljava/lang/Object;)Z",
                "pkg/BoxingObjectsOps#require!(Ljava/lang/Object;)Ljava/lang/Object;"));
        Path workspace = temp.resolve("out/llvm-boxing-objects");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.BoxingObjectsMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                5
                7
                true
                3.5
                true
                false
                ok
                NPE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(6, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"JDK_INTRINSIC_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_rt_integer_value_of"));
        assertTrue(source.contains("j2ll_rt_double_double_value"));
        assertTrue(source.contains("j2ll_rt_objects_require_non_null"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_BoxingObjectsOps.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_integer_value_of(ptr %j2ll_env"));
        assertTrue(llvm.contains("call double @j2ll_rt_double_double_value(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_objects_require_non_null(ptr %j2ll_env"));
    }

    @Test
    void stringConcatFactoryRunsInChildJvmThroughStringBuilderHelpers() throws Exception {
        Path inputJar = temp.resolve("llvm-string-concat.jar");
        writeJar(inputJar, Map.of(
                "pkg/StringConcat.class", AsmFixtureBuilder.classWithStringConcatMakeConcat("pkg/StringConcat"),
                "pkg/StringConcatMain.class", stringConcatMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/StringConcat#concat!(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
        Path workspace = temp.resolve("out/llvm-string-concat");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.StringConcatMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                ab
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(1, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"STRING_BUILDER_HELPER\""));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_StringConcat.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_string_builder_new(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_string_builder_to_string(ptr %j2ll_env"));
    }

    @Test
    void stringConcatFactoryConstantsRunInChildJvmThroughStringConstantHelper() throws Exception {
        Path inputJar = temp.resolve("llvm-string-concat-constants.jar");
        writeJar(inputJar, Map.of(
                "pkg/StringConcatRecipe.class",
                AsmFixtureBuilder.classWithStringConcatWithConstants("pkg/StringConcatRecipe"),
                "pkg/StringConcatRecipeMain.class", stringConcatRecipeMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/StringConcatRecipe#concatRecipe!(Ljava/lang/String;I)Ljava/lang/String;"));
        Path workspace = temp.resolve("out/llvm-string-concat-constants");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.StringConcatRecipeMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                value=x:7
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(1, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"STRING_CONCAT_CONSTANTS_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_rt_string_constant"));
        assertTrue(source.contains("j2ll_encrypted_string_constant_table"), source);
        assertFalse(source.contains("static const struct j2ll_string_constant_entry j2ll_string_constant_table"));
        assertTrue(source.contains("NewStringUTF"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_StringConcatRecipe.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_string_constant(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_string_builder_to_string(ptr %j2ll_env"));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
        assertFalse(symbolAudit.contains("value="));
    }

    @Test
    void protectedConstStringsRunThroughEncryptedHelperInLlvmAndTemplateBodies() throws Exception {
        Path inputJar = temp.resolve("protected-const-strings.jar");
        writeJar(inputJar, Map.of(
                "pkg/ProtectedStrings.class", protectedStringsClass(),
                "pkg/ProtectedStringBox.class", protectedStringBoxClass(),
                "pkg/ProtectedStringsMain.class", protectedStringsMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ProtectedStrings#literal!()Ljava/lang/String;",
                "pkg/ProtectedStringBox#<init>!()V"));
        Path workspace = temp.resolve("out/protected-const-strings");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ProtectedStringsMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                ordinary-secret
                ctor-secret
                """, differential.outputRun().stdout());
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_encrypted_string_constant_table"));
        assertTrue(source.contains("j2ll_rt_string_constant(env"));
        assertFalse(source.contains("ordinary-secret"));
        assertFalse(source.contains("ctor-secret"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ProtectedStrings.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_string_constant(ptr %j2ll_env"));
        assertFalse(llvm.contains("ordinary-secret"));
        assertFalse(llvm.contains("ctor-secret"));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
        assertFalse(symbolAudit.contains("ordinary-secret"));
        assertFalse(symbolAudit.contains("ctor-secret"));
        String protectionReport = Files.readString(workspace.resolve("reports/protection-report.json"));
        assertTrue(protectionReport.contains("\"passName\": \"STRING_ENCRYPTION\""));
        assertTrue(protectionReport.contains("\"status\": \"RAN\""));
        assertTrue(protectionReport.contains("\"pathKind\": \"TEMPLATE_JNI_PATH_STABLE_SURFACE\""));
        assertTrue(protectionReport.contains("\"gateMode\": \"blocking\""));
        String artifactAudit = Files.readString(workspace.resolve("reports/artifact-audit.json"));
        assertTrue(artifactAudit.contains("\"pathKind\": \"TEMPLATE_JNI_PATH_STABLE_SURFACE\""));
        assertTrue(artifactAudit.contains("\"reasonCode\": \"FORBIDDEN_PLAINTEXT_ABSENT\""));
        assertTrue(artifactAudit.contains("\"reasonCode\": \"FORBIDDEN_PLAINTEXT_ABSENT_FROM_JAR\""));
        assertFalse(artifactAudit.contains("ctor-secret"));
    }

    @Test
    void lambdaMetafactoryCommonShapesRunInChildJvmThroughJvmHelper() throws Exception {
        Path inputJar = temp.resolve("llvm-lambda-metafactory.jar");
        writeJar(inputJar, Map.of(
                "pkg/LambdaShapes.class", AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/LambdaShapes"),
                "pkg/LambdaMain.class", lambdaMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/LambdaShapes#nonCapturing!()Ljava/lang/Runnable;",
                "pkg/LambdaShapes#capturing!(Ljava/lang/String;)Ljava/util/function/Supplier;",
                "pkg/LambdaShapes#staticReference!()Ljava/util/function/Supplier;",
                "pkg/LambdaShapes#instanceReference!(Ljava/lang/String;)Ljava/util/function/Supplier;",
                "pkg/LambdaShapes#constructorReference!()Ljava/util/function/Supplier;"));
        Path workspace = temp.resolve("out/llvm-lambda-metafactory");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.LambdaMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                ran
                cap
                value
                trim
                true
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(5, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"LAMBDA_METAFACTORY_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_rt_lambda_new"));
        assertTrue(source.contains("LambdaMetafactory"));
        assertTrue(source.contains("privateLookupIn"));
        assertTrue(source.contains("invokeWithArguments"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_LambdaShapes.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_lambda_new(ptr %j2ll_env"));
        assertFalse(llvm.contains("call ptr @j2ll_call_dynamic"));
    }

    @Test
    void objectConstructionAndTypeHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-object-type.jar");
        writeJar(inputJar, Map.of(
                "pkg/ObjectPoint.class", objectPointClass(),
                "pkg/ObjectTypeOps.class", objectTypeOpsClass(),
                "pkg/ObjectTypeMain.class", objectTypeMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ObjectTypeOps#makePoint!(II)Lpkg/ObjectPoint;",
                "pkg/ObjectTypeOps#makePointSum!(II)I",
                "pkg/ObjectTypeOps#castString!(Ljava/lang/Object;)Ljava/lang/String;",
                "pkg/ObjectTypeOps#isString!(Ljava/lang/Object;)Z"));
        Path workspace = temp.resolve("out/llvm-object-type");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ObjectTypeMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                7
                8
                hello
                CCE
                true
                false
                false
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(4, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"CONSTRUCTOR_CALL_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"ALLOCATION_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"TYPE_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"DEFERRED_DISPATCH_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_rt_alloc_object"));
        assertTrue(source.contains("CallNonvirtualVoidMethod"));
        assertTrue(source.contains("IsInstanceOf"));
        assertTrue(source.contains("ClassCastException"));
        assertFalse(source.contains("malloc(sizeof(jobject"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ObjectTypeOps.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_alloc_object(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_call_constructor_void_i32_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_checkcast(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_instanceof(ptr %j2ll_env"));
    }

    @Test
    void virtualAndInterfaceDispatchHelpersRunInChildJvmThroughLlvmNativePath() throws Exception {
        Path inputJar = temp.resolve("llvm-dispatch.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/Base.class", dispatchBaseClass());
        entries.put("pkg/Sub.class", dispatchSubClass());
        entries.put("pkg/I.class", dispatchInterfaceClass());
        entries.put("pkg/Impl.class", dispatchImplClass());
        entries.put("pkg/DefaultI.class", dispatchDefaultInterfaceClass());
        entries.put("pkg/DefaultInherited.class", dispatchDefaultInheritedClass());
        entries.put("pkg/DefaultOverride.class", dispatchDefaultOverrideClass());
        entries.put("pkg/DefaultSuperImpl.class", dispatchDefaultSuperImplClass());
        entries.put("pkg/ConflictLeft.class", dispatchConflictLeftClass());
        entries.put("pkg/ConflictRight.class", dispatchConflictRightClass());
        entries.put("pkg/ConflictImpl.class", dispatchConflictImplClass());
        entries.put("pkg/DispatchOps.class", dispatchOpsClass());
        entries.put("pkg/DispatchMain.class", dispatchMainClass());
        writeJar(inputJar, entries);
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/DispatchOps#virtualValue!(Lpkg/Base;)I",
                "pkg/DispatchOps#virtualAdd!(Lpkg/Base;I)I",
                "pkg/DispatchOps#interfaceValue!(Lpkg/I;)I",
                "pkg/DispatchOps#interfaceName!(Lpkg/I;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/DispatchOps#defaultValue!(Lpkg/DefaultI;)I",
                "pkg/DispatchOps#defaultName!(Lpkg/DefaultI;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/DispatchOps#conflictValue!(Lpkg/ConflictLeft;)I",
                "pkg/DefaultSuperImpl#value!()I"));
        Path workspace = temp.resolve("out/llvm-dispatch");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.DispatchMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                41
                46
                7
                impl:ok
                33
                44
                default:ok
                override:ok
                35
                default-conflict
                NPE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(7, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"status\": \"frontendSkipped\""));
        assertTrue(report.contains("\"reasonCode\": \"DISPATCH_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"DEFAULT_INTERFACE_DISPATCH_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"DEFAULT_INTERFACE_DISPATCH_FALLBACK\""));
        assertTrue(report.contains("\"reasonCode\": \"UNSUPPORTED_DEFAULT_INTERFACE_CONFLICT\""));
        assertTrue(report.contains("\"reasonCode\": \"UNSUPPORTED_DEFAULT_INTERFACE_SUPER\""));
        assertTrue(report.contains("\"reasonCode\": \"DEFERRED_DISPATCH_HELPER\""));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertFalse(packagingReport.contains("\"registrationOwner\": \"pkg/DefaultSuperImpl\""));
        assertFalse(packagingReport.contains("pkg/DefaultSuperImpl#value!()I"));
        var defaultSuperClass = new AsmClassParser()
                .parseAll(new JarClassFileSource(pipeline.outputJar()))
                .artifact()
                .orElseThrow()
                .program()
                .findClass("pkg/DefaultSuperImpl")
                .orElseThrow();
        var defaultSuperValue = defaultSuperClass.methods().stream()
                .filter(method -> method.name().equals("value") && method.descriptor().equals("()I"))
                .findFirst()
                .orElseThrow();
        assertFalse(defaultSuperValue.accessFlags().isNative());
        assertTrue(defaultSuperValue.hasCode());
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_method_table"));
        assertTrue(source.contains("CallIntMethod"));
        assertFalse(source.contains("vtable"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_DispatchOps.ll"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_call_virtual_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_call_interface_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_call_virtual_i32_arg_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_call_interface_ref_arg_ref(ptr %j2ll_env"));
    }

    @Test
    void constructorAndClassInitializerBodyHelpersRunInChildJvm() throws Exception {
        Path inputJar = temp.resolve("constructor-clinit.jar");
        writeJar(inputJar, Map.of(
                "pkg/Point.class", pointClass(),
                "pkg/StaticInitOps.class", staticInitOpsClass(),
                "pkg/ConstructorClinitMain.class", constructorClinitMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/Point#<init>!(II)V",
                "pkg/StaticInitOps#<clinit>!()V"));
        Path workspace = temp.resolve("out/constructor-clinit");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ConstructorClinitMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                3
                17
                ready
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(report.contains("\"rewriteStrategy\": \"constructorStub\""));
        assertTrue(report.contains("\"rewriteStrategy\": \"classInitializerStub\""));
        assertTrue(report.contains("\"reasonCode\": \"CONSTRUCTOR_BODY_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"CLASS_INITIALIZER_BODY_HELPER\""));
        assertTrue(report.contains("\"nativeImplementationPath\": \"TEMPLATE_JNI_PATH\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("generic JVM-hosted body helper lowered from SSA IR"));
        assertTrue(source.contains("\"x\", \"I\""));
        assertTrue(source.contains("\"y\", \"I\""));
        assertTrue(source.contains("SetIntField"));
    }

    @Test
    void genericConstructorAndClassInitializerBodyHelpersRunInChildJvm() throws Exception {
        Path inputJar = temp.resolve("generic-constructor-clinit.jar");
        writeJar(inputJar, Map.of(
                "pkg/GenericBox.class", genericBoxClass(),
                "pkg/GenericStaticInit.class", genericStaticInitClass(),
                "pkg/GenericConstructorClinitMain.class", genericConstructorClinitMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/GenericBox#<init>!(ILjava/lang/String;)V",
                "pkg/GenericStaticInit#<clinit>!()V"));
        Path workspace = temp.resolve("out/generic-constructor-clinit");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.GenericConstructorClinitMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                9
                box
                4
                17
                22
                generic
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(report.contains("\"reasonCode\": \"CONSTRUCTOR_BODY_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"CLASS_INITIALIZER_BODY_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("generic JVM-hosted body helper lowered from SSA IR"));
        assertTrue(source.contains("\"total\", \"I\""));
        assertTrue(source.contains("\"label\", \"Ljava/lang/String;\""));
        assertTrue(source.contains("\"values\", \"[I\""));
        assertTrue(source.contains("NewIntArray"));
    }

    @Test
    void branchingConstructorAndClassInitializerBodyHelpersRunInChildJvm() throws Exception {
        Path inputJar = temp.resolve("branching-constructor-clinit.jar");
        writeJar(inputJar, Map.of(
                "pkg/BranchingBox.class", branchingBoxClass(),
                "pkg/BranchingStaticInit.class", branchingStaticInitClass(),
                "pkg/BranchingConstructorClinitMain.class", branchingConstructorClinitMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/BranchingBox#<init>!(I)V",
                "pkg/BranchingStaticInit#<clinit>!()V"));
        Path workspace = temp.resolve("out/branching-constructor-clinit");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.BranchingConstructorClinitMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                5
                -5
                11
                branched
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(report.contains("\"reasonCode\": \"CONSTRUCTOR_BODY_HELPER\""));
        assertTrue(report.contains("\"reasonCode\": \"CLASS_INITIALIZER_BODY_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("generic JVM-hosted body helper lowered from SSA IR"));
        assertTrue(source.contains("goto j2ll_block_"));
        assertTrue(source.contains("if (j2ll_v_"));
        assertTrue(source.contains("SetIntField"));
    }

    @Test
    void primitiveAndInstanceAbiShapesRunInChildJvm() throws Exception {
        Path inputJar = temp.resolve("abi.jar");
        writeJar(inputJar, Map.of(
                "pkg/PrimitiveOps.class", primitiveOpsClass(),
                "pkg/InstanceBox.class", instanceBoxClass(),
                "pkg/AbiMain.class", abiMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/PrimitiveOps#addLong!(JJ)J",
                "pkg/PrimitiveOps#addFloat!(FF)F",
                "pkg/PrimitiveOps#addDouble!(DD)D",
                "pkg/PrimitiveOps#truth!(Z)Z",
                "pkg/PrimitiveOps#mix!(ZIJFD)D",
                "pkg/PrimitiveOps#setLast!(I)V",
                "pkg/InstanceBox#addBase!(I)I",
                "pkg/InstanceBox#bump!(I)V",
                "pkg/InstanceBox#value!()I"));
        Path workspace = temp.resolve("out/abi");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.AbiMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                33
                3.5
                4.75
                true
                16.75
                91
                15
                17
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(report.contains("jobject"));
        assertTrue(report.contains("jclass"));
        assertTrue(report.contains("jdouble"));
    }

    @Test
    void multiClassMultiMethodRegistrationSharesOneEmbeddedLibrary() throws Exception {
        Path inputJar = temp.resolve("multi.jar");
        writeJar(inputJar, Map.of(
                "pkg/MultiA.class", multiAClass(),
                "pkg/MultiB.class", multiBClass(),
                "pkg/MultiMain.class", multiMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/MultiA#mul!(II)I",
                "pkg/MultiB#inc!(I)I"));
        Path workspace = temp.resolve("out/multi");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.MultiMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("35\n12\n", differential.outputRun().stdout());
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"class\": \"pkg/MultiA\""));
        assertTrue(packagingReport.contains("\"class\": \"pkg/MultiB\""));
        assertTrue(packagingReport.contains("\"registrationOwner\": \"pkg/MultiA\""));
        assertTrue(packagingReport.contains("\"registrationOwner\": \"pkg/MultiB\""));
        assertTrue(packagingReport.contains("\"registrationGroups\""));
        assertEquals(1, countEntries(pipeline.outputJar(), HostPlatform.detect().orElseThrow().target().libraryFileName()));
    }

    @Test
    void stringJniPathRunsInChildJvm() throws Exception {
        Path inputJar = temp.resolve("strings.jar");
        writeJar(inputJar, Map.of(
                "pkg/StringOps.class", stringOpsClass(),
                "pkg/StringMain.class", stringMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/StringOps#echo!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/StringOps#length!(Ljava/lang/String;)I",
                "pkg/StringOps#label!()Ljava/lang/String;"));
        Path workspace = temp.resolve("out/strings");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.StringMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("hello\n4\nbox\ntrue\nNPE\n", differential.outputRun().stdout());
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("GetStringUTFChars"));
        assertTrue(source.contains("NewStringUTF"));
    }

    @Test
    void primitiveIntArrayJniPathRunsInChildJvm() throws Exception {
        Path inputJar = temp.resolve("arrays.jar");
        writeJar(inputJar, Map.of(
                "pkg/ArrayOps.class", arrayOpsClass(),
                "pkg/ArrayMain.class", arrayMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ArrayOps#sum!([I)I",
                "pkg/ArrayOps#copyPlusOne!([I)[I"));
        Path workspace = temp.resolve("out/arrays");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ArrayMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("6\n0\n[2, 3]\nNPE\n", differential.outputRun().stdout());
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("GetArrayLength"));
        assertTrue(source.contains("GetIntArrayRegion"));
        assertTrue(source.contains("SetIntArrayRegion"));
        assertTrue(source.contains("NewIntArray"));
    }

    @Test
    void exceptionBridgeSmokePathRunsInChildJvm() throws Exception {
        Path inputJar = temp.resolve("exceptions.jar");
        writeJar(inputJar, Map.of(
                "pkg/ExceptionOps.class", exceptionOpsClass(),
                "pkg/ExceptionMain.class", exceptionMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ExceptionOps#failIfNegative!(I)I"));
        Path workspace = temp.resolve("out/exceptions");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ExceptionMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("7\nIllegalArgumentException:negative\n", differential.outputRun().stdout());
        assertTrue(Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"))
                .contains("ThrowNew"));
    }

    @Test
    void throwableFallbackKeepsMessageAndCauseInChildJvm() throws Exception {
        Path inputJar = temp.resolve("throwable-fallback.jar");
        writeJar(inputJar, Map.of(
                "pkg/ThrowableOps.class", throwableOpsClass(),
                "pkg/ThrowableMain.class", throwableMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ThrowableOps#messageAndCause!()Ljava/lang/String;"));
        Path workspace = temp.resolve("out/throwable-fallback");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ThrowableMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("outer:cause\n", differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(loweringReport.contains("\"reasonCode\": \"THROWABLE_HELPER\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"THROWABLE_HELPER_FALLBACK\""));
        assertTrue(loweringReport.contains("\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"fallbackReasonCode\": \"THROWABLE_HELPER_FALLBACK\""));
        assertTrue(packagingReport.contains("pkg/ThrowableOps#messageAndCause!()Ljava/lang/String;"));
    }

    @Test
    void threadAndWaitNotifyFallbacksRunInChildJvm() throws Exception {
        Path inputJar = temp.resolve("thread-fallback.jar");
        writeJar(inputJar, Map.of(
                "pkg/ThreadOps.class", threadOpsClass(),
                "pkg/ThreadOps$Worker.class", threadWorkerClass(),
                "pkg/ThreadMain.class", threadMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ThreadOps#runThread!()I",
                "pkg/ThreadOps#waitNotify!()Ljava/lang/String;"));
        Path workspace = temp.resolve("out/thread-fallback");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ThreadMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                7
                wait-boundary
                """, differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(loweringReport.contains("\"reasonCode\": \"THREAD_HELPER\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"THREAD_HELPER_FALLBACK\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"WAIT_NOTIFY_FALLBACK\""));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertEquals(1, countOccurrences(packagingReport, "\"fallbackReasonCode\": \"THREAD_HELPER_FALLBACK\""));
        assertEquals(1, countOccurrences(packagingReport, "\"fallbackReasonCode\": \"WAIT_NOTIFY_FALLBACK\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertFalse(source.contains("pthread_cond"));
        assertFalse(source.contains("pthread_create"));
        assertFalse(source.contains("MonitorQueue"));
    }

    @Test
    void nativeEmbeddedClassBlobFallbackDefinesHelperLazilyAndRunsInChildJvm() throws Exception {
        Path inputJar = temp.resolve("fallback.jar");
        writeJar(inputJar, Map.of(
                "pkg/JdkFallback.class", AsmFixtureBuilder.classWithUnsupportedJdkStringCall("pkg/JdkFallback"),
                "pkg/FallbackMain.class", fallbackMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/JdkFallback#substring!(Ljava/lang/String;)Ljava/lang/String;"));
        Path workspace = temp.resolve("out/fallback");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.FallbackMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("bcd\nxyz\n", differential.outputRun().stdout());
        try (JarFile jarFile = new JarFile(pipeline.outputJar().toFile())) {
            assertFalse(jarFile.stream()
                    .anyMatch(entry -> entry.getName().startsWith("j2ll/generated/fallback/")
                            && entry.getName().endsWith(".class")));
            assertFalse(jarFile.stream()
                    .anyMatch(entry -> entry.getName().contains("/J2llFallback$")
                            && entry.getName().endsWith(".class")));
            assertNotNull(jarFile.getJarEntry("xyz/melodysky/runtime/fallback/J2llFallbackSupport.class"));
        }
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"storageTarget\": \"nativeEmbeddedClassBlob\""));
        assertTrue(packagingReport.contains("\"definitionMechanism\": \"HiddenClass\""));
        assertTrue(packagingReport.contains("\"definitionMechanismReasonCode\": \"FALLBACK_HIDDEN_CLASS\""));
        assertTrue(packagingReport.contains("\"ownerLookupSupported\": true"));
        assertTrue(packagingReport.contains("\"cacheReasonCode\": \"FALLBACK_CACHE_REUSE\""));
        assertTrue(packagingReport.contains("\"classloaderReusePolicy\": \"lazyPerClassLoaderReuse\""));
        assertTrue(packagingReport.contains("\"cacheScope\": \"process\""));
        assertTrue(packagingReport.contains("\"cacheKey\": \"fallbackId+definingClassLoaderIdentity\""));
        assertTrue(packagingReport.contains("\"cacheLifetime\": \"processLifetime\""));
        assertTrue(packagingReport.contains("\"globalReferencePolicy\": \"globalRefPerFallbackClassAndClassLoader\""));
        assertTrue(packagingReport.contains("\"encodingVersion\": \"fallbackBlobEncodingV1\""));
        assertTrue(packagingReport.contains("\"originalSha256\""));
        assertTrue(packagingReport.contains("\"encodedSha256\""));
        assertTrue(packagingReport.contains("\"compressionAlgorithm\": \"j2ll-rle-byte-pairs-v1\""));
        assertTrue(packagingReport.contains("\"encryptionAlgorithm\": \"xor-sha256-key-stream-v1\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("DefineClass"));
        assertTrue(source.contains("j2ll_try_define_hidden_fallback"));
        assertTrue(source.contains("J2llFallbackSupport"));
        assertTrue(source.contains("defineHiddenFallback"));
        assertTrue(source.contains("j2ll_verify_sha256_hex"));
        assertTrue(source.contains("fallback encoded SHA-256 mismatch"));
        assertTrue(source.contains("fallback decoded SHA-256 mismatch"));
        assertTrue(source.contains("_cache_entry"));
        assertTrue(source.contains("_cache = entry"));
        assertFalse(source.contains("_loaders[16]"));
        assertTrue(source.contains("IsSameObject"));
        assertTrue(source.contains("_encoded[]"));
        assertTrue(source.contains("_decode(JNIEnv* env"));
        assertFalse(source.contains("_bytes[]"));
    }

    @Test
    void jdkCollectionPolicyFallsBackToEncodedHelperAndRunsInChildJvm() throws Exception {
        Path inputJar = temp.resolve("collection-fallback.jar");
        writeJar(inputJar, Map.of(
                "pkg/CollectionOps.class", collectionOpsClass(),
                "pkg/CollectionMain.class", collectionMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/CollectionOps#arrayListSummary!()Ljava/lang/String;",
                "pkg/CollectionOps#hashMapSummary!()Ljava/lang/String;",
                "pkg/CollectionOps#arraysSummary!()Ljava/lang/String;",
                "pkg/CollectionOps#optionalCollectionsFormatSummary!()Ljava/lang/String;"));
        Path workspace = temp.resolve("out/collection-fallback");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.CollectionMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                2:b:true
                true:v2
                true:7:3
                true:x:fallback:0:one:2:fmt-7
                """, differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(4, countOccurrences(loweringReport, "\"status\": \"halfLowered\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"JDK_COLLECTION_HELPER\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"JDK_HELPER_FALLBACK\""));
        assertTrue(loweringReport.contains("\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("pkg/CollectionOps#arrayListSummary!()Ljava/lang/String;"));
        assertTrue(packagingReport.contains("pkg/CollectionOps#hashMapSummary!()Ljava/lang/String;"));
        assertTrue(packagingReport.contains("pkg/CollectionOps#arraysSummary!()Ljava/lang/String;"));
        assertTrue(packagingReport.contains("pkg/CollectionOps#optionalCollectionsFormatSummary!()Ljava/lang/String;"));
        assertEquals(4, countOccurrences(packagingReport, "\"fallbackReasonCode\": \"JDK_HELPER_FALLBACK\""));
        try (JarFile jarFile = new JarFile(pipeline.outputJar().toFile())) {
            assertFalse(jarFile.stream()
                    .anyMatch(entry -> entry.getName().contains("/J2llFallback$")
                            && entry.getName().endsWith(".class")));
        }
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertFalse(source.contains("ArrayList.elementData"));
        assertFalse(source.contains("HashMap.table"));
        assertFalse(source.contains("ArraysSupport.native"));
        assertFalse(source.contains("Optional.value"));
    }

    @Test
    void mixedSupportedCorpusRunsWithProtectionAllOnInChildJvm() throws Exception {
        Path inputJar = temp.resolve("mixed-corpus.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/ReflectionTarget.class", reflectionTargetClass());
        entries.put("pkg/ReflectionOps.class", reflectionOpsClass());
        entries.put("pkg/ReflectionMain.class", reflectionMainClass());
        entries.put("pkg/ArraycopyOps.class", arraycopyOpsClass());
        entries.put("pkg/ArraycopyMain.class", arraycopyMainClass());
        entries.put("pkg/LambdaShapes.class", AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/LambdaShapes"));
        entries.put("pkg/LambdaMain.class", lambdaMainClass());
        entries.put("pkg/CollectionOps.class", collectionOpsClass());
        entries.put("pkg/CollectionMain.class", collectionMainClass());
        entries.put("pkg/ThrowableOps.class", throwableOpsClass());
        entries.put("pkg/ThrowableMain.class", throwableMainClass());
        entries.put("pkg/ThreadOps.class", threadOpsClass());
        entries.put("pkg/ThreadOps$Worker.class", threadWorkerClass());
        entries.put("pkg/ThreadMain.class", threadMainClass());
        entries.put("pkg/CorpusMain.class", corpusMainClass());
        writeJar(inputJar, entries);
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ReflectionOps#forName!()Ljava/lang/Class;",
                "pkg/ReflectionOps#invokeStatic!()Ljava/lang/String;",
                "pkg/ReflectionOps#invokeStaticArg!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#invokeInstanceArg!(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#constructAndInvoke!()Ljava/lang/String;",
                "pkg/ReflectionOps#constructWithArg!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#privateMethodAccessible!(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#privateVoidAccessible!(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#privateConstructorAccessible!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#privatePrimitiveAccessible!(Lpkg/ReflectionTarget;IJ)I",
                "pkg/ReflectionOps#refReturn!(Lpkg/ReflectionTarget;)Ljava/lang/String;",
                "pkg/ReflectionOps#constructPrimitiveAndRef!(ILjava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#arrayArg!([I)Ljava/lang/String;",
                "pkg/ReflectionOps#fieldInt!(Lpkg/ReflectionTarget;)I",
                "pkg/ReflectionOps#fieldRef!(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectionOps#fieldBoolean!(Lpkg/ReflectionTarget;)Z",
                "pkg/ReflectionOps#fieldLong!(Lpkg/ReflectionTarget;)J",
                "pkg/ReflectionOps#fieldDouble!(Lpkg/ReflectionTarget;)D",
                "pkg/ReflectionOps#staticLong!()J",
                "pkg/ReflectionOps#staticRef!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ArraycopyOps#copyInt!([I[I)V",
                "pkg/ArraycopyOps#copyByte!([B[B)V",
                "pkg/ArraycopyOps#copyLong!([J[J)V",
                "pkg/ArraycopyOps#copyDouble!([D[D)V",
                "pkg/ArraycopyOps#copyObject!([Ljava/lang/Object;[Ljava/lang/Object;)V",
                "pkg/ArraycopyOps#overlap!([I)V",
                "pkg/ArraycopyOps#copyObjectToString!([Ljava/lang/Object;[Ljava/lang/String;)V",
                "pkg/ArraycopyOps#copyNull!([Ljava/lang/Object;)V",
                "pkg/ArraycopyOps#copyOob!([I[I)V",
                "pkg/LambdaShapes#nonCapturing!()Ljava/lang/Runnable;",
                "pkg/LambdaShapes#capturing!(Ljava/lang/String;)Ljava/util/function/Supplier;",
                "pkg/LambdaShapes#staticReference!()Ljava/util/function/Supplier;",
                "pkg/LambdaShapes#instanceReference!(Ljava/lang/String;)Ljava/util/function/Supplier;",
                "pkg/LambdaShapes#constructorReference!()Ljava/util/function/Supplier;",
                "pkg/CollectionOps#arrayListSummary!()Ljava/lang/String;",
                "pkg/CollectionOps#hashMapSummary!()Ljava/lang/String;",
                "pkg/CollectionOps#arraysSummary!()Ljava/lang/String;",
                "pkg/CollectionOps#optionalCollectionsFormatSummary!()Ljava/lang/String;",
                "pkg/ThrowableOps#messageAndCause!()Ljava/lang/String;",
                "pkg/ThreadOps#runThread!()I",
                "pkg/ThreadOps#waitNotify!()Ljava/lang/String;"));
        Path workspace = temp.resolve("out/mixed-corpus");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.CorpusMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                pkg.ReflectionTarget
                hello
                static:arg
                target:arg
                target
                made
                private:arg
                null
                hidden
                52
                target
                seven:7
                len=3
                41
                field
                true
                1234567890123
                2.5
                88
                static-ref
                3
                8
                13
                2.5
                hi
                1
                3
                NPE
                OOB
                ASE
                ran
                cap
                value
                trim
                true
                2:b:true
                true:v2
                true:7:3
                true:x:fallback:0:one:2:fmt-7
                outer:cause
                7
                wait-boundary
                """, differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(34, countOccurrences(loweringReport, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertEquals(7, countOccurrences(loweringReport, "\"status\": \"halfLowered\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"REFLECTION_FIELD_HELPER\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"ARRAYCOPY_HELPER\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"LAMBDA_METAFACTORY_HELPER\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"JDK_COLLECTION_HELPER\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"JDK_HELPER_FALLBACK\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"THROWABLE_HELPER_FALLBACK\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"THREAD_HELPER_FALLBACK\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"WAIT_NOTIFY_FALLBACK\""));
        String protectionReport = Files.readString(workspace.resolve("reports/protection-report.json"));
        assertTrue(protectionReport.contains("\"passName\": \"STRING_ENCRYPTION\""));
        assertTrue(protectionReport.contains("\"status\": \"RAN\""));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertEquals(4, countOccurrences(packagingReport, "\"fallbackReasonCode\": \"JDK_HELPER_FALLBACK\""));
        assertEquals(1, countOccurrences(packagingReport, "\"fallbackReasonCode\": \"THROWABLE_HELPER_FALLBACK\""));
        assertEquals(1, countOccurrences(packagingReport, "\"fallbackReasonCode\": \"THREAD_HELPER_FALLBACK\""));
        assertEquals(1, countOccurrences(packagingReport, "\"fallbackReasonCode\": \"WAIT_NOTIFY_FALLBACK\""));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
        assertTrue(symbolAudit.contains("\"status\": \"passed\""));
        try (JarFile jarFile = new JarFile(pipeline.outputJar().toFile())) {
            assertFalse(jarFile.stream()
                    .anyMatch(entry -> entry.getName().contains("/J2llFallback$")
                            && entry.getName().endsWith(".class")));
        }
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("_encoded[]"));
        assertFalse(source.contains("_bytes[]"));
    }

    @Test
    void dynamicReflectionFallbackRunsFromEncodedHiddenHelperInChildJvm() throws Exception {
        Path inputJar = temp.resolve("reflection-fallback.jar");
        writeJar(inputJar, Map.of(
                "pkg/ReflectFallback.class", reflectionDynamicFallbackClass(),
                "pkg/ReflectionFallbackMain.class", reflectionFallbackMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/ReflectFallback#dynamicForName!(Ljava/lang/String;)V",
                "pkg/ReflectFallback#dynamicMethodName!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/ReflectFallback#dynamicParameterArray!([Ljava/lang/Class;)Ljava/lang/String;"));
        Path workspace = temp.resolve("out/reflection-fallback");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.ReflectionFallbackMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                ok
                ello
                reflection-ok
                """, differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(3, countOccurrences(loweringReport, "\"status\": \"halfLowered\""));
        assertEquals(3, countOccurrences(loweringReport, "\"reasonCode\": \"REFLECTION_DYNAMIC_FALLBACK\""));
        assertEquals(3, countOccurrences(loweringReport, "\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("pkg/ReflectFallback#dynamicForName!(Ljava/lang/String;)V"));
        assertTrue(packagingReport.contains("pkg/ReflectFallback#dynamicMethodName!(Ljava/lang/String;)Ljava/lang/String;"));
        assertTrue(packagingReport.contains("pkg/ReflectFallback#dynamicParameterArray!([Ljava/lang/Class;)Ljava/lang/String;"));
        assertTrue(packagingReport.contains("\"fallbackInvokeDescriptor\": \"(Ljava/lang/String;)V\""));
        assertTrue(packagingReport.contains("\"fallbackInvokeDescriptor\": \"(Ljava/lang/String;)Ljava/lang/String;\""));
        assertTrue(packagingReport.contains("\"fallbackInvokeDescriptor\": \"([Ljava/lang/Class;)Ljava/lang/String;\""));
        assertTrue(packagingReport.contains("\"definitionMechanism\": \"HiddenClass\""));
        assertTrue(packagingReport.contains("\"cacheReasonCode\": \"FALLBACK_CACHE_REUSE\""));
        assertTrue(packagingReport.contains("\"cacheLifetime\": \"processLifetime\""));
        try (JarFile jarFile = new JarFile(pipeline.outputJar().toFile())) {
            assertFalse(jarFile.stream()
                    .anyMatch(entry -> entry.getName().contains("/J2llFallback$")
                            && entry.getName().endsWith(".class")));
        }
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("CallStaticVoidMethod"));
        assertTrue(source.contains("\"invoke\""));
    }

    @Test
    void instanceFallbackPassesReceiverReturnsReferenceAndPropagatesException() throws Exception {
        Path inputJar = temp.resolve("instance-fallback.jar");
        writeJar(inputJar, Map.of(
                "pkg/InstanceFallback.class", instanceFallbackClass(),
                "pkg/InstanceFallbackMain.class", instanceFallbackMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/InstanceFallback#tail!(Ljava/lang/String;)Ljava/lang/String;"));
        Path workspace = temp.resolve("out/instance-fallback");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.InstanceFallbackMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("ello\ncaught-npe\n", differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(loweringReport.contains("\"status\": \"halfLowered\""));
        assertTrue(loweringReport.contains("\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("GetObjectClass(env, self)"));
        assertTrue(source.contains("CallStaticObjectMethod(env, helper, method, self, arg0)"));
    }

    @Test
    void methodHandleAdapterChainFallbackRunsFromEncodedHelperInChildJvm() throws Exception {
        Path inputJar = temp.resolve("method-handle-fallback.jar");
        writeJar(inputJar, Map.of(
                "pkg/MethodHandleAdapterFallback.class", methodHandleAdapterFallbackClass(),
                "pkg/MethodHandleAdapterMain.class", methodHandleAdapterMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/MethodHandleAdapterFallback#bindPrefix!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/MethodHandleAdapterFallback#asTypeLength!(Ljava/lang/String;)I",
                "pkg/MethodHandleAdapterFallback#dropMiddle!(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
                "pkg/MethodHandleAdapterFallback#permuteJoin!(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/MethodHandleAdapterFallback#filterArgument!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/MethodHandleAdapterFallback#foldPrefix!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/MethodHandleAdapterFallback#collectorBoundary!(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                "pkg/MethodHandleAdapterFallback#throwing!(Ljava/lang/String;)Ljava/lang/String;"));
        Path workspace = temp.resolve("out/method-handle-fallback");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.MethodHandleAdapterMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                pre-value
                5
                pre-post
                RL
                f:raw
                fold:ok
                col:2
                bang
                """, differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(4, countOccurrences(loweringReport, "\"reasonCode\": \"METHOD_HANDLE_CHAIN_FALLBACK\""));
        assertEquals(1, countOccurrences(loweringReport, "\"reasonCode\": \"METHOD_HANDLE_PERMUTE_FALLBACK\""));
        assertEquals(1, countOccurrences(loweringReport, "\"reasonCode\": \"METHOD_HANDLE_FILTER_FALLBACK\""));
        assertEquals(1, countOccurrences(loweringReport, "\"reasonCode\": \"METHOD_HANDLE_FOLD_FALLBACK\""));
        assertEquals(1, countOccurrences(loweringReport, "\"reasonCode\": \"METHOD_HANDLE_COLLECTOR_UNSUPPORTED\""));
        assertEquals(8, countOccurrences(loweringReport, "\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"fallbackReasonCode\": \"METHOD_HANDLE_CHAIN_FALLBACK\""));
        assertTrue(packagingReport.contains("\"fallbackReasonCode\": \"METHOD_HANDLE_PERMUTE_FALLBACK\""));
        assertTrue(packagingReport.contains("\"fallbackReasonCode\": \"METHOD_HANDLE_FILTER_FALLBACK\""));
        assertTrue(packagingReport.contains("\"fallbackReasonCode\": \"METHOD_HANDLE_FOLD_FALLBACK\""));
        assertTrue(packagingReport.contains("\"fallbackReasonCode\": \"METHOD_HANDLE_COLLECTOR_UNSUPPORTED\""));
        assertTrue(packagingReport.contains("\"fallbackInvokeDescriptor\": \"(Ljava/lang/String;)I\""));
        assertTrue(packagingReport.contains("\"fallbackInvokeDescriptor\": \"(Ljava/lang/String;)Ljava/lang/String;\""));
        assertTrue(packagingReport.contains("\"fallbackInvokeDescriptor\": \"(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\""));
        assertTrue(packagingReport.contains("\"fallbackInvokeDescriptor\": \"(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;\""));
        assertTrue(packagingReport.contains("\"fallbackInvokeDescriptor\": \"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\""));
        assertNoPlainFallbackClassEntry(pipeline.outputJar());
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("CallStaticObjectMethod"));
        assertTrue(source.contains("CallStaticIntMethod"));
        assertTrue(source.contains("\"invoke\""));
    }

    @Test
    void altMetafactoryUnsupportedCaptureFallbackRunsFromEncodedHelperInChildJvm() throws Exception {
        Path inputJar = temp.resolve("alt-lambda-fallback.jar");
        writeJar(inputJar, Map.of(
                "pkg/AltLambdaFallback.class", altLambdaFallbackClass(),
                "pkg/AltLambdaFallbackMain.class", altLambdaFallbackMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/AltLambdaFallback#serializableTwoCapture!(Ljava/lang/String;Ljava/lang/String;)Ljava/util/function/Supplier;"));
        Path workspace = temp.resolve("out/alt-lambda-fallback");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.AltLambdaFallbackMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                left-right
                true
                """, differential.outputRun().stdout());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(loweringReport.contains("\"reasonCode\": \"ALT_METAFACTORY_FALLBACK\""));
        assertTrue(loweringReport.contains("\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"fallbackReasonCode\": \"ALT_METAFACTORY_FALLBACK\""));
        assertTrue(packagingReport.contains("\"fallbackInvokeDescriptor\": \"(Ljava/lang/String;Ljava/lang/String;)Ljava/util/function/Supplier;\""));
        assertNoPlainFallbackClassEntry(pipeline.outputJar());
    }

    @Test
    void nativeEmbeddedFallbackIsIsolatedAcrossTwoClassloadersInChildJvm() throws Exception {
        Path inputJar = temp.resolve("fallback-classloader-isolation.jar");
        writeJar(inputJar, Map.of(
                "pkg/JdkFallback.class", AsmFixtureBuilder.classWithUnsupportedJdkStringCall("pkg/JdkFallback"),
                "pkg/FallbackClassLoaderMain.class", fallbackClassLoaderMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/JdkFallback#substring!(Ljava/lang/String;)Ljava/lang/String;"));
        Path workspace = temp.resolve("out/fallback-classloader-isolation");

        MainlinePipelineResult pipeline = runPipeline(config, workspace);
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.FallbackClassLoaderMain");

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                bcd
                xyz
                bcd
                false
                """, differential.outputRun().stdout());
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"cacheScope\": \"process\""));
        assertTrue(packagingReport.contains("\"cacheKey\": \"fallbackId+definingClassLoaderIdentity\""));
        assertTrue(packagingReport.contains("\"cacheLifetime\": \"processLifetime\""));
        assertTrue(packagingReport.contains("\"globalReferencePolicy\": \"globalRefPerFallbackClassAndClassLoader\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("IsSameObject(env, entry->loader, loader)"));
        assertTrue(source.contains("_cache_entry"));
    }

    private int countEntries(Path jar, String suffix) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return (int) jarFile.stream()
                    .filter(entry -> entry.getName().endsWith(suffix))
                    .count();
        }
    }

    private void assertNoPlainFallbackClassEntry(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            assertFalse(jarFile.stream()
                    .anyMatch(entry -> entry.getName().contains("/J2llFallback$")
                            && entry.getName().endsWith(".class")));
        }
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private MainlinePipelineResult runPipeline(ResolvedConfig config, Path workspace) throws Exception {
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home"))) {
            return new MainlinePipeline().run(config, workspace);
        }
    }

    private void writeJar(Path inputJar, Map<String, byte[]> entries) throws IOException {
        Map<String, byte[]> stableEntries = new LinkedHashMap<>(entries);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(inputJar))) {
            for (Map.Entry<String, byte[]> entry : stableEntries.entrySet()) {
                JarEntry classEntry = new JarEntry(entry.getKey());
                classEntry.setTime(0L);
                output.putNextEntry(classEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private ResolvedConfig config(Path inputJar, List<String> selectors) {
        JsonObject json = JsonParser.parseString(baseJson(inputJar, selectors)).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }

    private String baseJson(Path inputJar, List<String> selectors) {
        String selectorJson = selectors.stream()
                .map(selector -> "\"" + selector + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        return """
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "javaSupportTier": "TIER_5",
                  "fallbackMode": "nativeEmbeddedClassBlob",
                  "outputDirectory": "out",
                  "whiteList": [%s],
                  "blackList": [],
                  "target": %s,
                  "libraryName": "j2lle2e",
                  "embeddedLibraryDirectory": "native0",
                  "signaturePolicy": "fail",
                  "signing": null,
                  "intermediates": {
                    "enabled": true,
                    "includeDebugDumps": true,
                    "includePerClassIr": true,
                    "includePerClassLlvm": true,
                    "includePerClassC": true
                  },
                  "protection": {
                    "enabled": true,
                    "seed": null,
                    "intensity": "normal",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": { "enabled": true, "intensity": "normal" },
                      "fakeBranches": { "enabled": true, "intensity": "normal" },
                      "basicBlockSplitting": { "enabled": true, "intensity": "normal" },
                      "constantEncryption": { "enabled": true, "intensity": "normal" },
                      "stringEncryption": { "enabled": true, "intensity": "normal", "cacheStrings": false },
                      "methodInlining": { "enabled": true, "intensity": "normal" },
                      "methodSplitting": { "enabled": true, "intensity": "normal" },
                      "callIndirection": { "enabled": true, "intensity": "normal" },
                      "methodTableHiding": { "enabled": true, "intensity": "normal" }
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": { "enabled": true, "intensity": "normal" },
                      "opaquePredicates": { "enabled": true, "intensity": "normal" },
                      "blockLayoutPerturbation": { "enabled": true, "intensity": "normal" },
                      "indirectCalls": { "enabled": true, "intensity": "normal" },
                      "globalLayout": { "enabled": true, "intensity": "normal" },
                      "visibilityHardening": { "enabled": true }
                    },
                    "binary": {
                      "enabled": true,
                      "hideInternalSymbols": true,
                      "strip": true,
                      "removePdb": true,
                      "symbolAudit": true
                    }
                  }
                }
                """.formatted(inputJar.toString().replace("\\", "\\\\"), selectorJson, hostTargetJson());
    }

    private String hostTargetJson() {
        TargetTriple target = HostPlatform.detect().orElseThrow().target();
        return """
                {
                    "windowsX64": %s,
                    "windowsArm64": %s,
                    "linuxX64": %s,
                    "linuxArm64": %s,
                    "macosX64": %s,
                    "macosArm64": %s
                  }""".formatted(
                target == TargetTriple.WINDOWS_X64,
                target == TargetTriple.WINDOWS_ARM64,
                target == TargetTriple.LINUX_X64,
                target == TargetTriple.LINUX_ARM64,
                target == TargetTriple.MACOS_X64,
                target == TargetTriple.MACOS_ARM64);
    }

    private byte[] llvmOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/LlvmOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor add = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "add", "(II)I", null, null);
        add.visitCode();
        add.visitVarInsn(ILOAD, 0);
        add.visitVarInsn(ILOAD, 1);
        add.visitInsn(IADD);
        add.visitInsn(IRETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();
        MethodVisitor longArithmetic = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "arithmeticLong", "(JJ)J", null, null);
        longArithmetic.visitCode();
        longArithmetic.visitVarInsn(LLOAD, 0);
        longArithmetic.visitVarInsn(LLOAD, 2);
        longArithmetic.visitInsn(LSUB);
        longArithmetic.visitInsn(LRETURN);
        longArithmetic.visitMaxs(0, 0);
        longArithmetic.visitEnd();
        MethodVisitor doubleArithmetic = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "arithmeticDouble", "(DD)D", null, null);
        doubleArithmetic.visitCode();
        doubleArithmetic.visitVarInsn(DLOAD, 0);
        doubleArithmetic.visitVarInsn(DLOAD, 2);
        doubleArithmetic.visitInsn(DMUL);
        doubleArithmetic.visitInsn(DRETURN);
        doubleArithmetic.visitMaxs(0, 0);
        doubleArithmetic.visitEnd();
        MethodVisitor lessThan = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "lessThan", "(II)Z", null, null);
        org.objectweb.asm.Label falseLabel = new org.objectweb.asm.Label();
        lessThan.visitCode();
        lessThan.visitVarInsn(ILOAD, 0);
        lessThan.visitVarInsn(ILOAD, 1);
        lessThan.visitJumpInsn(IF_ICMPGE, falseLabel);
        lessThan.visitInsn(ICONST_1);
        lessThan.visitInsn(IRETURN);
        lessThan.visitLabel(falseLabel);
        lessThan.visitInsn(ICONST_0);
        lessThan.visitInsn(IRETURN);
        lessThan.visitMaxs(0, 0);
        lessThan.visitEnd();
        MethodVisitor noop = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "noop", "()V", null, null);
        noop.visitCode();
        noop.visitInsn(RETURN);
        noop.visitMaxs(0, 0);
        noop.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] llvmMainClass() {
        ClassWriter writer = mainClass("pkg/LlvmMain");
        MethodVisitor main = beginMain(writer);
        main.visitIntInsn(BIPUSH, 19);
        main.visitIntInsn(BIPUSH, 23);
        main.visitMethodInsn(INVOKESTATIC, "pkg/LlvmOps", "add", "(II)I", false);
        printTopInt(main);
        printStaticLong(main, "pkg/LlvmOps", "arithmeticLong", 50L, 8L);
        printStaticDouble(main, "pkg/LlvmOps", "arithmeticDouble", 2.5D, 4.0D);
        printStaticIntCompare(main, "pkg/LlvmOps", "lessThan", 3, 5);
        printStaticIntCompare(main, "pkg/LlvmOps", "lessThan", 7, 4);
        main.visitMethodInsn(INVOKESTATIC, "pkg/LlvmOps", "noop", "()V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("void");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] floatingConstantOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/FloatingConstantOps", null, "java/lang/Object", null);
        defaultConstructor(writer);

        MethodVisitor floatValue = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "floatValue", "()F", null, null);
        floatValue.visitCode();
        floatValue.visitLdcInsn(1.5F);
        floatValue.visitInsn(FRETURN);
        floatValue.visitMaxs(0, 0);
        floatValue.visitEnd();

        MethodVisitor floatNaN = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "floatNaN", "()F", null, null);
        floatNaN.visitCode();
        floatNaN.visitLdcInsn(Float.NaN);
        floatNaN.visitInsn(FRETURN);
        floatNaN.visitMaxs(0, 0);
        floatNaN.visitEnd();

        MethodVisitor negativeZero = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "negativeZero", "()D", null, null);
        negativeZero.visitCode();
        negativeZero.visitLdcInsn(-0.0D);
        negativeZero.visitInsn(DRETURN);
        negativeZero.visitMaxs(0, 0);
        negativeZero.visitEnd();

        MethodVisitor negativeInfinity = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "negativeInfinity",
                "()D",
                null,
                null);
        negativeInfinity.visitCode();
        negativeInfinity.visitLdcInsn(Double.NEGATIVE_INFINITY);
        negativeInfinity.visitInsn(DRETURN);
        negativeInfinity.visitMaxs(0, 0);
        negativeInfinity.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] floatingConstantMainClass() {
        ClassWriter writer = mainClass("pkg/FloatingConstantMain");
        MethodVisitor main = beginMain(writer);
        printStaticFloatRawBits(main, "pkg/FloatingConstantOps", "floatValue");
        printStaticFloatRawBits(main, "pkg/FloatingConstantOps", "floatNaN");
        printStaticDoubleRawBits(main, "pkg/FloatingConstantOps", "negativeZero");
        printStaticDoubleRawBits(main, "pkg/FloatingConstantOps", "negativeInfinity");
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] branchPhiOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/BranchPhiOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor ifElse = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "ifElse", "(I)I", null, null);
        org.objectweb.asm.Label nonPositive = new org.objectweb.asm.Label();
        ifElse.visitCode();
        ifElse.visitVarInsn(ILOAD, 0);
        ifElse.visitJumpInsn(IFLE, nonPositive);
        ifElse.visitVarInsn(ILOAD, 0);
        ifElse.visitIntInsn(BIPUSH, 10);
        ifElse.visitInsn(IADD);
        ifElse.visitInsn(IRETURN);
        ifElse.visitLabel(nonPositive);
        ifElse.visitVarInsn(ILOAD, 0);
        ifElse.visitIntInsn(BIPUSH, 10);
        ifElse.visitInsn(ISUB);
        ifElse.visitInsn(IRETURN);
        ifElse.visitMaxs(0, 0);
        ifElse.visitEnd();

        MethodVisitor nested = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "nested", "(I)I", null, null);
        org.objectweb.asm.Label notNegative = new org.objectweb.asm.Label();
        org.objectweb.asm.Label notZero = new org.objectweb.asm.Label();
        nested.visitCode();
        nested.visitVarInsn(ILOAD, 0);
        nested.visitJumpInsn(IFGE, notNegative);
        nested.visitInsn(ICONST_M1);
        nested.visitInsn(IRETURN);
        nested.visitLabel(notNegative);
        nested.visitVarInsn(ILOAD, 0);
        nested.visitJumpInsn(IFNE, notZero);
        nested.visitInsn(ICONST_0);
        nested.visitInsn(IRETURN);
        nested.visitLabel(notZero);
        nested.visitInsn(ICONST_1);
        nested.visitInsn(IRETURN);
        nested.visitMaxs(0, 0);
        nested.visitEnd();

        MethodVisitor isZero = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "isZero", "(I)Z", null, null);
        org.objectweb.asm.Label falseLabel = new org.objectweb.asm.Label();
        isZero.visitCode();
        isZero.visitVarInsn(ILOAD, 0);
        isZero.visitJumpInsn(IFNE, falseLabel);
        isZero.visitInsn(ICONST_1);
        isZero.visitInsn(IRETURN);
        isZero.visitLabel(falseLabel);
        isZero.visitInsn(ICONST_0);
        isZero.visitInsn(IRETURN);
        isZero.visitMaxs(0, 0);
        isZero.visitEnd();

        MethodVisitor merge = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "merge", "(I)I", null, null);
        org.objectweb.asm.Label negative = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        merge.visitCode();
        merge.visitVarInsn(ILOAD, 0);
        merge.visitJumpInsn(IFLE, negative);
        merge.visitVarInsn(ILOAD, 0);
        merge.visitInsn(ICONST_1);
        merge.visitInsn(IADD);
        merge.visitVarInsn(ISTORE, 1);
        merge.visitJumpInsn(GOTO, done);
        merge.visitLabel(negative);
        merge.visitVarInsn(ILOAD, 0);
        merge.visitInsn(ICONST_1);
        merge.visitInsn(ISUB);
        merge.visitVarInsn(ISTORE, 1);
        merge.visitLabel(done);
        merge.visitVarInsn(ILOAD, 1);
        merge.visitInsn(ICONST_2);
        merge.visitInsn(IMUL);
        merge.visitInsn(IRETURN);
        merge.visitMaxs(0, 0);
        merge.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] branchPhiMainClass() {
        ClassWriter writer = mainClass("pkg/BranchPhiMain");
        MethodVisitor main = beginMain(writer);
        printStaticIntCall(main, "pkg/BranchPhiOps", "ifElse", 5);
        printStaticIntCall(main, "pkg/BranchPhiOps", "ifElse", -2);
        printStaticIntCall(main, "pkg/BranchPhiOps", "nested", -5);
        printStaticIntCall(main, "pkg/BranchPhiOps", "nested", 0);
        printStaticIntCall(main, "pkg/BranchPhiOps", "nested", 7);
        printStaticBooleanIntCall(main, "pkg/BranchPhiOps", "isZero", 0);
        printStaticBooleanIntCall(main, "pkg/BranchPhiOps", "isZero", 3);
        printStaticIntCall(main, "pkg/BranchPhiOps", "merge", 4);
        printStaticIntCall(main, "pkg/BranchPhiOps", "merge", -3);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] switchNumericMainClass() {
        ClassWriter writer = mainClass("pkg/SwitchNumericMain");
        MethodVisitor main = beginMain(writer);
        printStaticIntCall(main, "pkg/TableSwitch", "select", 0);
        printStaticIntCall(main, "pkg/TableSwitch", "select", 1);
        printStaticIntCall(main, "pkg/TableSwitch", "select", 7);
        printStaticIntCall(main, "pkg/LookupSwitch", "lookup", 10);
        printStaticIntCall(main, "pkg/LookupSwitch", "lookup", 20);
        printStaticIntCall(main, "pkg/LookupSwitch", "lookup", 7);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(255L);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ConvertMore", "narrow", "(J)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(3.75F);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ConvertMore", "floatToInt", "(F)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(2.5F);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ConvertMore", "floatToDouble", "(F)D", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(D)V", false);
        printStaticLongCompare(main, 8L, 3L);
        printStaticLongCompare(main, 3L, 8L);
        printStaticFloatCompare(main, Float.NaN, 1.0F);
        printStaticFloatCompare(main, 2.0F, 2.0F);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] fieldCallOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/FieldCallOps", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "counter", "I", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "base", "I", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitFieldInsn(PUTFIELD, "pkg/FieldCallOps", "base", "I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor readCounter = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "readCounter", "()I", null, null);
        readCounter.visitCode();
        readCounter.visitFieldInsn(GETSTATIC, "pkg/FieldCallOps", "counter", "I");
        readCounter.visitInsn(IRETURN);
        readCounter.visitMaxs(0, 0);
        readCounter.visitEnd();

        MethodVisitor setCounter = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "setCounter", "(I)V", null, null);
        setCounter.visitCode();
        setCounter.visitVarInsn(ILOAD, 0);
        setCounter.visitFieldInsn(PUTSTATIC, "pkg/FieldCallOps", "counter", "I");
        setCounter.visitInsn(RETURN);
        setCounter.visitMaxs(0, 0);
        setCounter.visitEnd();

        MethodVisitor addBase = writer.visitMethod(ACC_PUBLIC, "addBase", "(I)I", null, null);
        addBase.visitCode();
        addBase.visitVarInsn(ALOAD, 0);
        addBase.visitFieldInsn(GETFIELD, "pkg/FieldCallOps", "base", "I");
        addBase.visitVarInsn(ILOAD, 1);
        addBase.visitInsn(IADD);
        addBase.visitInsn(IRETURN);
        addBase.visitMaxs(0, 0);
        addBase.visitEnd();

        MethodVisitor setBase = writer.visitMethod(ACC_PUBLIC, "setBase", "(I)V", null, null);
        setBase.visitCode();
        setBase.visitVarInsn(ALOAD, 0);
        setBase.visitVarInsn(ILOAD, 1);
        setBase.visitFieldInsn(PUTFIELD, "pkg/FieldCallOps", "base", "I");
        setBase.visitInsn(RETURN);
        setBase.visitMaxs(0, 0);
        setBase.visitEnd();

        MethodVisitor getBase = writer.visitMethod(ACC_PUBLIC, "getBase", "()I", null, null);
        getBase.visitCode();
        getBase.visitVarInsn(ALOAD, 0);
        getBase.visitFieldInsn(GETFIELD, "pkg/FieldCallOps", "base", "I");
        getBase.visitInsn(IRETURN);
        getBase.visitMaxs(0, 0);
        getBase.visitEnd();

        MethodVisitor callee = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "callee", "(I)I", null, null);
        callee.visitCode();
        callee.visitVarInsn(ILOAD, 0);
        callee.visitInsn(ICONST_2);
        callee.visitInsn(IMUL);
        callee.visitInsn(IRETURN);
        callee.visitMaxs(0, 0);
        callee.visitEnd();

        MethodVisitor caller = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "caller", "(I)I", null, null);
        caller.visitCode();
        caller.visitVarInsn(ILOAD, 0);
        caller.visitMethodInsn(INVOKESTATIC, "pkg/FieldCallOps", "callee", "(I)I", false);
        caller.visitInsn(ICONST_1);
        caller.visitInsn(IADD);
        caller.visitInsn(IRETURN);
        caller.visitMaxs(0, 0);
        caller.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] fieldCallMainClass() {
        ClassWriter writer = mainClass("pkg/FieldCallMain");
        MethodVisitor main = beginMain(writer);
        main.visitIntInsn(BIPUSH, 14);
        main.visitMethodInsn(INVOKESTATIC, "pkg/FieldCallOps", "setCounter", "(I)V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/FieldCallOps", "readCounter", "()I", false);
        printTopInt(main);
        main.visitTypeInsn(NEW, "pkg/FieldCallOps");
        main.visitInsn(DUP);
        main.visitInsn(ICONST_5);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/FieldCallOps", "<init>", "(I)V", false);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitIntInsn(BIPUSH, 7);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/FieldCallOps", "addBase", "(I)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitIntInsn(BIPUSH, 20);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/FieldCallOps", "setBase", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/FieldCallOps", "getBase", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        printStaticIntCall(main, "pkg/FieldCallOps", "caller", 9);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] specialCallOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/SpecialCallOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor helper = writer.visitMethod(ACC_PRIVATE, "helper", "(I)I", null, null);
        helper.visitCode();
        helper.visitVarInsn(ILOAD, 1);
        helper.visitInsn(ICONST_2);
        helper.visitInsn(IMUL);
        helper.visitInsn(IRETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        MethodVisitor call = writer.visitMethod(ACC_PUBLIC, "call", "(I)I", null, null);
        call.visitCode();
        call.visitVarInsn(ALOAD, 0);
        call.visitVarInsn(ILOAD, 1);
        call.visitMethodInsn(INVOKESPECIAL, "pkg/SpecialCallOps", "helper", "(I)I", false);
        call.visitInsn(ICONST_1);
        call.visitInsn(IADD);
        call.visitInsn(IRETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] specialCallMainClass() {
        ClassWriter writer = mainClass("pkg/SpecialCallMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/SpecialCallOps");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/SpecialCallOps", "<init>", "()V", false);
        main.visitIntInsn(BIPUSH, 7);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/SpecialCallOps", "call", "(I)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] methodHandleMainClass() {
        ClassWriter writer = mainClass("pkg/MethodHandleMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/MethodHandleOps", "direct", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] volatileMainClass() {
        ClassWriter writer = mainClass("pkg/VolatileMain");
        MethodVisitor main = beginMain(writer);
        main.visitTypeInsn(NEW, "pkg/VolatileBox");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/VolatileBox", "<init>", "()V", false);
        main.visitVarInsn(ASTORE, 1);
        main.visitVarInsn(ALOAD, 1);
        main.visitIntInsn(BIPUSH, 42);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/VolatileBox", "write", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/VolatileBox", "read", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] monitorOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/MonitorOps", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "counter", "I", null, null).visitEnd();
        defaultConstructor(writer);

        MethodVisitor reset = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "reset", "()V", null, null);
        reset.visitCode();
        reset.visitInsn(ICONST_0);
        reset.visitFieldInsn(PUTSTATIC, "pkg/MonitorOps", "counter", "I");
        reset.visitInsn(RETURN);
        reset.visitMaxs(0, 0);
        reset.visitEnd();

        MethodVisitor read = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "()I", null, null);
        read.visitCode();
        read.visitFieldInsn(GETSTATIC, "pkg/MonitorOps", "counter", "I");
        read.visitInsn(IRETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        MethodVisitor inc = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "inc", "(Ljava/lang/Object;)V", null, null);
        inc.visitCode();
        inc.visitVarInsn(ALOAD, 0);
        inc.visitInsn(MONITORENTER);
        inc.visitFieldInsn(GETSTATIC, "pkg/MonitorOps", "counter", "I");
        inc.visitInsn(ICONST_1);
        inc.visitInsn(IADD);
        inc.visitFieldInsn(PUTSTATIC, "pkg/MonitorOps", "counter", "I");
        inc.visitVarInsn(ALOAD, 0);
        inc.visitInsn(MONITOREXIT);
        inc.visitInsn(RETURN);
        inc.visitMaxs(0, 0);
        inc.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] monitorWorkerClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/MonitorWorker", null, "java/lang/Object", new String[] {"java/lang/Runnable"});
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "lock", "Ljava/lang/Object;", null, null).visitEnd();
        defaultConstructor(writer);

        MethodVisitor run = writer.visitMethod(ACC_PUBLIC, "run", "()V", null, null);
        org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        run.visitCode();
        run.visitInsn(ICONST_0);
        run.visitVarInsn(ISTORE, 1);
        run.visitLabel(loop);
        run.visitVarInsn(ILOAD, 1);
        run.visitIntInsn(SIPUSH, 1000);
        run.visitJumpInsn(IF_ICMPGE, done);
        run.visitFieldInsn(GETSTATIC, "pkg/MonitorWorker", "lock", "Ljava/lang/Object;");
        run.visitMethodInsn(INVOKESTATIC, "pkg/MonitorOps", "inc", "(Ljava/lang/Object;)V", false);
        run.visitIincInsn(1, 1);
        run.visitJumpInsn(GOTO, loop);
        run.visitLabel(done);
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] monitorMainClass() {
        ClassWriter writer = mainClass("pkg/MonitorMain");
        MethodVisitor main = beginMain(writer);
        main.visitTypeInsn(NEW, "java/lang/Object");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        main.visitFieldInsn(PUTSTATIC, "pkg/MonitorWorker", "lock", "Ljava/lang/Object;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/MonitorOps", "reset", "()V", false);
        newThreadWithWorker(main);
        main.visitVarInsn(ASTORE, 1);
        newThreadWithWorker(main);
        main.visitVarInsn(ASTORE, 2);
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
        main.visitVarInsn(ALOAD, 2);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "join", "()V", false);
        main.visitVarInsn(ALOAD, 2);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "join", "()V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/MonitorOps", "read", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void newThreadWithWorker(MethodVisitor method) {
        method.visitTypeInsn(NEW, "java/lang/Thread");
        method.visitInsn(DUP);
        method.visitTypeInsn(NEW, "pkg/MonitorWorker");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "pkg/MonitorWorker", "<init>", "()V", false);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Thread", "<init>", "(Ljava/lang/Runnable;)V", false);
    }

    private byte[] syncMethodOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/SyncMethodOps", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "staticCounter", "I", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "value", "I", null, null).visitEnd();
        defaultConstructor(writer);

        MethodVisitor add = writer.visitMethod(ACC_PUBLIC | ACC_SYNCHRONIZED, "add", "(I)V", null, null);
        add.visitCode();
        add.visitVarInsn(ALOAD, 0);
        add.visitInsn(DUP);
        add.visitFieldInsn(GETFIELD, "pkg/SyncMethodOps", "value", "I");
        add.visitVarInsn(ILOAD, 1);
        add.visitInsn(IADD);
        add.visitFieldInsn(PUTFIELD, "pkg/SyncMethodOps", "value", "I");
        add.visitInsn(RETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();

        MethodVisitor value = writer.visitMethod(ACC_PUBLIC | ACC_SYNCHRONIZED, "value", "()I", null, null);
        value.visitCode();
        value.visitVarInsn(ALOAD, 0);
        value.visitFieldInsn(GETFIELD, "pkg/SyncMethodOps", "value", "I");
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();

        MethodVisitor addStatic = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED,
                "addStatic",
                "(I)V",
                null,
                null);
        addStatic.visitCode();
        addStatic.visitFieldInsn(GETSTATIC, "pkg/SyncMethodOps", "staticCounter", "I");
        addStatic.visitVarInsn(ILOAD, 0);
        addStatic.visitInsn(IADD);
        addStatic.visitFieldInsn(PUTSTATIC, "pkg/SyncMethodOps", "staticCounter", "I");
        addStatic.visitInsn(RETURN);
        addStatic.visitMaxs(0, 0);
        addStatic.visitEnd();

        MethodVisitor staticValue = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED,
                "staticValue",
                "()I",
                null,
                null);
        staticValue.visitCode();
        staticValue.visitFieldInsn(GETSTATIC, "pkg/SyncMethodOps", "staticCounter", "I");
        staticValue.visitInsn(IRETURN);
        staticValue.visitMaxs(0, 0);
        staticValue.visitEnd();

        MethodVisitor fail = writer.visitMethod(
                ACC_PUBLIC | ACC_SYNCHRONIZED,
                "fail",
                "(Ljava/lang/RuntimeException;)V",
                null,
                null);
        fail.visitCode();
        fail.visitVarInsn(ALOAD, 1);
        fail.visitInsn(ATHROW);
        fail.visitMaxs(0, 0);
        fail.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] syncMethodMainClass() {
        ClassWriter writer = mainClass("pkg/SyncMethodMain");
        MethodVisitor main = beginMain(writer);
        main.visitTypeInsn(NEW, "pkg/SyncMethodOps");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/SyncMethodOps", "<init>", "()V", false);
        main.visitVarInsn(ASTORE, 1);
        main.visitVarInsn(ALOAD, 1);
        main.visitInsn(ICONST_3);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/SyncMethodOps", "add", "(I)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitInsn(ICONST_4);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/SyncMethodOps", "add", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/SyncMethodOps", "value", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitInsn(ICONST_5);
        main.visitMethodInsn(INVOKESTATIC, "pkg/SyncMethodOps", "addStatic", "(I)V", false);
        main.visitIntInsn(BIPUSH, 6);
        main.visitMethodInsn(INVOKESTATIC, "pkg/SyncMethodOps", "addStatic", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/SyncMethodOps", "staticValue", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);

        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label after = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/RuntimeException");
        main.visitLabel(tryStart);
        main.visitVarInsn(ALOAD, 1);
        main.visitTypeInsn(NEW, "java/lang/RuntimeException");
        main.visitInsn(DUP);
        main.visitLdcInsn("boom");
        main.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "pkg/SyncMethodOps",
                "fail",
                "(Ljava/lang/RuntimeException;)V",
                false);
        main.visitLabel(tryEnd);
        main.visitJumpInsn(GOTO, after);
        main.visitLabel(handler);
        main.visitVarInsn(ASTORE, 2);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 2);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/RuntimeException", "getMessage", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(after);
        main.visitVarInsn(ALOAD, 1);
        main.visitInsn(ICONST_1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/SyncMethodOps", "add", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/SyncMethodOps", "value", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] exceptionBridgeOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ExceptionBridgeOps", null, "java/lang/Object", null);
        defaultConstructor(writer);

        MethodVisitor runtime = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "throwRuntime", "()V", null, null);
        runtime.visitCode();
        runtime.visitTypeInsn(NEW, "java/lang/RuntimeException");
        runtime.visitInsn(DUP);
        runtime.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false);
        runtime.visitInsn(ATHROW);
        runtime.visitMaxs(0, 0);
        runtime.visitEnd();

        MethodVisitor illegal = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "throwIllegal", "()V", null, null);
        illegal.visitCode();
        illegal.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        illegal.visitInsn(DUP);
        illegal.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
        illegal.visitInsn(ATHROW);
        illegal.visitMaxs(0, 0);
        illegal.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] exceptionBridgeMainClass() {
        ClassWriter writer = mainClass("pkg/ExceptionBridgeMain");
        MethodVisitor main = beginMain(writer);

        org.objectweb.asm.Label runtimeStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label runtimeEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label runtimeHandler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label afterRuntime = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(runtimeStart, runtimeEnd, runtimeHandler, "java/lang/RuntimeException");
        main.visitLabel(runtimeStart);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ExceptionBridgeOps", "throwRuntime", "()V", false);
        main.visitLabel(runtimeEnd);
        main.visitJumpInsn(GOTO, afterRuntime);
        main.visitLabel(runtimeHandler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("RuntimeException");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(afterRuntime);

        org.objectweb.asm.Label illegalStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label illegalEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label illegalHandler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label afterIllegal = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(illegalStart, illegalEnd, illegalHandler, "java/lang/IllegalArgumentException");
        main.visitLabel(illegalStart);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ExceptionBridgeOps", "throwIllegal", "()V", false);
        main.visitLabel(illegalEnd);
        main.visitJumpInsn(GOTO, afterIllegal);
        main.visitLabel(illegalHandler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("IllegalArgumentException");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(afterIllegal);

        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] throwableOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ThrowableOps", null, "java/lang/Object", null);
        defaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "messageAndCause",
                "()Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitTypeInsn(NEW, "java/lang/RuntimeException");
        method.visitInsn(DUP);
        method.visitLdcInsn("outer");
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false);
        method.visitVarInsn(ASTORE, 0);
        method.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        method.visitInsn(DUP);
        method.visitLdcInsn("cause");
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false);
        method.visitVarInsn(ASTORE, 1);
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Throwable",
                "initCause",
                "(Ljava/lang/Throwable;)Ljava/lang/Throwable;",
                false);
        method.visitInsn(POP);
        method.visitTypeInsn(NEW, "java/lang/StringBuilder");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "getMessage", "()Ljava/lang/String;", false);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        method.visitLdcInsn(":");
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "getCause", "()Ljava/lang/Throwable;", false);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "getMessage", "()Ljava/lang/String;", false);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] throwableMainClass() {
        ClassWriter writer = mainClass("pkg/ThrowableMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ThrowableOps", "messageAndCause", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] threadOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ThreadOps", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "value", "I", null, null).visitEnd();
        defaultConstructor(writer);

        MethodVisitor runThread = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "runThread", "()I", null, null);
        runThread.visitCode();
        runThread.visitInsn(ICONST_0);
        runThread.visitFieldInsn(PUTSTATIC, "pkg/ThreadOps", "value", "I");
        runThread.visitTypeInsn(NEW, "java/lang/Thread");
        runThread.visitInsn(DUP);
        runThread.visitTypeInsn(NEW, "pkg/ThreadOps$Worker");
        runThread.visitInsn(DUP);
        runThread.visitMethodInsn(INVOKESPECIAL, "pkg/ThreadOps$Worker", "<init>", "()V", false);
        runThread.visitMethodInsn(INVOKESPECIAL, "java/lang/Thread", "<init>", "(Ljava/lang/Runnable;)V", false);
        runThread.visitVarInsn(ASTORE, 0);
        runThread.visitVarInsn(ALOAD, 0);
        runThread.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
        runThread.visitVarInsn(ALOAD, 0);
        runThread.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "join", "()V", false);
        runThread.visitFieldInsn(GETSTATIC, "pkg/ThreadOps", "value", "I");
        runThread.visitInsn(IRETURN);
        runThread.visitMaxs(0, 0);
        runThread.visitEnd();

        MethodVisitor waitNotify = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "waitNotify", "()Ljava/lang/String;", null, null);
        waitNotify.visitCode();
        waitNotify.visitTypeInsn(NEW, "java/lang/Object");
        waitNotify.visitInsn(DUP);
        waitNotify.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        waitNotify.visitVarInsn(ASTORE, 0);
        waitNotify.visitVarInsn(ALOAD, 0);
        waitNotify.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "notify", "()V", false);
        waitNotify.visitVarInsn(ALOAD, 0);
        waitNotify.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "wait", "()V", false);
        waitNotify.visitLdcInsn("waited");
        waitNotify.visitInsn(ARETURN);
        waitNotify.visitMaxs(0, 0);
        waitNotify.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] threadWorkerClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                "pkg/ThreadOps$Worker",
                null,
                "java/lang/Object",
                new String[] {"java/lang/Runnable"});
        defaultConstructor(writer);
        MethodVisitor run = writer.visitMethod(ACC_PUBLIC, "run", "()V", null, null);
        run.visitCode();
        run.visitIntInsn(BIPUSH, 7);
        run.visitFieldInsn(PUTSTATIC, "pkg/ThreadOps", "value", "I");
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] threadMainClass() {
        ClassWriter writer = mainClass("pkg/ThreadMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ThreadOps", "runThread", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/IllegalMonitorStateException");
        main.visitLabel(start);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ThreadOps", "waitNotify", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("wait-boundary");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] reflectionTargetClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ReflectionTarget", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "count", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "note", "Ljava/lang/String;", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "flag", "Z", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "big", "J", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "ratio", "D", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "staticBig", "J", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "staticNote", "Ljava/lang/String;", null, null).visitEnd();
        defaultConstructor(writer);
        MethodVisitor stringConstructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        stringConstructor.visitCode();
        stringConstructor.visitVarInsn(ALOAD, 0);
        stringConstructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        stringConstructor.visitVarInsn(ALOAD, 0);
        stringConstructor.visitVarInsn(ALOAD, 1);
        stringConstructor.visitFieldInsn(PUTFIELD, "pkg/ReflectionTarget", "note", "Ljava/lang/String;");
        stringConstructor.visitInsn(RETURN);
        stringConstructor.visitMaxs(0, 0);
        stringConstructor.visitEnd();
        MethodVisitor objectConstructor = writer.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/lang/Object;)V", null, null);
        objectConstructor.visitCode();
        objectConstructor.visitVarInsn(ALOAD, 0);
        objectConstructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        objectConstructor.visitVarInsn(ALOAD, 0);
        objectConstructor.visitVarInsn(ALOAD, 1);
        objectConstructor.visitTypeInsn(CHECKCAST, "java/lang/String");
        objectConstructor.visitFieldInsn(PUTFIELD, "pkg/ReflectionTarget", "note", "Ljava/lang/String;");
        objectConstructor.visitInsn(RETURN);
        objectConstructor.visitMaxs(0, 0);
        objectConstructor.visitEnd();
        MethodVisitor intStringConstructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(ILjava/lang/String;)V", null, null);
        intStringConstructor.visitCode();
        intStringConstructor.visitVarInsn(ALOAD, 0);
        intStringConstructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        intStringConstructor.visitVarInsn(ALOAD, 0);
        intStringConstructor.visitVarInsn(ALOAD, 2);
        intStringConstructor.visitLdcInsn(":");
        intStringConstructor.visitVarInsn(ILOAD, 1);
        intStringConstructor.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001\u0001");
        intStringConstructor.visitFieldInsn(PUTFIELD, "pkg/ReflectionTarget", "note", "Ljava/lang/String;");
        intStringConstructor.visitInsn(RETURN);
        intStringConstructor.visitMaxs(0, 0);
        intStringConstructor.visitEnd();

        MethodVisitor greet = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "greet",
                "()Ljava/lang/String;",
                null,
                null);
        greet.visitCode();
        greet.visitLdcInsn("hello");
        greet.visitInsn(ARETURN);
        greet.visitMaxs(0, 0);
        greet.visitEnd();
        MethodVisitor greetArg = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "greetArg",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        greetArg.visitCode();
        greetArg.visitLdcInsn("static:");
        greetArg.visitVarInsn(ALOAD, 0);
        greetArg.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001");
        greetArg.visitInsn(ARETURN);
        greetArg.visitMaxs(0, 0);
        greetArg.visitEnd();

        MethodVisitor label = writer.visitMethod(ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null);
        label.visitCode();
        label.visitLdcInsn("target");
        label.visitInsn(ARETURN);
        label.visitMaxs(0, 0);
        label.visitEnd();
        MethodVisitor note = writer.visitMethod(ACC_PUBLIC, "note", "()Ljava/lang/String;", null, null);
        note.visitCode();
        note.visitVarInsn(ALOAD, 0);
        note.visitFieldInsn(GETFIELD, "pkg/ReflectionTarget", "note", "Ljava/lang/String;");
        note.visitInsn(ARETURN);
        note.visitMaxs(0, 0);
        note.visitEnd();
        MethodVisitor prefix = writer.visitMethod(
                ACC_PUBLIC,
                "prefix",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        prefix.visitCode();
        prefix.visitLdcInsn("target:");
        prefix.visitVarInsn(ALOAD, 1);
        prefix.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001");
        prefix.visitInsn(ARETURN);
        prefix.visitMaxs(0, 0);
        prefix.visitEnd();
        MethodVisitor secret = writer.visitMethod(
                ACC_PRIVATE,
                "secret",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        secret.visitCode();
        secret.visitLdcInsn("private:");
        secret.visitVarInsn(ALOAD, 1);
        secret.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001");
        secret.visitInsn(ARETURN);
        secret.visitMaxs(0, 0);
        secret.visitEnd();
        MethodVisitor primitive = writer.visitMethod(
                ACC_PRIVATE,
                "primitive",
                "(IJ)I",
                null,
                null);
        primitive.visitCode();
        primitive.visitVarInsn(ILOAD, 1);
        primitive.visitVarInsn(LLOAD, 2);
        primitive.visitInsn(L2I);
        primitive.visitInsn(IADD);
        primitive.visitInsn(IRETURN);
        primitive.visitMaxs(0, 0);
        primitive.visitEnd();
        MethodVisitor arrayLabel = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "arrayLabel",
                "([I)Ljava/lang/String;",
                null,
                null);
        arrayLabel.visitCode();
        arrayLabel.visitLdcInsn("len=");
        arrayLabel.visitVarInsn(ALOAD, 0);
        arrayLabel.visitInsn(ARRAYLENGTH);
        arrayLabel.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001");
        arrayLabel.visitInsn(ARETURN);
        arrayLabel.visitMaxs(0, 0);
        arrayLabel.visitEnd();
        MethodVisitor setNotePrivate = writer.visitMethod(
                ACC_PRIVATE,
                "setNotePrivate",
                "(Ljava/lang/String;)V",
                null,
                null);
        setNotePrivate.visitCode();
        setNotePrivate.visitVarInsn(ALOAD, 0);
        setNotePrivate.visitVarInsn(ALOAD, 1);
        setNotePrivate.visitFieldInsn(PUTFIELD, "pkg/ReflectionTarget", "note", "Ljava/lang/String;");
        setNotePrivate.visitInsn(RETURN);
        setNotePrivate.visitMaxs(0, 0);
        setNotePrivate.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] reflectionOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ReflectionOps", null, "java/lang/Object", null);
        defaultConstructor(writer);

        MethodVisitor forName = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "forName",
                "()Ljava/lang/Class;",
                null,
                new String[] {"java/lang/Exception"});
        forName.visitCode();
        forName.visitLdcInsn("pkg.ReflectionTarget");
        forName.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                false);
        forName.visitInsn(ARETURN);
        forName.visitMaxs(0, 0);
        forName.visitEnd();

        MethodVisitor invokeStatic = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "invokeStatic",
                "()Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        invokeStatic.visitCode();
        invokeStatic.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        invokeStatic.visitLdcInsn("greet");
        invokeStatic.visitInsn(ICONST_0);
        invokeStatic.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        invokeStatic.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        invokeStatic.visitInsn(ACONST_NULL);
        invokeStatic.visitInsn(ICONST_0);
        invokeStatic.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        invokeStatic.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        invokeStatic.visitTypeInsn(CHECKCAST, "java/lang/String");
        invokeStatic.visitInsn(ARETURN);
        invokeStatic.visitMaxs(0, 0);
        invokeStatic.visitEnd();

        MethodVisitor invokeStaticArg = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "invokeStaticArg",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        invokeStaticArg.visitCode();
        invokeStaticArg.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        invokeStaticArg.visitLdcInsn("greetArg");
        classArrayOfString(invokeStaticArg);
        invokeStaticArg.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        invokeStaticArg.visitInsn(ACONST_NULL);
        invokeStaticArg.visitInsn(ICONST_1);
        invokeStaticArg.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        invokeStaticArg.visitInsn(DUP);
        invokeStaticArg.visitInsn(ICONST_0);
        invokeStaticArg.visitVarInsn(ALOAD, 0);
        invokeStaticArg.visitInsn(AASTORE);
        invokeStaticArg.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        invokeStaticArg.visitTypeInsn(CHECKCAST, "java/lang/String");
        invokeStaticArg.visitInsn(ARETURN);
        invokeStaticArg.visitMaxs(0, 0);
        invokeStaticArg.visitEnd();

        MethodVisitor invokeInstanceArg = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "invokeInstanceArg",
                "(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        invokeInstanceArg.visitCode();
        invokeInstanceArg.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        invokeInstanceArg.visitLdcInsn("prefix");
        classArrayOfString(invokeInstanceArg);
        invokeInstanceArg.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        invokeInstanceArg.visitVarInsn(ALOAD, 0);
        invokeInstanceArg.visitInsn(ICONST_1);
        invokeInstanceArg.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        invokeInstanceArg.visitInsn(DUP);
        invokeInstanceArg.visitInsn(ICONST_0);
        invokeInstanceArg.visitVarInsn(ALOAD, 1);
        invokeInstanceArg.visitInsn(AASTORE);
        invokeInstanceArg.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        invokeInstanceArg.visitTypeInsn(CHECKCAST, "java/lang/String");
        invokeInstanceArg.visitInsn(ARETURN);
        invokeInstanceArg.visitMaxs(0, 0);
        invokeInstanceArg.visitEnd();

        MethodVisitor constructAndInvoke = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "constructAndInvoke",
                "()Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        constructAndInvoke.visitCode();
        constructAndInvoke.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        constructAndInvoke.visitInsn(ICONST_0);
        constructAndInvoke.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        constructAndInvoke.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);
        constructAndInvoke.visitInsn(ICONST_0);
        constructAndInvoke.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        constructAndInvoke.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        constructAndInvoke.visitTypeInsn(CHECKCAST, "pkg/ReflectionTarget");
        constructAndInvoke.visitVarInsn(ASTORE, 0);
        constructAndInvoke.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        constructAndInvoke.visitLdcInsn("label");
        constructAndInvoke.visitInsn(ICONST_0);
        constructAndInvoke.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        constructAndInvoke.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        constructAndInvoke.visitVarInsn(ALOAD, 0);
        constructAndInvoke.visitInsn(ICONST_0);
        constructAndInvoke.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        constructAndInvoke.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        constructAndInvoke.visitTypeInsn(CHECKCAST, "java/lang/String");
        constructAndInvoke.visitInsn(ARETURN);
        constructAndInvoke.visitMaxs(0, 0);
        constructAndInvoke.visitEnd();

        MethodVisitor constructWithArg = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "constructWithArg",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        constructWithArg.visitCode();
        constructWithArg.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        classArrayOfString(constructWithArg);
        constructWithArg.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);
        constructWithArg.visitInsn(ICONST_1);
        constructWithArg.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        constructWithArg.visitInsn(DUP);
        constructWithArg.visitInsn(ICONST_0);
        constructWithArg.visitVarInsn(ALOAD, 0);
        constructWithArg.visitInsn(AASTORE);
        constructWithArg.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        constructWithArg.visitTypeInsn(CHECKCAST, "pkg/ReflectionTarget");
        constructWithArg.visitMethodInsn(INVOKEVIRTUAL, "pkg/ReflectionTarget", "note", "()Ljava/lang/String;", false);
        constructWithArg.visitInsn(ARETURN);
        constructWithArg.visitMaxs(0, 0);
        constructWithArg.visitEnd();

        MethodVisitor privateMethodAccessible = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "privateMethodAccessible",
                "(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        privateMethodAccessible.visitCode();
        privateMethodAccessible.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        privateMethodAccessible.visitLdcInsn("secret");
        classArrayOfString(privateMethodAccessible);
        privateMethodAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        privateMethodAccessible.visitVarInsn(ASTORE, 2);
        privateMethodAccessible.visitVarInsn(ALOAD, 2);
        privateMethodAccessible.visitInsn(ICONST_1);
        privateMethodAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "setAccessible",
                "(Z)V",
                false);
        privateMethodAccessible.visitVarInsn(ALOAD, 2);
        privateMethodAccessible.visitVarInsn(ALOAD, 0);
        privateMethodAccessible.visitInsn(ICONST_1);
        privateMethodAccessible.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        privateMethodAccessible.visitInsn(DUP);
        privateMethodAccessible.visitInsn(ICONST_0);
        privateMethodAccessible.visitVarInsn(ALOAD, 1);
        privateMethodAccessible.visitInsn(AASTORE);
        privateMethodAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        privateMethodAccessible.visitTypeInsn(CHECKCAST, "java/lang/String");
        privateMethodAccessible.visitInsn(ARETURN);
        privateMethodAccessible.visitMaxs(0, 0);
        privateMethodAccessible.visitEnd();

        MethodVisitor privateVoidAccessible = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "privateVoidAccessible",
                "(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        privateVoidAccessible.visitCode();
        privateVoidAccessible.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        privateVoidAccessible.visitLdcInsn("setNotePrivate");
        classArrayOfString(privateVoidAccessible);
        privateVoidAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        privateVoidAccessible.visitVarInsn(ASTORE, 2);
        privateVoidAccessible.visitVarInsn(ALOAD, 2);
        privateVoidAccessible.visitInsn(ICONST_1);
        privateVoidAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "setAccessible",
                "(Z)V",
                false);
        privateVoidAccessible.visitVarInsn(ALOAD, 2);
        privateVoidAccessible.visitVarInsn(ALOAD, 0);
        privateVoidAccessible.visitInsn(ICONST_1);
        privateVoidAccessible.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        privateVoidAccessible.visitInsn(DUP);
        privateVoidAccessible.visitInsn(ICONST_0);
        privateVoidAccessible.visitVarInsn(ALOAD, 1);
        privateVoidAccessible.visitInsn(AASTORE);
        privateVoidAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        privateVoidAccessible.visitTypeInsn(CHECKCAST, "java/lang/String");
        privateVoidAccessible.visitInsn(ARETURN);
        privateVoidAccessible.visitMaxs(0, 0);
        privateVoidAccessible.visitEnd();

        MethodVisitor privateConstructorAccessible = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "privateConstructorAccessible",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        privateConstructorAccessible.visitCode();
        privateConstructorAccessible.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        classArrayOfObject(privateConstructorAccessible);
        privateConstructorAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);
        privateConstructorAccessible.visitVarInsn(ASTORE, 1);
        privateConstructorAccessible.visitVarInsn(ALOAD, 1);
        privateConstructorAccessible.visitInsn(ICONST_1);
        privateConstructorAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "setAccessible",
                "(Z)V",
                false);
        privateConstructorAccessible.visitVarInsn(ALOAD, 1);
        privateConstructorAccessible.visitInsn(ICONST_1);
        privateConstructorAccessible.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        privateConstructorAccessible.visitInsn(DUP);
        privateConstructorAccessible.visitInsn(ICONST_0);
        privateConstructorAccessible.visitVarInsn(ALOAD, 0);
        privateConstructorAccessible.visitInsn(AASTORE);
        privateConstructorAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        privateConstructorAccessible.visitTypeInsn(CHECKCAST, "pkg/ReflectionTarget");
        privateConstructorAccessible.visitMethodInsn(INVOKEVIRTUAL, "pkg/ReflectionTarget", "note", "()Ljava/lang/String;", false);
        privateConstructorAccessible.visitInsn(ARETURN);
        privateConstructorAccessible.visitMaxs(0, 0);
        privateConstructorAccessible.visitEnd();

        MethodVisitor privatePrimitiveAccessible = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "privatePrimitiveAccessible",
                "(Lpkg/ReflectionTarget;IJ)I",
                null,
                new String[] {"java/lang/Exception"});
        privatePrimitiveAccessible.visitCode();
        privatePrimitiveAccessible.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        privatePrimitiveAccessible.visitLdcInsn("primitive");
        classArrayOfIntegerAndLong(privatePrimitiveAccessible);
        privatePrimitiveAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        privatePrimitiveAccessible.visitVarInsn(ASTORE, 4);
        privatePrimitiveAccessible.visitVarInsn(ALOAD, 4);
        privatePrimitiveAccessible.visitInsn(ICONST_1);
        privatePrimitiveAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "setAccessible",
                "(Z)V",
                false);
        privatePrimitiveAccessible.visitVarInsn(ALOAD, 4);
        privatePrimitiveAccessible.visitVarInsn(ALOAD, 0);
        privatePrimitiveAccessible.visitInsn(ICONST_2);
        privatePrimitiveAccessible.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        privatePrimitiveAccessible.visitInsn(DUP);
        privatePrimitiveAccessible.visitInsn(ICONST_0);
        privatePrimitiveAccessible.visitVarInsn(ILOAD, 1);
        privatePrimitiveAccessible.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        privatePrimitiveAccessible.visitInsn(AASTORE);
        privatePrimitiveAccessible.visitInsn(DUP);
        privatePrimitiveAccessible.visitInsn(ICONST_1);
        privatePrimitiveAccessible.visitVarInsn(LLOAD, 2);
        privatePrimitiveAccessible.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
        privatePrimitiveAccessible.visitInsn(AASTORE);
        privatePrimitiveAccessible.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        privatePrimitiveAccessible.visitTypeInsn(CHECKCAST, "java/lang/Integer");
        privatePrimitiveAccessible.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
        privatePrimitiveAccessible.visitInsn(IRETURN);
        privatePrimitiveAccessible.visitMaxs(0, 0);
        privatePrimitiveAccessible.visitEnd();

        MethodVisitor refReturn = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "refReturn",
                "(Lpkg/ReflectionTarget;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        refReturn.visitCode();
        refReturn.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        refReturn.visitLdcInsn("label");
        refReturn.visitInsn(ICONST_0);
        refReturn.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        refReturn.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        refReturn.visitVarInsn(ALOAD, 0);
        refReturn.visitInsn(ICONST_0);
        refReturn.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        refReturn.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        refReturn.visitTypeInsn(CHECKCAST, "java/lang/String");
        refReturn.visitInsn(ARETURN);
        refReturn.visitMaxs(0, 0);
        refReturn.visitEnd();

        MethodVisitor constructPrimitiveAndRef = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "constructPrimitiveAndRef",
                "(ILjava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        constructPrimitiveAndRef.visitCode();
        constructPrimitiveAndRef.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        classArrayOfIntegerAndString(constructPrimitiveAndRef);
        constructPrimitiveAndRef.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);
        constructPrimitiveAndRef.visitInsn(ICONST_2);
        constructPrimitiveAndRef.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        constructPrimitiveAndRef.visitInsn(DUP);
        constructPrimitiveAndRef.visitInsn(ICONST_0);
        constructPrimitiveAndRef.visitVarInsn(ILOAD, 0);
        constructPrimitiveAndRef.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        constructPrimitiveAndRef.visitInsn(AASTORE);
        constructPrimitiveAndRef.visitInsn(DUP);
        constructPrimitiveAndRef.visitInsn(ICONST_1);
        constructPrimitiveAndRef.visitVarInsn(ALOAD, 1);
        constructPrimitiveAndRef.visitInsn(AASTORE);
        constructPrimitiveAndRef.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        constructPrimitiveAndRef.visitTypeInsn(CHECKCAST, "pkg/ReflectionTarget");
        constructPrimitiveAndRef.visitMethodInsn(INVOKEVIRTUAL, "pkg/ReflectionTarget", "note", "()Ljava/lang/String;", false);
        constructPrimitiveAndRef.visitInsn(ARETURN);
        constructPrimitiveAndRef.visitMaxs(0, 0);
        constructPrimitiveAndRef.visitEnd();

        MethodVisitor arrayArg = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "arrayArg",
                "([I)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        arrayArg.visitCode();
        arrayArg.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        arrayArg.visitLdcInsn("arrayLabel");
        classArrayOfIntArray(arrayArg);
        arrayArg.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        arrayArg.visitInsn(ACONST_NULL);
        arrayArg.visitInsn(ICONST_1);
        arrayArg.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        arrayArg.visitInsn(DUP);
        arrayArg.visitInsn(ICONST_0);
        arrayArg.visitVarInsn(ALOAD, 0);
        arrayArg.visitInsn(AASTORE);
        arrayArg.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        arrayArg.visitTypeInsn(CHECKCAST, "java/lang/String");
        arrayArg.visitInsn(ARETURN);
        arrayArg.visitMaxs(0, 0);
        arrayArg.visitEnd();

        MethodVisitor fieldInt = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "fieldInt",
                "(Lpkg/ReflectionTarget;)I",
                null,
                new String[] {"java/lang/Exception"});
        fieldInt.visitCode();
        fieldInt.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        fieldInt.visitLdcInsn("count");
        fieldInt.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        fieldInt.visitVarInsn(ASTORE, 1);
        fieldInt.visitVarInsn(ALOAD, 1);
        fieldInt.visitVarInsn(ALOAD, 0);
        fieldInt.visitIntInsn(BIPUSH, 41);
        fieldInt.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "setInt",
                "(Ljava/lang/Object;I)V",
                false);
        fieldInt.visitVarInsn(ALOAD, 1);
        fieldInt.visitVarInsn(ALOAD, 0);
        fieldInt.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "getInt",
                "(Ljava/lang/Object;)I",
                false);
        fieldInt.visitInsn(IRETURN);
        fieldInt.visitMaxs(0, 0);
        fieldInt.visitEnd();

        MethodVisitor fieldRef = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "fieldRef",
                "(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        fieldRef.visitCode();
        fieldRef.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        fieldRef.visitLdcInsn("note");
        fieldRef.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        fieldRef.visitVarInsn(ASTORE, 2);
        fieldRef.visitVarInsn(ALOAD, 2);
        fieldRef.visitVarInsn(ALOAD, 0);
        fieldRef.visitVarInsn(ALOAD, 1);
        fieldRef.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "set",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                false);
        fieldRef.visitVarInsn(ALOAD, 2);
        fieldRef.visitVarInsn(ALOAD, 0);
        fieldRef.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        fieldRef.visitTypeInsn(CHECKCAST, "java/lang/String");
        fieldRef.visitInsn(ARETURN);
        fieldRef.visitMaxs(0, 0);
        fieldRef.visitEnd();

        MethodVisitor fieldBoolean = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "fieldBoolean",
                "(Lpkg/ReflectionTarget;)Z",
                null,
                new String[] {"java/lang/Exception"});
        fieldBoolean.visitCode();
        fieldBoolean.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        fieldBoolean.visitLdcInsn("flag");
        fieldBoolean.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        fieldBoolean.visitVarInsn(ASTORE, 1);
        fieldBoolean.visitVarInsn(ALOAD, 1);
        fieldBoolean.visitVarInsn(ALOAD, 0);
        fieldBoolean.visitInsn(ICONST_1);
        fieldBoolean.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "setBoolean",
                "(Ljava/lang/Object;Z)V",
                false);
        fieldBoolean.visitVarInsn(ALOAD, 1);
        fieldBoolean.visitVarInsn(ALOAD, 0);
        fieldBoolean.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "getBoolean",
                "(Ljava/lang/Object;)Z",
                false);
        fieldBoolean.visitInsn(IRETURN);
        fieldBoolean.visitMaxs(0, 0);
        fieldBoolean.visitEnd();

        MethodVisitor fieldLong = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "fieldLong",
                "(Lpkg/ReflectionTarget;)J",
                null,
                new String[] {"java/lang/Exception"});
        fieldLong.visitCode();
        fieldLong.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        fieldLong.visitLdcInsn("big");
        fieldLong.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        fieldLong.visitVarInsn(ASTORE, 1);
        fieldLong.visitVarInsn(ALOAD, 1);
        fieldLong.visitVarInsn(ALOAD, 0);
        fieldLong.visitLdcInsn(1234567890123L);
        fieldLong.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "setLong",
                "(Ljava/lang/Object;J)V",
                false);
        fieldLong.visitVarInsn(ALOAD, 1);
        fieldLong.visitVarInsn(ALOAD, 0);
        fieldLong.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "getLong",
                "(Ljava/lang/Object;)J",
                false);
        fieldLong.visitInsn(LRETURN);
        fieldLong.visitMaxs(0, 0);
        fieldLong.visitEnd();

        MethodVisitor fieldDouble = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "fieldDouble",
                "(Lpkg/ReflectionTarget;)D",
                null,
                new String[] {"java/lang/Exception"});
        fieldDouble.visitCode();
        fieldDouble.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        fieldDouble.visitLdcInsn("ratio");
        fieldDouble.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        fieldDouble.visitVarInsn(ASTORE, 1);
        fieldDouble.visitVarInsn(ALOAD, 1);
        fieldDouble.visitVarInsn(ALOAD, 0);
        fieldDouble.visitLdcInsn(2.5D);
        fieldDouble.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "setDouble",
                "(Ljava/lang/Object;D)V",
                false);
        fieldDouble.visitVarInsn(ALOAD, 1);
        fieldDouble.visitVarInsn(ALOAD, 0);
        fieldDouble.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "getDouble",
                "(Ljava/lang/Object;)D",
                false);
        fieldDouble.visitInsn(DRETURN);
        fieldDouble.visitMaxs(0, 0);
        fieldDouble.visitEnd();

        MethodVisitor staticLong = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "staticLong",
                "()J",
                null,
                new String[] {"java/lang/Exception"});
        staticLong.visitCode();
        staticLong.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        staticLong.visitLdcInsn("staticBig");
        staticLong.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        staticLong.visitVarInsn(ASTORE, 0);
        staticLong.visitVarInsn(ALOAD, 0);
        staticLong.visitInsn(ACONST_NULL);
        staticLong.visitLdcInsn(88L);
        staticLong.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "setLong",
                "(Ljava/lang/Object;J)V",
                false);
        staticLong.visitVarInsn(ALOAD, 0);
        staticLong.visitInsn(ACONST_NULL);
        staticLong.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "getLong",
                "(Ljava/lang/Object;)J",
                false);
        staticLong.visitInsn(LRETURN);
        staticLong.visitMaxs(0, 0);
        staticLong.visitEnd();

        MethodVisitor staticRef = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "staticRef",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        staticRef.visitCode();
        staticRef.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/ReflectionTarget"));
        staticRef.visitLdcInsn("staticNote");
        staticRef.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        staticRef.visitVarInsn(ASTORE, 1);
        staticRef.visitVarInsn(ALOAD, 1);
        staticRef.visitInsn(ACONST_NULL);
        staticRef.visitVarInsn(ALOAD, 0);
        staticRef.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "set",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                false);
        staticRef.visitVarInsn(ALOAD, 1);
        staticRef.visitInsn(ACONST_NULL);
        staticRef.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        staticRef.visitTypeInsn(CHECKCAST, "java/lang/String");
        staticRef.visitInsn(ARETURN);
        staticRef.visitMaxs(0, 0);
        staticRef.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] reflectionMainClass() {
        ClassWriter writer = mainClass("pkg/ReflectionMain");
        MethodVisitor main = beginMain(writer);
        main.visitTypeInsn(NEW, "pkg/ReflectionTarget");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/ReflectionTarget", "<init>", "()V", false);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReflectionOps", "forName", "()Ljava/lang/Class;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReflectionOps", "invokeStatic", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("arg");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "invokeStaticArg",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitLdcInsn("arg");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "invokeInstanceArg",
                "(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReflectionOps", "constructAndInvoke", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("made");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "constructWithArg",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitLdcInsn("arg");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "privateMethodAccessible",
                "(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitLdcInsn("voided");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "privateVoidAccessible",
                "(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("hidden");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "privateConstructorAccessible",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitIntInsn(BIPUSH, 10);
        main.visitLdcInsn(42L);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "privatePrimitiveAccessible",
                "(Lpkg/ReflectionTarget;IJ)I",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "refReturn",
                "(Lpkg/ReflectionTarget;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitIntInsn(BIPUSH, 7);
        main.visitLdcInsn("seven");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "constructPrimitiveAndRef",
                "(ILjava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        intArray(main, 4, 5, 6);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "arrayArg",
                "([I)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "fieldInt",
                "(Lpkg/ReflectionTarget;)I",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitLdcInsn("field");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "fieldRef",
                "(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "fieldBoolean",
                "(Lpkg/ReflectionTarget;)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "fieldLong",
                "(Lpkg/ReflectionTarget;)J",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(J)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "fieldDouble",
                "(Lpkg/ReflectionTarget;)D",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(D)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReflectionOps", "staticLong", "()J", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(J)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("static-ref");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectionOps",
                "staticRef",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] unsafeTargetClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/UnsafeTarget", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "value", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "constructed", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitIntInsn(BIPUSH, 7);
        constructor.visitFieldInsn(PUTFIELD, "pkg/UnsafeTarget", "value", "I");
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitInsn(ICONST_1);
        constructor.visitFieldInsn(PUTFIELD, "pkg/UnsafeTarget", "constructed", "I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] unsafeOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/UnsafeOps", null, "java/lang/Object", null);
        defaultConstructor(writer);

        MethodVisitor offset = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "offset",
                "(Lsun/misc/Unsafe;)J",
                null,
                new String[] {"java/lang/Exception"});
        offset.visitCode();
        offset.visitVarInsn(ALOAD, 0);
        offset.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/UnsafeTarget"));
        offset.visitLdcInsn("value");
        offset.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        offset.visitMethodInsn(
                INVOKEVIRTUAL,
                "sun/misc/Unsafe",
                "objectFieldOffset",
                "(Ljava/lang/reflect/Field;)J",
                false);
        offset.visitInsn(LRETURN);
        offset.visitMaxs(0, 0);
        offset.visitEnd();

        MethodVisitor read = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "read",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;J)I",
                null,
                null);
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitVarInsn(ALOAD, 1);
        read.visitVarInsn(LLOAD, 2);
        read.visitMethodInsn(
                INVOKEVIRTUAL,
                "sun/misc/Unsafe",
                "getInt",
                "(Ljava/lang/Object;J)I",
                false);
        read.visitInsn(IRETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        MethodVisitor write = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "write",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;JI)V",
                null,
                null);
        write.visitCode();
        write.visitVarInsn(ALOAD, 0);
        write.visitVarInsn(ALOAD, 1);
        write.visitVarInsn(LLOAD, 2);
        write.visitVarInsn(ILOAD, 4);
        write.visitMethodInsn(
                INVOKEVIRTUAL,
                "sun/misc/Unsafe",
                "putInt",
                "(Ljava/lang/Object;JI)V",
                false);
        write.visitInsn(RETURN);
        write.visitMaxs(0, 0);
        write.visitEnd();

        MethodVisitor cas = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "cas",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;JII)Z",
                null,
                null);
        cas.visitCode();
        cas.visitVarInsn(ALOAD, 0);
        cas.visitVarInsn(ALOAD, 1);
        cas.visitVarInsn(LLOAD, 2);
        cas.visitVarInsn(ILOAD, 4);
        cas.visitVarInsn(ILOAD, 5);
        cas.visitMethodInsn(
                INVOKEVIRTUAL,
                "sun/misc/Unsafe",
                "compareAndSwapInt",
                "(Ljava/lang/Object;JII)Z",
                false);
        cas.visitInsn(IRETURN);
        cas.visitMaxs(0, 0);
        cas.visitEnd();

        MethodVisitor allocate = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "allocate",
                "(Lsun/misc/Unsafe;)Lpkg/UnsafeTarget;",
                null,
                new String[] {"java/lang/InstantiationException"});
        allocate.visitCode();
        allocate.visitVarInsn(ALOAD, 0);
        allocate.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/UnsafeTarget"));
        allocate.visitMethodInsn(
                INVOKEVIRTUAL,
                "sun/misc/Unsafe",
                "allocateInstance",
                "(Ljava/lang/Class;)Ljava/lang/Object;",
                false);
        allocate.visitTypeInsn(CHECKCAST, "pkg/UnsafeTarget");
        allocate.visitInsn(ARETURN);
        allocate.visitMaxs(0, 0);
        allocate.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] unsafeMainClass() {
        ClassWriter writer = mainClass("pkg/UnsafeMain");
        MethodVisitor main = beginMain(writer);
        main.visitLdcInsn(org.objectweb.asm.Type.getObjectType("sun/misc/Unsafe"));
        main.visitLdcInsn("theUnsafe");
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        main.visitVarInsn(ASTORE, 1);
        main.visitVarInsn(ALOAD, 1);
        main.visitInsn(ICONST_1);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        main.visitTypeInsn(CHECKCAST, "sun/misc/Unsafe");
        main.visitVarInsn(ASTORE, 1);

        main.visitTypeInsn(NEW, "pkg/UnsafeTarget");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/UnsafeTarget", "<init>", "()V", false);
        main.visitVarInsn(ASTORE, 2);
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKESTATIC, "pkg/UnsafeOps", "offset", "(Lsun/misc/Unsafe;)J", false);
        main.visitVarInsn(LSTORE, 3);

        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitVarInsn(LLOAD, 3);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "read",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;J)I",
                false);
        printTopInt(main);

        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitVarInsn(LLOAD, 3);
        main.visitIntInsn(BIPUSH, 11);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "write",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;JI)V",
                false);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitVarInsn(LLOAD, 3);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "read",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;J)I",
                false);
        printTopInt(main);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitVarInsn(LLOAD, 3);
        main.visitIntInsn(BIPUSH, 11);
        main.visitIntInsn(BIPUSH, 13);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "cas",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;JII)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitVarInsn(LLOAD, 3);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "read",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;J)I",
                false);
        printTopInt(main);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitVarInsn(LLOAD, 3);
        main.visitIntInsn(BIPUSH, 11);
        main.visitIntInsn(BIPUSH, 99);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "cas",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;JII)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitVarInsn(LLOAD, 3);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "read",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;J)I",
                false);
        printTopInt(main);

        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "allocate",
                "(Lsun/misc/Unsafe;)Lpkg/UnsafeTarget;",
                false);
        main.visitVarInsn(ASTORE, 5);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 5);
        main.visitFieldInsn(GETFIELD, "pkg/UnsafeTarget", "constructed", "I");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 5);
        main.visitVarInsn(LLOAD, 3);
        main.visitIntInsn(BIPUSH, 21);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "write",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;JI)V",
                false);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 5);
        main.visitVarInsn(LLOAD, 3);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/UnsafeOps",
                "read",
                "(Lsun/misc/Unsafe;Lpkg/UnsafeTarget;J)I",
                false);
        printTopInt(main);

        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] varHandleTargetClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/VarHandleTarget", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "value", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitInsn(ICONST_3);
        constructor.visitFieldInsn(PUTFIELD, "pkg/VarHandleTarget", "value", "I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] varHandleMainClass() {
        ClassWriter writer = mainClass("pkg/VarHandleMain");
        MethodVisitor main = beginMain(writer);
        main.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;",
                false);
        main.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/VarHandleTarget"));
        main.visitLdcInsn("value");
        main.visitFieldInsn(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandles$Lookup",
                "findVarHandle",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
                false);
        main.visitVarInsn(ASTORE, 1);
        main.visitTypeInsn(NEW, "pkg/VarHandleTarget");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/VarHandleTarget", "<init>", "()V", false);
        main.visitVarInsn(ASTORE, 2);

        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/VarHandleOps",
                "getInt",
                "(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;)I",
                false);
        printTopInt(main);

        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitIntInsn(BIPUSH, 9);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/VarHandleOps",
                "setInt",
                "(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;I)V",
                false);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/VarHandleOps",
                "getInt",
                "(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;)I",
                false);
        printTopInt(main);

        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitIntInsn(BIPUSH, 12);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/VarHandleOps",
                "setVolatileInt",
                "(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;I)V",
                false);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/VarHandleOps",
                "getVolatileInt",
                "(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;)I",
                false);
        printTopInt(main);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitIntInsn(BIPUSH, 12);
        main.visitIntInsn(BIPUSH, 15);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/VarHandleOps",
                "compareAndSetInt",
                "(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;II)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/VarHandleOps",
                "getInt",
                "(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;)I",
                false);
        printTopInt(main);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitIntInsn(BIPUSH, 12);
        main.visitIntInsn(BIPUSH, 21);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/VarHandleOps",
                "compareAndSetInt",
                "(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;II)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/VarHandleOps",
                "getInt",
                "(Ljava/lang/invoke/VarHandle;Lpkg/VarHandleTarget;)I",
                false);
        printTopInt(main);

        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] divRemOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/DivRemOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        binary(writer, "div", "(II)I", ILOAD, IDIV, IRETURN);
        binary(writer, "rem", "(II)I", ILOAD, IREM, IRETURN);
        binary(writer, "ldiv", "(JJ)J", LLOAD, LDIV, LRETURN);
        binary(writer, "lrem", "(JJ)J", LLOAD, LREM, LRETURN);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] divRemMainClass() {
        ClassWriter writer = mainClass("pkg/DivRemMain");
        MethodVisitor main = beginMain(writer);
        printStaticIntPair(main, "pkg/DivRemOps", "div", 21, 3);
        printStaticIntPair(main, "pkg/DivRemOps", "rem", 22, 3);
        printStaticLong(main, "pkg/DivRemOps", "ldiv", 20L, 4L);
        printStaticLong(main, "pkg/DivRemOps", "lrem", 23L, 7L);
        printArithmeticCatch(main, "div");
        printArithmeticCatch(main, "rem");
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] referenceFieldOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ReferenceFieldOps", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "value", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "label", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(ILjava/lang/String;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitFieldInsn(PUTFIELD, "pkg/ReferenceFieldOps", "value", "I");
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ALOAD, 2);
        constructor.visitFieldInsn(PUTFIELD, "pkg/ReferenceFieldOps", "label", "Ljava/lang/String;");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor readValue = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "readValue",
                "(Lpkg/ReferenceFieldOps;)I",
                null,
                null);
        readValue.visitCode();
        readValue.visitVarInsn(ALOAD, 0);
        readValue.visitFieldInsn(GETFIELD, "pkg/ReferenceFieldOps", "value", "I");
        readValue.visitInsn(IRETURN);
        readValue.visitMaxs(0, 0);
        readValue.visitEnd();

        MethodVisitor readLabel = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "readLabel",
                "(Lpkg/ReferenceFieldOps;)Ljava/lang/String;",
                null,
                null);
        readLabel.visitCode();
        readLabel.visitVarInsn(ALOAD, 0);
        readLabel.visitFieldInsn(GETFIELD, "pkg/ReferenceFieldOps", "label", "Ljava/lang/String;");
        readLabel.visitInsn(ARETURN);
        readLabel.visitMaxs(0, 0);
        readLabel.visitEnd();

        MethodVisitor setLabel = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "setLabel",
                "(Lpkg/ReferenceFieldOps;Ljava/lang/String;)V",
                null,
                null);
        setLabel.visitCode();
        setLabel.visitVarInsn(ALOAD, 0);
        setLabel.visitVarInsn(ALOAD, 1);
        setLabel.visitFieldInsn(PUTFIELD, "pkg/ReferenceFieldOps", "label", "Ljava/lang/String;");
        setLabel.visitInsn(RETURN);
        setLabel.visitMaxs(0, 0);
        setLabel.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] referenceFieldMainClass() {
        ClassWriter writer = mainClass("pkg/ReferenceFieldMain");
        MethodVisitor main = beginMain(writer);
        main.visitTypeInsn(NEW, "pkg/ReferenceFieldOps");
        main.visitInsn(DUP);
        main.visitIntInsn(BIPUSH, 42);
        main.visitLdcInsn("hello");
        main.visitMethodInsn(INVOKESPECIAL, "pkg/ReferenceFieldOps", "<init>", "(ILjava/lang/String;)V", false);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReferenceFieldOps", "readValue", "(Lpkg/ReferenceFieldOps;)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReferenceFieldOps", "readLabel", "(Lpkg/ReferenceFieldOps;)Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReferenceFieldOps", "setLabel", "(Lpkg/ReferenceFieldOps;Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReferenceFieldOps", "readLabel", "(Lpkg/ReferenceFieldOps;)Ljava/lang/String;", false);
        org.objectweb.asm.Label notNull = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitJumpInsn(IFNONNULL, notNull);
        main.visitInsn(ICONST_1);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(notNull);
        main.visitInsn(ICONST_0);
        main.visitLabel(done);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        printNpeCatchForReferenceField(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] arrayHelperOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ArrayHelperOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor firstPlusLength = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "firstPlusLength", "([I)I", null, null);
        firstPlusLength.visitCode();
        firstPlusLength.visitVarInsn(ALOAD, 0);
        firstPlusLength.visitInsn(ARRAYLENGTH);
        firstPlusLength.visitVarInsn(ALOAD, 0);
        firstPlusLength.visitInsn(ICONST_0);
        firstPlusLength.visitInsn(IALOAD);
        firstPlusLength.visitInsn(IADD);
        firstPlusLength.visitInsn(IRETURN);
        firstPlusLength.visitMaxs(0, 0);
        firstPlusLength.visitEnd();

        MethodVisitor setFirst = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "setFirst", "([II)I", null, null);
        setFirst.visitCode();
        setFirst.visitVarInsn(ALOAD, 0);
        setFirst.visitInsn(ICONST_0);
        setFirst.visitVarInsn(ILOAD, 1);
        setFirst.visitInsn(IASTORE);
        setFirst.visitVarInsn(ALOAD, 0);
        setFirst.visitInsn(ICONST_0);
        setFirst.visitInsn(IALOAD);
        setFirst.visitInsn(IRETURN);
        setFirst.visitMaxs(0, 0);
        setFirst.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] arrayHelperMainClass() {
        ClassWriter writer = mainClass("pkg/ArrayHelperMain");
        MethodVisitor main = beginMain(writer);
        intArray(main, 4, 5);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArrayHelperOps", "firstPlusLength", "([I)I", false);
        printTopInt(main);
        intArray(main, 1, 2);
        main.visitIntInsn(BIPUSH, 9);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArrayHelperOps", "setFirst", "([II)I", false);
        printTopInt(main);
        printNpeCatchForArrayHelper(main);
        printArrayBoundsCatchForArrayHelper(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] referenceArrayOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ReferenceArrayOps", null, "java/lang/Object", null);
        defaultConstructor(writer);

        MethodVisitor byteRoundtrip = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "byteRoundtrip", "([B)I", null, null);
        byteRoundtrip.visitCode();
        byteRoundtrip.visitVarInsn(ALOAD, 0);
        byteRoundtrip.visitInsn(ICONST_0);
        byteRoundtrip.visitInsn(BALOAD);
        byteRoundtrip.visitVarInsn(ISTORE, 1);
        byteRoundtrip.visitVarInsn(ALOAD, 0);
        byteRoundtrip.visitInsn(ICONST_1);
        byteRoundtrip.visitVarInsn(ILOAD, 1);
        byteRoundtrip.visitInsn(ICONST_3);
        byteRoundtrip.visitInsn(IADD);
        byteRoundtrip.visitInsn(BASTORE);
        byteRoundtrip.visitVarInsn(ALOAD, 0);
        byteRoundtrip.visitInsn(ICONST_1);
        byteRoundtrip.visitInsn(BALOAD);
        byteRoundtrip.visitInsn(IRETURN);
        byteRoundtrip.visitMaxs(0, 0);
        byteRoundtrip.visitEnd();

        MethodVisitor byteAt = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "byteAt", "([BI)I", null, null);
        byteAt.visitCode();
        byteAt.visitVarInsn(ALOAD, 0);
        byteAt.visitVarInsn(ILOAD, 1);
        byteAt.visitInsn(BALOAD);
        byteAt.visitInsn(IRETURN);
        byteAt.visitMaxs(0, 0);
        byteAt.visitEnd();

        MethodVisitor stringRoundtrip = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "stringRoundtrip",
                "([Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        stringRoundtrip.visitCode();
        stringRoundtrip.visitVarInsn(ALOAD, 0);
        stringRoundtrip.visitInsn(ICONST_0);
        stringRoundtrip.visitVarInsn(ALOAD, 1);
        stringRoundtrip.visitInsn(AASTORE);
        stringRoundtrip.visitVarInsn(ALOAD, 0);
        stringRoundtrip.visitInsn(ICONST_0);
        stringRoundtrip.visitInsn(AALOAD);
        stringRoundtrip.visitInsn(ARETURN);
        stringRoundtrip.visitMaxs(0, 0);
        stringRoundtrip.visitEnd();

        MethodVisitor objectRoundtrip = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "objectRoundtrip",
                "([Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        objectRoundtrip.visitCode();
        objectRoundtrip.visitVarInsn(ALOAD, 0);
        objectRoundtrip.visitInsn(ICONST_0);
        objectRoundtrip.visitVarInsn(ALOAD, 1);
        objectRoundtrip.visitInsn(AASTORE);
        objectRoundtrip.visitVarInsn(ALOAD, 0);
        objectRoundtrip.visitInsn(ICONST_0);
        objectRoundtrip.visitInsn(AALOAD);
        objectRoundtrip.visitInsn(ARETURN);
        objectRoundtrip.visitMaxs(0, 0);
        objectRoundtrip.visitEnd();

        MethodVisitor newStringArrayRoundtrip = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "newStringArrayRoundtrip",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        newStringArrayRoundtrip.visitCode();
        newStringArrayRoundtrip.visitInsn(ICONST_2);
        newStringArrayRoundtrip.visitTypeInsn(ANEWARRAY, "java/lang/String");
        newStringArrayRoundtrip.visitInsn(DUP);
        newStringArrayRoundtrip.visitInsn(ICONST_0);
        newStringArrayRoundtrip.visitVarInsn(ALOAD, 0);
        newStringArrayRoundtrip.visitInsn(AASTORE);
        newStringArrayRoundtrip.visitInsn(DUP);
        newStringArrayRoundtrip.visitInsn(ICONST_1);
        newStringArrayRoundtrip.visitVarInsn(ALOAD, 1);
        newStringArrayRoundtrip.visitInsn(AASTORE);
        newStringArrayRoundtrip.visitInsn(ICONST_1);
        newStringArrayRoundtrip.visitInsn(AALOAD);
        newStringArrayRoundtrip.visitInsn(ARETURN);
        newStringArrayRoundtrip.visitMaxs(0, 0);
        newStringArrayRoundtrip.visitEnd();

        MethodVisitor newObjectArrayRoundtrip = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "newObjectArrayRoundtrip",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        newObjectArrayRoundtrip.visitCode();
        newObjectArrayRoundtrip.visitInsn(ICONST_1);
        newObjectArrayRoundtrip.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        newObjectArrayRoundtrip.visitInsn(DUP);
        newObjectArrayRoundtrip.visitInsn(ICONST_0);
        newObjectArrayRoundtrip.visitVarInsn(ALOAD, 0);
        newObjectArrayRoundtrip.visitInsn(AASTORE);
        newObjectArrayRoundtrip.visitInsn(ICONST_0);
        newObjectArrayRoundtrip.visitInsn(AALOAD);
        newObjectArrayRoundtrip.visitInsn(ARETURN);
        newObjectArrayRoundtrip.visitMaxs(0, 0);
        newObjectArrayRoundtrip.visitEnd();

        MethodVisitor nullElement = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "nullElement",
                "([Ljava/lang/String;)Z",
                null,
                null);
        org.objectweb.asm.Label notNull = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        nullElement.visitCode();
        nullElement.visitVarInsn(ALOAD, 0);
        nullElement.visitInsn(ICONST_0);
        nullElement.visitInsn(AALOAD);
        nullElement.visitJumpInsn(IFNONNULL, notNull);
        nullElement.visitInsn(ICONST_1);
        nullElement.visitJumpInsn(GOTO, done);
        nullElement.visitLabel(notNull);
        nullElement.visitInsn(ICONST_0);
        nullElement.visitLabel(done);
        nullElement.visitInsn(IRETURN);
        nullElement.visitMaxs(0, 0);
        nullElement.visitEnd();

        MethodVisitor wrongStore = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "wrongStore",
                "([Ljava/lang/String;Ljava/lang/Object;)V",
                null,
                null);
        wrongStore.visitCode();
        wrongStore.visitVarInsn(ALOAD, 0);
        wrongStore.visitInsn(ICONST_0);
        wrongStore.visitVarInsn(ALOAD, 1);
        wrongStore.visitInsn(AASTORE);
        wrongStore.visitInsn(RETURN);
        wrongStore.visitMaxs(0, 0);
        wrongStore.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] referenceArrayMainClass() {
        ClassWriter writer = mainClass("pkg/ReferenceArrayMain");
        MethodVisitor main = beginMain(writer);
        byteArray(main, 4, 0);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReferenceArrayOps", "byteRoundtrip", "([B)I", false);
        printTopInt(main);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        pushInt(main, 1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/String");
        main.visitLdcInsn("alpha");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReferenceArrayOps",
                "stringRoundtrip",
                "([Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        pushInt(main, 1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        main.visitLdcInsn("beta");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReferenceArrayOps",
                "objectRoundtrip",
                "([Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("first");
        main.visitLdcInsn("second");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReferenceArrayOps",
                "newStringArrayRoundtrip",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("gamma");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReferenceArrayOps",
                "newObjectArrayRoundtrip",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        pushInt(main, 1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/String");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReferenceArrayOps",
                "nullElement",
                "([Ljava/lang/String;)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);

        printReferenceArrayNpeCatch(main);
        printReferenceArrayBoundsCatch(main);
        printReferenceArrayStoreCatch(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] broadPrimitiveArrayOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/BroadPrimitiveArrayOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        primitiveArrayLength(writer, "byteLength", T_BYTE);
        primitiveArrayLength(writer, "shortLength", T_SHORT);
        primitiveArrayLength(writer, "charLength", T_CHAR);
        primitiveArrayLength(writer, "longLength", T_LONG);
        primitiveArrayLength(writer, "floatLength", T_FLOAT);
        primitiveArrayLength(writer, "doubleLength", T_DOUBLE);

        MethodVisitor shorts = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "shortRoundtrip", "([S)I", null, null);
        shorts.visitCode();
        shorts.visitVarInsn(ALOAD, 0);
        shorts.visitInsn(ICONST_0);
        shorts.visitInsn(SALOAD);
        shorts.visitInsn(ICONST_3);
        shorts.visitInsn(IADD);
        shorts.visitVarInsn(ISTORE, 1);
        shorts.visitVarInsn(ALOAD, 0);
        shorts.visitInsn(ICONST_1);
        shorts.visitVarInsn(ILOAD, 1);
        shorts.visitInsn(SASTORE);
        shorts.visitVarInsn(ALOAD, 0);
        shorts.visitInsn(ICONST_1);
        shorts.visitInsn(SALOAD);
        shorts.visitInsn(IRETURN);
        shorts.visitMaxs(0, 0);
        shorts.visitEnd();

        MethodVisitor chars = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "charRoundtrip", "([C)I", null, null);
        chars.visitCode();
        chars.visitVarInsn(ALOAD, 0);
        chars.visitInsn(ICONST_0);
        chars.visitInsn(CALOAD);
        chars.visitInsn(ICONST_1);
        chars.visitInsn(IADD);
        chars.visitVarInsn(ISTORE, 1);
        chars.visitVarInsn(ALOAD, 0);
        chars.visitInsn(ICONST_1);
        chars.visitVarInsn(ILOAD, 1);
        chars.visitInsn(CASTORE);
        chars.visitVarInsn(ALOAD, 0);
        chars.visitInsn(ICONST_1);
        chars.visitInsn(CALOAD);
        chars.visitInsn(IRETURN);
        chars.visitMaxs(0, 0);
        chars.visitEnd();

        MethodVisitor longs = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "longRoundtrip", "([J)J", null, null);
        longs.visitCode();
        longs.visitVarInsn(ALOAD, 0);
        longs.visitInsn(ICONST_0);
        longs.visitInsn(LALOAD);
        longs.visitLdcInsn(3L);
        longs.visitInsn(LADD);
        longs.visitVarInsn(LSTORE, 1);
        longs.visitVarInsn(ALOAD, 0);
        longs.visitInsn(ICONST_1);
        longs.visitVarInsn(LLOAD, 1);
        longs.visitInsn(LASTORE);
        longs.visitVarInsn(ALOAD, 0);
        longs.visitInsn(ICONST_1);
        longs.visitInsn(LALOAD);
        longs.visitInsn(LRETURN);
        longs.visitMaxs(0, 0);
        longs.visitEnd();

        MethodVisitor floats = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "floatRoundtrip", "([F)F", null, null);
        floats.visitCode();
        floats.visitVarInsn(ALOAD, 0);
        floats.visitInsn(ICONST_0);
        floats.visitInsn(FALOAD);
        floats.visitLdcInsn(2.0F);
        floats.visitInsn(FMUL);
        floats.visitVarInsn(FSTORE, 1);
        floats.visitVarInsn(ALOAD, 0);
        floats.visitInsn(ICONST_1);
        floats.visitVarInsn(FLOAD, 1);
        floats.visitInsn(FASTORE);
        floats.visitVarInsn(ALOAD, 0);
        floats.visitInsn(ICONST_1);
        floats.visitInsn(FALOAD);
        floats.visitInsn(FRETURN);
        floats.visitMaxs(0, 0);
        floats.visitEnd();

        MethodVisitor doubles = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "doubleRoundtrip", "([D)D", null, null);
        doubles.visitCode();
        doubles.visitVarInsn(ALOAD, 0);
        doubles.visitInsn(ICONST_0);
        doubles.visitInsn(DALOAD);
        doubles.visitLdcInsn(2.0D);
        doubles.visitInsn(DMUL);
        doubles.visitVarInsn(DSTORE, 1);
        doubles.visitVarInsn(ALOAD, 0);
        doubles.visitInsn(ICONST_1);
        doubles.visitVarInsn(DLOAD, 1);
        doubles.visitInsn(DASTORE);
        doubles.visitVarInsn(ALOAD, 0);
        doubles.visitInsn(ICONST_1);
        doubles.visitInsn(DALOAD);
        doubles.visitInsn(DRETURN);
        doubles.visitMaxs(0, 0);
        doubles.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] broadPrimitiveArrayMainClass() {
        ClassWriter writer = mainClass("pkg/BroadPrimitiveArrayMain");
        MethodVisitor main = beginMain(writer);
        printStaticIntCall(main, "pkg/BroadPrimitiveArrayOps", "byteLength", 1);
        printStaticIntCall(main, "pkg/BroadPrimitiveArrayOps", "shortLength", 2);
        printStaticIntCall(main, "pkg/BroadPrimitiveArrayOps", "charLength", 3);
        printStaticIntCall(main, "pkg/BroadPrimitiveArrayOps", "longLength", 4);
        printStaticIntCall(main, "pkg/BroadPrimitiveArrayOps", "floatLength", 5);
        printStaticIntCall(main, "pkg/BroadPrimitiveArrayOps", "doubleLength", 6);

        shortArray(main, 4, 0);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BroadPrimitiveArrayOps", "shortRoundtrip", "([S)I", false);
        printTopInt(main);
        charArray(main, 65, 0);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BroadPrimitiveArrayOps", "charRoundtrip", "([C)I", false);
        printTopInt(main);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        longArray(main, 4L, 0L);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BroadPrimitiveArrayOps", "longRoundtrip", "([J)J", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(J)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        floatArray(main, 1.5F, 0.0F);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BroadPrimitiveArrayOps", "floatRoundtrip", "([F)F", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(F)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        doubleArray(main, 2.5D, 0.0D);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BroadPrimitiveArrayOps", "doubleRoundtrip", "([D)D", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(D)V", false);

        printBroadPrimitiveArrayNpeCatch(main);
        printBroadPrimitiveArrayBoundsCatch(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] allocationStringOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/AllocationStringOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor intLength = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "intLength", "(I)I", null, null);
        intLength.visitCode();
        intLength.visitVarInsn(ILOAD, 0);
        intLength.visitIntInsn(NEWARRAY, T_INT);
        intLength.visitInsn(ARRAYLENGTH);
        intLength.visitInsn(IRETURN);
        intLength.visitMaxs(0, 0);
        intLength.visitEnd();
        MethodVisitor stringArrayLength = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "stringArrayLength", "(I)I", null, null);
        stringArrayLength.visitCode();
        stringArrayLength.visitVarInsn(ILOAD, 0);
        stringArrayLength.visitTypeInsn(ANEWARRAY, "java/lang/String");
        stringArrayLength.visitInsn(ARRAYLENGTH);
        stringArrayLength.visitInsn(IRETURN);
        stringArrayLength.visitMaxs(0, 0);
        stringArrayLength.visitEnd();
        MethodVisitor length = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "length", "(Ljava/lang/String;)I", null, null);
        length.visitCode();
        length.visitVarInsn(ALOAD, 0);
        length.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        length.visitInsn(IRETURN);
        length.visitMaxs(0, 0);
        length.visitEnd();
        MethodVisitor same = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "same",
                "(Ljava/lang/String;Ljava/lang/String;)Z",
                null,
                null);
        same.visitCode();
        same.visitVarInsn(ALOAD, 0);
        same.visitVarInsn(ALOAD, 1);
        same.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        same.visitInsn(IRETURN);
        same.visitMaxs(0, 0);
        same.visitEnd();
        MethodVisitor empty = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "empty", "(Ljava/lang/String;)Z", null, null);
        empty.visitCode();
        empty.visitVarInsn(ALOAD, 0);
        empty.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
        empty.visitInsn(IRETURN);
        empty.visitMaxs(0, 0);
        empty.visitEnd();
        MethodVisitor charAt = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "charAt", "(Ljava/lang/String;I)I", null, null);
        charAt.visitCode();
        charAt.visitVarInsn(ALOAD, 0);
        charAt.visitVarInsn(ILOAD, 1);
        charAt.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        charAt.visitInsn(IRETURN);
        charAt.visitMaxs(0, 0);
        charAt.visitEnd();
        MethodVisitor starts = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "starts",
                "(Ljava/lang/String;Ljava/lang/String;)Z",
                null,
                null);
        starts.visitCode();
        starts.visitVarInsn(ALOAD, 0);
        starts.visitVarInsn(ALOAD, 1);
        starts.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false);
        starts.visitInsn(IRETURN);
        starts.visitMaxs(0, 0);
        starts.visitEnd();
        MethodVisitor ends = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "ends",
                "(Ljava/lang/String;Ljava/lang/String;)Z",
                null,
                null);
        ends.visitCode();
        ends.visitVarInsn(ALOAD, 0);
        ends.visitVarInsn(ALOAD, 1);
        ends.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "endsWith", "(Ljava/lang/String;)Z", false);
        ends.visitInsn(IRETURN);
        ends.visitMaxs(0, 0);
        ends.visitEnd();
        MethodVisitor middle = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "middle",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        middle.visitCode();
        middle.visitVarInsn(ALOAD, 0);
        middle.visitInsn(ICONST_1);
        middle.visitInsn(ICONST_4);
        middle.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        middle.visitInsn(ARETURN);
        middle.visitMaxs(0, 0);
        middle.visitEnd();
        MethodVisitor builder = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "builder",
                "(Ljava/lang/String;IJ)Ljava/lang/String;",
                null,
                null);
        builder.visitCode();
        builder.visitTypeInsn(NEW, "java/lang/StringBuilder");
        builder.visitInsn(DUP);
        builder.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        builder.visitVarInsn(ALOAD, 0);
        builder.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        builder.visitVarInsn(ILOAD, 1);
        builder.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(I)Ljava/lang/StringBuilder;",
                false);
        builder.visitVarInsn(LLOAD, 2);
        builder.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(J)Ljava/lang/StringBuilder;",
                false);
        builder.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false);
        builder.visitInsn(ARETURN);
        builder.visitMaxs(0, 0);
        builder.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] arraycopyOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ArraycopyOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor copyInt = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "copyInt", "([I[I)V", null, null);
        copyInt.visitCode();
        copyInt.visitVarInsn(ALOAD, 0);
        copyInt.visitInsn(ICONST_0);
        copyInt.visitVarInsn(ALOAD, 1);
        copyInt.visitInsn(ICONST_0);
        copyInt.visitVarInsn(ALOAD, 0);
        copyInt.visitInsn(ARRAYLENGTH);
        copyInt.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        copyInt.visitInsn(RETURN);
        copyInt.visitMaxs(0, 0);
        copyInt.visitEnd();
        MethodVisitor copyByte = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "copyByte", "([B[B)V", null, null);
        copyByte.visitCode();
        copyByte.visitVarInsn(ALOAD, 0);
        copyByte.visitInsn(ICONST_0);
        copyByte.visitVarInsn(ALOAD, 1);
        copyByte.visitInsn(ICONST_0);
        copyByte.visitVarInsn(ALOAD, 0);
        copyByte.visitInsn(ARRAYLENGTH);
        copyByte.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        copyByte.visitInsn(RETURN);
        copyByte.visitMaxs(0, 0);
        copyByte.visitEnd();
        MethodVisitor copyLong = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "copyLong", "([J[J)V", null, null);
        copyLong.visitCode();
        copyLong.visitVarInsn(ALOAD, 0);
        copyLong.visitInsn(ICONST_0);
        copyLong.visitVarInsn(ALOAD, 1);
        copyLong.visitInsn(ICONST_0);
        copyLong.visitVarInsn(ALOAD, 0);
        copyLong.visitInsn(ARRAYLENGTH);
        copyLong.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        copyLong.visitInsn(RETURN);
        copyLong.visitMaxs(0, 0);
        copyLong.visitEnd();
        MethodVisitor copyDouble = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "copyDouble", "([D[D)V", null, null);
        copyDouble.visitCode();
        copyDouble.visitVarInsn(ALOAD, 0);
        copyDouble.visitInsn(ICONST_0);
        copyDouble.visitVarInsn(ALOAD, 1);
        copyDouble.visitInsn(ICONST_0);
        copyDouble.visitVarInsn(ALOAD, 0);
        copyDouble.visitInsn(ARRAYLENGTH);
        copyDouble.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        copyDouble.visitInsn(RETURN);
        copyDouble.visitMaxs(0, 0);
        copyDouble.visitEnd();
        MethodVisitor copyObject = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "copyObject",
                "([Ljava/lang/Object;[Ljava/lang/Object;)V",
                null,
                null);
        copyObject.visitCode();
        copyObject.visitVarInsn(ALOAD, 0);
        copyObject.visitInsn(ICONST_0);
        copyObject.visitVarInsn(ALOAD, 1);
        copyObject.visitInsn(ICONST_0);
        copyObject.visitVarInsn(ALOAD, 0);
        copyObject.visitInsn(ARRAYLENGTH);
        copyObject.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        copyObject.visitInsn(RETURN);
        copyObject.visitMaxs(0, 0);
        copyObject.visitEnd();
        MethodVisitor overlap = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "overlap", "([I)V", null, null);
        overlap.visitCode();
        overlap.visitVarInsn(ALOAD, 0);
        overlap.visitInsn(ICONST_0);
        overlap.visitVarInsn(ALOAD, 0);
        overlap.visitInsn(ICONST_1);
        overlap.visitInsn(ICONST_3);
        overlap.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        overlap.visitInsn(RETURN);
        overlap.visitMaxs(0, 0);
        overlap.visitEnd();
        MethodVisitor copyObjectToString = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "copyObjectToString",
                "([Ljava/lang/Object;[Ljava/lang/String;)V",
                null,
                null);
        copyObjectToString.visitCode();
        copyObjectToString.visitVarInsn(ALOAD, 0);
        copyObjectToString.visitInsn(ICONST_0);
        copyObjectToString.visitVarInsn(ALOAD, 1);
        copyObjectToString.visitInsn(ICONST_0);
        copyObjectToString.visitVarInsn(ALOAD, 0);
        copyObjectToString.visitInsn(ARRAYLENGTH);
        copyObjectToString.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        copyObjectToString.visitInsn(RETURN);
        copyObjectToString.visitMaxs(0, 0);
        copyObjectToString.visitEnd();
        MethodVisitor copyNull = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "copyNull", "([Ljava/lang/Object;)V", null, null);
        copyNull.visitCode();
        copyNull.visitInsn(ACONST_NULL);
        copyNull.visitInsn(ICONST_0);
        copyNull.visitVarInsn(ALOAD, 0);
        copyNull.visitInsn(ICONST_0);
        copyNull.visitInsn(ICONST_1);
        copyNull.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        copyNull.visitInsn(RETURN);
        copyNull.visitMaxs(0, 0);
        copyNull.visitEnd();
        MethodVisitor copyOob = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "copyOob", "([I[I)V", null, null);
        copyOob.visitCode();
        copyOob.visitVarInsn(ALOAD, 0);
        copyOob.visitInsn(ICONST_0);
        copyOob.visitVarInsn(ALOAD, 1);
        copyOob.visitInsn(ICONST_0);
        copyOob.visitIntInsn(BIPUSH, 5);
        copyOob.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        copyOob.visitInsn(RETURN);
        copyOob.visitMaxs(0, 0);
        copyOob.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] allocationStringMainClass() {
        ClassWriter writer = mainClass("pkg/AllocationStringMain");
        MethodVisitor main = beginMain(writer);
        printStaticIntCall(main, "pkg/AllocationStringOps", "intLength", 3);
        printStaticIntCall(main, "pkg/AllocationStringOps", "stringArrayLength", 2);
        printStaticStringLength(main, "pkg/AllocationStringOps", "length", "abcd");
        printStaticStringEquals(main, "hi", "hi");
        printStaticStringEquals(main, "hi", "bye");
        printAllocationStringEmpty(main);
        printAllocationStringCharAt(main);
        printAllocationStringStartsEnds(main);
        printStaticStringCall(main, "pkg/AllocationStringOps", "middle", "hello");
        printAllocationStringBuilder(main);
        printStringCharAtBoundsCatch(main);
        printNpeCatchForAllocationStringLength(main);
        printNegativeArrayCatchForAllocation(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] arraycopyMainClass() {
        ClassWriter writer = mainClass("pkg/ArraycopyMain");
        MethodVisitor main = beginMain(writer);
        main.visitInsn(ICONST_3);
        main.visitIntInsn(NEWARRAY, T_INT);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_0);
        main.visitInsn(ICONST_1);
        main.visitInsn(IASTORE);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_1);
        main.visitInsn(ICONST_2);
        main.visitInsn(IASTORE);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_2);
        main.visitInsn(ICONST_3);
        main.visitInsn(IASTORE);
        main.visitVarInsn(ASTORE, 1);
        main.visitInsn(ICONST_3);
        main.visitIntInsn(NEWARRAY, T_INT);
        main.visitVarInsn(ASTORE, 2);
        main.visitVarInsn(ALOAD, 1);
        main.visitVarInsn(ALOAD, 2);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArraycopyOps", "copyInt", "([I[I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 2);
        main.visitInsn(ICONST_2);
        main.visitInsn(IALOAD);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);

        main.visitInsn(ICONST_2);
        main.visitIntInsn(NEWARRAY, T_BYTE);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_0);
        main.visitIntInsn(BIPUSH, 7);
        main.visitInsn(BASTORE);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_1);
        main.visitIntInsn(BIPUSH, 8);
        main.visitInsn(BASTORE);
        main.visitVarInsn(ASTORE, 3);
        main.visitInsn(ICONST_2);
        main.visitIntInsn(NEWARRAY, T_BYTE);
        main.visitVarInsn(ASTORE, 4);
        main.visitVarInsn(ALOAD, 3);
        main.visitVarInsn(ALOAD, 4);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArraycopyOps", "copyByte", "([B[B)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 4);
        main.visitInsn(ICONST_1);
        main.visitInsn(BALOAD);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);

        longArray(main, 11L, 13L);
        main.visitVarInsn(ASTORE, 11);
        main.visitInsn(ICONST_2);
        main.visitIntInsn(NEWARRAY, T_LONG);
        main.visitVarInsn(ASTORE, 12);
        main.visitVarInsn(ALOAD, 11);
        main.visitVarInsn(ALOAD, 12);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArraycopyOps", "copyLong", "([J[J)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 12);
        main.visitInsn(ICONST_1);
        main.visitInsn(LALOAD);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(J)V", false);

        doubleArray(main, 1.5D, 2.5D);
        main.visitVarInsn(ASTORE, 13);
        main.visitInsn(ICONST_2);
        main.visitIntInsn(NEWARRAY, T_DOUBLE);
        main.visitVarInsn(ASTORE, 14);
        main.visitVarInsn(ALOAD, 13);
        main.visitVarInsn(ALOAD, 14);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArraycopyOps", "copyDouble", "([D[D)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 14);
        main.visitInsn(ICONST_1);
        main.visitInsn(DALOAD);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(D)V", false);

        main.visitInsn(ICONST_1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        main.visitInsn(DUP);
        main.visitInsn(ICONST_0);
        main.visitLdcInsn("hi");
        main.visitInsn(AASTORE);
        main.visitVarInsn(ASTORE, 5);
        main.visitInsn(ICONST_1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        main.visitVarInsn(ASTORE, 6);
        main.visitVarInsn(ALOAD, 5);
        main.visitVarInsn(ALOAD, 6);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ArraycopyOps",
                "copyObject",
                "([Ljava/lang/Object;[Ljava/lang/Object;)V",
                false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 6);
        main.visitInsn(ICONST_0);
        main.visitInsn(AALOAD);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false);

        main.visitInsn(ICONST_4);
        main.visitIntInsn(NEWARRAY, T_INT);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_0);
        main.visitInsn(ICONST_1);
        main.visitInsn(IASTORE);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_1);
        main.visitInsn(ICONST_2);
        main.visitInsn(IASTORE);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_2);
        main.visitInsn(ICONST_3);
        main.visitInsn(IASTORE);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_3);
        main.visitInsn(ICONST_4);
        main.visitInsn(IASTORE);
        main.visitVarInsn(ASTORE, 7);
        main.visitVarInsn(ALOAD, 7);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArraycopyOps", "overlap", "([I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 7);
        main.visitInsn(ICONST_1);
        main.visitInsn(IALOAD);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 7);
        main.visitInsn(ICONST_3);
        main.visitInsn(IALOAD);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);

        printArraycopyNpeCatch(main);
        printArraycopyOobCatch(main);
        printArraycopyStoreCatch(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mathHelperOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/MathHelperOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor ints = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "ints", "(II)I", null, null);
        ints.visitCode();
        ints.visitVarInsn(ILOAD, 0);
        ints.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false);
        ints.visitVarInsn(ILOAD, 0);
        ints.visitVarInsn(ILOAD, 1);
        ints.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
        ints.visitInsn(IADD);
        ints.visitVarInsn(ILOAD, 0);
        ints.visitVarInsn(ILOAD, 1);
        ints.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
        ints.visitInsn(IADD);
        ints.visitInsn(IRETURN);
        ints.visitMaxs(0, 0);
        ints.visitEnd();
        MethodVisitor longs = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "longs", "(JJ)J", null, null);
        longs.visitCode();
        longs.visitVarInsn(LLOAD, 0);
        longs.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(J)J", false);
        longs.visitVarInsn(LLOAD, 0);
        longs.visitVarInsn(LLOAD, 2);
        longs.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false);
        longs.visitInsn(LADD);
        longs.visitVarInsn(LLOAD, 0);
        longs.visitVarInsn(LLOAD, 2);
        longs.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(JJ)J", false);
        longs.visitInsn(LADD);
        longs.visitInsn(LRETURN);
        longs.visitMaxs(0, 0);
        longs.visitEnd();
        MethodVisitor floats = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "floats", "(FF)F", null, null);
        floats.visitCode();
        floats.visitVarInsn(FLOAD, 0);
        floats.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(F)F", false);
        floats.visitVarInsn(FLOAD, 0);
        floats.visitVarInsn(FLOAD, 1);
        floats.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(FF)F", false);
        floats.visitInsn(FADD);
        floats.visitVarInsn(FLOAD, 0);
        floats.visitVarInsn(FLOAD, 1);
        floats.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(FF)F", false);
        floats.visitInsn(FADD);
        floats.visitInsn(FRETURN);
        floats.visitMaxs(0, 0);
        floats.visitEnd();
        MethodVisitor doubles = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "doubles", "(DD)D", null, null);
        doubles.visitCode();
        doubles.visitVarInsn(DLOAD, 0);
        doubles.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
        doubles.visitVarInsn(DLOAD, 0);
        doubles.visitVarInsn(DLOAD, 2);
        doubles.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
        doubles.visitInsn(DADD);
        doubles.visitVarInsn(DLOAD, 0);
        doubles.visitVarInsn(DLOAD, 2);
        doubles.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        doubles.visitInsn(DADD);
        doubles.visitInsn(DRETURN);
        doubles.visitMaxs(0, 0);
        doubles.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mathHelperMainClass() {
        ClassWriter writer = mainClass("pkg/MathHelperMain");
        MethodVisitor main = beginMain(writer);
        printStaticIntPair(main, "pkg/MathHelperOps", "ints", -7, 4);
        printStaticLong(main, "pkg/MathHelperOps", "longs", -9L, 5L);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(-2.0F);
        main.visitLdcInsn(5.0F);
        main.visitMethodInsn(INVOKESTATIC, "pkg/MathHelperOps", "floats", "(FF)F", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(F)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(-3.0D);
        main.visitLdcInsn(4.0D);
        main.visitMethodInsn(INVOKESTATIC, "pkg/MathHelperOps", "doubles", "(DD)D", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(D)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] boxingObjectsOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/BoxingObjectsOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor boxedInt = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "boxedInt", "(I)I", null, null);
        boxedInt.visitCode();
        boxedInt.visitVarInsn(ILOAD, 0);
        boxedInt.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        boxedInt.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
        boxedInt.visitInsn(ICONST_1);
        boxedInt.visitInsn(IADD);
        boxedInt.visitInsn(IRETURN);
        boxedInt.visitMaxs(0, 0);
        boxedInt.visitEnd();
        MethodVisitor boxedLong = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "boxedLong", "(J)J", null, null);
        boxedLong.visitCode();
        boxedLong.visitVarInsn(LLOAD, 0);
        boxedLong.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
        boxedLong.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
        boxedLong.visitLdcInsn(2L);
        boxedLong.visitInsn(LADD);
        boxedLong.visitInsn(LRETURN);
        boxedLong.visitMaxs(0, 0);
        boxedLong.visitEnd();
        MethodVisitor boxedBoolean = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "boxedBoolean", "(Z)Z", null, null);
        boxedBoolean.visitCode();
        boxedBoolean.visitVarInsn(ILOAD, 0);
        boxedBoolean.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        boxedBoolean.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
        boxedBoolean.visitInsn(IRETURN);
        boxedBoolean.visitMaxs(0, 0);
        boxedBoolean.visitEnd();
        MethodVisitor boxedDouble = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "boxedDouble", "(D)D", null, null);
        boxedDouble.visitCode();
        boxedDouble.visitVarInsn(DLOAD, 0);
        boxedDouble.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        boxedDouble.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
        boxedDouble.visitLdcInsn(1.5D);
        boxedDouble.visitInsn(DADD);
        boxedDouble.visitInsn(DRETURN);
        boxedDouble.visitMaxs(0, 0);
        boxedDouble.visitEnd();
        MethodVisitor same = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "same",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                null,
                null);
        same.visitCode();
        same.visitVarInsn(ALOAD, 0);
        same.visitVarInsn(ALOAD, 1);
        same.visitMethodInsn(
                INVOKESTATIC,
                "java/util/Objects",
                "equals",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                false);
        same.visitInsn(IRETURN);
        same.visitMaxs(0, 0);
        same.visitEnd();
        MethodVisitor require = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "require",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        require.visitCode();
        require.visitVarInsn(ALOAD, 0);
        require.visitMethodInsn(
                INVOKESTATIC,
                "java/util/Objects",
                "requireNonNull",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        require.visitInsn(ARETURN);
        require.visitMaxs(0, 0);
        require.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] boxingObjectsMainClass() {
        ClassWriter writer = mainClass("pkg/BoxingObjectsMain");
        MethodVisitor main = beginMain(writer);
        printStaticIntCall(main, "pkg/BoxingObjectsOps", "boxedInt", 4);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(5L);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BoxingObjectsOps", "boxedLong", "(J)J", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(J)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ICONST_1);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BoxingObjectsOps", "boxedBoolean", "(Z)Z", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(2.0D);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BoxingObjectsOps", "boxedDouble", "(D)D", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(D)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("x");
        main.visitLdcInsn("x");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/BoxingObjectsOps",
                "same",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("x");
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/BoxingObjectsOps",
                "same",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("ok");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/BoxingObjectsOps",
                "require",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        main.visitTypeInsn(CHECKCAST, "java/lang/String");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        printRequireNonNullNpeCatch(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] stringConcatMainClass() {
        ClassWriter writer = mainClass("pkg/StringConcatMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("a");
        main.visitLdcInsn("b");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/StringConcat",
                "concat",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] stringConcatRecipeMainClass() {
        ClassWriter writer = mainClass("pkg/StringConcatRecipeMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("x");
        main.visitIntInsn(BIPUSH, 7);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/StringConcatRecipe",
                "concatRecipe",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] lambdaMainClass() {
        ClassWriter writer = mainClass("pkg/LambdaMain");
        MethodVisitor main = beginMain(writer);

        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/LambdaShapes",
                "nonCapturing",
                "()Ljava/lang/Runnable;",
                false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("ran");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("cap");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/LambdaShapes",
                "capturing",
                "(Ljava/lang/String;)Ljava/util/function/Supplier;",
                false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        main.visitTypeInsn(CHECKCAST, "java/lang/String");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/LambdaShapes",
                "staticReference",
                "()Ljava/util/function/Supplier;",
                false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        main.visitTypeInsn(CHECKCAST, "java/lang/String");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("  trim  ");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/LambdaShapes",
                "instanceReference",
                "(Ljava/lang/String;)Ljava/util/function/Supplier;",
                false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        main.visitTypeInsn(CHECKCAST, "java/lang/String");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/LambdaShapes",
                "constructorReference",
                "()Ljava/util/function/Supplier;",
                false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        main.visitTypeInsn(INSTANCEOF, "java/lang/Object");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);

        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] objectPointClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ObjectPoint", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "x", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "y", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(II)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitFieldInsn(PUTFIELD, "pkg/ObjectPoint", "x", "I");
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 2);
        constructor.visitFieldInsn(PUTFIELD, "pkg/ObjectPoint", "y", "I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor sum = writer.visitMethod(ACC_PUBLIC, "sum", "()I", null, null);
        sum.visitCode();
        sum.visitVarInsn(ALOAD, 0);
        sum.visitFieldInsn(GETFIELD, "pkg/ObjectPoint", "x", "I");
        sum.visitVarInsn(ALOAD, 0);
        sum.visitFieldInsn(GETFIELD, "pkg/ObjectPoint", "y", "I");
        sum.visitInsn(IADD);
        sum.visitInsn(IRETURN);
        sum.visitMaxs(0, 0);
        sum.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] objectTypeOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ObjectTypeOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor makePoint = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "makePoint",
                "(II)Lpkg/ObjectPoint;",
                null,
                null);
        makePoint.visitCode();
        makePoint.visitTypeInsn(NEW, "pkg/ObjectPoint");
        makePoint.visitInsn(DUP);
        makePoint.visitVarInsn(ILOAD, 0);
        makePoint.visitVarInsn(ILOAD, 1);
        makePoint.visitMethodInsn(INVOKESPECIAL, "pkg/ObjectPoint", "<init>", "(II)V", false);
        makePoint.visitInsn(ARETURN);
        makePoint.visitMaxs(0, 0);
        makePoint.visitEnd();
        MethodVisitor makePointSum = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "makePointSum", "(II)I", null, null);
        makePointSum.visitCode();
        makePointSum.visitTypeInsn(NEW, "pkg/ObjectPoint");
        makePointSum.visitInsn(DUP);
        makePointSum.visitVarInsn(ILOAD, 0);
        makePointSum.visitVarInsn(ILOAD, 1);
        makePointSum.visitMethodInsn(INVOKESPECIAL, "pkg/ObjectPoint", "<init>", "(II)V", false);
        makePointSum.visitMethodInsn(INVOKEVIRTUAL, "pkg/ObjectPoint", "sum", "()I", false);
        makePointSum.visitInsn(IRETURN);
        makePointSum.visitMaxs(0, 0);
        makePointSum.visitEnd();
        MethodVisitor castString = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "castString",
                "(Ljava/lang/Object;)Ljava/lang/String;",
                null,
                null);
        castString.visitCode();
        castString.visitVarInsn(ALOAD, 0);
        castString.visitTypeInsn(CHECKCAST, "java/lang/String");
        castString.visitInsn(ARETURN);
        castString.visitMaxs(0, 0);
        castString.visitEnd();
        MethodVisitor isString = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "isString", "(Ljava/lang/Object;)Z", null, null);
        isString.visitCode();
        isString.visitVarInsn(ALOAD, 0);
        isString.visitTypeInsn(INSTANCEOF, "java/lang/String");
        isString.visitInsn(IRETURN);
        isString.visitMaxs(0, 0);
        isString.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] objectTypeMainClass() {
        ClassWriter writer = mainClass("pkg/ObjectTypeMain");
        MethodVisitor main = beginMain(writer);
        printStaticIntPair(main, "pkg/ObjectTypeOps", "makePointSum", 2, 5);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        pushInt(main, 8);
        pushInt(main, 9);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ObjectTypeOps", "makePoint", "(II)Lpkg/ObjectPoint;", false);
        main.visitFieldInsn(GETFIELD, "pkg/ObjectPoint", "x", "I");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("hello");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ObjectTypeOps",
                "castString",
                "(Ljava/lang/Object;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        printClassCastCatchForObjectType(main);
        printIsStringForString(main);
        printIsStringForNewObject(main);
        printIsStringForNull(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchBaseClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/Base", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitInsn(ICONST_1);
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        MethodVisitor add = writer.visitMethod(ACC_PUBLIC, "add", "(I)I", null, null);
        add.visitCode();
        add.visitVarInsn(ILOAD, 1);
        add.visitInsn(ICONST_1);
        add.visitInsn(IADD);
        add.visitInsn(IRETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchSubClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/Sub", null, "pkg/Base", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "pkg/Base", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitIntInsn(BIPUSH, 41);
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        MethodVisitor add = writer.visitMethod(ACC_PUBLIC, "add", "(I)I", null, null);
        add.visitCode();
        add.visitVarInsn(ILOAD, 1);
        add.visitIntInsn(BIPUSH, 41);
        add.visitInsn(IADD);
        add.visitInsn(IRETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchInterfaceClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "pkg/I", null, "java/lang/Object", null);
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "value", "()I", null, null);
        value.visitEnd();
        MethodVisitor name = writer.visitMethod(
                ACC_PUBLIC | ACC_ABSTRACT,
                "name",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        name.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchImplClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/Impl", null, "java/lang/Object", new String[] {"pkg/I"});
        defaultConstructor(writer);
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitIntInsn(BIPUSH, 7);
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        MethodVisitor name = writer.visitMethod(
                ACC_PUBLIC,
                "name",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        name.visitCode();
        name.visitLdcInsn("impl:");
        name.visitVarInsn(ALOAD, 1);
        name.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001");
        name.visitInsn(ARETURN);
        name.visitMaxs(0, 0);
        name.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchDefaultInterfaceClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "pkg/DefaultI", null, "java/lang/Object", null);
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitIntInsn(BIPUSH, 33);
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        MethodVisitor name = writer.visitMethod(
                ACC_PUBLIC,
                "name",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        name.visitCode();
        name.visitLdcInsn("default:");
        name.visitVarInsn(ALOAD, 1);
        name.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001");
        name.visitInsn(ARETURN);
        name.visitMaxs(0, 0);
        name.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchDefaultInheritedClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/DefaultInherited", null, "java/lang/Object", new String[] {"pkg/DefaultI"});
        defaultConstructor(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchDefaultOverrideClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/DefaultOverride", null, "java/lang/Object", new String[] {"pkg/DefaultI"});
        defaultConstructor(writer);
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitIntInsn(BIPUSH, 44);
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        MethodVisitor name = writer.visitMethod(
                ACC_PUBLIC,
                "name",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        name.visitCode();
        name.visitLdcInsn("override:");
        name.visitVarInsn(ALOAD, 1);
        name.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001");
        name.visitInsn(ARETURN);
        name.visitMaxs(0, 0);
        name.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchDefaultSuperImplClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/DefaultSuperImpl", null, "java/lang/Object", new String[] {"pkg/DefaultI"});
        defaultConstructor(writer);
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitVarInsn(ALOAD, 0);
        value.visitMethodInsn(INVOKESPECIAL, "pkg/DefaultI", "value", "()I", true);
        value.visitInsn(ICONST_2);
        value.visitInsn(IADD);
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchConflictLeftClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "pkg/ConflictLeft", null, "java/lang/Object", null);
        MethodVisitor answer = writer.visitMethod(ACC_PUBLIC, "answer", "()I", null, null);
        answer.visitCode();
        answer.visitIntInsn(BIPUSH, 11);
        answer.visitInsn(IRETURN);
        answer.visitMaxs(0, 0);
        answer.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchConflictRightClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "pkg/ConflictRight", null, "java/lang/Object", null);
        MethodVisitor answer = writer.visitMethod(ACC_PUBLIC, "answer", "()I", null, null);
        answer.visitCode();
        answer.visitIntInsn(BIPUSH, 22);
        answer.visitInsn(IRETURN);
        answer.visitMaxs(0, 0);
        answer.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchConflictImplClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                "pkg/ConflictImpl",
                null,
                "java/lang/Object",
                new String[] {"pkg/ConflictLeft", "pkg/ConflictRight"});
        defaultConstructor(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/DispatchOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor virtualValue = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "virtualValue",
                "(Lpkg/Base;)I",
                null,
                null);
        virtualValue.visitCode();
        virtualValue.visitVarInsn(ALOAD, 0);
        virtualValue.visitMethodInsn(INVOKEVIRTUAL, "pkg/Base", "value", "()I", false);
        virtualValue.visitInsn(IRETURN);
        virtualValue.visitMaxs(0, 0);
        virtualValue.visitEnd();
        MethodVisitor virtualAdd = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "virtualAdd",
                "(Lpkg/Base;I)I",
                null,
                null);
        virtualAdd.visitCode();
        virtualAdd.visitVarInsn(ALOAD, 0);
        virtualAdd.visitVarInsn(ILOAD, 1);
        virtualAdd.visitMethodInsn(INVOKEVIRTUAL, "pkg/Base", "add", "(I)I", false);
        virtualAdd.visitInsn(IRETURN);
        virtualAdd.visitMaxs(0, 0);
        virtualAdd.visitEnd();
        MethodVisitor interfaceValue = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "interfaceValue",
                "(Lpkg/I;)I",
                null,
                null);
        interfaceValue.visitCode();
        interfaceValue.visitVarInsn(ALOAD, 0);
        interfaceValue.visitMethodInsn(INVOKEINTERFACE, "pkg/I", "value", "()I", true);
        interfaceValue.visitInsn(IRETURN);
        interfaceValue.visitMaxs(0, 0);
        interfaceValue.visitEnd();
        MethodVisitor interfaceName = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "interfaceName",
                "(Lpkg/I;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        interfaceName.visitCode();
        interfaceName.visitVarInsn(ALOAD, 0);
        interfaceName.visitVarInsn(ALOAD, 1);
        interfaceName.visitMethodInsn(
                INVOKEINTERFACE,
                "pkg/I",
                "name",
                "(Ljava/lang/String;)Ljava/lang/String;",
                true);
        interfaceName.visitInsn(ARETURN);
        interfaceName.visitMaxs(0, 0);
        interfaceName.visitEnd();
        MethodVisitor defaultValue = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "defaultValue",
                "(Lpkg/DefaultI;)I",
                null,
                null);
        defaultValue.visitCode();
        defaultValue.visitVarInsn(ALOAD, 0);
        defaultValue.visitMethodInsn(INVOKEINTERFACE, "pkg/DefaultI", "value", "()I", true);
        defaultValue.visitInsn(IRETURN);
        defaultValue.visitMaxs(0, 0);
        defaultValue.visitEnd();
        MethodVisitor defaultName = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "defaultName",
                "(Lpkg/DefaultI;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        defaultName.visitCode();
        defaultName.visitVarInsn(ALOAD, 0);
        defaultName.visitVarInsn(ALOAD, 1);
        defaultName.visitMethodInsn(
                INVOKEINTERFACE,
                "pkg/DefaultI",
                "name",
                "(Ljava/lang/String;)Ljava/lang/String;",
                true);
        defaultName.visitInsn(ARETURN);
        defaultName.visitMaxs(0, 0);
        defaultName.visitEnd();
        MethodVisitor conflictValue = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "conflictValue",
                "(Lpkg/ConflictLeft;)I",
                null,
                null);
        conflictValue.visitCode();
        conflictValue.visitVarInsn(ALOAD, 0);
        conflictValue.visitMethodInsn(INVOKEINTERFACE, "pkg/ConflictLeft", "answer", "()I", true);
        conflictValue.visitInsn(IRETURN);
        conflictValue.visitMaxs(0, 0);
        conflictValue.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchMainClass() {
        ClassWriter writer = mainClass("pkg/DispatchMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/Sub");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/Sub", "<init>", "()V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/DispatchOps", "virtualValue", "(Lpkg/Base;)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/Sub");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/Sub", "<init>", "()V", false);
        pushInt(main, 5);
        main.visitMethodInsn(INVOKESTATIC, "pkg/DispatchOps", "virtualAdd", "(Lpkg/Base;I)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/Impl");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/Impl", "<init>", "()V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/DispatchOps", "interfaceValue", "(Lpkg/I;)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/Impl");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/Impl", "<init>", "()V", false);
        main.visitLdcInsn("ok");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/DispatchOps",
                "interfaceName",
                "(Lpkg/I;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/DefaultInherited");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/DefaultInherited", "<init>", "()V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/DispatchOps", "defaultValue", "(Lpkg/DefaultI;)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/DefaultOverride");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/DefaultOverride", "<init>", "()V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/DispatchOps", "defaultValue", "(Lpkg/DefaultI;)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/DefaultInherited");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/DefaultInherited", "<init>", "()V", false);
        main.visitLdcInsn("ok");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/DispatchOps",
                "defaultName",
                "(Lpkg/DefaultI;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/DefaultOverride");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/DefaultOverride", "<init>", "()V", false);
        main.visitLdcInsn("ok");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/DispatchOps",
                "defaultName",
                "(Lpkg/DefaultI;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/DefaultSuperImpl");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/DefaultSuperImpl", "<init>", "()V", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/DefaultSuperImpl", "value", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        printDefaultConflictCatchForDispatch(main);
        printNpeCatchForDispatch(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] pointClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/Point", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "x", "I", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "y", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(II)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitFieldInsn(PUTFIELD, "pkg/Point", "x", "I");
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 2);
        constructor.visitFieldInsn(PUTFIELD, "pkg/Point", "y", "I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor sum = writer.visitMethod(ACC_PUBLIC, "sum", "()I", null, null);
        sum.visitCode();
        sum.visitVarInsn(ALOAD, 0);
        sum.visitFieldInsn(GETFIELD, "pkg/Point", "x", "I");
        sum.visitVarInsn(ALOAD, 0);
        sum.visitFieldInsn(GETFIELD, "pkg/Point", "y", "I");
        sum.visitInsn(IADD);
        sum.visitInsn(IRETURN);
        sum.visitMaxs(0, 0);
        sum.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] staticInitOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/StaticInitOps", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "value", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "label", "Ljava/lang/String;", null, null).visitEnd();
        defaultConstructor(writer);
        MethodVisitor clinit = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitIntInsn(BIPUSH, 17);
        clinit.visitFieldInsn(PUTSTATIC, "pkg/StaticInitOps", "value", "I");
        clinit.visitLdcInsn("ready");
        clinit.visitFieldInsn(PUTSTATIC, "pkg/StaticInitOps", "label", "Ljava/lang/String;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] protectedStringsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ProtectedStrings", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor literal = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "literal",
                "()Ljava/lang/String;",
                null,
                null);
        literal.visitCode();
        literal.visitLdcInsn("ordinary-secret");
        literal.visitInsn(ARETURN);
        literal.visitMaxs(0, 0);
        literal.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] protectedStringBoxClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ProtectedStringBox", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "label", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitLdcInsn("ctor-secret");
        constructor.visitFieldInsn(PUTFIELD, "pkg/ProtectedStringBox", "label", "Ljava/lang/String;");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor label = writer.visitMethod(ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null);
        label.visitCode();
        label.visitVarInsn(ALOAD, 0);
        label.visitFieldInsn(GETFIELD, "pkg/ProtectedStringBox", "label", "Ljava/lang/String;");
        label.visitInsn(ARETURN);
        label.visitMaxs(0, 0);
        label.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] protectedStringsMainClass() {
        ClassWriter writer = mainClass("pkg/ProtectedStringsMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ProtectedStrings", "literal", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/ProtectedStringBox");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/ProtectedStringBox", "<init>", "()V", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/ProtectedStringBox", "label", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] constructorClinitMainClass() {
        ClassWriter writer = mainClass("pkg/ConstructorClinitMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/Point");
        main.visitInsn(DUP);
        main.visitInsn(ICONST_1);
        main.visitInsn(ICONST_2);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/Point", "<init>", "(II)V", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/Point", "sum", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitFieldInsn(GETSTATIC, "pkg/StaticInitOps", "value", "I");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitFieldInsn(GETSTATIC, "pkg/StaticInitOps", "label", "Ljava/lang/String;");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] genericBoxClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/GenericBox", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "total", "I", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "label", "Ljava/lang/String;", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "values", "[I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                ACC_PUBLIC,
                "<init>",
                "(ILjava/lang/String;)V",
                null,
                null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitIntInsn(BIPUSH, 5);
        constructor.visitInsn(IADD);
        constructor.visitFieldInsn(PUTFIELD, "pkg/GenericBox", "total", "I");
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ALOAD, 2);
        constructor.visitFieldInsn(PUTFIELD, "pkg/GenericBox", "label", "Ljava/lang/String;");
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitIntInsn(NEWARRAY, T_INT);
        constructor.visitFieldInsn(PUTFIELD, "pkg/GenericBox", "values", "[I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor total = writer.visitMethod(ACC_PUBLIC, "total", "()I", null, null);
        total.visitCode();
        total.visitVarInsn(ALOAD, 0);
        total.visitFieldInsn(GETFIELD, "pkg/GenericBox", "total", "I");
        total.visitInsn(IRETURN);
        total.visitMaxs(0, 0);
        total.visitEnd();
        MethodVisitor label = writer.visitMethod(ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null);
        label.visitCode();
        label.visitVarInsn(ALOAD, 0);
        label.visitFieldInsn(GETFIELD, "pkg/GenericBox", "label", "Ljava/lang/String;");
        label.visitInsn(ARETURN);
        label.visitMaxs(0, 0);
        label.visitEnd();
        MethodVisitor valueLength = writer.visitMethod(ACC_PUBLIC, "valueLength", "()I", null, null);
        valueLength.visitCode();
        valueLength.visitVarInsn(ALOAD, 0);
        valueLength.visitFieldInsn(GETFIELD, "pkg/GenericBox", "values", "[I");
        valueLength.visitInsn(ARRAYLENGTH);
        valueLength.visitInsn(IRETURN);
        valueLength.visitMaxs(0, 0);
        valueLength.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] genericStaticInitClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/GenericStaticInit", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "count", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "wide", "J", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "label", "Ljava/lang/String;", null, null).visitEnd();
        defaultConstructor(writer);
        MethodVisitor clinit = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitIntInsn(BIPUSH, 10);
        clinit.visitIntInsn(BIPUSH, 7);
        clinit.visitInsn(IADD);
        clinit.visitFieldInsn(PUTSTATIC, "pkg/GenericStaticInit", "count", "I");
        clinit.visitLdcInsn(20L);
        clinit.visitLdcInsn(2L);
        clinit.visitInsn(LADD);
        clinit.visitFieldInsn(PUTSTATIC, "pkg/GenericStaticInit", "wide", "J");
        clinit.visitLdcInsn("generic");
        clinit.visitFieldInsn(PUTSTATIC, "pkg/GenericStaticInit", "label", "Ljava/lang/String;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] genericConstructorClinitMainClass() {
        ClassWriter writer = mainClass("pkg/GenericConstructorClinitMain");
        MethodVisitor main = beginMain(writer);
        main.visitTypeInsn(NEW, "pkg/GenericBox");
        main.visitInsn(DUP);
        main.visitInsn(ICONST_4);
        main.visitLdcInsn("box");
        main.visitMethodInsn(INVOKESPECIAL, "pkg/GenericBox", "<init>", "(ILjava/lang/String;)V", false);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/GenericBox", "total", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/GenericBox", "label", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/GenericBox", "valueLength", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitFieldInsn(GETSTATIC, "pkg/GenericStaticInit", "count", "I");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitFieldInsn(GETSTATIC, "pkg/GenericStaticInit", "wide", "J");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(J)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitFieldInsn(GETSTATIC, "pkg/GenericStaticInit", "label", "Ljava/lang/String;");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] branchingBoxClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/BranchingBox", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "value", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null);
        org.objectweb.asm.Label nonPositive = new org.objectweb.asm.Label();
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitJumpInsn(IFLE, nonPositive);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitInsn(ICONST_1);
        constructor.visitInsn(IADD);
        constructor.visitFieldInsn(PUTFIELD, "pkg/BranchingBox", "value", "I");
        constructor.visitInsn(RETURN);
        constructor.visitLabel(nonPositive);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitInsn(ICONST_1);
        constructor.visitInsn(ISUB);
        constructor.visitFieldInsn(PUTFIELD, "pkg/BranchingBox", "value", "I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitVarInsn(ALOAD, 0);
        value.visitFieldInsn(GETFIELD, "pkg/BranchingBox", "value", "I");
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] branchingStaticInitClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/BranchingStaticInit", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "value", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "label", "Ljava/lang/String;", null, null).visitEnd();
        defaultConstructor(writer);
        MethodVisitor clinit = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        org.objectweb.asm.Label fallback = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        clinit.visitCode();
        clinit.visitInsn(ICONST_5);
        clinit.visitInsn(ICONST_3);
        clinit.visitJumpInsn(IF_ICMPLE, fallback);
        clinit.visitIntInsn(BIPUSH, 11);
        clinit.visitFieldInsn(PUTSTATIC, "pkg/BranchingStaticInit", "value", "I");
        clinit.visitJumpInsn(GOTO, done);
        clinit.visitLabel(fallback);
        clinit.visitIntInsn(BIPUSH, 22);
        clinit.visitFieldInsn(PUTSTATIC, "pkg/BranchingStaticInit", "value", "I");
        clinit.visitLabel(done);
        clinit.visitLdcInsn("branched");
        clinit.visitFieldInsn(PUTSTATIC, "pkg/BranchingStaticInit", "label", "Ljava/lang/String;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] branchingConstructorClinitMainClass() {
        ClassWriter writer = mainClass("pkg/BranchingConstructorClinitMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/BranchingBox");
        main.visitInsn(DUP);
        main.visitInsn(ICONST_4);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/BranchingBox", "<init>", "(I)V", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/BranchingBox", "value", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/BranchingBox");
        main.visitInsn(DUP);
        main.visitIntInsn(BIPUSH, -4);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/BranchingBox", "<init>", "(I)V", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/BranchingBox", "value", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitFieldInsn(GETSTATIC, "pkg/BranchingStaticInit", "value", "I");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitFieldInsn(GETSTATIC, "pkg/BranchingStaticInit", "label", "Ljava/lang/String;");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] primitiveOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/PrimitiveOps", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "last", "I", null, null).visitEnd();
        defaultConstructor(writer);
        binary(writer, "addLong", "(JJ)J", LLOAD, LADD, LRETURN);
        binary(writer, "addFloat", "(FF)F", FLOAD, FADD, FRETURN);
        binary(writer, "addDouble", "(DD)D", DLOAD, DADD, DRETURN);
        MethodVisitor truth = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "truth", "(Z)Z", null, null);
        truth.visitCode();
        truth.visitVarInsn(ILOAD, 0);
        truth.visitInsn(IRETURN);
        truth.visitMaxs(0, 0);
        truth.visitEnd();
        MethodVisitor mix = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "mix", "(ZIJFD)D", null, null);
        mix.visitCode();
        mix.visitVarInsn(ILOAD, 0);
        mix.visitInsn(I2D);
        mix.visitVarInsn(ILOAD, 1);
        mix.visitInsn(I2D);
        mix.visitInsn(DADD);
        mix.visitVarInsn(LLOAD, 2);
        mix.visitInsn(L2D);
        mix.visitInsn(DADD);
        mix.visitVarInsn(FLOAD, 4);
        mix.visitInsn(F2D);
        mix.visitInsn(DADD);
        mix.visitVarInsn(DLOAD, 5);
        mix.visitInsn(DADD);
        mix.visitInsn(DRETURN);
        mix.visitMaxs(0, 0);
        mix.visitEnd();
        MethodVisitor setLast = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "setLast", "(I)V", null, null);
        setLast.visitCode();
        setLast.visitVarInsn(ILOAD, 0);
        setLast.visitFieldInsn(PUTSTATIC, "pkg/PrimitiveOps", "last", "I");
        setLast.visitInsn(RETURN);
        setLast.visitMaxs(0, 0);
        setLast.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] instanceBoxClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/InstanceBox", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "base", "I", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE, "value", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitFieldInsn(PUTFIELD, "pkg/InstanceBox", "base", "I");
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitFieldInsn(PUTFIELD, "pkg/InstanceBox", "value", "I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor addBase = writer.visitMethod(ACC_PUBLIC, "addBase", "(I)I", null, null);
        addBase.visitCode();
        addBase.visitVarInsn(ALOAD, 0);
        addBase.visitFieldInsn(GETFIELD, "pkg/InstanceBox", "base", "I");
        addBase.visitVarInsn(ILOAD, 1);
        addBase.visitInsn(IADD);
        addBase.visitInsn(IRETURN);
        addBase.visitMaxs(0, 0);
        addBase.visitEnd();
        MethodVisitor bump = writer.visitMethod(ACC_PUBLIC, "bump", "(I)V", null, null);
        bump.visitCode();
        bump.visitVarInsn(ALOAD, 0);
        bump.visitInsn(DUP);
        bump.visitFieldInsn(GETFIELD, "pkg/InstanceBox", "value", "I");
        bump.visitVarInsn(ILOAD, 1);
        bump.visitInsn(IADD);
        bump.visitFieldInsn(PUTFIELD, "pkg/InstanceBox", "value", "I");
        bump.visitInsn(RETURN);
        bump.visitMaxs(0, 0);
        bump.visitEnd();
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitVarInsn(ALOAD, 0);
        value.visitFieldInsn(GETFIELD, "pkg/InstanceBox", "value", "I");
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] abiMainClass() {
        ClassWriter writer = mainClass("pkg/AbiMain");
        MethodVisitor main = beginMain(writer);
        printStaticLong(main, "pkg/PrimitiveOps", "addLong", 11L, 22L);
        printStaticFloat(main, "pkg/PrimitiveOps", "addFloat", 1.25F, 2.25F);
        printStaticDouble(main, "pkg/PrimitiveOps", "addDouble", 1.5D, 3.25D);
        printStaticBoolean(main, "pkg/PrimitiveOps", "truth", true);
        printMix(main);
        main.visitIntInsn(BIPUSH, 91);
        main.visitMethodInsn(INVOKESTATIC, "pkg/PrimitiveOps", "setLast", "(I)V", false);
        printStaticField(main, "pkg/PrimitiveOps", "last");
        main.visitTypeInsn(NEW, "pkg/InstanceBox");
        main.visitInsn(DUP);
        main.visitIntInsn(BIPUSH, 10);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/InstanceBox", "<init>", "(I)V", false);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitInsn(ICONST_5);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/InstanceBox", "addBase", "(I)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitVarInsn(ALOAD, 1);
        main.visitIntInsn(BIPUSH, 7);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/InstanceBox", "bump", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/InstanceBox", "value", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] multiAClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/MultiA", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "marker", "I", null, null).visitEnd();
        defaultConstructor(writer);
        MethodVisitor clinit = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(ICONST_5);
        clinit.visitFieldInsn(PUTSTATIC, "pkg/MultiA", "marker", "I");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor mul = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "mul", "(II)I", null, null);
        mul.visitCode();
        mul.visitVarInsn(ILOAD, 0);
        mul.visitVarInsn(ILOAD, 1);
        mul.visitInsn(IMUL);
        mul.visitInsn(IRETURN);
        mul.visitMaxs(0, 0);
        mul.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] multiBClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/MultiB", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor inc = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "inc", "(I)I", null, null);
        inc.visitCode();
        inc.visitVarInsn(ILOAD, 0);
        inc.visitInsn(ICONST_1);
        inc.visitInsn(IADD);
        inc.visitInsn(IRETURN);
        inc.visitMaxs(0, 0);
        inc.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] multiMainClass() {
        ClassWriter writer = mainClass("pkg/MultiMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitIntInsn(BIPUSH, 6);
        main.visitIntInsn(BIPUSH, 5);
        main.visitMethodInsn(INVOKESTATIC, "pkg/MultiA", "mul", "(II)I", false);
        main.visitFieldInsn(GETSTATIC, "pkg/MultiA", "marker", "I");
        main.visitInsn(IADD);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitIntInsn(BIPUSH, 11);
        main.visitMethodInsn(INVOKESTATIC, "pkg/MultiB", "inc", "(I)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] stringOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/StringOps", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "label", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ALOAD, 1);
        constructor.visitFieldInsn(PUTFIELD, "pkg/StringOps", "label", "Ljava/lang/String;");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor echo = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "echo", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        echo.visitCode();
        org.objectweb.asm.Label notNull = new org.objectweb.asm.Label();
        echo.visitVarInsn(ALOAD, 0);
        echo.visitJumpInsn(IFNONNULL, notNull);
        echo.visitInsn(ACONST_NULL);
        echo.visitInsn(ARETURN);
        echo.visitLabel(notNull);
        echo.visitTypeInsn(NEW, "java/lang/String");
        echo.visitInsn(DUP);
        echo.visitVarInsn(ALOAD, 0);
        echo.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "(Ljava/lang/String;)V", false);
        echo.visitInsn(ARETURN);
        echo.visitMaxs(0, 0);
        echo.visitEnd();
        MethodVisitor length = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "length", "(Ljava/lang/String;)I", null, null);
        length.visitCode();
        length.visitVarInsn(ALOAD, 0);
        length.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        length.visitInsn(IRETURN);
        length.visitMaxs(0, 0);
        length.visitEnd();
        MethodVisitor label = writer.visitMethod(ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null);
        label.visitCode();
        label.visitVarInsn(ALOAD, 0);
        label.visitFieldInsn(GETFIELD, "pkg/StringOps", "label", "Ljava/lang/String;");
        label.visitInsn(ARETURN);
        label.visitMaxs(0, 0);
        label.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] stringMainClass() {
        ClassWriter writer = mainClass("pkg/StringMain");
        MethodVisitor main = beginMain(writer);
        printStaticStringCall(main, "pkg/StringOps", "echo", "hello");
        printStaticStringLength(main, "pkg/StringOps", "length", "abcd");
        main.visitTypeInsn(NEW, "pkg/StringOps");
        main.visitInsn(DUP);
        main.visitLdcInsn("box");
        main.visitMethodInsn(INVOKESPECIAL, "pkg/StringOps", "<init>", "(Ljava/lang/String;)V", false);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "pkg/StringOps", "label", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/StringOps", "echo", "(Ljava/lang/String;)Ljava/lang/String;", false);
        org.objectweb.asm.Label falseLabel = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitJumpInsn(IFNONNULL, falseLabel);
        main.visitInsn(ICONST_1);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(falseLabel);
        main.visitInsn(ICONST_0);
        main.visitLabel(done);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        printNpeCatchForStringLength(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] arrayOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ArrayOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor sum = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "sum", "([I)I", null, null);
        org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        sum.visitCode();
        sum.visitInsn(ICONST_0);
        sum.visitVarInsn(ISTORE, 1);
        sum.visitInsn(ICONST_0);
        sum.visitVarInsn(ISTORE, 2);
        sum.visitLabel(loop);
        sum.visitVarInsn(ILOAD, 2);
        sum.visitVarInsn(ALOAD, 0);
        sum.visitInsn(ARRAYLENGTH);
        sum.visitJumpInsn(IF_ICMPGE, done);
        sum.visitVarInsn(ILOAD, 1);
        sum.visitVarInsn(ALOAD, 0);
        sum.visitVarInsn(ILOAD, 2);
        sum.visitInsn(IALOAD);
        sum.visitInsn(IADD);
        sum.visitVarInsn(ISTORE, 1);
        sum.visitIincInsn(2, 1);
        sum.visitJumpInsn(GOTO, loop);
        sum.visitLabel(done);
        sum.visitVarInsn(ILOAD, 1);
        sum.visitInsn(IRETURN);
        sum.visitMaxs(0, 0);
        sum.visitEnd();
        MethodVisitor copy = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "copyPlusOne", "([I)[I", null, null);
        copy.visitCode();
        copy.visitInsn(ICONST_2);
        copy.visitIntInsn(NEWARRAY, T_INT);
        copy.visitVarInsn(ASTORE, 1);
        copy.visitVarInsn(ALOAD, 1);
        copy.visitInsn(ICONST_0);
        copy.visitVarInsn(ALOAD, 0);
        copy.visitInsn(ICONST_0);
        copy.visitInsn(IALOAD);
        copy.visitInsn(ICONST_1);
        copy.visitInsn(IADD);
        copy.visitInsn(IASTORE);
        copy.visitVarInsn(ALOAD, 1);
        copy.visitInsn(ICONST_1);
        copy.visitVarInsn(ALOAD, 0);
        copy.visitInsn(ICONST_1);
        copy.visitInsn(IALOAD);
        copy.visitInsn(ICONST_1);
        copy.visitInsn(IADD);
        copy.visitInsn(IASTORE);
        copy.visitVarInsn(ALOAD, 1);
        copy.visitInsn(ARETURN);
        copy.visitMaxs(0, 0);
        copy.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] arrayMainClass() {
        ClassWriter writer = mainClass("pkg/ArrayMain");
        MethodVisitor main = beginMain(writer);
        intArray(main, 1, 2, 3);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArrayOps", "sum", "([I)I", false);
        printTopInt(main);
        main.visitInsn(ICONST_0);
        main.visitIntInsn(NEWARRAY, T_INT);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArrayOps", "sum", "([I)I", false);
        printTopInt(main);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        intArray(main, 1, 2);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArrayOps", "copyPlusOne", "([I)[I", false);
        main.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "toString", "([I)Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        printNpeCatchForArraySum(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] exceptionOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ExceptionOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor fail = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "failIfNegative", "(I)I", null, null);
        org.objectweb.asm.Label ok = new org.objectweb.asm.Label();
        fail.visitCode();
        fail.visitVarInsn(ILOAD, 0);
        fail.visitJumpInsn(IFGE, ok);
        fail.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        fail.visitInsn(DUP);
        fail.visitLdcInsn("negative");
        fail.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false);
        fail.visitInsn(ATHROW);
        fail.visitLabel(ok);
        fail.visitVarInsn(ILOAD, 0);
        fail.visitInsn(IRETURN);
        fail.visitMaxs(0, 0);
        fail.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] exceptionMainClass() {
        ClassWriter writer = mainClass("pkg/ExceptionMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitIntInsn(BIPUSH, 7);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ExceptionOps", "failIfNegative", "(I)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/IllegalArgumentException");
        main.visitLabel(start);
        main.visitInsn(ICONST_M1);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ExceptionOps", "failIfNegative", "(I)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false);
        main.visitLdcInsn(":");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "getMessage", "()Ljava/lang/String;", false);
        main.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001\u0001");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] fallbackMainClass() {
        ClassWriter writer = mainClass("pkg/FallbackMain");
        MethodVisitor main = beginMain(writer);
        printStaticStringCall(main, "pkg/JdkFallback", "substring", "abcd");
        printStaticStringCall(main, "pkg/JdkFallback", "substring", "wxyz");
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] collectionOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/CollectionOps", null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor list = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "arrayListSummary", "()Ljava/lang/String;", null, null);
        list.visitCode();
        list.visitTypeInsn(NEW, "java/util/ArrayList");
        list.visitInsn(DUP);
        list.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        list.visitVarInsn(ASTORE, 0);
        list.visitVarInsn(ALOAD, 0);
        list.visitLdcInsn("a");
        list.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        list.visitInsn(POP);
        list.visitVarInsn(ALOAD, 0);
        list.visitLdcInsn("b");
        list.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        list.visitInsn(POP);
        list.visitTypeInsn(NEW, "java/lang/StringBuilder");
        list.visitInsn(DUP);
        list.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        list.visitVarInsn(ALOAD, 0);
        list.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false);
        list.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        list.visitLdcInsn(":");
        list.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        list.visitVarInsn(ALOAD, 0);
        list.visitInsn(ICONST_1);
        list.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "get", "(I)Ljava/lang/Object;", false);
        list.visitTypeInsn(CHECKCAST, "java/lang/String");
        list.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        list.visitLdcInsn(":");
        list.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        list.visitVarInsn(ALOAD, 0);
        list.visitLdcInsn("a");
        list.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "contains", "(Ljava/lang/Object;)Z", false);
        list.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        list.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        list.visitInsn(ARETURN);
        list.visitMaxs(0, 0);
        list.visitEnd();

        MethodVisitor map = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "hashMapSummary", "()Ljava/lang/String;", null, null);
        map.visitCode();
        map.visitTypeInsn(NEW, "java/util/HashMap");
        map.visitInsn(DUP);
        map.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        map.visitVarInsn(ASTORE, 0);
        map.visitVarInsn(ALOAD, 0);
        map.visitLdcInsn("k");
        map.visitLdcInsn("v");
        map.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/util/HashMap",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        map.visitInsn(POP);
        map.visitVarInsn(ALOAD, 0);
        map.visitLdcInsn("k");
        map.visitLdcInsn("v2");
        map.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/util/HashMap",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        map.visitInsn(POP);
        map.visitTypeInsn(NEW, "java/lang/StringBuilder");
        map.visitInsn(DUP);
        map.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        map.visitVarInsn(ALOAD, 0);
        map.visitLdcInsn("k");
        map.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "containsKey", "(Ljava/lang/Object;)Z", false);
        map.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        map.visitLdcInsn(":");
        map.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        map.visitVarInsn(ALOAD, 0);
        map.visitLdcInsn("k");
        map.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        map.visitTypeInsn(CHECKCAST, "java/lang/String");
        map.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        map.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        map.visitInsn(ARETURN);
        map.visitMaxs(0, 0);
        map.visitEnd();

        MethodVisitor arrays = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "arraysSummary", "()Ljava/lang/String;", null, null);
        arrays.visitCode();
        arrays.visitInsn(ICONST_2);
        arrays.visitIntInsn(NEWARRAY, T_INT);
        arrays.visitInsn(DUP);
        arrays.visitInsn(ICONST_0);
        arrays.visitInsn(ICONST_1);
        arrays.visitInsn(IASTORE);
        arrays.visitInsn(DUP);
        arrays.visitInsn(ICONST_1);
        arrays.visitInsn(ICONST_2);
        arrays.visitInsn(IASTORE);
        arrays.visitVarInsn(ASTORE, 0);
        arrays.visitVarInsn(ALOAD, 0);
        arrays.visitInsn(ICONST_3);
        arrays.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "copyOf", "([II)[I", false);
        arrays.visitVarInsn(ASTORE, 1);
        arrays.visitVarInsn(ALOAD, 1);
        arrays.visitInsn(ICONST_2);
        arrays.visitInsn(ICONST_3);
        arrays.visitIntInsn(BIPUSH, 7);
        arrays.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "fill", "([IIII)V", false);
        arrays.visitInsn(ICONST_3);
        arrays.visitIntInsn(NEWARRAY, T_INT);
        arrays.visitInsn(DUP);
        arrays.visitInsn(ICONST_0);
        arrays.visitInsn(ICONST_1);
        arrays.visitInsn(IASTORE);
        arrays.visitInsn(DUP);
        arrays.visitInsn(ICONST_1);
        arrays.visitInsn(ICONST_2);
        arrays.visitInsn(IASTORE);
        arrays.visitInsn(DUP);
        arrays.visitInsn(ICONST_2);
        arrays.visitIntInsn(BIPUSH, 7);
        arrays.visitInsn(IASTORE);
        arrays.visitVarInsn(ASTORE, 2);
        arrays.visitTypeInsn(NEW, "java/lang/StringBuilder");
        arrays.visitInsn(DUP);
        arrays.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        arrays.visitVarInsn(ALOAD, 1);
        arrays.visitVarInsn(ALOAD, 2);
        arrays.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "equals", "([I[I)Z", false);
        arrays.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        arrays.visitLdcInsn(":");
        arrays.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        arrays.visitVarInsn(ALOAD, 1);
        arrays.visitInsn(ICONST_2);
        arrays.visitInsn(IALOAD);
        arrays.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        arrays.visitLdcInsn(":");
        arrays.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        arrays.visitVarInsn(ALOAD, 1);
        arrays.visitInsn(ARRAYLENGTH);
        arrays.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        arrays.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        arrays.visitInsn(ARETURN);
        arrays.visitMaxs(0, 0);
        arrays.visitEnd();

        MethodVisitor optional = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "optionalCollectionsFormatSummary",
                "()Ljava/lang/String;",
                null,
                null);
        optional.visitCode();
        optional.visitLdcInsn("x");
        optional.visitMethodInsn(
                INVOKESTATIC,
                "java/util/Optional",
                "ofNullable",
                "(Ljava/lang/Object;)Ljava/util/Optional;",
                false);
        optional.visitVarInsn(ASTORE, 0);
        optional.visitInsn(ACONST_NULL);
        optional.visitMethodInsn(
                INVOKESTATIC,
                "java/util/Optional",
                "ofNullable",
                "(Ljava/lang/Object;)Ljava/util/Optional;",
                false);
        optional.visitVarInsn(ASTORE, 1);
        optional.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false);
        optional.visitVarInsn(ASTORE, 2);
        optional.visitLdcInsn("one");
        optional.visitMethodInsn(
                INVOKESTATIC,
                "java/util/Collections",
                "singletonList",
                "(Ljava/lang/Object;)Ljava/util/List;",
                false);
        optional.visitVarInsn(ASTORE, 3);
        optional.visitInsn(ICONST_2);
        optional.visitTypeInsn(ANEWARRAY, "java/lang/String");
        optional.visitInsn(DUP);
        optional.visitInsn(ICONST_0);
        optional.visitLdcInsn("a");
        optional.visitInsn(AASTORE);
        optional.visitInsn(DUP);
        optional.visitInsn(ICONST_1);
        optional.visitLdcInsn("b");
        optional.visitInsn(AASTORE);
        optional.visitMethodInsn(
                INVOKESTATIC,
                "java/util/Arrays",
                "asList",
                "([Ljava/lang/Object;)Ljava/util/List;",
                false);
        optional.visitVarInsn(ASTORE, 4);
        optional.visitTypeInsn(NEW, "java/lang/StringBuilder");
        optional.visitInsn(DUP);
        optional.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        optional.visitVarInsn(ALOAD, 0);
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false);
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        optional.visitLdcInsn(":");
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitVarInsn(ALOAD, 0);
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false);
        optional.visitTypeInsn(CHECKCAST, "java/lang/String");
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitLdcInsn(":");
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitVarInsn(ALOAD, 1);
        optional.visitLdcInsn("fallback");
        optional.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/util/Optional",
                "orElse",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        optional.visitTypeInsn(CHECKCAST, "java/lang/String");
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitLdcInsn(":");
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitVarInsn(ALOAD, 2);
        optional.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        optional.visitLdcInsn(":");
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitVarInsn(ALOAD, 3);
        optional.visitInsn(ICONST_0);
        optional.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        optional.visitTypeInsn(CHECKCAST, "java/lang/String");
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitLdcInsn(":");
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitVarInsn(ALOAD, 4);
        optional.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        optional.visitLdcInsn(":");
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitLdcInsn("%s-%d");
        optional.visitInsn(ICONST_2);
        optional.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        optional.visitInsn(DUP);
        optional.visitInsn(ICONST_0);
        optional.visitLdcInsn("fmt");
        optional.visitInsn(AASTORE);
        optional.visitInsn(DUP);
        optional.visitInsn(ICONST_1);
        optional.visitIntInsn(BIPUSH, 7);
        optional.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        optional.visitInsn(AASTORE);
        optional.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/String",
                "format",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                false);
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        optional.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        optional.visitInsn(ARETURN);
        optional.visitMaxs(0, 0);
        optional.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] collectionMainClass() {
        ClassWriter writer = mainClass("pkg/CollectionMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/CollectionOps", "arrayListSummary", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/CollectionOps", "hashMapSummary", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/CollectionOps", "arraysSummary", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/CollectionOps",
                "optionalCollectionsFormatSummary",
                "()Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] corpusMainClass() {
        ClassWriter writer = mainClass("pkg/CorpusMain");
        MethodVisitor main = beginMain(writer);
        callMain(main, "pkg/ReflectionMain");
        callMain(main, "pkg/ArraycopyMain");
        callMain(main, "pkg/LambdaMain");
        callMain(main, "pkg/CollectionMain");
        callMain(main, "pkg/ThrowableMain");
        callMain(main, "pkg/ThreadMain");
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void callMain(MethodVisitor method, String owner) {
        method.visitInsn(ICONST_0);
        method.visitTypeInsn(ANEWARRAY, "java/lang/String");
        method.visitMethodInsn(INVOKESTATIC, owner, "main", "([Ljava/lang/String;)V", false);
    }

    private byte[] reflectionDynamicFallbackClass() {
        ClassWriter writer = mainClass("pkg/ReflectFallback");
        MethodVisitor dynamicForName = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "dynamicForName",
                "(Ljava/lang/String;)V",
                null,
                new String[] {"java/lang/Exception"});
        dynamicForName.visitCode();
        dynamicForName.visitVarInsn(ALOAD, 0);
        dynamicForName.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        dynamicForName.visitInsn(POP);
        dynamicForName.visitInsn(RETURN);
        dynamicForName.visitMaxs(0, 0);
        dynamicForName.visitEnd();

        MethodVisitor dynamicMethodName = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "dynamicMethodName",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        dynamicMethodName.visitCode();
        dynamicMethodName.visitLdcInsn(org.objectweb.asm.Type.getObjectType("java/lang/String"));
        dynamicMethodName.visitVarInsn(ALOAD, 0);
        dynamicMethodName.visitInsn(ICONST_0);
        dynamicMethodName.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        dynamicMethodName.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        dynamicMethodName.visitLdcInsn(" ok ");
        dynamicMethodName.visitInsn(ICONST_0);
        dynamicMethodName.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        dynamicMethodName.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        dynamicMethodName.visitTypeInsn(CHECKCAST, "java/lang/String");
        dynamicMethodName.visitInsn(ARETURN);
        dynamicMethodName.visitMaxs(0, 0);
        dynamicMethodName.visitEnd();

        MethodVisitor dynamicParameterArray = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "dynamicParameterArray",
                "([Ljava/lang/Class;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Exception"});
        dynamicParameterArray.visitCode();
        dynamicParameterArray.visitLdcInsn(org.objectweb.asm.Type.getObjectType("java/lang/String"));
        dynamicParameterArray.visitLdcInsn("substring");
        dynamicParameterArray.visitVarInsn(ALOAD, 0);
        dynamicParameterArray.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        dynamicParameterArray.visitLdcInsn("hello");
        dynamicParameterArray.visitInsn(ICONST_1);
        dynamicParameterArray.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        dynamicParameterArray.visitInsn(DUP);
        dynamicParameterArray.visitInsn(ICONST_0);
        dynamicParameterArray.visitInsn(ICONST_1);
        dynamicParameterArray.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        dynamicParameterArray.visitInsn(AASTORE);
        dynamicParameterArray.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        dynamicParameterArray.visitTypeInsn(CHECKCAST, "java/lang/String");
        dynamicParameterArray.visitInsn(ARETURN);
        dynamicParameterArray.visitMaxs(0, 0);
        dynamicParameterArray.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] reflectionFallbackMainClass() {
        ClassWriter writer = mainClass("pkg/ReflectionFallbackMain");
        MethodVisitor main = beginMain(writer);
        main.visitLdcInsn("java.lang.String");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectFallback",
                "dynamicForName",
                "(Ljava/lang/String;)V",
                false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("trim");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectFallback",
                "dynamicMethodName",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ICONST_1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        main.visitInsn(DUP);
        main.visitInsn(ICONST_0);
        main.visitFieldInsn(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
        main.visitInsn(AASTORE);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReflectFallback",
                "dynamicParameterArray",
                "([Ljava/lang/Class;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("reflection-ok");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] instanceFallbackClass() {
        ClassWriter writer = mainClass("pkg/InstanceFallback");
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "tail",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] instanceFallbackMainClass() {
        ClassWriter writer = mainClass("pkg/InstanceFallbackMain");
        MethodVisitor main = beginMain(writer);
        main.visitTypeInsn(NEW, "pkg/InstanceFallback");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/InstanceFallback", "<init>", "()V", false);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitLdcInsn("hello");
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "pkg/InstanceFallback",
                "tail",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitVarInsn(ALOAD, 1);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "pkg/InstanceFallback",
                "tail",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitVarInsn(ASTORE, 2);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("caught-npe");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] methodHandleAdapterFallbackClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/MethodHandleAdapterFallback", null, "java/lang/Object", null);
        defaultConstructor(writer);

        MethodVisitor prefixTarget = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "prefixTarget",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        prefixTarget.visitCode();
        prefixTarget.visitVarInsn(ALOAD, 0);
        prefixTarget.visitVarInsn(ALOAD, 1);
        prefixTarget.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        prefixTarget.visitInsn(ARETURN);
        prefixTarget.visitMaxs(0, 0);
        prefixTarget.visitEnd();

        MethodVisitor identityString = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "identityString",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        identityString.visitCode();
        identityString.visitVarInsn(ALOAD, 0);
        identityString.visitInsn(ARETURN);
        identityString.visitMaxs(0, 0);
        identityString.visitEnd();

        MethodVisitor filterPrefix = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "filterPrefix",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        filterPrefix.visitCode();
        filterPrefix.visitLdcInsn("f:");
        filterPrefix.visitVarInsn(ALOAD, 0);
        filterPrefix.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        filterPrefix.visitInsn(ARETURN);
        filterPrefix.visitMaxs(0, 0);
        filterPrefix.visitEnd();

        MethodVisitor constantPrefix = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "constantPrefix",
                "()Ljava/lang/String;",
                null,
                null);
        constantPrefix.visitCode();
        constantPrefix.visitLdcInsn("fold:");
        constantPrefix.visitInsn(ARETURN);
        constantPrefix.visitMaxs(0, 0);
        constantPrefix.visitEnd();

        MethodVisitor lengthObject = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "lengthObject",
                "(Ljava/lang/Object;)I",
                null,
                null);
        lengthObject.visitCode();
        lengthObject.visitVarInsn(ALOAD, 0);
        lengthObject.visitTypeInsn(CHECKCAST, "java/lang/String");
        lengthObject.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        lengthObject.visitInsn(IRETURN);
        lengthObject.visitMaxs(0, 0);
        lengthObject.visitEnd();

        MethodVisitor throwTarget = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "throwTarget",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        org.objectweb.asm.Label ok = new org.objectweb.asm.Label();
        throwTarget.visitCode();
        throwTarget.visitVarInsn(ALOAD, 1);
        throwTarget.visitLdcInsn("!");
        throwTarget.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        throwTarget.visitJumpInsn(IFEQ, ok);
        throwTarget.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        throwTarget.visitInsn(DUP);
        throwTarget.visitLdcInsn("bang");
        throwTarget.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/IllegalArgumentException",
                "<init>",
                "(Ljava/lang/String;)V",
                false);
        throwTarget.visitInsn(ATHROW);
        throwTarget.visitLabel(ok);
        throwTarget.visitVarInsn(ALOAD, 0);
        throwTarget.visitVarInsn(ALOAD, 1);
        throwTarget.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        throwTarget.visitInsn(ARETURN);
        throwTarget.visitMaxs(0, 0);
        throwTarget.visitEnd();

        MethodVisitor arrayTarget = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "arrayTarget",
                "(Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        arrayTarget.visitCode();
        arrayTarget.visitVarInsn(ALOAD, 0);
        arrayTarget.visitVarInsn(ALOAD, 1);
        arrayTarget.visitInsn(ARRAYLENGTH);
        arrayTarget.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false),
                "\u0001\u0001");
        arrayTarget.visitInsn(ARETURN);
        arrayTarget.visitMaxs(0, 0);
        arrayTarget.visitEnd();

        MethodVisitor bindPrefix = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "bindPrefix",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        bindPrefix.visitCode();
        emitFindStaticMethodHandle(
                bindPrefix,
                "pkg/MethodHandleAdapterFallback",
                "prefixTarget",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        bindPrefix.visitLdcInsn("pre-");
        bindPrefix.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "bindTo",
                "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;",
                false);
        bindPrefix.visitVarInsn(ALOAD, 0);
        bindPrefix.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        bindPrefix.visitInsn(ARETURN);
        bindPrefix.visitMaxs(0, 0);
        bindPrefix.visitEnd();

        MethodVisitor asTypeLength = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "asTypeLength",
                "(Ljava/lang/String;)I",
                null,
                null);
        asTypeLength.visitCode();
        emitFindStaticMethodHandle(
                asTypeLength,
                "pkg/MethodHandleAdapterFallback",
                "lengthObject",
                "(Ljava/lang/Object;)I");
        asTypeLength.visitLdcInsn(org.objectweb.asm.Type.getMethodType("(Ljava/lang/String;)I"));
        asTypeLength.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "asType",
                "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                false);
        asTypeLength.visitVarInsn(ALOAD, 0);
        asTypeLength.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "(Ljava/lang/String;)I",
                false);
        asTypeLength.visitInsn(IRETURN);
        asTypeLength.visitMaxs(0, 0);
        asTypeLength.visitEnd();

        MethodVisitor dropMiddle = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "dropMiddle",
                "(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
                null,
                null);
        dropMiddle.visitCode();
        emitFindStaticMethodHandle(
                dropMiddle,
                "pkg/MethodHandleAdapterFallback",
                "prefixTarget",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        dropMiddle.visitInsn(ICONST_1);
        dropMiddle.visitInsn(ICONST_1);
        dropMiddle.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        dropMiddle.visitInsn(DUP);
        dropMiddle.visitInsn(ICONST_0);
        dropMiddle.visitFieldInsn(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
        dropMiddle.visitInsn(AASTORE);
        dropMiddle.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "dropArguments",
                "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
                false);
        dropMiddle.visitVarInsn(ALOAD, 0);
        dropMiddle.visitVarInsn(ILOAD, 1);
        dropMiddle.visitVarInsn(ALOAD, 2);
        dropMiddle.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
                false);
        dropMiddle.visitInsn(ARETURN);
        dropMiddle.visitMaxs(0, 0);
        dropMiddle.visitEnd();

        MethodVisitor permuteJoin = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "permuteJoin",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Throwable"});
        permuteJoin.visitCode();
        emitFindStaticMethodHandle(
                permuteJoin,
                "pkg/MethodHandleAdapterFallback",
                "prefixTarget",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        permuteJoin.visitLdcInsn(org.objectweb.asm.Type.getMethodType("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
        permuteJoin.visitInsn(ICONST_2);
        permuteJoin.visitIntInsn(NEWARRAY, T_INT);
        permuteJoin.visitInsn(DUP);
        permuteJoin.visitInsn(ICONST_0);
        permuteJoin.visitInsn(ICONST_1);
        permuteJoin.visitInsn(IASTORE);
        permuteJoin.visitInsn(DUP);
        permuteJoin.visitInsn(ICONST_1);
        permuteJoin.visitInsn(ICONST_0);
        permuteJoin.visitInsn(IASTORE);
        permuteJoin.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "permuteArguments",
                "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;[I)Ljava/lang/invoke/MethodHandle;",
                false);
        permuteJoin.visitVarInsn(ALOAD, 0);
        permuteJoin.visitVarInsn(ALOAD, 1);
        permuteJoin.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        permuteJoin.visitInsn(ARETURN);
        permuteJoin.visitMaxs(0, 0);
        permuteJoin.visitEnd();

        MethodVisitor filterArgument = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "filterArgument",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Throwable"});
        filterArgument.visitCode();
        emitFindStaticMethodHandle(
                filterArgument,
                "pkg/MethodHandleAdapterFallback",
                "identityString",
                "(Ljava/lang/String;)Ljava/lang/String;");
        filterArgument.visitVarInsn(ASTORE, 1);
        emitFindStaticMethodHandle(
                filterArgument,
                "pkg/MethodHandleAdapterFallback",
                "filterPrefix",
                "(Ljava/lang/String;)Ljava/lang/String;");
        filterArgument.visitVarInsn(ASTORE, 2);
        filterArgument.visitVarInsn(ALOAD, 1);
        filterArgument.visitInsn(ICONST_0);
        filterArgument.visitInsn(ICONST_1);
        filterArgument.visitTypeInsn(ANEWARRAY, "java/lang/invoke/MethodHandle");
        filterArgument.visitInsn(DUP);
        filterArgument.visitInsn(ICONST_0);
        filterArgument.visitVarInsn(ALOAD, 2);
        filterArgument.visitInsn(AASTORE);
        filterArgument.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "filterArguments",
                "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandle;",
                false);
        filterArgument.visitVarInsn(ALOAD, 0);
        filterArgument.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        filterArgument.visitInsn(ARETURN);
        filterArgument.visitMaxs(0, 0);
        filterArgument.visitEnd();

        MethodVisitor foldPrefix = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "foldPrefix",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                new String[] {"java/lang/Throwable"});
        foldPrefix.visitCode();
        emitFindStaticMethodHandle(
                foldPrefix,
                "pkg/MethodHandleAdapterFallback",
                "prefixTarget",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        foldPrefix.visitVarInsn(ASTORE, 1);
        emitFindStaticMethodHandle(
                foldPrefix,
                "pkg/MethodHandleAdapterFallback",
                "constantPrefix",
                "()Ljava/lang/String;");
        foldPrefix.visitVarInsn(ASTORE, 2);
        foldPrefix.visitVarInsn(ALOAD, 1);
        foldPrefix.visitVarInsn(ALOAD, 2);
        foldPrefix.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "foldArguments",
                "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandle;",
                false);
        foldPrefix.visitVarInsn(ALOAD, 0);
        foldPrefix.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        foldPrefix.visitInsn(ARETURN);
        foldPrefix.visitMaxs(0, 0);
        foldPrefix.visitEnd();

        MethodVisitor collectorBoundary = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "collectorBoundary",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        collectorBoundary.visitCode();
        emitFindStaticMethodHandle(
                collectorBoundary,
                "pkg/MethodHandleAdapterFallback",
                "arrayTarget",
                "(Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;");
        collectorBoundary.visitLdcInsn(org.objectweb.asm.Type.getType("[Ljava/lang/String;"));
        collectorBoundary.visitInsn(ICONST_2);
        collectorBoundary.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "asCollector",
                "(Ljava/lang/Class;I)Ljava/lang/invoke/MethodHandle;",
                false);
        collectorBoundary.visitVarInsn(ALOAD, 0);
        collectorBoundary.visitVarInsn(ALOAD, 1);
        collectorBoundary.visitVarInsn(ALOAD, 2);
        collectorBoundary.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        collectorBoundary.visitInsn(ARETURN);
        collectorBoundary.visitMaxs(0, 0);
        collectorBoundary.visitEnd();

        MethodVisitor throwing = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "throwing",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        throwing.visitCode();
        emitFindStaticMethodHandle(
                throwing,
                "pkg/MethodHandleAdapterFallback",
                "throwTarget",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        throwing.visitLdcInsn("pre-");
        throwing.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "bindTo",
                "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;",
                false);
        throwing.visitVarInsn(ALOAD, 0);
        throwing.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        throwing.visitInsn(ARETURN);
        throwing.visitMaxs(0, 0);
        throwing.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitFindStaticMethodHandle(
            MethodVisitor method,
            String owner,
            String name,
            String descriptor) {
        method.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;",
                false);
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType(owner));
        method.visitLdcInsn(name);
        method.visitLdcInsn(org.objectweb.asm.Type.getMethodType(descriptor));
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandles$Lookup",
                "findStatic",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                false);
    }

    private byte[] methodHandleAdapterMainClass() {
        ClassWriter writer = mainClass("pkg/MethodHandleAdapterMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("value");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/MethodHandleAdapterFallback",
                "bindPrefix",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("hello");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/MethodHandleAdapterFallback",
                "asTypeLength",
                "(Ljava/lang/String;)I",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("pre-");
        main.visitIntInsn(BIPUSH, 99);
        main.visitLdcInsn("post");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/MethodHandleAdapterFallback",
                "dropMiddle",
                "(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("L");
        main.visitLdcInsn("R");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/MethodHandleAdapterFallback",
                "permuteJoin",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("raw");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/MethodHandleAdapterFallback",
                "filterArgument",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("ok");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/MethodHandleAdapterFallback",
                "foldPrefix",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("col:");
        main.visitLdcInsn("a");
        main.visitLdcInsn("b");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/MethodHandleAdapterFallback",
                "collectorBoundary",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/IllegalArgumentException");
        main.visitLabel(start);
        main.visitLdcInsn("!");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/MethodHandleAdapterFallback",
                "throwing",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/IllegalArgumentException",
                "getMessage",
                "()Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] altLambdaFallbackClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/AltLambdaFallback", null, "java/lang/Object", null);
        defaultConstructor(writer);

        MethodVisitor join = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "join",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        join.visitCode();
        join.visitVarInsn(ALOAD, 0);
        join.visitVarInsn(ALOAD, 1);
        join.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        join.visitInsn(ARETURN);
        join.visitMaxs(0, 0);
        join.visitEnd();

        MethodVisitor lambda = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "serializableTwoCapture",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/function/Supplier;",
                null,
                null);
        lambda.visitCode();
        lambda.visitVarInsn(ALOAD, 0);
        lambda.visitVarInsn(ALOAD, 1);
        lambda.visitInvokeDynamicInsn(
                "get",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/function/Supplier;",
                lambdaMetafactoryBootstrap("altMetafactory"),
                org.objectweb.asm.Type.getMethodType("()Ljava/lang/Object;"),
                new org.objectweb.asm.Handle(
                        H_INVOKESTATIC,
                        "pkg/AltLambdaFallback",
                        "join",
                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                        false),
                org.objectweb.asm.Type.getMethodType("()Ljava/lang/String;"),
                1);
        lambda.visitInsn(ARETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] altLambdaFallbackMainClass() {
        ClassWriter writer = mainClass("pkg/AltLambdaFallbackMain");
        MethodVisitor main = beginMain(writer);
        main.visitLdcInsn("left-");
        main.visitLdcInsn("right");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/AltLambdaFallback",
                "serializableTwoCapture",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/function/Supplier;",
                false);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKEINTERFACE,
                "java/util/function/Supplier",
                "get",
                "()Ljava/lang/Object;",
                true);
        main.visitTypeInsn(CHECKCAST, "java/lang/String");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitTypeInsn(INSTANCEOF, "java/io/Serializable");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private org.objectweb.asm.Handle lambdaMetafactoryBootstrap(String name) {
        String descriptor = name.equals("metafactory")
                ? "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"
                : "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
        return new org.objectweb.asm.Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                name,
                descriptor,
                false);
    }

    private byte[] fallbackClassLoaderMainClass() {
        ClassWriter writer = mainClass("pkg/FallbackClassLoaderMain");
        emitNewIsolatedLoader(writer);
        emitFallbackClassLoaderCall(writer);
        emitSameFallbackClass(writer);

        MethodVisitor main = beginMain(writer);
        main.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/FallbackClassLoaderMain"));
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getProtectionDomain",
                "()Ljava/security/ProtectionDomain;",
                false);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/security/ProtectionDomain",
                "getCodeSource",
                "()Ljava/security/CodeSource;",
                false);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/security/CodeSource",
                "getLocation",
                "()Ljava/net/URL;",
                false);
        main.visitVarInsn(ASTORE, 1);
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/FallbackClassLoaderMain",
                "newIsolatedLoader",
                "(Ljava/net/URL;)Ljava/net/URLClassLoader;",
                false);
        main.visitVarInsn(ASTORE, 2);
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/FallbackClassLoaderMain",
                "newIsolatedLoader",
                "(Ljava/net/URL;)Ljava/net/URLClassLoader;",
                false);
        main.visitVarInsn(ASTORE, 3);
        printFallbackClassLoaderCall(main, 2, "abcd");
        printFallbackClassLoaderCall(main, 2, "wxyz");
        printFallbackClassLoaderCall(main, 3, "abcd");
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 2);
        main.visitVarInsn(ALOAD, 3);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/FallbackClassLoaderMain",
                "sameFallbackClass",
                "(Ljava/lang/ClassLoader;Ljava/lang/ClassLoader;)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitNewIsolatedLoader(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                "newIsolatedLoader",
                "(Ljava/net/URL;)Ljava/net/URLClassLoader;",
                null,
                null);
        method.visitCode();
        method.visitTypeInsn(NEW, "java/net/URLClassLoader");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_1);
        method.visitTypeInsn(ANEWARRAY, "java/net/URL");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(AASTORE);
        method.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/ClassLoader",
                "getPlatformClassLoader",
                "()Ljava/lang/ClassLoader;",
                false);
        method.visitMethodInsn(
                INVOKESPECIAL,
                "java/net/URLClassLoader",
                "<init>",
                "([Ljava/net/URL;Ljava/lang/ClassLoader;)V",
                false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void emitFallbackClassLoaderCall(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                "call",
                "(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/Thread",
                "currentThread",
                "()Ljava/lang/Thread;",
                false);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Thread",
                "setContextClassLoader",
                "(Ljava/lang/ClassLoader;)V",
                false);
        method.visitLdcInsn("pkg.JdkFallback");
        method.visitInsn(ICONST_1);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                false);
        method.visitVarInsn(ASTORE, 2);
        method.visitVarInsn(ALOAD, 2);
        method.visitLdcInsn("substring");
        method.visitInsn(ICONST_1);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitLdcInsn(org.objectweb.asm.Type.getType("Ljava/lang/String;"));
        method.visitInsn(AASTORE);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        method.visitVarInsn(ASTORE, 3);
        method.visitVarInsn(ALOAD, 3);
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ICONST_1);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(AASTORE);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        method.visitTypeInsn(CHECKCAST, "java/lang/String");
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void emitSameFallbackClass(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                "sameFallbackClass",
                "(Ljava/lang/ClassLoader;Ljava/lang/ClassLoader;)Z",
                null,
                null);
        org.objectweb.asm.Label notSame = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitLdcInsn("pkg.JdkFallback");
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                false);
        method.visitVarInsn(ASTORE, 2);
        method.visitLdcInsn("pkg.JdkFallback");
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                false);
        method.visitVarInsn(ASTORE, 3);
        method.visitVarInsn(ALOAD, 2);
        method.visitVarInsn(ALOAD, 3);
        method.visitJumpInsn(IF_ACMPNE, notSame);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitLabel(notSame);
        method.visitInsn(ICONST_0);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void printFallbackClassLoaderCall(MethodVisitor main, int loaderSlot, String value) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, loaderSlot);
        main.visitLdcInsn(value);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/FallbackClassLoaderMain",
                "call",
                "(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
    }

    private ClassWriter mainClass(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        defaultConstructor(writer);
        return writer;
    }

    private MethodVisitor beginMain(ClassWriter writer) {
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        return main;
    }

    private void endMain(MethodVisitor main) {
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
    }

    private void defaultConstructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private void binary(ClassWriter writer, String name, String descriptor, int loadOpcode, int addOpcode, int returnOpcode) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(loadOpcode, 0);
        method.visitVarInsn(loadOpcode, loadOpcode == LLOAD || loadOpcode == DLOAD ? 2 : 1);
        method.visitInsn(addOpcode);
        method.visitInsn(returnOpcode);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void primitiveArrayLength(ClassWriter writer, String name, int arrayType) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(I)I", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitIntInsn(NEWARRAY, arrayType);
        method.visitInsn(ARRAYLENGTH);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void printStaticLong(MethodVisitor main, String owner, String name, long a, long b) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(a);
        main.visitLdcInsn(b);
        main.visitMethodInsn(INVOKESTATIC, owner, name, "(JJ)J", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(J)V", false);
    }

    private void printStaticFloat(MethodVisitor main, String owner, String name, float a, float b) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(a);
        main.visitLdcInsn(b);
        main.visitMethodInsn(INVOKESTATIC, owner, name, "(FF)F", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(F)V", false);
    }

    private void printStaticDouble(MethodVisitor main, String owner, String name, double a, double b) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(a);
        main.visitLdcInsn(b);
        main.visitMethodInsn(INVOKESTATIC, owner, name, "(DD)D", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(D)V", false);
    }

    private void printStaticFloatRawBits(MethodVisitor main, String owner, String name) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, owner, name, "()F", false);
        main.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private void printStaticDoubleRawBits(MethodVisitor main, String owner, String name) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, owner, name, "()D", false);
        main.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(J)V", false);
    }

    private void printStaticBoolean(MethodVisitor main, String owner, String name, boolean value) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(value ? ICONST_1 : ICONST_0);
        main.visitMethodInsn(INVOKESTATIC, owner, name, "(Z)Z", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
    }

    private void printStaticIntCompare(MethodVisitor main, String owner, String name, int a, int b) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        pushInt(main, a);
        pushInt(main, b);
        main.visitMethodInsn(INVOKESTATIC, owner, name, "(II)Z", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
    }

    private void printStaticIntCall(MethodVisitor main, String owner, String name, int value) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        pushInt(main, value);
        main.visitMethodInsn(INVOKESTATIC, owner, name, "(I)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private void printStaticIntPair(MethodVisitor main, String owner, String name, int left, int right) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        pushInt(main, left);
        pushInt(main, right);
        main.visitMethodInsn(INVOKESTATIC, owner, name, "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private void printStaticLongCompare(MethodVisitor main, long left, long right) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(left);
        main.visitLdcInsn(right);
        main.visitMethodInsn(INVOKESTATIC, "pkg/CompareMore", "longCmp", "(JJ)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private void printStaticFloatCompare(MethodVisitor main, float left, float right) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(left);
        main.visitLdcInsn(right);
        main.visitMethodInsn(INVOKESTATIC, "pkg/CompareMore", "floatCmp", "(FF)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private void printStaticBooleanIntCall(MethodVisitor main, String owner, String name, int value) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        pushInt(main, value);
        main.visitMethodInsn(INVOKESTATIC, owner, name, "(I)Z", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
    }

    private void printMix(MethodVisitor main) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ICONST_1);
        main.visitInsn(ICONST_2);
        main.visitLdcInsn(3L);
        main.visitLdcInsn(4.5F);
        main.visitLdcInsn(6.25D);
        main.visitMethodInsn(INVOKESTATIC, "pkg/PrimitiveOps", "mix", "(ZIJFD)D", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(D)V", false);
    }

    private void printStaticField(MethodVisitor main, String owner, String name) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitFieldInsn(GETSTATIC, owner, name, "I");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private void printStaticStringCall(MethodVisitor main, String owner, String method, String value) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(value);
        main.visitMethodInsn(INVOKESTATIC, owner, method, "(Ljava/lang/String;)Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
    }

    private void classArrayOfString(MethodVisitor method) {
        method.visitInsn(ICONST_1);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType("java/lang/String"));
        method.visitInsn(AASTORE);
    }

    private void classArrayOfObject(MethodVisitor method) {
        method.visitInsn(ICONST_1);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType("java/lang/Object"));
        method.visitInsn(AASTORE);
    }

    private void classArrayOfIntegerAndLong(MethodVisitor method) {
        method.visitInsn(ICONST_2);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitFieldInsn(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
        method.visitInsn(AASTORE);
        method.visitInsn(DUP);
        method.visitInsn(ICONST_1);
        method.visitFieldInsn(GETSTATIC, "java/lang/Long", "TYPE", "Ljava/lang/Class;");
        method.visitInsn(AASTORE);
    }

    private void classArrayOfIntegerAndString(MethodVisitor method) {
        method.visitInsn(ICONST_2);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitFieldInsn(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
        method.visitInsn(AASTORE);
        method.visitInsn(DUP);
        method.visitInsn(ICONST_1);
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType("java/lang/String"));
        method.visitInsn(AASTORE);
    }

    private void classArrayOfIntArray(MethodVisitor method) {
        method.visitInsn(ICONST_1);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitLdcInsn(org.objectweb.asm.Type.getType("[I"));
        method.visitInsn(AASTORE);
    }

    private void printStaticStringLength(MethodVisitor main, String owner, String method, String value) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(value);
        main.visitMethodInsn(INVOKESTATIC, owner, method, "(Ljava/lang/String;)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private void printStaticStringEquals(MethodVisitor main, String left, String right) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(left);
        main.visitLdcInsn(right);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/AllocationStringOps",
                "same",
                "(Ljava/lang/String;Ljava/lang/String;)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
    }

    private void printAllocationStringEmpty(MethodVisitor main) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("");
        main.visitMethodInsn(INVOKESTATIC, "pkg/AllocationStringOps", "empty", "(Ljava/lang/String;)Z", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
    }

    private void printAllocationStringCharAt(MethodVisitor main) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("abc");
        main.visitInsn(ICONST_1);
        main.visitMethodInsn(INVOKESTATIC, "pkg/AllocationStringOps", "charAt", "(Ljava/lang/String;I)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private void printAllocationStringStartsEnds(MethodVisitor main) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("hello");
        main.visitLdcInsn("he");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/AllocationStringOps",
                "starts",
                "(Ljava/lang/String;Ljava/lang/String;)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("hello");
        main.visitLdcInsn("lo");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/AllocationStringOps",
                "ends",
                "(Ljava/lang/String;Ljava/lang/String;)Z",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
    }

    private void printAllocationStringBuilder(MethodVisitor main) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("x");
        main.visitIntInsn(BIPUSH, 7);
        main.visitLdcInsn(42L);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/AllocationStringOps",
                "builder",
                "(Ljava/lang/String;IJ)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
    }

    private void printTopInt(MethodVisitor main) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(SWAP);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private void printArithmeticCatch(MethodVisitor main, String method) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/ArithmeticException");
        main.visitLabel(start);
        main.visitInsn(ICONST_1);
        main.visitInsn(ICONST_0);
        main.visitMethodInsn(INVOKESTATIC, "pkg/DivRemOps", method, "(II)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "getMessage", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void intArray(MethodVisitor main, int... values) {
        pushInt(main, values.length);
        main.visitIntInsn(NEWARRAY, T_INT);
        for (int index = 0; index < values.length; index++) {
            main.visitInsn(DUP);
            pushInt(main, index);
            pushInt(main, values[index]);
            main.visitInsn(IASTORE);
        }
    }

    private void byteArray(MethodVisitor main, int... values) {
        pushInt(main, values.length);
        main.visitIntInsn(NEWARRAY, T_BYTE);
        for (int index = 0; index < values.length; index++) {
            main.visitInsn(DUP);
            pushInt(main, index);
            pushInt(main, values[index]);
            main.visitInsn(BASTORE);
        }
    }

    private void shortArray(MethodVisitor main, int... values) {
        pushInt(main, values.length);
        main.visitIntInsn(NEWARRAY, T_SHORT);
        for (int index = 0; index < values.length; index++) {
            main.visitInsn(DUP);
            pushInt(main, index);
            pushInt(main, values[index]);
            main.visitInsn(SASTORE);
        }
    }

    private void charArray(MethodVisitor main, int... values) {
        pushInt(main, values.length);
        main.visitIntInsn(NEWARRAY, T_CHAR);
        for (int index = 0; index < values.length; index++) {
            main.visitInsn(DUP);
            pushInt(main, index);
            pushInt(main, values[index]);
            main.visitInsn(CASTORE);
        }
    }

    private void longArray(MethodVisitor main, long... values) {
        pushInt(main, values.length);
        main.visitIntInsn(NEWARRAY, T_LONG);
        for (int index = 0; index < values.length; index++) {
            main.visitInsn(DUP);
            pushInt(main, index);
            main.visitLdcInsn(values[index]);
            main.visitInsn(LASTORE);
        }
    }

    private void floatArray(MethodVisitor main, float... values) {
        pushInt(main, values.length);
        main.visitIntInsn(NEWARRAY, T_FLOAT);
        for (int index = 0; index < values.length; index++) {
            main.visitInsn(DUP);
            pushInt(main, index);
            main.visitLdcInsn(values[index]);
            main.visitInsn(FASTORE);
        }
    }

    private void doubleArray(MethodVisitor main, double... values) {
        pushInt(main, values.length);
        main.visitIntInsn(NEWARRAY, T_DOUBLE);
        for (int index = 0; index < values.length; index++) {
            main.visitInsn(DUP);
            pushInt(main, index);
            main.visitLdcInsn(values[index]);
            main.visitInsn(DASTORE);
        }
    }

    private void pushInt(MethodVisitor method, int value) {
        if (value >= -1 && value <= 5) {
            method.visitInsn(ICONST_0 + value);
        } else {
            method.visitIntInsn(BIPUSH, value);
        }
    }

    private void printClassCastCatchForObjectType(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/ClassCastException");
        main.visitLabel(start);
        main.visitTypeInsn(NEW, "java/lang/Object");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ObjectTypeOps",
                "castString",
                "(Ljava/lang/Object;)Ljava/lang/String;",
                false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("CCE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printIsStringForString(MethodVisitor main) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("typed");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ObjectTypeOps", "isString", "(Ljava/lang/Object;)Z", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
    }

    private void printIsStringForNewObject(MethodVisitor main) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "java/lang/Object");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ObjectTypeOps", "isString", "(Ljava/lang/Object;)Z", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
    }

    private void printIsStringForNull(MethodVisitor main) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ObjectTypeOps", "isString", "(Ljava/lang/Object;)Z", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Z)V", false);
    }

    private void printNpeCatchForStringLength(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/StringOps", "length", "(Ljava/lang/String;)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printNpeCatchForAllocationStringLength(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/AllocationStringOps", "length", "(Ljava/lang/String;)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printStringCharAtBoundsCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/StringIndexOutOfBoundsException");
        main.visitLabel(start);
        main.visitLdcInsn("hi");
        main.visitIntInsn(BIPUSH, 9);
        main.visitMethodInsn(INVOKESTATIC, "pkg/AllocationStringOps", "charAt", "(Ljava/lang/String;I)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("SIOOBE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printArraycopyNpeCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ICONST_1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArraycopyOps", "copyNull", "([Ljava/lang/Object;)V", false);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printArraycopyOobCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/IndexOutOfBoundsException");
        main.visitLabel(start);
        main.visitInsn(ICONST_2);
        main.visitIntInsn(NEWARRAY, T_INT);
        main.visitInsn(ICONST_2);
        main.visitIntInsn(NEWARRAY, T_INT);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArraycopyOps", "copyOob", "([I[I)V", false);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("OOB");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printArraycopyStoreCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/ArrayStoreException");
        main.visitLabel(start);
        main.visitInsn(ICONST_1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        main.visitInsn(DUP);
        main.visitInsn(ICONST_0);
        main.visitTypeInsn(NEW, "java/lang/Object");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        main.visitInsn(AASTORE);
        main.visitInsn(ICONST_1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/String");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ArraycopyOps",
                "copyObjectToString",
                "([Ljava/lang/Object;[Ljava/lang/String;)V",
                false);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("ASE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printRequireNonNullNpeCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/BoxingObjectsOps",
                "require",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printReferenceArrayNpeCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ACONST_NULL);
        main.visitLdcInsn("x");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReferenceArrayOps",
                "stringRoundtrip",
                "([Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printReferenceArrayBoundsCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/ArrayIndexOutOfBoundsException");
        main.visitLabel(start);
        byteArray(main, 1);
        pushInt(main, 2);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReferenceArrayOps", "byteAt", "([BI)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("AIOOBE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printReferenceArrayStoreCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/ArrayStoreException");
        main.visitLabel(start);
        pushInt(main, 1);
        main.visitTypeInsn(ANEWARRAY, "java/lang/String");
        main.visitTypeInsn(NEW, "java/lang/Object");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReferenceArrayOps",
                "wrongStore",
                "([Ljava/lang/String;Ljava/lang/Object;)V",
                false);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("ASE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printNegativeArrayCatchForAllocation(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NegativeArraySizeException");
        main.visitLabel(start);
        main.visitInsn(ICONST_M1);
        main.visitMethodInsn(INVOKESTATIC, "pkg/AllocationStringOps", "intLength", "(I)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NASE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printNpeCatchForDispatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/DispatchOps", "virtualValue", "(Lpkg/Base;)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printDefaultConflictCatchForDispatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/IncompatibleClassChangeError");
        main.visitLabel(start);
        main.visitTypeInsn(NEW, "pkg/ConflictImpl");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/ConflictImpl", "<init>", "()V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/DispatchOps", "conflictValue", "(Lpkg/ConflictLeft;)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("default-conflict");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printNpeCatchForArraySum(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArrayOps", "sum", "([I)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printNpeCatchForReferenceField(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReferenceFieldOps", "readValue", "(Lpkg/ReferenceFieldOps;)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printNpeCatchForArrayHelper(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArrayHelperOps", "firstPlusLength", "([I)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printArrayBoundsCatchForArrayHelper(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/ArrayIndexOutOfBoundsException");
        main.visitLabel(start);
        main.visitInsn(ICONST_0);
        main.visitIntInsn(NEWARRAY, T_INT);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ArrayHelperOps", "firstPlusLength", "([I)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("AIOOBE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printBroadPrimitiveArrayNpeCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
        main.visitLabel(start);
        main.visitInsn(ACONST_NULL);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BroadPrimitiveArrayOps", "shortRoundtrip", "([S)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("NPE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }

    private void printBroadPrimitiveArrayBoundsCatch(MethodVisitor main) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/ArrayIndexOutOfBoundsException");
        main.visitLabel(start);
        main.visitInsn(ICONST_0);
        main.visitIntInsn(NEWARRAY, T_SHORT);
        main.visitMethodInsn(INVOKESTATIC, "pkg/BroadPrimitiveArrayOps", "shortRoundtrip", "([S)I", false);
        main.visitInsn(POP);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("AIOOBE");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
    }
}
