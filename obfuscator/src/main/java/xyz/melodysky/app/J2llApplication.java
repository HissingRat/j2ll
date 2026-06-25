package xyz.melodysky.app;

import xyz.melodysky.backend.llvm.LlvmTextBackend;
import xyz.melodysky.config.Config;
import xyz.melodysky.console.ConsoleProgressDisplay;
import xyz.melodysky.filter.ClassMethodFilter;
import xyz.melodysky.filter.ClassMethodList;
import xyz.melodysky.frontend.jar.FrontendSkipReport;
import xyz.melodysky.frontend.jar.JarIrBuilder;
import xyz.melodysky.ir.pass.CfgCleanupPass;
import xyz.melodysky.ir.pass.CfgPerturbationPass;
import xyz.melodysky.ir.pass.ConstantSplittingPass;
import xyz.melodysky.ir.pass.IrMethodPass;
import xyz.melodysky.ir.pass.StringObfuscationPass;
import xyz.melodysky.packaging.IrJarRepacker;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.pipeline.IrPipelineCompiler;
import xyz.melodysky.runtime.IrRuntimeStubGenerator;
import xyz.melodysky.toolchain.IrNativeBuildDriver;
import xyz.melodysky.zig.ZigManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class J2llApplication {

    private final Class<?> applicationAnchor;

    public J2llApplication(Class<?> applicationAnchor) {
        this.applicationAnchor = applicationAnchor;
    }

    public int run(Config config, boolean debug) throws Exception {
        Path buildDirectory = config.createBuildDirectory();
        System.out.println("Build workspace: " + buildDirectory.toAbsolutePath());
        return runIrPipeline(config, buildDirectory, debug);
    }

    public int analyze(Config config) throws Exception {
        Path buildDirectory = config.createBuildDirectory();
        System.out.println("Build workspace: " + buildDirectory.toAbsolutePath());
        return runAnalysis(config, buildDirectory);
    }

    private int runAnalysis(Config config, Path buildDirectory) throws Exception {
        ClassMethodFilter classMethodFilter = createClassMethodFilter(config);
        ConsoleProgressDisplay progressDisplay = new ConsoleProgressDisplay();
        ConsoleProgressAdapters.PipelineConsoleProgress pipelineProgress = ConsoleProgressAdapters.pipeline(progressDisplay);
        JarIrBuilder.BuildResult frontendResult = new JarIrBuilder().build(
                config.getJarFilePath(),
                classMethodFilter,
                new JarIrBuilder.ProgressListener() {
                    @Override
                    public void onReadStart(int totalClasses) {
                        pipelineProgress.onBytecodeReadStart(totalClasses);
                    }

                    @Override
                    public void onClassRead(int current, int totalClasses, String className) {
                        pipelineProgress.onBytecodeReadProgress(current, totalClasses, className);
                    }

                    @Override
                    public void onLowerStart(int totalClasses) {
                        pipelineProgress.onIrLowerStart(totalClasses);
                    }

                    @Override
                    public void onClassLowered(int current, int totalClasses, String className) {
                        pipelineProgress.onIrLowerProgress(current, totalClasses, className);
                    }
                }
        );
        pipelineProgress.finish();

        AnalysisReport report = AnalysisReport.from(config.getJarFilePath(), buildDirectory, classMethodFilter, frontendResult);
        Path reportPath = buildDirectory.resolve("analysis-report.json");
        Files.writeString(reportPath, report.toJson(), StandardCharsets.UTF_8);

        FrontendSkipReport skipReport = FrontendSkipReport.from(frontendResult);
        if (!skipReport.isEmpty()) {
            Files.writeString(buildDirectory.resolve("frontend-skips.txt"), skipReport.toText(), StandardCharsets.UTF_8);
            Files.writeString(buildDirectory.resolve("frontend-skips.json"), skipReport.toJson(), StandardCharsets.UTF_8);
        }

        System.out.println("Analyze complete.");
        System.out.println("Native-lowered methods: " + report.nativeLoweredMethods());
        System.out.println("Kept as Java methods: " + report.keptAsJavaMethods());
        System.out.println("Whitelist hits: " + report.whiteListHitMethods());
        System.out.println("Blacklist hits: " + report.blackListHitMethods());
        System.out.println("Analysis report: " + OutputPathFormatter.formatIrOutputHint(reportPath));
        return 0;
    }

    private int runIrPipeline(Config config, Path buildDirectory, boolean debug) throws Exception {
        ClassMethodFilter classMethodFilter = createClassMethodFilter(config);
        ArrayList<IrMethodPass> methodPasses = new ArrayList<>();
        methodPasses.add(new CfgCleanupPass());
        if (config.stringObfuscation != null && config.stringObfuscation.enabled) {
            methodPasses.add(new StringObfuscationPass(config.stringObfuscation.cacheStrings));
        }
        methodPasses.add(new ConstantSplittingPass());
        methodPasses.add(new CfgPerturbationPass());
        IrJarRepacker jarRepacker = new IrJarRepacker();
        String nativeDir = jarRepacker.planNativeDir(config.getJarFilePath(), config.embeddedLibraryDirectory);
        ConsoleProgressDisplay progressDisplay = new ConsoleProgressDisplay();
        ConsoleProgressAdapters.PipelineConsoleProgress pipelineProgress = ConsoleProgressAdapters.pipeline(progressDisplay);
        Integer maxShardBytes = config.getMaxShardBytes();
        LlvmTextBackend llvmBackend = maxShardBytes != null
                ? LlvmTextBackend.withMaxShardBytes(maxShardBytes)
                : new LlvmTextBackend();
        IrRuntimeStubGenerator runtimeStubGenerator = new IrRuntimeStubGenerator();
        IrPipelineCompiler.DirectoryBuildResult result = new IrPipelineCompiler(
                new JarIrBuilder(),
                methodPasses,
                llvmBackend,
                runtimeStubGenerator
        )
                .compileToDirectory(config.getJarFilePath(), buildDirectory, classMethodFilter, pipelineProgress);
        pipelineProgress.finish();
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner()
                .plan(config.getJarFilePath(), result.requestedClasses());
        String llvmText = Files.readString(result.outputArtifacts().llvmFile(), StandardCharsets.UTF_8);
        IrRuntimeStubGenerator.RuntimeSourceSet runtimeSourceSet = runtimeStubGenerator.generateSourceSet(
                llvmText,
                registrationPlan,
                nativeDir + "/Loader",
                Math.max(1, result.outputArtifacts().llvmModuleFiles().size() - 1),
                maxShardBytes
        );
        Files.writeString(
                result.outputArtifacts().runtimeStubFile(),
                runtimeSourceSet.monolithicText(),
                StandardCharsets.UTF_8
        );
        Path runtimeModulesDirectory = result.outputArtifacts().runtimeDirectory();
        Files.createDirectories(runtimeModulesDirectory);
        ArrayList<Path> runtimeSourceFiles = new ArrayList<>(runtimeSourceSet.sourceFiles().size());
        for (IrRuntimeStubGenerator.RuntimeFragment fragment : runtimeSourceSet.sourceFiles()) {
            Path runtimeSourceFile = runtimeModulesDirectory.resolve(fragment.fileName());
            Files.writeString(runtimeSourceFile, fragment.sourceText(), StandardCharsets.UTF_8);
            runtimeSourceFiles.add(runtimeSourceFile);
        }
        ZigManager zigManager = new ZigManager(ZigManager.resolveApplicationDirectory(applicationAnchor), buildDirectory);
        String zigCommand = zigManager.ensureZigCommand();
        ConsoleProgressAdapters.NativeBuildConsoleProgress nativeBuildProgress = ConsoleProgressAdapters.nativeBuild(progressDisplay);
        IrNativeBuildDriver.BuildResult nativeBuild = new IrNativeBuildDriver(
                buildDirectory,
                debug
        ).build(
                zigCommand,
                result.outputArtifacts().llvmModuleFiles(),
                List.copyOf(runtimeSourceFiles),
                config.getEnabledTargets(),
                nativeBuildProgress
        );
        nativeBuildProgress.finish();
        ConsoleProgressDisplay packingDisplay = new ConsoleProgressDisplay();
        if (!debug) {
            packingDisplay.updateLines(List.of("Packing jar..."));
        }
        IrJarRepacker.RepackResult repackResult = jarRepacker.repack(
                config.getJarFilePath(),
                buildDirectory.resolve(config.getJarFilePath().getFileName()),
                nativeBuild.artifacts(),
                nativeDir,
                config.libraryName,
                registrationPlan
        );
        if (debug) {
            System.out.println("IR pipeline output: " + OutputPathFormatter.formatIrOutputHint(result.outputArtifacts().llvmFile()));
            System.out.println("IR module shards: " + result.outputArtifacts().llvmModuleFiles().size());
            System.out.println("IR runtime stubs: " + OutputPathFormatter.formatIrOutputHint(result.outputArtifacts().runtimeStubFile()));
            System.out.println("IR runtime shards: " + runtimeSourceFiles.size());
            for (IrNativeBuildDriver.BuildArtifact artifact : nativeBuild.artifacts()) {
                System.out.println("Native artifact [" + artifact.target().getConfigKey() + "]: "
                        + OutputPathFormatter.formatIrOutputHint(artifact.libraryFile()));
                System.out.println("Native timing  [" + artifact.target().getConfigKey() + "]: compile="
                        + artifact.timing().compileMillis() + "ms, link="
                        + artifact.timing().linkMillis() + "ms, total="
                        + artifact.timing().totalMillis() + "ms, llvm="
                        + artifact.timing().llvmShardCount() + ", runtime="
                        + artifact.timing().runtimeSourceCount());
            }
            System.out.println("IR repacked jar: " + OutputPathFormatter.formatIrOutputHint(repackResult.outputJar()));
            if (result.outputArtifacts().frontendSkipsFile() != null) {
                System.out.println("Frontend skips: " + OutputPathFormatter.formatIrOutputHint(result.outputArtifacts().frontendSkipsFile()));
            }
        } else {
            packingDisplay.completeLines(List.of("Check " + OutputPathFormatter.relativizeOutputPath(repackResult.outputJar())));
        }
        return 0;
    }

    private ClassMethodFilter createClassMethodFilter(Config config) {
        return new ClassMethodFilter(
                ClassMethodList.parse(config.getBlackList()),
                ClassMethodList.parse(config.getWhiteList())
        );
    }
}
