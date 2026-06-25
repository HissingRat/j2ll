package xyz.melodysky.pipeline;

import xyz.melodysky.backend.llvm.LlvmTextBackend;
import xyz.melodysky.filter.ClassMethodFilter;
import xyz.melodysky.frontend.jar.FrontendSkipReport;
import xyz.melodysky.frontend.jar.JarIrBuilder;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.pass.CfgCleanupPass;
import xyz.melodysky.ir.pass.IrMethodPass;
import xyz.melodysky.ir.pass.IrMethodPassPipeline;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.runtime.IrRuntimeStubGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IrPipelineCompiler {

    private final JarIrBuilder jarIrBuilder;
    private final List<IrMethodPass> methodPasses;
    private final LlvmTextBackend llvmBackend;
    private final IrRuntimeStubGenerator runtimeStubGenerator;
    private final int llvmShardCount;
    private static final ProgressListener NO_PROGRESS = new ProgressListener() {};

    public interface ProgressListener {
        default void onBytecodeReadStart(int totalClasses) {}
        default void onBytecodeReadProgress(int current, int totalClasses, String className) {}
        default void onIrLowerStart(int totalClasses) {}
        default void onIrLowerProgress(int current, int totalClasses, String className) {}
        default void onLlvmEmitStart(int totalClasses) {}
        default void onLlvmEmitProgress(int current, int totalClasses, String className) {}
    }

    public IrPipelineCompiler() {
        this(
                new JarIrBuilder(),
                List.of(new CfgCleanupPass()),
                new LlvmTextBackend(),
                new IrRuntimeStubGenerator(),
                defaultLlvmShardCount()
        );
    }

    public IrPipelineCompiler(JarIrBuilder jarIrBuilder, List<IrMethodPass> methodPasses,
                              LlvmTextBackend llvmBackend, IrRuntimeStubGenerator runtimeStubGenerator) {
        this(jarIrBuilder, methodPasses, llvmBackend, runtimeStubGenerator, defaultLlvmShardCount());
    }

    public IrPipelineCompiler(JarIrBuilder jarIrBuilder, List<IrMethodPass> methodPasses,
                              LlvmTextBackend llvmBackend, IrRuntimeStubGenerator runtimeStubGenerator,
                              int llvmShardCount) {
        this.jarIrBuilder = jarIrBuilder;
        this.methodPasses = List.copyOf(methodPasses);
        this.llvmBackend = llvmBackend;
        this.runtimeStubGenerator = runtimeStubGenerator;
        this.llvmShardCount = Math.max(1, llvmShardCount);
    }

    public BuildResult compile(Path jarPath) throws IOException {
        return compile(jarPath, ClassMethodFilter.allowAll());
    }

    public BuildResult compile(Path jarPath, ClassMethodFilter classMethodFilter) throws IOException {
        return compile(jarPath, classMethodFilter, NO_PROGRESS);
    }

    public BuildResult compile(Path jarPath, ClassMethodFilter classMethodFilter, ProgressListener progressListener) throws IOException {
        JarIrBuilder.BuildResult frontendResult = jarIrBuilder.build(jarPath, classMethodFilter, new JarIrBuilder.ProgressListener() {
            @Override
            public void onReadStart(int totalClasses) {
                progressListener.onBytecodeReadStart(totalClasses);
            }

            @Override
            public void onClassRead(int current, int totalClasses, String className) {
                progressListener.onBytecodeReadProgress(current, totalClasses, className);
            }

            @Override
            public void onLowerStart(int totalClasses) {
                progressListener.onIrLowerStart(totalClasses);
            }

            @Override
            public void onClassLowered(int current, int totalClasses, String className) {
                progressListener.onIrLowerProgress(current, totalClasses, className);
            }
        });
        IrProgram transformedProgram = runMethodPasses(frontendResult.program());
        LlvmTextBackend.ModuleSet moduleSet = llvmBackend.emitModuleSet(transformedProgram, llvmShardCount, new LlvmTextBackend.EmissionProgressListener() {
            @Override
            public void onEmissionStart(int totalClasses) {
                progressListener.onLlvmEmitStart(totalClasses);
            }

            @Override
            public void onClassEmitted(int current, int totalClasses, String className) {
                progressListener.onLlvmEmitProgress(current, totalClasses, className);
            }
        });
        return new BuildResult(frontendResult, transformedProgram, moduleSet.monolithicText(), moduleSet.shardModules(), null);
    }

    public DirectoryBuildResult compileToDirectory(Path jarPath, Path outputDirectory) throws IOException {
        return compileToDirectory(jarPath, outputDirectory, ClassMethodFilter.allowAll());
    }

    public DirectoryBuildResult compileToDirectory(Path jarPath, Path outputDirectory, ClassMethodFilter classMethodFilter) throws IOException {
        return compileToDirectory(jarPath, outputDirectory, classMethodFilter, NO_PROGRESS);
    }

    public DirectoryBuildResult compileToDirectory(Path jarPath, Path outputDirectory, ClassMethodFilter classMethodFilter,
                                                   ProgressListener progressListener) throws IOException {
        BuildResult result = compile(jarPath, classMethodFilter, progressListener);
        Files.createDirectories(outputDirectory);

        Path runtimeDirectory = outputDirectory.resolve("runtime");
        Path llvmModulesDirectory = outputDirectory.resolve("llvm-modules");
        Files.createDirectories(runtimeDirectory);
        Files.createDirectories(llvmModulesDirectory);
        Path llvmPath = llvmModulesDirectory.resolve("program.ll");
        Path skipsPath = outputDirectory.resolve("frontend-skips.txt");
        Path skipsJsonPath = outputDirectory.resolve("frontend-skips.json");
        Path runtimeStubPath = runtimeDirectory.resolve("ir_runtime_stubs.c");

        String llvmText = result.llvmText();
        Files.writeString(llvmPath, llvmText, StandardCharsets.UTF_8);
        Path writtenSkipsPath = null;
        Path writtenSkipsJsonPath = null;
        FrontendSkipReport skipReport = FrontendSkipReport.from(result.frontendResult());
        if (!skipReport.isEmpty()) {
            Files.writeString(skipsPath, skipReport.toText(), StandardCharsets.UTF_8);
            Files.writeString(skipsJsonPath, skipReport.toJson(), StandardCharsets.UTF_8);
            writtenSkipsPath = skipsPath;
            writtenSkipsJsonPath = skipsJsonPath;
        }
        Files.writeString(runtimeStubPath, runtimeStubGenerator.generate(llvmText), StandardCharsets.UTF_8);
        ArrayList<Path> llvmModuleFiles = new ArrayList<>(result.llvmModules().size());
        for (LlvmTextBackend.ModuleFragment fragment : result.llvmModules()) {
            Path moduleFile = llvmModulesDirectory.resolve(fragment.fileName());
            Files.writeString(moduleFile, fragment.llvmText(), StandardCharsets.UTF_8);
            llvmModuleFiles.add(moduleFile);
        }

        return new DirectoryBuildResult(
                NativeRegistrationPlanner.RequestedClass.fromProgram(result.transformedProgram()),
                new OutputArtifacts(outputDirectory, llvmPath, writtenSkipsPath, runtimeStubPath, runtimeDirectory,
                        List.copyOf(llvmModuleFiles), writtenSkipsJsonPath)
        );
    }

    private IrProgram runMethodPasses(IrProgram program) {
        IrMethodPassPipeline pipeline = new IrMethodPassPipeline(methodPasses);
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

    public record BuildResult(
            JarIrBuilder.BuildResult frontendResult,
            IrProgram transformedProgram,
            String llvmText,
            List<LlvmTextBackend.ModuleFragment> llvmModules,
            OutputArtifacts outputArtifacts
    ) {
    }

    public record DirectoryBuildResult(
            List<NativeRegistrationPlanner.RequestedClass> requestedClasses,
            OutputArtifacts outputArtifacts
    ) {
    }

    public record OutputArtifacts(Path outputDirectory, Path llvmFile, Path frontendSkipsFile, Path runtimeStubFile,
                                  Path runtimeDirectory, List<Path> llvmModuleFiles, Path frontendSkipsJsonFile) {
    }

    private static int defaultLlvmShardCount() {
        Integer physicalCoreCount = detectPhysicalCoreCount();
        if (physicalCoreCount != null && physicalCoreCount > 0) {
            return physicalCoreCount;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static Integer detectPhysicalCoreCount() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (osName.contains("win")) {
                return parsePositiveInt(runProbeCommand(
                        "powershell",
                        "-NoProfile",
                        "-Command",
                        "(Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum"
                ));
            }
            if (osName.contains("mac")) {
                return parsePositiveInt(runProbeCommand("sysctl", "-n", "hw.physicalcpu"));
            }
            if (osName.contains("linux")) {
                return parsePositiveInt(runProbeCommand(
                        "sh",
                        "-lc",
                        "lscpu -p=Core 2>/dev/null | grep -v '^#' | sort -u | wc -l"
                ));
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String runProbeCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (process.waitFor() != 0) {
            return "";
        }
        return output;
    }

    private static Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        int newlineIndex = normalized.indexOf('\n');
        if (newlineIndex >= 0) {
            normalized = normalized.substring(0, newlineIndex).trim();
        }
        try {
            int parsed = Integer.parseInt(normalized);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
