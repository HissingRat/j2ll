package xyz.melodysky;

import sun.misc.Signal;
import xyz.melodysky.backend.llvm.LlvmTextBackend;
import xyz.melodysky.console.ConsoleProgressDisplay;
import xyz.melodysky.config.Config;
import xyz.melodysky.filter.ClassMethodFilter;
import xyz.melodysky.filter.ClassMethodList;
import xyz.melodysky.packaging.IrJarRepacker;
import xyz.melodysky.packaging.NativeMethodClassRewriter;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.pipeline.IrPipelineCompiler;
import xyz.melodysky.process.SubprocessRegistry;
import xyz.melodysky.ir.pass.ConstantSplittingPass;
import xyz.melodysky.ir.pass.CfgCleanupPass;
import xyz.melodysky.ir.pass.CfgPerturbationPass;
import xyz.melodysky.ir.pass.IrMethodPass;
import xyz.melodysky.ir.pass.StringObfuscationPass;
import xyz.melodysky.runtime.IrRuntimeStubGenerator;
import xyz.melodysky.toolchain.IrNativeBuildDriver;
import xyz.melodysky.zig.ZigManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CancellationException;

public class Main {

    private static final String VERSION = "3.5.4r";

    public static void main(String[] args) {
        System.setProperty("java.net.useSystemProxies", "true");
        installCancellationSignalHandlers(Thread.currentThread());
        int exitCode;
        try {
            CliOptions options = parseArgs(args);
            Config config = options.configPath() == null
                    ? Config.loadOrCreateDefault()
                    : Config.load(options.configPath());
            exitCode = config == null ? 0 : run(config, options.debug());
        } catch (Exception exception) {
            if (isCancellation(exception)) {
                restoreInterruptStatusIfNeeded(exception);
                System.out.println();
                System.out.println("Build cancelled by user.");
                exitCode = 130;
            } else {
                throw new RuntimeException("Failed to run j2ll", exception);
            }
        }
        System.exit(exitCode);
    }

    private static int run(Config config, boolean debug) throws Exception {
        Path buildDirectory = config.createBuildDirectory();
        System.out.println("Build workspace: " + buildDirectory.toAbsolutePath());
        return runIrPipeline(config, buildDirectory, debug);
    }

    private static int runIrPipeline(Config config, Path buildDirectory, boolean debug) throws Exception {
        ClassMethodFilter classMethodFilter = new ClassMethodFilter(
                ClassMethodList.parse(config.getBlackList()),
                ClassMethodList.parse(config.getWhiteList())
        );
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
        PipelineConsoleProgress pipelineProgress = new PipelineConsoleProgress(progressDisplay);
        Integer maxShardBytes = config.getMaxShardBytes();
        LlvmTextBackend llvmBackend = maxShardBytes != null
                ? LlvmTextBackend.withMaxShardBytes(maxShardBytes)
                : new LlvmTextBackend();
        IrRuntimeStubGenerator runtimeStubGenerator = new IrRuntimeStubGenerator();
        IrPipelineCompiler.DirectoryBuildResult result = new IrPipelineCompiler(
                new xyz.melodysky.frontend.jar.JarIrBuilder(),
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
        ZigManager zigManager = new ZigManager(ZigManager.resolveApplicationDirectory(Main.class), buildDirectory);
        String zigCommand = zigManager.ensureZigCommand();
        NativeBuildConsoleProgress nativeBuildProgress = new NativeBuildConsoleProgress(progressDisplay);
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
            System.out.println("IR pipeline output: " + formatIrOutputHint(result.outputArtifacts().llvmFile()));
            System.out.println("IR module shards: " + result.outputArtifacts().llvmModuleFiles().size());
            System.out.println("IR runtime stubs: " + formatIrOutputHint(result.outputArtifacts().runtimeStubFile()));
            System.out.println("IR runtime shards: " + runtimeSourceFiles.size());
            for (IrNativeBuildDriver.BuildArtifact artifact : nativeBuild.artifacts()) {
                System.out.println("Native artifact [" + artifact.target().getConfigKey() + "]: "
                        + formatIrOutputHint(artifact.libraryFile()));
                System.out.println("Native timing  [" + artifact.target().getConfigKey() + "]: compile="
                        + artifact.timing().compileMillis() + "ms, link="
                        + artifact.timing().linkMillis() + "ms, total="
                        + artifact.timing().totalMillis() + "ms, llvm="
                        + artifact.timing().llvmShardCount() + ", runtime="
                        + artifact.timing().runtimeSourceCount());
            }
            System.out.println("IR repacked jar: " + formatIrOutputHint(repackResult.outputJar()));
            if (result.outputArtifacts().frontendSkipsFile() != null) {
                System.out.println("Frontend skips: " + formatIrOutputHint(result.outputArtifacts().frontendSkipsFile()));
            }
        } else {
            packingDisplay.completeLines(List.of("Check " + relativizeOutputPath(repackResult.outputJar())));
        }
        return 0;
    }

    private static final class PipelineConsoleProgress implements IrPipelineCompiler.ProgressListener {
        private static final int WIDTH = 28;
        private static final long MIN_RENDER_INTERVAL_MS = 100L;

        private final ConsoleProgressDisplay display;
        private int readTotal;
        private int readCurrent;
        private String readName = "waiting";
        private int lowerTotal;
        private int lowerCurrent;
        private String lowerName = "waiting";
        private int llvmTotal;
        private int llvmCurrent;
        private String llvmName = "waiting";
        private long lastRenderAt;

        private PipelineConsoleProgress(ConsoleProgressDisplay display) {
            this.display = display;
        }

        @Override
        public void onBytecodeReadStart(int totalClasses) {
            readTotal = totalClasses;
            render(true);
        }

        @Override
        public void onBytecodeReadProgress(int current, int totalClasses, String className) {
            readCurrent = current;
            readTotal = totalClasses;
            readName = abbreviateClassName(className);
            render(current >= totalClasses);
        }

        @Override
        public void onIrLowerStart(int totalClasses) {
            lowerTotal = totalClasses;
            render(true);
        }

        @Override
        public void onIrLowerProgress(int current, int totalClasses, String className) {
            lowerCurrent = current;
            lowerTotal = totalClasses;
            lowerName = abbreviateClassName(className);
            render(current >= totalClasses);
        }

        @Override
        public void onLlvmEmitStart(int totalClasses) {
            llvmTotal = totalClasses;
            render(true);
        }

        @Override
        public void onLlvmEmitProgress(int current, int totalClasses, String className) {
            llvmCurrent = current;
            llvmTotal = totalClasses;
            llvmName = abbreviateClassName(className);
            render(current >= totalClasses);
        }

        private void render(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastRenderAt < MIN_RENDER_INTERVAL_MS) {
                return;
            }
            lastRenderAt = now;
            display.updateLines(List.of(
                    formatLine("Read bytecode", readCurrent, readTotal, readName),
                    formatLine("Lower to IR", lowerCurrent, lowerTotal, lowerName),
                    formatLine("Emit LLVM IR", llvmCurrent, llvmTotal, llvmName)
            ));
        }

        private String formatLine(String label, int current, int total, String name) {
            return String.format(Locale.ROOT, "%-14s %s %d/%d  %s",
                    label,
                    display.formatProgressBar(current, total, WIDTH),
                    current, Math.max(total, current),
                    name);
        }

        private void finish() {
            readCurrent = Math.max(readCurrent, readTotal);
            lowerCurrent = Math.max(lowerCurrent, lowerTotal);
            llvmCurrent = Math.max(llvmCurrent, llvmTotal);
            readName = "done";
            lowerName = "done";
            llvmName = "done";
            display.completeLines(List.of(
                    formatLine("Read bytecode", readCurrent, readTotal, readName),
                    formatLine("Lower to IR", lowerCurrent, lowerTotal, lowerName),
                    formatLine("Emit LLVM IR", llvmCurrent, llvmTotal, llvmName)
            ));
        }
    }

    private static final class NativeBuildConsoleProgress implements IrNativeBuildDriver.ProgressListener {
        private static final int WIDTH = 28;

        private final ConsoleProgressDisplay display;
        private String targetName = "idle";
        private int compileCompleted;
        private int compileTotal;
        private String compileLabel = "waiting";
        private String stage = "starting";

        private NativeBuildConsoleProgress(ConsoleProgressDisplay display) {
            this.display = display;
        }

        @Override
        public void onTargetStart(xyz.melodysky.config.BuildTarget target, int totalUnits) {
            targetName = target.getConfigKey();
            compileCompleted = 0;
            compileTotal = totalUnits;
            compileLabel = "starting";
            stage = "compiling";
            render();
        }

        @Override
        public void onCompileProgress(xyz.melodysky.config.BuildTarget target, int completedUnits, int totalUnits, String unitLabel) {
            targetName = target.getConfigKey();
            compileCompleted = completedUnits;
            compileTotal = totalUnits;
            compileLabel = unitLabel;
            stage = "compiling";
            render();
        }

        @Override
        public void onLinkStart(xyz.melodysky.config.BuildTarget target) {
            targetName = target.getConfigKey();
            stage = "linking";
            compileLabel = "zig build " + target.getConfigKey();
            render();
        }

        @Override
        public void onTargetComplete(xyz.melodysky.config.BuildTarget target, IrNativeBuildDriver.BuildTiming timing) {
            targetName = target.getConfigKey();
            compileCompleted = compileTotal;
            stage = "done";
            compileLabel = "compile=" + timing.compileMillis() + "ms link=" + timing.linkMillis() + "ms";
            render();
        }

        private void render() {
            display.updateLines(List.of(
                    String.format(Locale.ROOT, "Build native   %s %d/%d  %s",
                            display.formatProgressBar(compileCompleted, compileTotal, WIDTH),
                            compileCompleted, Math.max(compileTotal, compileCompleted),
                            targetName),
                    String.format(Locale.ROOT, "Stage          %-10s %s", stage, compileLabel)
            ));
        }

        private void finish() {
            compileCompleted = Math.max(compileCompleted, compileTotal);
            stage = "done";
            compileLabel = "done";
            display.completeLines(List.of(
                    String.format(Locale.ROOT, "Build native   %s %d/%d  %s",
                            display.formatProgressBar(compileCompleted, compileTotal, WIDTH),
                            compileCompleted, Math.max(compileTotal, compileCompleted),
                            targetName),
                    String.format(Locale.ROOT, "Stage          %-10s %s", stage, compileLabel)
            ));
        }
    }

    private static String abbreviateClassName(String className) {
        if (className == null || className.isBlank()) {
            return "waiting";
        }
        String normalized = className.replace('\\', '/');
        if (normalized.length() <= 72) {
            return normalized;
        }
        return "..." + normalized.substring(normalized.length() - 69);
    }

    static void installCancellationSignalHandlers(Thread mainThread) {
        installCancellationSignalHandler("INT", mainThread);
        installCancellationSignalHandler("TERM", mainThread);
    }

    private static void installCancellationSignalHandler(String signalName, Thread mainThread) {
        try {
            Signal.handle(new Signal(signalName), ignored -> {
                SubprocessRegistry.requestShutdownNow();
                if (mainThread != null) {
                    mainThread.interrupt();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    static boolean isCancellation(Throwable throwable) {
        if (SubprocessRegistry.isShutdownRequested()) {
            return true;
        }
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    private static void restoreInterruptStatusIfNeeded(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static CliOptions parseArgs(String[] args) {
        boolean debug = false;
        Path configPath = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--debug" -> debug = true;
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for --config");
                    }
                    configPath = Path.of(args[++i]);
                }
                default -> throw new IllegalArgumentException(
                        "Usage: java -jar j2ll.jar [--debug] [--config <file>]"
                );
            }
        }
        return new CliOptions(debug, configPath);
    }

    private static String relativizeOutputPath(Path artifactPath) {
        try {
            Path base = Path.of("").toAbsolutePath().normalize();
            return base.relativize(artifactPath.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return formatIrOutputHint(artifactPath);
        }
    }

    static String formatIrOutputHint(Path artifactPath) {
        return artifactPath.toString().replace('\\', '/');
    }

    private record CliOptions(boolean debug, Path configPath) {
    }
}
