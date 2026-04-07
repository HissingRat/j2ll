package xyz.melodysky.toolchain;

import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmTextBackend;
import xyz.melodysky.config.BuildTarget;
import xyz.melodysky.frontend.jar.JarIrBuilder;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrClassRef;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.pass.CfgCleanupPass;
import xyz.melodysky.ir.pass.IrMethodPassPipeline;
import xyz.melodysky.runtime.IrRuntimeStubGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

public class MacosArm64IrProbeTest {

    @Test
    public void probeMacosArm64CompatibilityByClassAndMethod() throws Exception {
        String jarPath = propertyOrEnv("probe.jar", "PROBE_JAR");
        if (jarPath == null || jarPath.isBlank()) {
            return;
        }

        String zigCommand = propertyOrEnv("probe.zig", "PROBE_ZIG");
        if (zigCommand == null || zigCommand.isBlank()) {
            fail("Missing -Dprobe.zig=<zig executable>");
        }

        JarIrBuilder.BuildResult frontendResult = new JarIrBuilder().build(Path.of(jarPath));
        IrProgram transformedProgram = runMethodPasses(frontendResult.program());

        ArrayList<String> failingClasses = new ArrayList<>();
        ArrayList<String> failingMethods = new ArrayList<>();
        ArrayList<String> combinationOnlyClasses = new ArrayList<>();

        for (IrClass irClass : transformedProgram.classes()) {
            if (compilesAsSingleClass(irClass, zigCommand)) {
                continue;
            }
            failingClasses.add(irClass.reference().internalName());

            boolean anyMethodFailed = false;
            for (IrMethod method : irClass.methods()) {
                if (compilesAsSingleMethod(irClass.reference().internalName(), method, zigCommand)) {
                    continue;
                }
                anyMethodFailed = true;
                failingMethods.add(irClass.reference().internalName() + " :: " + method.name());
            }
            if (!anyMethodFailed) {
                combinationOnlyClasses.add(irClass.reference().internalName());
            }
        }

        if (!failingClasses.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append("Failing classes (").append(failingClasses.size()).append("):\n");
            for (String failingClass : failingClasses) {
                message.append(" - ").append(failingClass).append('\n');
            }
            if (!failingMethods.isEmpty()) {
                message.append("Failing methods (").append(failingMethods.size()).append("):\n");
                for (String failingMethod : failingMethods) {
                    message.append(" - ").append(failingMethod).append('\n');
                }
            }
            if (!combinationOnlyClasses.isEmpty()) {
                message.append("Classes that fail only when combined with siblings:\n");
                for (String className : combinationOnlyClasses) {
                    message.append(" - ").append(className).append('\n');
                }
            }
            fail(message.toString());
        }
    }

    private IrProgram runMethodPasses(IrProgram program) {
        IrMethodPassPipeline pipeline = new IrMethodPassPipeline(List.of(new CfgCleanupPass()));
        ArrayList<IrClass> classes = new ArrayList<>();
        for (IrClass irClass : program.classes()) {
            ArrayList<IrMethod> methods = new ArrayList<>();
            for (IrMethod method : irClass.methods()) {
                methods.add(pipeline.run(method));
            }
            classes.add(new IrClass(irClass.reference(), methods));
        }
        return new IrProgram(classes);
    }

    private boolean compilesAsSingleClass(IrClass irClass, String zigCommand) throws Exception {
        return compiles(new IrProgram(List.of(irClass)), zigCommand);
    }

    private boolean compilesAsSingleMethod(String internalName, IrMethod method, String zigCommand) throws Exception {
        IrClass probeClass = new IrClass(new IrClassRef(internalName), List.of(method));
        return compiles(new IrProgram(List.of(probeClass)), zigCommand);
    }

    private boolean compiles(IrProgram program, String zigCommand) throws Exception {
        Path workspace = Files.createTempDirectory("macos-arm64-probe-");
        try {
            String llvmText = new LlvmTextBackend().emit(program);
            Path llvmFile = workspace.resolve("program.ll");
            Path runtimeDirectory = workspace.resolve("runtime");
            Files.createDirectories(runtimeDirectory);
            Path runtimeStubFile = runtimeDirectory.resolve("ir_runtime_stubs.c");
            Files.writeString(llvmFile, llvmText, StandardCharsets.UTF_8);
            Files.writeString(runtimeStubFile, new IrRuntimeStubGenerator().generate(llvmText), StandardCharsets.UTF_8);
            new IrNativeBuildDriver(workspace).build(
                    zigCommand,
                    llvmFile,
                    runtimeStubFile,
                    List.of(BuildTarget.MACOS_ARM64)
            );
            return true;
        } catch (IOException exception) {
            return false;
        } finally {
            deleteRecursively(workspace);
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String propertyOrEnv(String propertyName, String envName) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return null;
    }
}
