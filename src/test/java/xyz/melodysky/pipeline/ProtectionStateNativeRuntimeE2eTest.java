package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.testsupport.JvmRunResult;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.NativeLibraryName;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.symbols.NativeBinaryPrivacyInspector;
import xyz.melodysky.toolchain.symbols.NativeSymbolInspector;
import xyz.melodysky.toolchain.symbols.SymbolVisibilityPlanner;

class ProtectionStateNativeRuntimeE2eTest implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void internalizedFieldsAndHiddenMethodTableRunInRealHostJvm() throws Exception {
        Path j2llHome = realJ2llHome();
        assumeTrue(j2llHome != null && Files.isRegularFile(zigExecutable(j2llHome)),
                "set -Dj2ll.realHome=<distribution containing zig/zig(.exe)> "
                        + "to run the real native protection-state E2E");
        assertEquals("0.15.2", runZigVersion(zigExecutable(j2llHome)));

        Path inputJar = temp.resolve("native-state.jar");
        writeJar(inputJar, nativeStateClass(), nativeStateChildClass());
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out/native-state");

        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(
                    config,
                    workspace,
                    xyz.melodysky.progress.BuildProgressListener.none(),
                    xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy.strict(),
                    SkippedMethodApproval.allowAll());
        }
        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());

        JvmRunResult original = runHarness(inputJar);
        JvmRunResult rewritten = runHarness(pipeline.outputJar());
        assertEquals(0, original.exitCode(), original.stderr());
        assertEquals(0, rewritten.exitCode(), rewritten.stderr());
        assertEquals(original.stdout(), rewritten.stdout());
        assertEquals("""
                first=600/1200
                second-before=0/0
                second-after=7/11
                constant=73
                constant-float-bits=7fa12345
                constant-double-bits=8000000000000000
                narrow=-1/-1/65535/0/1
                float-bits=7fa12345/80000000
                double-bits=7ff123456789abcd/8000000000000000
                object=true/true/true
                instance-array=true/6/true/15
                skipped-shared=lvm-value/kipped-value/skipped-value
                second-types=0/0/0/0/0/0/null/null
                """, rewritten.stdout());

        assertFieldDispositionAndMinimalLoader(pipeline.outputJar());
        String fieldReportText = Files.readString(
                workspace.resolve("reports/field-internalization-report.json"));
        assertTrue(fieldReportText.contains("\"status\": \"INTERNALIZED\""));
        assertTrue(fieldReportText.contains("\"status\": \"KEPT\""));
        assertTrue(fieldReportText.contains("\"removedFromOutputClass\": true"));
        assertTrue(fieldReportText.contains("\"removedFromOutputClass\": false"));
        assertTrue(fieldReportText.contains("\"finalImplementationPaths\": ["));
        assertTrue(fieldReportText.contains("\"LLVM_NATIVE_PATH\""));
        assertTrue(fieldReportText.contains("\"UNKNOWN\""));
        assertFalse(fieldReportText.contains("\"NON_LLVM_PATH\""));
        for (String kind : List.of(
                "BOOLEAN", "BYTE", "SHORT", "CHAR", "INT", "LONG",
                "FLOAT", "DOUBLE", "REFERENCE")) {
            assertTrue(fieldReportText.contains("\"storageKind\": \"" + kind + "\""), kind);
        }
        assertTrue(fieldReportText.contains("\"referenceStoragePolicy\": \"jvmClassValueObjectArray\""));
        assertTrue(fieldReportText.contains(
                "\"cachePolicy\": "
                        + "\"jvmClassValuePerDefiningClass+lazyPerNativeFunctionActivationLocalRef\""));
        JsonObject fieldReport = JsonParser.parseString(fieldReportText).getAsJsonObject();
        int internalizedFields = 0;
        int keptFields = 0;
        int compileTimeConstants = 0;
        JsonObject keptField = null;
        for (var decisionElement : fieldReport.getAsJsonArray("decisions")) {
            JsonObject decision = decisionElement.getAsJsonObject();
            if ("INTERNALIZED".equals(decision.get("status").getAsString())) {
                internalizedFields++;
                if ("COMPILE_TIME_CONSTANT".equals(
                        decision.get("internalizationStorage").getAsString())) {
                    compileTimeConstants++;
                    assertTrue(decision.get("nativeSlotId").isJsonNull());
                    assertTrue(decision.get("referenceSidecarIndex").isJsonNull());
                }
            } else if ("KEPT".equals(decision.get("status").getAsString())) {
                keptFields++;
                keptField = decision;
            }
        }
        assertEquals(13, internalizedFields);
        assertEquals(4, compileTimeConstants);
        assertEquals(1, keptFields);
        assertEquals("REFERENCE", keptField.get("storageKind").getAsString());
        assertFalse(keptField.get("removedFromOutputClass").getAsBoolean());
        assertTrue(keptField.getAsJsonArray("finalImplementationPaths").asList().stream()
                .anyMatch(path -> "LLVM_NATIVE_PATH".equals(path.getAsString())));
        assertTrue(keptField.getAsJsonArray("finalImplementationPaths").asList().stream()
                .anyMatch(path -> "UNKNOWN".equals(path.getAsString())));
        assertTrue(keptField.getAsJsonArray("reasonCodes").asList().stream()
                .anyMatch(reason -> "ACCESS_PATH_NOT_LLVM_NATIVE".equals(reason.getAsString())));

        String protectionReport = Files.readString(workspace.resolve("reports/protection-report.json"));
        assertTrue(protectionReport.contains("\"passName\": \"FIELD_INTERNALIZATION\""));
        assertTrue(protectionReport.contains("\"passName\": \"METHOD_TABLE_HIDING\""));
        assertTrue(protectionReport.contains("\"reasonCode\": \"FLOAT_CONSTANT_ENCRYPTION\""));
        assertTrue(protectionReport.contains("\"reasonCode\": \"DOUBLE_CONSTANT_ENCRYPTION\""));
        assertTrue(protectionReport.contains(
                "\"reasonCode\": \"METHOD_TABLE_HIDING_TRANSIENT_OWNER_LAYOUT\""));
        Path protectedIrPath;
        try (var paths = Files.walk(workspace.resolve("intermediates/classes"))) {
            protectedIrPath = paths
                    .filter(path -> path.getFileName().toString().equals(
                            "protected.ssa.ir"))
                    .filter(path -> path.getParent()
                            .getParent()
                            .getFileName()
                            .toString()
                            .startsWith("NativeState__"))
                    .findFirst()
                    .orElseThrow();
        }
        String protectedIr = Files.readString(protectedIrPath);
        assertFalse(protectedIr.contains("opcode=CONST_FLOAT"), protectedIr);
        assertFalse(protectedIr.contains("opcode=CONST_DOUBLE"), protectedIr);
        assertTrue(protectedIr.contains("opcode=BITCAST_I32_TO_F32"), protectedIr);
        assertTrue(protectedIr.contains("opcode=BITCAST_I64_TO_F64"), protectedIr);
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        JsonObject methodTableEvidence = JsonParser.parseString(packagingReport)
                .getAsJsonObject()
                .getAsJsonObject("methodTableHiding");
        assertTrue(methodTableEvidence.get("enabled").getAsBoolean());
        assertEquals("RAN", methodTableEvidence.get("status").getAsString());
        assertEquals(1, methodTableEvidence.get("ownerCount").getAsInt());
        assertEquals(17, methodTableEvidence.get("bindingCount").getAsInt());
        assertFalse(packagingReport.contains("\"fallbackInvokeDescriptor\""));
        assertFalse(packagingReport.contains("\"fallbackBlobs\""));
        assertFalse(packagingReport.contains("\"nativeEmbeddedClassBlob\""));

        String skippedMethodReport = Files.readString(
                workspace.resolve("reports/skipped-method-report.json"));
        assertTrue(skippedMethodReport.contains(
                "\"selector\": \"pkg/NativeState#skippedRead!()Ljava/lang/String;\""));
        assertTrue(skippedMethodReport.contains(
                "\"selector\": "
                        + "\"pkg/NativeState#skippedWrite!(Ljava/lang/String;)Ljava/lang/String;\""));
        assertEquals(2, JsonParser.parseString(skippedMethodReport)
                .getAsJsonObject()
                .getAsJsonArray("entries")
                .size());
        assertTrue(skippedMethodReport.contains(
                "\"confirmationDecision\": \"approved\""));
        assertFalse(skippedMethodReport.contains("\"halfLowered\""));

        Path nativeLibrary = workspace.resolve("native")
                .resolve(HostPlatform.detect().orElseThrow().target().libraryFileName());
        List<String> exports = new NativeSymbolInspector().exportedSymbols(
                HostPlatform.detect().orElseThrow().target(),
                nativeLibrary);
        assertEquals(
                new SymbolVisibilityPlanner().loaderExports(
                                HostPlatform.detect().orElseThrow().target())
                        .symbols().stream()
                        .map(symbol -> symbol.name())
                        .toList(),
                exports);
        byte[] nativeBytes = Files.readAllBytes(nativeLibrary);
        for (String sensitive : List.of(
                "pkg/NativeState",
                "pkg/NativeStateChild",
                "counter",
                "total",
                "distinctiveByteState",
                "distinctiveShortState",
                "distinctiveCharState",
                "distinctiveBooleanState",
                "distinctiveFloatState",
                "distinctiveDoubleState",
                "distinctiveObjectState",
                "instanceByteArrayState",
                "getCounter",
                "constantLimit",
                "constantAlgorithm",
                "constantFloat",
                "constantDouble",
                "getConstantLimit",
                "getConstantFloat",
                "getConstantDouble",
                "getTotal",
                "addLong",
                "setInstanceByteArray",
                "getInstanceByteArray",
                "skippedRead",
                "skippedWrite")) {
            assertFalse(NativeBinaryPrivacyInspector.contains(
                    nativeBytes, sensitive.getBytes(StandardCharsets.UTF_8)), sensitive);
            assertFalse(NativeBinaryPrivacyInspector.contains(
                    nativeBytes, sensitive.getBytes(StandardCharsets.UTF_16LE)), sensitive);
        }
        String generatedC = Files.readString(workspace.resolve("native/zig-workspace/jni")
                .resolve(NativeLibraryName.derive(config.protection().seed()) + ".c"));
        assertFalse(generatedC.contains("pkg/NativeState"));
        assertFalse(generatedC.contains("\"counter\""));
        assertFalse(generatedC.contains("\"getCounter\""));
        assertTrue(generatedC.contains("j2ll_nfs_get_b"));
        assertTrue(generatedC.contains("j2ll_nfs_get_f32_bits"));
        assertTrue(generatedC.contains("j2ll_nfs_reference_sidecar_cached"));
        assertTrue(generatedC.contains("j2ll_nfs_release_reference_sidecar"));
        assertFalse(generatedC.contains("j2ll_fallback_sidecar"));
        assertFalse(generatedC.contains("nativeEmbeddedClassBlob"));
        assertFalse(generatedC.contains(
                "NewGlobalRef(env, value)"));
    }

    private void assertFieldDispositionAndMinimalLoader(Path outputJar) throws Exception {
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            JarEntry entry = jar.getJarEntry("pkg/NativeState.class");
            ClassNode node = new ClassNode();
            new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
            assertEquals(
                    List.of("distinctiveObjectState"),
                    node.fields.stream().map(field -> field.name).toList());
            JarEntry loaderEntry = jar.getJarEntry("native_state_test/Loader.class");
            ClassReader loader = new ClassReader(jar.getInputStream(loaderEntry).readAllBytes());
            assertEquals("java/lang/ClassValue", loader.getSuperName());
            assertTrue(jar.stream().noneMatch(candidate ->
                    candidate.getName().startsWith("native_state_test/Loader$")));
        }
    }

    private JvmRunResult runHarness(Path jar) throws Exception {
        Path java = Path.of(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java");
        Process process = new ProcessBuilder(
                        java.toString(),
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "xyz.melodysky.testsupport.IsolatedStaticStateHarness",
                        jar.toString())
                .start();
        boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IllegalStateException("native field child JVM timed out");
        }
        return new JvmRunResult(
                process.exitValue(),
                normalize(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)),
                normalize(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private ResolvedConfig config(Path inputJar) {
        JsonObject json = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "CLOSED_WORLD",
                  "outputDirectory": "out",
                  "whiteList": [
                    "pkg/NativeState#add!(I)I",
                    "pkg/NativeState#addLong!(J)J",
                    "pkg/NativeState#getCounter!()I",
                    "pkg/NativeState#getConstantLimit!()I",
                    "pkg/NativeState#getConstantFloat!()F",
                    "pkg/NativeState#getConstantDouble!()D",
                    "pkg/NativeState#getTotal!()J",
                    "pkg/NativeState#setByte!(I)I",
                    "pkg/NativeState#setShort!(I)I",
                    "pkg/NativeState#setChar!(I)I",
                    "pkg/NativeState#setBoolean!(I)I",
                    "pkg/NativeState#setFloat!(F)F",
                    "pkg/NativeState#setDouble!(D)D",
                    "pkg/NativeState#setObject!(Ljava/lang/Object;)Ljava/lang/Object;",
                    "pkg/NativeState#getObject!()Ljava/lang/Object;",
                    "pkg/NativeState#setInstanceByteArray!([B)V",
                    "pkg/NativeState#getInstanceByteArray!()[B",
                    "pkg/NativeState#skippedRead!()Ljava/lang/String;",
                    "pkg/NativeState#skippedWrite!(Ljava/lang/String;)Ljava/lang/String;"
                  ],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "native_state_test",
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
                    "seed": "field-method-table-native-e2e",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": false,
                      "fakeBranches": false,
                      "basicBlockSplitting": false,
                      "constantEncryption": true,
                      "stringEncryption": false,
                      "methodInlining": false,
                      "methodSplitting": false,
                      "callIndirection": false,
                      "fieldInternalization": true,
                      "methodInternalization": false,
                      "publicMethodInternalizationAllowList": [],
                      "methodTableHiding": true,
                      "blockNameObfuscation": false
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": true,
                      "opaquePredicates": false,
                      "blockLayoutPerturbation": false,
                      "indirectCalls": false,
                      "globalLayout": false
                    },
                    "binary": {
                      "enabled": true,
                      "hideInternalSymbols": true,
                      "strip": true,
                      "removePdb": true,
                      "symbolAudit": true,
                      "retainUnwindInfo": false
                    }
                  }
                }
                """.formatted(
                inputJar.toString().replace("\\", "\\\\"),
                hostTargetJson())).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
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

    private void writeJar(Path jar, byte[] classBytes, byte[] childClassBytes) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(output, "pkg/NativeState.class", classBytes);
            writeEntry(output, "pkg/NativeStateChild.class", childClassBytes);
        }
    }

    private void writeEntry(JarOutputStream output, String name, byte[] bytes) throws Exception {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private byte[] nativeStateClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/NativeState", null,
                "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "counter", "I", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "total", "J", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "distinctiveByteState", "B", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "distinctiveShortState", "S", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "distinctiveCharState", "C", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "distinctiveBooleanState", "Z", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "distinctiveFloatState", "F", null, null).visitEnd();
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "distinctiveDoubleState", "D", null, null).visitEnd();
        writer.visitField(
                ACC_PRIVATE | ACC_STATIC,
                "distinctiveObjectState",
                "Ljava/lang/Object;",
                null,
                null).visitEnd();
        writer.visitField(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                "constantLimit",
                "I",
                null,
                Integer.valueOf(73)).visitEnd();
        writer.visitField(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                "constantAlgorithm",
                "Ljava/lang/String;",
                null,
                "field-constant-e2e-secret").visitEnd();
        writer.visitField(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                "constantFloat",
                "F",
                null,
                Float.intBitsToFloat(0x7fa12345)).visitEnd();
        writer.visitField(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                "constantDouble",
                "D",
                null,
                Double.longBitsToDouble(0x8000000000000000L)).visitEnd();
        writer.visitField(
                ACC_PRIVATE | ACC_STATIC,
                "instanceByteArrayState",
                "[B",
                null,
                null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor add = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED, "add", "(I)I", null, null);
        add.visitCode();
        add.visitFieldInsn(GETSTATIC, "pkg/NativeState", "counter", "I");
        add.visitVarInsn(ILOAD, 0);
        add.visitInsn(IADD);
        add.visitInsn(DUP);
        add.visitFieldInsn(PUTSTATIC, "pkg/NativeState", "counter", "I");
        add.visitInsn(IRETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();

        MethodVisitor addLong = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED, "addLong", "(J)J", null, null);
        addLong.visitCode();
        addLong.visitFieldInsn(GETSTATIC, "pkg/NativeState", "total", "J");
        addLong.visitVarInsn(LLOAD, 0);
        addLong.visitInsn(LADD);
        addLong.visitInsn(DUP2);
        addLong.visitFieldInsn(PUTSTATIC, "pkg/NativeState", "total", "J");
        addLong.visitInsn(LRETURN);
        addLong.visitMaxs(0, 0);
        addLong.visitEnd();

        MethodVisitor getCounter = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "getCounter", "()I", null, null);
        getCounter.visitCode();
        getCounter.visitFieldInsn(GETSTATIC, "pkg/NativeState", "counter", "I");
        getCounter.visitInsn(IRETURN);
        getCounter.visitMaxs(0, 0);
        getCounter.visitEnd();

        MethodVisitor getConstantLimit = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "getConstantLimit",
                "()I",
                null,
                null);
        getConstantLimit.visitCode();
        // Deliberately keep GETSTATIC instead of javac-style LDC so the E2E
        // exercises SSA constant folding before the declaration is removed.
        getConstantLimit.visitFieldInsn(
                GETSTATIC,
                "pkg/NativeState",
                "constantLimit",
                "I");
        getConstantLimit.visitInsn(IRETURN);
        getConstantLimit.visitMaxs(0, 0);
        getConstantLimit.visitEnd();

        MethodVisitor getConstantFloat = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "getConstantFloat",
                "()F",
                null,
                null);
        getConstantFloat.visitCode();
        getConstantFloat.visitFieldInsn(
                GETSTATIC,
                "pkg/NativeState",
                "constantFloat",
                "F");
        getConstantFloat.visitInsn(FRETURN);
        getConstantFloat.visitMaxs(0, 0);
        getConstantFloat.visitEnd();

        MethodVisitor getConstantDouble = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "getConstantDouble",
                "()D",
                null,
                null);
        getConstantDouble.visitCode();
        getConstantDouble.visitFieldInsn(
                GETSTATIC,
                "pkg/NativeState",
                "constantDouble",
                "D");
        getConstantDouble.visitInsn(DRETURN);
        getConstantDouble.visitMaxs(0, 0);
        getConstantDouble.visitEnd();

        MethodVisitor getTotal = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "getTotal", "()J", null, null);
        getTotal.visitCode();
        getTotal.visitFieldInsn(GETSTATIC, "pkg/NativeState", "total", "J");
        getTotal.visitInsn(LRETURN);
        getTotal.visitMaxs(0, 0);
        getTotal.visitEnd();

        emitIntFieldRoundTrip(writer, "setByte", "distinctiveByteState", "B");
        emitIntFieldRoundTrip(writer, "setShort", "distinctiveShortState", "S");
        emitIntFieldRoundTrip(writer, "setChar", "distinctiveCharState", "C");
        emitIntFieldRoundTrip(writer, "setBoolean", "distinctiveBooleanState", "Z");
        emitFloatFieldRoundTrip(writer);
        emitDoubleFieldRoundTrip(writer);
        emitObjectAccessors(writer);
        emitInstanceByteArrayAccessors(writer);
        emitObjectSkippedAccessors(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] nativeStateChildClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, "pkg/NativeStateChild", null,
                "pkg/NativeState", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "pkg/NativeState", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitIntFieldRoundTrip(
            ClassWriter writer,
            String methodName,
            String fieldName,
            String fieldDescriptor) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                methodName,
                "(I)I",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitFieldInsn(PUTSTATIC, "pkg/NativeState", fieldName, fieldDescriptor);
        method.visitFieldInsn(GETSTATIC, "pkg/NativeState", fieldName, fieldDescriptor);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void emitFloatFieldRoundTrip(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "setFloat",
                "(F)F",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(FLOAD, 0);
        method.visitFieldInsn(PUTSTATIC, "pkg/NativeState", "distinctiveFloatState", "F");
        method.visitFieldInsn(GETSTATIC, "pkg/NativeState", "distinctiveFloatState", "F");
        method.visitInsn(FRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void emitDoubleFieldRoundTrip(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "setDouble",
                "(D)D",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(DLOAD, 0);
        method.visitFieldInsn(PUTSTATIC, "pkg/NativeState", "distinctiveDoubleState", "D");
        method.visitFieldInsn(GETSTATIC, "pkg/NativeState", "distinctiveDoubleState", "D");
        method.visitInsn(DRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void emitObjectAccessors(ClassWriter writer) {
        MethodVisitor set = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "setObject",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        set.visitCode();
        set.visitVarInsn(ALOAD, 0);
        set.visitFieldInsn(
                PUTSTATIC,
                "pkg/NativeState",
                "distinctiveObjectState",
                "Ljava/lang/Object;");
        set.visitFieldInsn(
                GETSTATIC,
                "pkg/NativeState",
                "distinctiveObjectState",
                "Ljava/lang/Object;");
        set.visitInsn(ARETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        MethodVisitor get = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "getObject",
                "()Ljava/lang/Object;",
                null,
                null);
        get.visitCode();
        get.visitFieldInsn(
                GETSTATIC,
                "pkg/NativeState",
                "distinctiveObjectState",
                "Ljava/lang/Object;");
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
    }

    private void emitInstanceByteArrayAccessors(ClassWriter writer) {
        MethodVisitor set = writer.visitMethod(
                ACC_PUBLIC | ACC_SYNCHRONIZED,
                "setInstanceByteArray",
                "([B)V",
                null,
                null);
        set.visitCode();
        set.visitVarInsn(ALOAD, 1);
        set.visitFieldInsn(PUTSTATIC, "pkg/NativeState", "instanceByteArrayState", "[B");
        set.visitInsn(RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        MethodVisitor get = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED,
                "getInstanceByteArray",
                "()[B",
                null,
                null);
        get.visitCode();
        get.visitFieldInsn(GETSTATIC, "pkg/NativeState", "instanceByteArrayState", "[B");
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
    }

    private void emitObjectSkippedAccessors(ClassWriter writer) {
        MethodVisitor read = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "skippedRead",
                "()Ljava/lang/String;",
                null,
                null);
        org.objectweb.asm.Label readStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label readEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label readHandler = new org.objectweb.asm.Label();
        read.visitTryCatchBlock(readStart, readEnd, readHandler, null);
        read.visitCode();
        read.visitLabel(readStart);
        read.visitFieldInsn(
                GETSTATIC,
                "pkg/NativeState",
                "distinctiveObjectState",
                "Ljava/lang/Object;");
        read.visitTypeInsn(CHECKCAST, "java/lang/String");
        read.visitLabel(readEnd);
        read.visitInsn(ICONST_1);
        read.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/String",
                "substring",
                "(I)Ljava/lang/String;",
                false);
        read.visitInsn(ARETURN);
        read.visitLabel(readHandler);
        read.visitVarInsn(ASTORE, 0);
        read.visitLdcInsn("skipped-error");
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        MethodVisitor write = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "skippedWrite",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        org.objectweb.asm.Label writeStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label writeEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label writeHandler = new org.objectweb.asm.Label();
        write.visitTryCatchBlock(
                writeStart,
                writeEnd,
                writeHandler,
                null);
        write.visitCode();
        write.visitLabel(writeStart);
        write.visitVarInsn(ALOAD, 0);
        write.visitFieldInsn(
                PUTSTATIC,
                "pkg/NativeState",
                "distinctiveObjectState",
                "Ljava/lang/Object;");
        write.visitLabel(writeEnd);
        write.visitVarInsn(ALOAD, 0);
        write.visitInsn(ICONST_1);
        write.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/String",
                "substring",
                "(I)Ljava/lang/String;",
                false);
        write.visitInsn(ARETURN);
        write.visitLabel(writeHandler);
        write.visitVarInsn(ASTORE, 1);
        write.visitVarInsn(ALOAD, 0);
        write.visitInsn(ARETURN);
        write.visitMaxs(0, 0);
        write.visitEnd();
    }

    private Path realJ2llHome() {
        String configured = System.getProperty("j2ll.realHome");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("J2LL_REAL_HOME");
        }
        return configured == null || configured.isBlank()
                ? null
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private Path zigExecutable(Path home) {
        return home.resolve("zig").resolve(isWindows() ? "zig.exe" : "zig");
    }

    private String runZigVersion(Path zig) throws Exception {
        Process process = new ProcessBuilder(zig.toString(), "version").start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    private AutoCloseable useJ2llHome(Path home) {
        String previous = System.getProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
        System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, home.toString());
        return () -> {
            if (previous == null) {
                System.clearProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, previous);
            }
        };
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
