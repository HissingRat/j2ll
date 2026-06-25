package xyz.melodysky.toolchain;

import xyz.melodysky.config.BuildTarget;
import xyz.melodysky.process.SubprocessRegistry;
import xyz.melodysky.zig.ZigWorkspaceEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class IrNativeBuildDriver {

    private static final int MAX_CAPTURE_BYTES = 1_048_576;

    private final Path workspaceDirectory;
    private final boolean preserveIntermediates;
    private final NativeBuildWorkspacePaths workspacePaths;
    private final NativeBuildCommandFactory commandFactory;
    private final ZigBuildProjectWriter buildProjectWriter;
    private static final ProgressListener NO_PROGRESS = new ProgressListener() {};

    public interface ProgressListener {
        default void onTargetStart(BuildTarget target, int totalUnits) {}
        default void onCompileProgress(BuildTarget target, int completedUnits, int totalUnits, String unitLabel) {}
        default void onLinkStart(BuildTarget target) {}
        default void onTargetComplete(BuildTarget target, BuildTiming timing) {}
    }

    public IrNativeBuildDriver(Path workspaceDirectory) {
        this(workspaceDirectory, false);
    }

    public IrNativeBuildDriver(Path workspaceDirectory, boolean preserveIntermediates) {
        this.workspaceDirectory = workspaceDirectory;
        this.preserveIntermediates = preserveIntermediates;
        this.workspacePaths = new NativeBuildWorkspacePaths(workspaceDirectory);
        this.commandFactory = new NativeBuildCommandFactory(workspacePaths);
        this.buildProjectWriter = new ZigBuildProjectWriter(workspacePaths);
    }

    public BuildResult build(String zigCommand, Path llvmFile, Path runtimeStubFile, List<BuildTarget> targets) throws Exception {
        return build(zigCommand, List.of(llvmFile), List.of(runtimeStubFile), targets);
    }

    public BuildResult build(String zigCommand, List<Path> llvmModuleFiles, Path runtimeStubFile, List<BuildTarget> targets) throws Exception {
        return build(zigCommand, llvmModuleFiles, List.of(runtimeStubFile), targets);
    }

    public BuildResult build(String zigCommand, List<Path> llvmModuleFiles, List<Path> runtimeSourceFiles, List<BuildTarget> targets) throws Exception {
        return build(zigCommand, llvmModuleFiles, runtimeSourceFiles, targets, NO_PROGRESS);
    }

    public BuildResult build(String zigCommand, List<Path> llvmModuleFiles, List<Path> runtimeSourceFiles,
                             List<BuildTarget> targets, ProgressListener progressListener) throws Exception {
        Path outputDirectory = workspacePaths.outputDirectory();
        Path logsDirectory = workspacePaths.logsDirectory();
        Files.createDirectories(outputDirectory);
        Files.createDirectories(logsDirectory);

        ArrayList<BuildArtifact> artifacts = new ArrayList<>();
        try {
            ArrayList<NativeTargetBuildState> targetStates = new ArrayList<>();
            for (BuildTarget target : targets) {
                Path libraryFile = workspacePaths.libraryFile(target);
                Path logFile = workspacePaths.logFile(target);
                targetStates.add(compileTarget(
                        zigCommand,
                        llvmModuleFiles,
                        runtimeSourceFiles,
                        libraryFile,
                        target,
                        logFile,
                        progressListener
                ));
            }

            Path buildProjectDirectory = buildProjectWriter.prepare(outputDirectory, targetStates, runtimeSourceFiles);
            for (NativeTargetBuildState targetState : targetStates) {
                List<String> linkCommand = commandFactory.createZigBuildCommand(zigCommand, outputDirectory, buildProjectDirectory, targetState.target());
                progressListener.onLinkStart(targetState.target());
                long linkStartNanos = System.nanoTime();
                CommandResult linkResult = runInWorkingDirectory(linkCommand, buildProjectDirectory);
                long linkEndNanos = System.nanoTime();
                long linkMillis = nanosToMillis(linkEndNanos - linkStartNanos);
                BuildTiming timing = new BuildTiming(
                        targetState.llvmShardCount(),
                        targetState.runtimeSourceCount(),
                        targetState.compileMillis(),
                        linkMillis,
                        targetState.compileMillis() + linkMillis
                );
                writeTargetLog(targetState, linkCommand, linkResult.output(), timing);
                if (linkResult.exitCode() != 0) {
                    throw new IOException("Failed to build IR native artifact for command: " + renderCommand(linkCommand)
                            + System.lineSeparator() + "See log: " + targetState.logFile().toAbsolutePath());
                }
                progressListener.onTargetComplete(targetState.target(), timing);
                artifacts.add(new BuildArtifact(targetState.target(), targetState.libraryFile(), targetState.logFile(), timing));
            }
        } finally {
            cleanupIntermediatesIfNeeded();
        }

        return new BuildResult(outputDirectory, List.copyOf(artifacts));
    }

    List<String> createCompileCommand(String zigCommand, Path llvmFile, Path runtimeStubFile,
                                      Path outputFile, BuildTarget target) {
        return commandFactory.createCompileCommand(zigCommand, llvmFile, runtimeStubFile, outputFile, target);
    }

    List<String> createLlvmObjectCompileCommand(String zigCommand, Path llvmModuleFile, Path outputFile, BuildTarget target) {
        return commandFactory.createLlvmObjectCompileCommand(zigCommand, llvmModuleFile, outputFile, target);
    }

    List<String> createRuntimeObjectCompileCommand(String zigCommand, Path runtimeStubFile, Path outputFile, BuildTarget target) {
        return commandFactory.createRuntimeObjectCompileCommand(zigCommand, runtimeStubFile, outputFile, target);
    }

    List<String> createLinkCommand(String zigCommand, List<Path> objectFiles, Path outputFile, BuildTarget target) {
        return commandFactory.createLinkCommand(zigCommand, objectFiles, outputFile, target);
    }

    String outputFileName(BuildTarget target) {
        return workspacePaths.outputFileName(target);
    }

    private NativeTargetBuildState compileTarget(String zigCommand, List<Path> llvmModuleFiles, List<Path> runtimeSourceFiles,
                                                 Path outputFile, BuildTarget target, Path logFile,
                                                 ProgressListener progressListener) throws Exception {
        Path objectDirectory = workspacePaths.objectDirectory(target);
        Files.createDirectories(objectDirectory);

        ArrayList<NativeCompileUnit> compileUnits = new ArrayList<>();
        int llvmCompileUnitCount = 0;
        for (int index = 0; index < llvmModuleFiles.size(); index++) {
            Path llvmModuleFile = llvmModuleFiles.get(index);
            Path objectFile = objectDirectory.resolve(workspacePaths.moduleObjectName(index, llvmModuleFile, target));
            compileUnits.add(new NativeCompileUnit(
                    "llvm[" + llvmModuleFile.getFileName() + "]",
                    objectFile,
                    createLlvmObjectCompileCommand(zigCommand, llvmModuleFile, objectFile, target)
            ));
            llvmCompileUnitCount++;
        }
        int runtimeCompileUnitCount = runtimeSourceFiles.size();

        progressListener.onTargetStart(target, compileUnits.size());
        long compileStartNanos = System.nanoTime();
        NativeCompileBatchResult compileBatchResult = runParallelCompiles(compileUnits, target, progressListener);
        long compileEndNanos = System.nanoTime();

        if (compileBatchResult.failedUnit() != null) {
            Files.writeString(logFile, renderCompileLog(compileUnits, compileBatchResult), StandardCharsets.UTF_8);
            throw new IOException("Failed to build IR native artifact for command: "
                    + renderCommand(compileBatchResult.failedUnit().command())
                    + System.lineSeparator() + "See log: " + logFile.toAbsolutePath());
        }
        return new NativeTargetBuildState(
                target,
                outputFile,
                logFile,
                List.copyOf(compileUnits),
                compileBatchResult,
                llvmCompileUnitCount,
                runtimeCompileUnitCount,
                nanosToMillis(compileEndNanos - compileStartNanos)
        );
    }

    private void writeTargetLog(NativeTargetBuildState targetState, List<String> linkCommand, String linkOutput,
                                BuildTiming timing) throws IOException {
        StringBuilder logContent = new StringBuilder(renderCompileLog(targetState.compileUnits(), targetState.compileBatchResult()));
        logContent.append("$ ").append(renderCommand(linkCommand)).append(System.lineSeparator());
        logContent.append(linkOutput);
        logContent.append(System.lineSeparator())
                .append("== timing ==")
                .append(System.lineSeparator())
                .append("llvm module compiles: ")
                .append(timing.llvmShardCount())
                .append(System.lineSeparator())
                .append("runtime source compiles: ")
                .append(timing.runtimeSourceCount())
                .append(System.lineSeparator())
                .append("compile phase ms: ")
                .append(timing.compileMillis())
                .append(System.lineSeparator())
                .append("link phase ms: ")
                .append(timing.linkMillis())
                .append(System.lineSeparator())
                .append("total ms: ")
                .append(timing.totalMillis())
                .append(System.lineSeparator());
        Files.writeString(targetState.logFile(), logContent.toString(), StandardCharsets.UTF_8);
    }

    private String renderCompileLog(List<NativeCompileUnit> compileUnits, NativeCompileBatchResult compileBatchResult) {
        StringBuilder logContent = new StringBuilder();
        for (NativeCompileUnit unit : compileUnits) {
            logContent.append("$ ").append(renderCommand(unit.command())).append(System.lineSeparator());
            logContent.append(compileBatchResult.outputByUnit().getOrDefault(unit.label(), ""));
        }
        return logContent.toString();
    }

    private NativeCompileBatchResult runParallelCompiles(List<NativeCompileUnit> compileUnits, BuildTarget target,
                                                         ProgressListener progressListener) throws Exception {
        int threadCount = Math.max(1, Math.min(compileUnits.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            ArrayList<Callable<CompileOutcome>> tasks = new ArrayList<>(compileUnits.size());
            for (NativeCompileUnit unit : compileUnits) {
                tasks.add(() -> new CompileOutcome(unit, runCompileUnit(unit)));
            }
            ExecutorCompletionService<CompileOutcome> completionService =
                    new ExecutorCompletionService<>(executor);
            for (Callable<CompileOutcome> task : tasks) {
                completionService.submit(task);
            }
            ArrayList<CompileOutcome> outcomes = new ArrayList<>(compileUnits.size());
            for (int completed = 1; completed <= compileUnits.size(); completed++) {
                Future<CompileOutcome> future = waitForCompletedCompile(completionService);
                outcomes.add(future.get());
                progressListener.onCompileProgress(target, completed, compileUnits.size(),
                        outcomes.getLast().unit().label());
            }
            outcomes.sort(Comparator.comparingInt(outcome -> compileUnits.indexOf(outcome.unit())));

            LinkedHashMap<String, String> outputByUnit = new LinkedHashMap<>();
            NativeCompileUnit failedUnit = null;
            for (CompileOutcome outcome : outcomes) {
                outputByUnit.put(outcome.unit().label(), outcome.result().output());
                if (failedUnit == null && outcome.result().exitCode() != 0) {
                    failedUnit = outcome.unit();
                }
            }
            return new NativeCompileBatchResult(outputByUnit, failedUnit);
        } finally {
            executor.shutdownNow();
        }
    }

    private void run(List<String> command, Path logFile) throws Exception {
        CommandResult result = run(command);
        String logContent = "$ " + renderCommand(command) + System.lineSeparator() + result.output();
        Files.writeString(logFile, logContent, StandardCharsets.UTF_8);
        if (result.exitCode() != 0) {
            throw new IOException("Failed to build IR native artifact for command: " + renderCommand(command)
                    + System.lineSeparator() + "See log: " + logFile.toAbsolutePath());
        }
    }

    private CommandResult runCompileUnit(NativeCompileUnit unit) throws Exception {
        return run(unit.command());
    }

    private CommandResult run(List<String> command) throws Exception {
        return runInWorkingDirectory(command, workspaceDirectory);
    }

    private CommandResult runInWorkingDirectory(List<String> command, Path workingDirectory) throws Exception {
        return run(command, workingDirectory, null);
    }

    private CommandResult run(List<String> command, String stdinText) throws Exception {
        return run(command, workspaceDirectory, stdinText);
    }

    private CommandResult run(List<String> command, Path workingDirectory, String stdinText) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        ZigWorkspaceEnvironment.configure(
                builder.environment(),
                command,
                workspaceDirectory,
                System.getProperty("os.name", "").toLowerCase().contains("win")
        );

        Process process = builder.start();
        boolean completed = false;
        AtomicReference<String> outputRef = new AtomicReference<>("");
        AtomicReference<IOException> outputFailureRef = new AtomicReference<>();
        AtomicReference<IOException> inputFailureRef = new AtomicReference<>();
        Thread outputReader = new Thread(() -> {
            try (var input = process.getInputStream()) {
                outputRef.set(readProcessOutput(input));
            } catch (IOException exception) {
                outputFailureRef.set(exception);
            }
        }, "ir-native-build-output");
        outputReader.setDaemon(true);
        outputReader.start();
        Thread inputWriter = new Thread(() -> {
            try (var output = process.getOutputStream()) {
                if (stdinText != null) {
                    output.write(stdinText.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException exception) {
                inputFailureRef.set(exception);
            }
        }, "ir-native-build-input");
        inputWriter.setDaemon(true);
        inputWriter.start();
        SubprocessRegistry.Registration registration = SubprocessRegistry.register(process);
        try {
            while (true) {
                throwIfCancellationRequested();
                if (process.waitFor(100L, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }

            joinInputWriter(inputWriter);
            String output = awaitCollectedOutput(outputReader, outputRef, outputFailureRef);
            int exitCode = process.exitValue();
            completed = true;
            registration.close();
            IOException inputFailure = inputFailureRef.get();
            if (inputFailure != null && exitCode != 0) {
                output = output + System.lineSeparator() + "[stdin write failed: " + inputFailure.getMessage() + "]";
            }
            return new CommandResult(exitCode, output);
        } finally {
            if (!completed) {
                SubprocessRegistry.destroyProcessTree(process);
                registration.close();
            }
            joinThreadQuietly(inputWriter);
            joinOutputReaderQuietly(outputReader);
        }
    }

    private Future<CompileOutcome> waitForCompletedCompile(ExecutorCompletionService<CompileOutcome> completionService)
            throws Exception {
        while (true) {
            throwIfCancellationRequested();
            Future<CompileOutcome> completedFuture = completionService.poll(100L, TimeUnit.MILLISECONDS);
            if (completedFuture != null) {
                return completedFuture;
            }
        }
    }

    private void throwIfCancellationRequested() throws InterruptedException {
        if (SubprocessRegistry.isShutdownRequested() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Native build cancelled");
        }
    }

    private String awaitCollectedOutput(Thread outputReader, AtomicReference<String> outputRef,
                                        AtomicReference<IOException> outputFailureRef) throws Exception {
        while (outputReader.isAlive()) {
            throwIfCancellationRequested();
            outputReader.join(100L);
        }
        IOException outputFailure = outputFailureRef.get();
        if (outputFailure != null) {
            throw outputFailure;
        }
        return outputRef.get();
    }

    private void joinOutputReaderQuietly(Thread outputReader) {
        joinThreadQuietly(outputReader);
    }

    private void joinInputWriter(Thread inputWriter) throws InterruptedException {
        while (inputWriter.isAlive()) {
            throwIfCancellationRequested();
            inputWriter.join(100L);
        }
    }

    private void joinThreadQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join(100L);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String readProcessOutput(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = MAX_CAPTURE_BYTES - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(read, remaining));
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        String text = output.toString(StandardCharsets.UTF_8);
        if (truncated) {
            return text + System.lineSeparator() + "[output truncated]";
        }
        return text;
    }

    private String renderCommand(List<String> command) {
        ArrayList<String> rendered = new ArrayList<>(command.size());
        for (String part : command) {
            rendered.add(quoteIfNeeded(part));
        }
        return String.join(" ", rendered);
    }

    private String quoteIfNeeded(String part) {
        if (part.indexOf(' ') < 0 && part.indexOf('\t') < 0) {
            return part;
        }
        return "\"" + part.replace("\"", "\\\"") + "\"";
    }

    private long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    void cleanupIntermediates() {
        workspacePaths.cleanupIntermediates();
    }

    void cleanupIntermediatesIfNeeded() {
        if (!preserveIntermediates) {
            cleanupIntermediates();
        }
    }

    public record BuildArtifact(BuildTarget target, Path libraryFile, Path logFile, BuildTiming timing) {
    }

    public record BuildResult(Path outputDirectory, List<BuildArtifact> artifacts) {
    }

    public record BuildTiming(int llvmShardCount, int runtimeSourceCount, long compileMillis, long linkMillis, long totalMillis) {
    }

    private record CompileOutcome(NativeCompileUnit unit, CommandResult result) {
    }

    private record CommandResult(int exitCode, String output) {
    }

}
