package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_BranchPhiOps.ll"));
        assertTrue(llvm.contains("br i1"));
        assertTrue(llvm.contains("icmp"));
        assertTrue(llvm.contains(" phi i32 "));
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
        assertTrue(llvm.matches("(?s).*call i32 @j2ll_f_[0-9a-f]{32}\\(.*"));
        assertFalse(llvm.contains("@j2ll_call_pkg_FieldCallOps_callee"));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
        assertTrue(symbolAudit.contains("\"JNI_OnLoad\""));
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
        assertTrue(llvm.matches("(?s).*call i32 @j2ll_f_[0-9a-f]{32}\\(.*"));
        assertTrue(llvm.contains("ptr %p0, i32 %p1"));
        assertFalse(llvm.contains("@j2ll_call_pkg_SpecialCallOps_helper"));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
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
        assertTrue(llvm.matches("(?s).*call i32 @j2ll_f_[0-9a-f]{32}\\(.*"));
        assertFalse(llvm.contains("call ptr @j2ll_rt_method_handle_invoke_exact"));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
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
        assertTrue(report.contains("\"reasonCode\": \"CONSTRUCTOR_CALL_HELPER\""));
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
                "pkg/ReflectionOps#constructAndInvoke!()Ljava/lang/String;",
                "pkg/ReflectionOps#fieldInt!(Lpkg/ReflectionTarget;)I",
                "pkg/ReflectionOps#fieldRef!(Lpkg/ReflectionTarget;Ljava/lang/String;)Ljava/lang/String;"));
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
                target
                41
                field
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(5, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"REFLECTION_HELPER\""));
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
        assertTrue(source.contains("j2ll_rt_reflect_field_get_int"));
        assertTrue(source.contains("j2ll_rt_reflect_field_set_int"));
        assertTrue(source.contains("j2ll_rt_reflect_field_get"));
        assertTrue(source.contains("j2ll_rt_reflect_field_set"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_ReflectionOps.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_class_for_name_static(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_get_declared_method(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_get_declared_field(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_get_declared_constructor(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_reflect_invoke(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_reflect_new_instance(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_reflect_field_get_int(ptr %j2ll_env"));
        assertTrue(llvm.contains("call void @j2ll_rt_reflect_field_set_int(ptr %j2ll_env"));
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
                hi
                1
                3
                NPE
                OOB
                ASE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(7, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
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
        assertTrue(source.contains("j2ll_encrypted_string_constant_table"));
        assertFalse(source.contains("static const struct j2ll_string_constant_entry j2ll_string_constant_table"));
        assertTrue(source.contains("NewStringUTF"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_StringConcatRecipe.ll"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_string_constant(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @j2ll_rt_string_builder_to_string(ptr %j2ll_env"));
        String symbolAudit = Files.readString(workspace.resolve("reports/symbol-audit.json"));
        assertFalse(symbolAudit.contains("value="));
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
        writeJar(inputJar, Map.of(
                "pkg/Base.class", dispatchBaseClass(),
                "pkg/Sub.class", dispatchSubClass(),
                "pkg/I.class", dispatchInterfaceClass(),
                "pkg/Impl.class", dispatchImplClass(),
                "pkg/DispatchOps.class", dispatchOpsClass(),
                "pkg/DispatchMain.class", dispatchMainClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/DispatchOps#virtualValue!(Lpkg/Base;)I",
                "pkg/DispatchOps#interfaceValue!(Lpkg/I;)I"));
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
                7
                NPE
                """, differential.outputRun().stdout());
        String report = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(2, countOccurrences(report, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(report.contains("\"reasonCode\": \"DEFERRED_DISPATCH_HELPER\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("j2ll_method_table"));
        assertTrue(source.contains("CallIntMethod"));
        assertFalse(source.contains("vtable"));
        String llvm = Files.readString(workspace.resolve("native/zig-workspace/llvm/pkg_DispatchOps.ll"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_call_virtual_i32(ptr %j2ll_env"));
        assertTrue(llvm.contains("call i32 @j2ll_rt_call_interface_i32(ptr %j2ll_env"));
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
        }
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"storageTarget\": \"nativeEmbeddedClassBlob\""));
        assertTrue(packagingReport.contains("\"definitionMechanism\": \"DefineClass\""));
        assertTrue(packagingReport.contains("\"definitionMechanismReasonCode\": \"FALLBACK_DEFINE_CLASS\""));
        assertTrue(packagingReport.contains("\"classloaderReusePolicy\": \"lazyPerClassLoaderReuse\""));
        assertTrue(packagingReport.contains("\"encodingVersion\": \"fallbackBlobEncodingV1\""));
        assertTrue(packagingReport.contains("\"originalSha256\""));
        assertTrue(packagingReport.contains("\"encodedSha256\""));
        assertTrue(packagingReport.contains("\"compressionAlgorithm\": \"j2ll-rle-byte-pairs-v1\""));
        assertTrue(packagingReport.contains("\"encryptionAlgorithm\": \"xor-sha256-key-stream-v1\""));
        String source = Files.readString(workspace.resolve("native/zig-workspace/jni/j2lle2e.c"));
        assertTrue(source.contains("DefineClass"));
        assertTrue(source.contains("j2ll_verify_sha256_hex"));
        assertTrue(source.contains("fallback encoded SHA-256 mismatch"));
        assertTrue(source.contains("fallback decoded SHA-256 mismatch"));
        assertTrue(source.contains("_loaders[16]"));
        assertTrue(source.contains("IsSameObject"));
        assertTrue(source.contains("_encoded[]"));
        assertTrue(source.contains("_decode(JNIEnv* env"));
        assertFalse(source.contains("_bytes[]"));
    }

    private int countEntries(Path jar, String suffix) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return (int) jarFile.stream()
                    .filter(entry -> entry.getName().endsWith(suffix))
                    .count();
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

    private byte[] reflectionTargetClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ReflectionTarget", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "count", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "note", "Ljava/lang/String;", null, null).visitEnd();
        defaultConstructor(writer);

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

        MethodVisitor label = writer.visitMethod(ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null);
        label.visitCode();
        label.visitLdcInsn("target");
        label.visitInsn(ARETURN);
        label.visitMaxs(0, 0);
        label.visitEnd();

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
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReflectionOps", "constructAndInvoke", "()Ljava/lang/String;", false);
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
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dispatchInterfaceClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "pkg/I", null, "java/lang/Object", null);
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "value", "()I", null, null);
        value.visitEnd();
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
        main.visitTypeInsn(NEW, "pkg/Impl");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/Impl", "<init>", "()V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/DispatchOps", "interfaceValue", "(Lpkg/I;)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
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
